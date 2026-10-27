package com.phoneftp.ftp

import android.util.Log
import com.phoneftp.FtpSettings
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal but real FTP server (RFC 959 + SIZE/MDTM/EPSV extensions).
 *
 * - Binds 0.0.0.0, so it is reachable on whatever interface is currently up:
 *   the Wi-Fi network the phone joined, the phone's own hotspot, ethernet, ...
 *   Because it never binds to one specific IP, changing Wi-Fi or toggling the
 *   hotspot never requires rebinding — clients just use the phone's new IP.
 * - Passive mode only (PASV/EPSV). Active mode (PORT) is refused: it cannot
 *   work reliably through NAT/hotspot setups and is a security liability.
 */
class FtpServer(
    private val settings: FtpSettings,
    private val root: File
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val sessions = ConcurrentHashMap.newKeySet<FtpSession>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "ftp-session").apply { isDaemon = true }
    }

    val isRunning: Boolean get() = running.get()
    val clientCount: Int get() = sessions.size

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val socket = ServerSocket(settings.port, 50, InetAddress.getByName("0.0.0.0"))
            serverSocket = socket
            running.set(true)
            Thread({ acceptLoop(socket) }, "ftp-accept").apply { isDaemon = true }.start()
            Log.i(TAG, "FTP server listening on 0.0.0.0:${settings.port}, root=${root.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FTP server on port ${settings.port}", e)
            running.set(false)
            false
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        sessions.forEach { it.closeQuietly() }
        sessions.clear()
        Log.i(TAG, "FTP server stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                val session = FtpSession(client, settings, root) { sessions.remove(it) }
                sessions.add(session)
                executor.execute { session.run() }
            } catch (_: SocketException) {
                if (running.get()) Log.w(TAG, "Accept socket error")
                break
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Accept failed", e)
            }
        }
    }

    companion object {
        const val TAG = "PhoneFTP"
        /** Passive data ports handed to clients. */
        val PASSIVE_PORT_RANGE = 50000..50100
    }
}
