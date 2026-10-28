package com.phoneftp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phoneftp.FtpSettings

/**
 * Watchdog alarm (every 15 minutes): if the FTP service was killed (Samsung
 * battery manager, low memory, phone rebooted into a state where boot
 * start was blocked...), this restarts it — provided "Start automatically"
 * is enabled in the app and the user did not explicitly press Stop in the
 * notification/app. Also re-arms the next watchdog alarm.
 *
 * Combined with the boot receiver and the Wi-Fi/hotspot network watchers,
 * this guarantees: whatever happened before — Wi-Fi disconnected, Wi-Fi
 * turned off, phone restarted — as soon as the phone is on, the FTP server
 * comes back on its usual port (default 9999).
 */
class FtpWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != FtpService.ACTION_WATCHDOG) return
        val settings = FtpSettings.load(context)
        if (settings.autoStart && !FtpSettings.isManuallyStopped(context) && !FtpService.isRunning) {
            try {
                FtpService.start(context)
            } catch (_: Exception) {
                // Background-start limits: boot/Wi-Fi receivers and opening
                // the app remain as other restart paths.
            }
        }
        FtpService.scheduleWatchdog(context)
    }
}
