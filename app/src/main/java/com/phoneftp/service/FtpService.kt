package com.phoneftp.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phoneftp.FtpSettings
import com.phoneftp.R
import com.phoneftp.ftp.FtpServer
import com.phoneftp.ftp.LocalIp
import com.phoneftp.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "always on" part of the app.
 *
 * Permanence strategy (as permanent as Android allows a normal app to be):
 *  1. Foreground service + START_STICKY — Android restarts it after process death
 *     and the persistent notification keeps it out of the low-memory killer's way.
 *  2. Boot / app-update receiver — starts it again after a reboot (BootReceiver).
 *  3. ConnectivityManager.NetworkCallback + Wi-Fi AP state broadcasts — whenever
 *     the network changes (new Wi-Fi joined, Wi-Fi lost, hotspot toggled on/off),
 *     we make sure the server is running and refresh the address shown.
 *     The server binds 0.0.0.0, so no rebinding is needed: it is reachable on the
 *     new interface as soon as the interface has an address.
 *  4. onTaskRemoved / onDestroy schedule an AlarmManager restart as a last resort.
 *
 * The user can still stop it explicitly from the UI/notification; that sets a
 * "manually stopped" flag so the auto-restart mechanisms respect the choice
 * until the user (or a reboot, which re-checks auto-start setting) starts it again.
 */
class FtpService : Service() {

