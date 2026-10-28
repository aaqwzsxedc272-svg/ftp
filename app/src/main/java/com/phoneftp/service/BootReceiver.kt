package com.phoneftp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phoneftp.FtpSettings

/**
 * Starts the FTP service automatically after the phone boots or after the app
 * is updated — so the server is "just there" without opening the app.
 * (A reboot also clears the "manually stopped" flag on purpose: after you
 * restart your phone, having your FTP server back is the expected behaviour.
 * Turn off "Start automatically" in the app if you don't want that.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val settings = FtpSettings.load(context)
                if (settings.autoStart) {
                    FtpSettings.setManuallyStopped(context, false)
                    FtpService.start(context)
                    // Arm the watchdog too, so if the service gets killed
                    // later it still comes back on its own — and as soon as
                    // Wi-Fi connects, the network watchers start the FTP.
                    FtpService.scheduleWatchdog(context)
                }
            }
        }
    }
}
