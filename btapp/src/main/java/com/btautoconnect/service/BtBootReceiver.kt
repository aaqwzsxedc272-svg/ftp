package com.btautoconnect.service

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the auto-connect service after boot, after app updates, and when
 * Bluetooth is switched on — so your headphones/earbuds connect by
 * themselves without opening the app.
 */
class BtBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val shouldStart = when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> true
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                    BluetoothAdapter.STATE_ON
            }
            else -> false
        }
        if (shouldStart && BtAutoConnectService.isAutoRunEnabled(context)) {
            try {
                BtAutoConnectService.start(context)
            } catch (_: Exception) {
                // Foreground-service background-start limits on some versions;
                // the service also restarts itself via alarm when running.
            }
        }
    }
}