    private var server: FtpServer? = null
    private var connectivityManager: ConnectivityManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val networkCallbackRegistered = AtomicBoolean(false)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged("available")
        override fun onLost(network: Network) = onNetworkChanged("lost")
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) =
            onNetworkChanged("linkProperties")
        override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) =
            updateNotification()
    }

    /** Wi-Fi hotspot (AP) on/off + Wi-Fi on/off — hotspot is not a ConnectivityManager "network". */
    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = onNetworkChanged("wifi-state")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithNotification()
        acquireLocks()
        registerNetworkWatchers()
        scheduleWatchdog(this)
        ensureServerRunning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                FtpSettings.setManuallyStopped(this, true)
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                // Full restart with the user's saved settings (port 9999,
                // username/password, shared folder...): ensureServerRunning()
                // re-reads FtpSettings, so whatever is currently saved in the
                // app is what the server comes back up with.
                android.util.Log.i(FtpServer.TAG, "Restart requested — restarting FTP server with saved settings")
                FtpSettings.setManuallyStopped(this, false)
                releaseLocks()
                acquireLocks()
                stopServer()
                ensureServerRunning()
                // Safety retry in case the old socket was still closing.
                mainHandler.postDelayed({
                    if (server?.isRunning != true && shouldAutoRun()) ensureServerRunning()
                    updateNotification()
                }, 1_500)
            }
            else -> { // ACTION_START or system restart (null intent)
                FtpSettings.setManuallyStopped(this, false)
                ensureServerRunning()
            }
        }
        updateNotification()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from recents — come back (unless the user pressed Stop).
        if (shouldAutoRun()) scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (shouldAutoRun()) scheduleRestart()
        unregisterNetworkWatchers()
        stopServer()
        releaseLocks()
        super.onDestroy()
    }

    // ------------------------------------------------------------- internals

    private fun shouldAutoRun(): Boolean {
        val settings = FtpSettings.load(this)
        return settings.autoStart && !FtpSettings.isManuallyStopped(this)
    }

    @Synchronized
    private fun ensureServerRunning() {
        if (server?.isRunning == true) {
            updateNotification()
            return
        }
        val settings = FtpSettings.load(this)
        val newServer = FtpServer(settings, settings.resolveRoot(this))
        if (newServer.start()) server = newServer
        isRunning = server?.isRunning == true
        activePort = settings.port
        updateNotification()
    }

    @Synchronized
    private fun stopServer() {
        server?.stop()
        server = null
        isRunning = false
    }

    private fun onNetworkChanged(reason: String) {
        android.util.Log.i(FtpServer.TAG, "Network changed ($reason) — ensuring FTP server is up")
        // Give the interface a moment to actually get its IP address.
        // This covers: joining a new Wi-Fi, switching Wi-Fi, and turning the
        // phone's own hotspot (point d'accès mobile) ON or OFF. The server
        // binds 0.0.0.0, so it stays on the SAME configured port (default
        // 9999) on every interface — clients just use the phone's new IP.
        // Two retries: a hotspot interface can take a few seconds to get
        // its address (typically 192.168.43.x on Samsung).
        mainHandler.postDelayed({
            if (shouldAutoRun()) ensureServerRunning() else updateNotification()
        }, 800)
        mainHandler.postDelayed({
            if (shouldAutoRun()) ensureServerRunning() else updateNotification()
        }, 3_000)
        mainHandler.postDelayed({ updateNotification() }, 6_000)
    }

    private fun registerNetworkWatchers() {
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        try {
            // No TRANSPORT/INTERNET restriction: we also care about networks
            // without internet (e.g. a router with no WAN, tethering links).
            val request = NetworkRequest.Builder().build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered.set(true)
        } catch (_: Exception) { /* some OEMs restrict this — Wi-Fi receiver below still covers us */ }

        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction("android.net.conn.TETHER_STATE_CHANGED")
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(wifiStateReceiver, filter)
        }
    }

    private fun unregisterNetworkWatchers() {
        if (networkCallbackRegistered.compareAndSet(true, false)) {
            try { connectivityManager?.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        }
        try { unregisterReceiver(wifiStateReceiver) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneFTP:server")
                ?.apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
        try {
            wifiLock = getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL, "PhoneFTP:wifi")
                ?.apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
    }

    private fun scheduleRestart() {
        try {
            val intent = PendingIntent.getService(
                this, 42, Intent(this, FtpService::class.java).setAction(ACTION_START),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            getSystemService(AlarmManager::class.java)
                ?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2_000, intent)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ notification

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notification_channel_desc) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (server == null && !isRunning) return
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val settings = FtpSettings.load(this)
        val ips = LocalIp.getLocalIpAddresses()
        val text = when {
            server?.isRunning == true && ips.isNotEmpty() ->
                ips.joinToString("  •  ") { "ftp://$it:${settings.port}" }
            server?.isRunning == true -> getString(R.string.notification_waiting_network)
            else -> getString(R.string.notification_starting)
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, FtpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val restartIntent = PendingIntent.getService(
            this, 2, Intent(this, FtpService::class.java).setAction(ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ftp_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.restart), restartIntent)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.phoneftp.action.START"
        const val ACTION_STOP = "com.phoneftp.action.STOP"
        const val ACTION_RESTART = "com.phoneftp.action.RESTART"
        const val ACTION_WATCHDOG = "com.phoneftp.action.WATCHDOG"
        private const val WATCHDOG_MS = 15 * 60_000L

        private const val CHANNEL_ID = "phone_ftp_server"
        private const val NOTIFICATION_ID = 1001
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        /** Lets the UI show live status without IPC plumbing. */
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var activePort: Int = FtpSettings.DEFAULT_PORT
            private set

        fun start(context: Context) {
            FtpSettings.setManuallyStopped(context, false)
            ContextCompat.startForegroundService(
                context, Intent(context, FtpService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FtpService::class.java).setAction(ACTION_STOP))
        }

        fun restart(context: Context) {
            FtpSettings.setManuallyStopped(context, false)
            ContextCompat.startForegroundService(
                context, Intent(context, FtpService::class.java).setAction(ACTION_RESTART)
            )
        }

        /**
         * Arms (or re-arms) the 15-minute watchdog alarm — see
         * [FtpWatchdogReceiver]. Same PendingIntent each time, so there is
         * always exactly one watchdog scheduled.
         */
        fun scheduleWatchdog(context: Context) {
            try {
                val intent = PendingIntent.getBroadcast(
                    context, 45,
                    Intent(context, FtpWatchdogReceiver::class.java).setAction(ACTION_WATCHDOG),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                context.getSystemService(AlarmManager::class.java)
                    ?.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + WATCHDOG_MS, intent
                    )
            } catch (_: Exception) {}
        }
    }
}
