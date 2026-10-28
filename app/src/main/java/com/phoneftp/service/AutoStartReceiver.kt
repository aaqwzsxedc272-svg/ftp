package com.phoneftp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phoneftp.FtpSettings

/**
 * Extra safety net for network changes, including the phone's own hotspot
 * (point d'accès mobile) being turned on/off.
 *
 * The running [FtpService] already watches these events itself; this
 * manifest-declared receiver covers the case where the system had killed
 * the app process first — when the hotspot/Wi-Fi state changes, Android
 * can wake the app through this receiver and we restart the FTP service
 * (on the same configured port, default 9999, and same shared folder).
 *
 * Respects the user's choices: does nothing if auto-start is turned off
 * in the app, or if the user explicitly pressed Stop (until next start/boot).
 */
class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            "android.net.wifi.WIFI_AP_STATE_CHANGED",
            "android.net.conn.TETHER_STATE_CHANGED",
            "android.net.conn.CONNECTIVITY_CHANGE",
            "android.net.wifi.STATE_CHANGE",
            "android.net.wifi.WIFI_STATE_CHANGED" -> {
                val settings = FtpSettings.load(context)
                if (settings.autoStart && !FtpSettings.isManuallyStopped(context)) {
                    try {
                        FtpService.start(context)
                    } catch (_: Exception) {
                        // Background-start restrictions on some versions —
                        // the foreground service's own watchers + boot
                        // receiver remain as fallbacks.
                    }
                }
            }
        }
    }
}
