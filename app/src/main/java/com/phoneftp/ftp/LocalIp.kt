package com.phoneftp.ftp

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalIp {
    /**
     * All usable LAN IPv4 addresses of this phone (Wi-Fi client, Wi-Fi hotspot/AP,
     * ethernet, USB tethering...). Reading the interfaces directly — instead of only
     * asking ConnectivityManager — is what makes the hotspot case work: when the
     * phone *is* the access point it has no "upstream" network, but its `ap*`/`wlan*`
     * interface still owns e.g. 192.168.43.1.
     */
    fun getLocalIpAddresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { if (it !in result) result.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
            // No network yet — caller retries on the next network-change event.
        }
        // Prefer typical LAN / hotspot addresses first.
        return result.sortedBy { if (it.startsWith("192.168.") || it.startsWith("10.")) 0 else 1 }
    }

    fun primary(): String? = getLocalIpAddresses().firstOrNull()
}
