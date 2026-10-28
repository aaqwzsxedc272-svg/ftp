package com.btautoconnect.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.btautoconnect.R
import com.btautoconnect.bt.TargetDevices
import com.btautoconnect.service.BtAutoConnectService

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var bluetoothText: TextView
    private lateinit var targetsContainer: LinearLayout

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        bluetoothText = findViewById(R.id.bluetoothText)
        targetsContainer = findViewById(R.id.targetsContainer)

        buildTargetToggles()

        // Always running: auto-launch after phone restart/power-on and when
        // Bluetooth turns on. On by default (applied once by the service).
        BtAutoConnectService.ensureAlwaysRunningDefault(this)
        val autoStartCheck = findViewById<CheckBox>(R.id.autoStartCheck)
        autoStartCheck.isChecked = BtAutoConnectService.isAutoRunEnabled(this)
        autoStartCheck.setOnCheckedChangeListener { _, isChecked ->
            BtAutoConnectService.setAutoRunEnabled(this, isChecked)
            if (isChecked) {
                ensurePermissionsThen {
                    BtAutoConnectService.start(this)
                    BtAutoConnectService.scheduleWatchdog(this)
                }
            }
        }

        findViewById<Button>(R.id.connectNowButton).setOnClickListener {
            ensurePermissionsThen { BtAutoConnectService.connectNow(this) }
        }
        findViewById<Button>(R.id.startButton).setOnClickListener {
            ensurePermissionsThen {
                BtAutoConnectService.start(this)
                toast(getString(R.string.service_started))
            }
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            BtAutoConnectService.stop(this)
            toast(getString(R.string.service_stopped))
        }
        findViewById<Button>(R.id.pairButton).setOnClickListener {
            // Pairing itself must be done in Android's Bluetooth settings
            // (Android does not allow apps to pair headsets silently).
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        ensurePermissionsThen {
            if (BtAutoConnectService.isAutoRunEnabled(this)) {
                BtAutoConnectService.start(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    /** One checkbox per target device: enabled devices are auto-connected (in list order). */
    private fun buildTargetToggles() {
        targetsContainer.removeAllViews()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        for (target in TargetDevices.TARGETS) {
            val checkBox = CheckBox(this).apply {
                text = target.displayName
                textSize = 16f
                isChecked = prefs.getBoolean(target.prefKey, true)
                setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean(target.prefKey, isChecked).apply()
                    BtAutoConnectService.connectNow(this@MainActivity)
                }
            }
            targetsContainer.addView(checkBox)
        }
    }

    private fun render() {
        statusText.text = when {
            BtAutoConnectService.currentStatus.isNotBlank() -> BtAutoConnectService.currentStatus
            BtAutoConnectService.isServiceRunning -> getString(R.string.status_searching)
            else -> getString(R.string.status_stopped)
        }
        findViewById<TextView>(R.id.logText).text =
            BtAutoConnectService.logText.ifBlank { getString(R.string.log_empty) }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        bluetoothText.text = when {
            adapter == null -> getString(R.string.status_no_bluetooth)
            adapter.isEnabled -> getString(R.string.bluetooth_on)
            else -> getString(R.string.bluetooth_off_hint)
        }
    }

    // -------------------------------------------------------------- permissions

    private fun ensurePermissionsThen(action: () -> Unit) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) needed.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!granted(Manifest.permission.POST_NOTIFICATIONS)) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) {
            action()
        } else {
            pendingAction = action
            requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private var pendingAction: (() -> Unit)? = null

    @Deprecated("Legacy permission callback")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            pendingAction?.invoke()
            pendingAction = null
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_PERMISSIONS = 20
        private const val PREFS = "bt_auto_connect_prefs"
    }
}
