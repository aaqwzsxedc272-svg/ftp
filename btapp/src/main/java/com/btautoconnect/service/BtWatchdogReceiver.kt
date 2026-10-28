package com.btautoconnect.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Watchdog alarm (every 15 minutes): if the auto-connect service was killed
 * (Samsung battery manager, low memory, swipe-away...), this restarts it —
 * as long as the user hasn't turned "always running" off in the app.
 * Also re-arms the next watchdog alarm, so the check keeps happening.
 */
class BtWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != BtAutoConnectService.ACTION_WATCHDOG) return
        BtAutoConnectService.ensureAlwaysRunningDefault(context)
        if (BtAutoConnectService.isAutoRunEnabled(context)) {
            try {
                BtAutoConnectService.start(context)
            } catch (_: Exception) {
                // Background-start limits: boot/Bluetooth-on receivers and
                // opening the app remain as other restart paths.
            }
        }
        // Re-arm the next check (also happens in the service's onCreate).
        BtAutoConnectService.scheduleWatchdog(context)
    }
}
