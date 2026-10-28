package com.btautoconnect.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.btautoconnect.R
import com.btautoconnect.bt.TargetDevices
import com.btautoconnect.ui.MainActivity

/**
 * Always-on auto-connect engine.
 *
 * Behaviour:
 * - When Bluetooth turns ON (or the service starts / the phone boots), it
 *   searches (discovery scan) for the user's own devices and connects to the
 *   first one found, in this priority order:
 *     1) Roseland TWS RE-500   2) itel T1Neo   3) MusicMan BT-X78
 * - Only ONE device at a time: if a target device is already connected (or
 *   connecting), it never connects another one. If some *other* audio device
 *   is connected, it leaves it alone and doesn't add a second one.
 * - If the connected device drops, or Bluetooth is toggled, it searches and
 *   reconnects again automatically (checked every 20 seconds).
 *
 * Devices must already be PAIRED with the phone (Android does not allow apps
 * to pair headsets silently). Connecting uses the standard profile-proxy
 * mechanism (A2DP for music + Headset for calls, both on the SAME device).
 */
class BtAutoConnectService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var adapter: BluetoothAdapter? = null
    private var a2dpProxy: BluetoothProfile? = null
    private var headsetProxy: BluetoothProfile? = null

    /** Bluetooth address -> last time (ms) a discovery scan saw it. */
    private val lastSeen = mutableMapOf<String, Long>()
    @Volatile private var lastConnectAttempt = 0L
    @Volatile private var nextCandidateIndex = 0
    @Volatile private var stoppedByUser = false

    private val ticker = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val eventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_ON) {
                        updateStatus(getString(R.string.status_bluetooth_on))
                        handler.postDelayed({ evaluate() }, 1_500)
                    } else if (state == BluetoothAdapter.STATE_OFF) {
                        connectedDeviceName = null
                        updateStatus(getString(R.string.status_bluetooth_off))
                    }
                }
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null && TargetDevices.targetFor(device) != null) {
                        safeName(device)?.let { Log.i(TAG, "Found target nearby: $it") }
                        synchronized(lastSeen) { lastSeen[device.address] = System.currentTimeMillis() }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> evaluate()
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    handler.postDelayed({ evaluate() }, 2_000)
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = proxy
                BluetoothProfile.HEADSET -> headsetProxy = proxy
            }
            evaluate()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = null
                BluetoothProfile.HEADSET -> headsetProxy = null
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        startForegroundWithNotification(getString(R.string.status_starting))
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        registerEventReceivers()
        connectProfileProxies()
        scheduleWatchdog(this)
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stoppedByUser = true
                isServiceRunning = false
                setAutoRunEnabled(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT_NOW -> {
                stoppedByUser = false
                lastConnectAttempt = 0L
                evaluate()
            }
            else -> {
                stoppedByUser = false
                evaluate()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!stoppedByUser && isAutoRunEnabled(this)) scheduleRestartViaAlarm()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isServiceRunning = false
        handler.removeCallbacks(ticker)
        try { unregisterReceiver(eventsReceiver) } catch (_: Exception) {}
        closeProfileProxies()
        if (!stoppedByUser && isAutoRunEnabled(this)) scheduleRestartViaAlarm()
        super.onDestroy()
    }

    // ------------------------------------------------------------- core logic

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun evaluate() {
        val bt = adapter ?: run {
            updateStatus(getString(R.string.status_no_bluetooth))
            return
        }
        if (!hasConnectPermission()) {
            updateStatus(getString(R.string.status_no_permission))
            return
        }
        if (!bt.isEnabled) {
            connectedDeviceName = null
            updateStatus(getString(R.string.status_bluetooth_off))
            return
        }
        isServiceRunning = true

        // 1) One device at a time: if anything is connected/connecting on the
        //    audio profiles, respect that and do not connect another device.
        val active = activeAudioDevice()
        if (active != null) {
            val (device, state) = active
            if (state == BluetoothProfile.STATE_CONNECTED) {
                val target = TargetDevices.targetFor(device)
                connectedDeviceName = safeName(device)
                updateStatus(
                    if (target != null) getString(R.string.status_connected, connectedDeviceName)
                    else getString(R.string.status_other_connected, connectedDeviceName ?: "")
                )
                stopDiscovery()
                return
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                updateStatus(getString(R.string.status_connecting, safeName(device) ?: ""))
                return
            }
        }
        connectedDeviceName = null

        // 2) Choose from the PAIRED target devices. Paired earbuds/headsets
        //    are usually NOT visible to discovery scans (only in pairing
        //    mode), so we must try connecting to paired devices directly —
        //    scanning is only a bonus signal for priority.
        val bonded = bondedTargets()
        if (bonded.isEmpty()) {
            updateStatus(getString(R.string.status_none_paired))
            logEvent("No paired target devices found — pair one in Bluetooth settings")
            startSearch()
            return
        }
        val now = System.currentTimeMillis()
        val seenRecently = bonded.filter { device ->
            synchronized(lastSeen) { now - (lastSeen[device.address] ?: 0L) < SEEN_WINDOW_MS }
        }

        // 3) Connect! Prefer a device a scan recently saw; otherwise walk
        //    through the paired devices in priority order, rotating on each
        //    attempt so an unavailable #1 doesn't block #2/#3 forever.
        if (now - lastConnectAttempt <= CONNECT_COOLDOWN_MS) {
            updateStatus(getString(R.string.status_searching))
            return
        }
        startSearch() // keep learning which devices are around
        val candidate = if (seenRecently.isNotEmpty()) {
            seenRecently.first()
        } else {
            val picked = bonded[nextCandidateIndex % bonded.size]
            nextCandidateIndex = (bonded.indexOf(picked) + 1) % bonded.size
            picked
        }
        tryConnect(candidate)
    }

    @SuppressLint("MissingPermission")
    private fun bondedTargets(): List<BluetoothDevice> {
        return try {
            (adapter?.bondedDevices ?: emptySet())
                .filter { TargetDevices.targetFor(it) != null && isTargetEnabled(it) }
                .sortedBy { TargetDevices.priorityOf(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** @return the (device, state) currently using an audio profile, if any. */
    @SuppressLint("MissingPermission")
    private fun activeAudioDevice(): Pair<BluetoothDevice, Int>? {
        for (proxy in listOfNotNull(a2dpProxy, headsetProxy)) {
            try {
                for (device in proxy.connectedDevices) {
                    return device to BluetoothProfile.STATE_CONNECTED
                }
                // Also detect a device that is mid-connection.
                for (device in bondedTargets()) {
                    val state = proxy.getConnectionState(device)
                    if (state == BluetoothProfile.STATE_CONNECTING) return device to state
                }
            } catch (_: Exception) { /* proxy not ready */ }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice) {
        lastConnectAttempt = System.currentTimeMillis()
        val name = safeName(device) ?: device.address
        Log.i(TAG, "Trying to connect: $name")
        logEvent("Trying to connect to $name…")
        updateStatus(getString(R.string.status_connecting, name))
        stopDiscovery()
        if (a2dpProxy == null && headsetProxy == null) {
            logEvent("Bluetooth profile service not ready yet — retrying shortly")
            connectProfileProxies()
            handler.postDelayed({ evaluate() }, 3_000)
            return
        }
        // Connect A2DP (music) and Headset (calls) — same single device.
        var requested = false
        for (proxy in listOfNotNull(a2dpProxy, headsetProxy)) {
            val profileName = if (proxy === a2dpProxy) "A2DP" else "Headset"
            if (connectViaReflection(proxy, device)) {
                requested = true
                logEvent("$profileName connect requested for $name")
            } else {
                logEvent("$profileName connect was refused for $name")
            }
        }
        // Fallback used by many devices/firmwares: ask the device itself to
        // connect all its profiles.
        if (!requested && connectAllProfiles(device)) {
            requested = true
            logEvent("connectAllProfiles requested for $name")
        }
        if (!requested) {
            updateStatus(getString(R.string.status_connect_failed, name))
        }
        handler.postDelayed({ evaluate() }, 6_000)
    }

    /**
     * BluetoothA2dp.connect() / BluetoothHeadset.connect() are hidden system
     * APIs — reflection is the standard way apps trigger a profile connection
     * to an already-paired device. Searches the proxy's class and interfaces
     * so it also works on firmwares where the method sits on a superclass.
     */
    private fun connectViaReflection(proxy: BluetoothProfile, device: BluetoothDevice): Boolean {
        return try {
            val method = proxy.javaClass.methods.firstOrNull { m ->
                m.name == "connect" && m.parameterTypes.contentEquals(
                    arrayOf(BluetoothDevice::class.java)
                )
            } ?: proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            method.isAccessible = true
            method.invoke(proxy, device) as? Boolean ?: true
        } catch (e: Exception) {
            Log.w(TAG, "connect reflection failed for ${proxy.javaClass.simpleName}: ${e.cause?.message ?: e.message}")
            logEvent("connect method failed: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}")
            false
        }
    }

    /** Hidden BluetoothDevice.connectAllProfiles() — fallback connect path. */
    private fun connectAllProfiles(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.methods.firstOrNull { it.name == "connectAllProfiles" }
                ?: return false
            method.isAccessible = true
            method.invoke(device)
            true
        } catch (e: Exception) {
            Log.w(TAG, "connectAllProfiles failed: ${e.cause?.message ?: e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSearch() {
        try {
            val bt = adapter ?: return
            if (hasScanPermission() && !bt.isDiscovering) {
                Log.i(TAG, "Searching for your devices…")
                bt.startDiscovery()
            }
        } catch (_: Exception) { /* missing permission etc. */ }
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscovery() {
        try {
            if (adapter?.isDiscovering == true) adapter?.cancelDiscovery()
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ profile glue

    @SuppressLint("MissingPermission")
    private fun connectProfileProxies() {
        try {
            adapter?.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
            adapter?.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
        } catch (_: Exception) { /* permissions */ }
    }

    private fun closeProfileProxies() {
        try {
            a2dpProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headsetProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        } catch (_: Exception) {}
    }

    private fun registerEventReceivers() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(eventsReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(eventsReceiver, filter)
        }
    }

    private fun scheduleRestartViaAlarm() {
        try {
            val intent = PendingIntent.getService(
                this, 43, Intent(this, BtAutoConnectService::class.java).setAction(ACTION_START),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            getSystemService(android.app.AlarmManager::class.java)
                ?.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2_000, intent
                )
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- helpers

    private fun isTargetEnabled(device: BluetoothDevice): Boolean {
        val target = TargetDevices.targetFor(device) ?: return false
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(target.prefKey, true)
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.BLUETOOTH_CONNECT
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.BLUETOOTH_SCAN
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun safeName(device: BluetoothDevice): String? =
        try { device.name } catch (_: Exception) { null }

    // ------------------------------------------------------------ notification

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notification_channel_desc) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateStatus(text: String) {
        currentStatus = text
        if (isServiceRunning) {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val connectIntent = PendingIntent.getService(
            this, 1, Intent(this, BtAutoConnectService::class.java).setAction(ACTION_CONNECT_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, BtAutoConnectService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.connect_now), connectIntent)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
    }

    companion object {
        const val TAG = "BTAutoConnect"
        const val ACTION_START = "com.btautoconnect.action.START"
        const val ACTION_STOP = "com.btautoconnect.action.STOP"
        const val ACTION_CONNECT_NOW = "com.btautoconnect.action.CONNECT_NOW"

        private const val CHANNEL_ID = "bt_auto_connect"
        private const val NOTIFICATION_ID = 2001
        private const val PREFS = "bt_auto_connect_prefs"
        private const val KEY_AUTORUN = "auto_run"
        private const val KEY_ALWAYS_RUNNING_V2 = "always_running_default_v2"
        const val ACTION_WATCHDOG = "com.btautoconnect.action.WATCHDOG"
        private const val WATCHDOG_MS = 15 * 60_000L
        private const val TICK_MS = 20_000L
        private const val SEEN_WINDOW_MS = 3 * 60_000L
        private const val CONNECT_COOLDOWN_MS = 12_000L

        @Volatile var currentStatus: String = ""
        @Volatile var connectedDeviceName: String? = null
        @Volatile var isServiceRunning: Boolean = false

        /** Last few actions taken — shown in the app so problems are visible. */
        @Volatile var logText: String = ""
        private val eventLines = mutableListOf<String>()

        fun logEvent(message: String) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            synchronized(eventLines) {
                eventLines.add("$time  $message")
                while (eventLines.size > 6) eventLines.removeAt(0)
                logText = eventLines.joinToString("\n")
            }
            Log.i(TAG, message)
        }

        /**
         * One-time default for this version: always-running is ON (auto-start
         * at phone restart/power-on and when Bluetooth turns on), even for
         * installs where the old Stop button had turned auto-run off. After
         * this default is applied once, the user's own toggle choice rules.
         */
        fun ensureAlwaysRunningDefault(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ALWAYS_RUNNING_V2, false)) {
                prefs.edit()
                    .putBoolean(KEY_AUTORUN, true)
                    .putBoolean(KEY_ALWAYS_RUNNING_V2, true)
                    .apply()
            }
        }

        fun isAutoRunEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTORUN, true)

        /**
         * Watchdog: every 15 minutes an alarm wakes the app (via
         * BtWatchdogReceiver) and restarts this service if it was killed —
         * e.g. by Samsung's battery manager. Same PendingIntent each time,
         * so there is always exactly one watchdog alarm scheduled.
         */
        fun scheduleWatchdog(context: Context) {
            try {
                val intent = PendingIntent.getBroadcast(
                    context, 44,
                    Intent(context, BtWatchdogReceiver::class.java).setAction(ACTION_WATCHDOG),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                context.getSystemService(android.app.AlarmManager::class.java)
                    ?.setAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + WATCHDOG_MS, intent
                    )
            } catch (_: Exception) {}
        }

        fun setAutoRunEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTORUN, enabled).apply()
        }

        fun start(context: Context) {
            setAutoRunEnabled(context, true)
            ContextCompat.startForegroundService(
                context, Intent(context, BtAutoConnectService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BtAutoConnectService::class.java).setAction(ACTION_STOP)
            )
        }

        fun connectNow(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BtAutoConnectService::class.java).setAction(ACTION_CONNECT_NOW)
            )
        }
    }
}
