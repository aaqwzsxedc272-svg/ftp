package com.btautoconnect.bt

import android.bluetooth.BluetoothDevice

/** One of the user's own audio devices that we auto-connect to. */
data class TargetDevice(
    val displayName: String,
    /** Normalised (uppercase, no spaces/dashes) fragments matched against device names. */
    val matchKeys: List<String>,
    val prefKey: String
)

/**
 * The user's devices, in priority order — the first one found nearby wins.
 * Only ONE of them is ever connected at a time.
 */
object TargetDevices {
    val TARGETS: List<TargetDevice> = listOf(
        TargetDevice(
            displayName = "Roseland TWS RE-500",
            matchKeys = listOf("RE500", "ROSELAND"),
            prefKey = "target_roseland_re500"
        ),
        TargetDevice(
            displayName = "itel T1Neo",
            matchKeys = listOf("T1NEO", "ITEL"),
            prefKey = "target_itel_t1neo"
        ),
        TargetDevice(
            displayName = "MusicMan BT-X78",
            matchKeys = listOf("BTX78", "MUSICMAN"),
            prefKey = "target_musicman_btx78"
        )
    )

    private fun normalise(name: String?): String =
        (name ?: "").uppercase().replace(" ", "").replace("-", "").replace("_", "")

    /** Which of the user's target devices is this (bonded/found) device, if any? */
    fun targetFor(device: BluetoothDevice): TargetDevice? {
        val name = try { device.name } catch (_: SecurityException) { null } catch (_: Exception) { null }
        val normalised = normalise(name)
        if (normalised.isEmpty()) return null
        return TARGETS.firstOrNull { target -> target.matchKeys.any { normalised.contains(it) } }
    }

    fun priorityOf(device: BluetoothDevice): Int {
        val target = targetFor(device) ?: return Int.MAX_VALUE
        return TARGETS.indexOf(target)
    }
}
