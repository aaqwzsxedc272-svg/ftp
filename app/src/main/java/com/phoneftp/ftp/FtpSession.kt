package com.phoneftp.ftp

import com.phoneftp.FtpSettings
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One FTP control connection. Handles one client until QUIT / disconnect. */
class FtpSession(
    private val control: Socket,
    private val settings: FtpSettings,
    private val root: File,
    private val onClosed: (FtpSession) -> Unit
) {
    @Volatile private var closed = false
    private lateinit var reader: BufferedReader
    private lateinit var writer: BufferedWriter

    private var pendingUser: String? = null
    private var loggedIn = false
    private var anonymous = false

    /** Current directory, always relative to [root] and starting with "/". */
    private var cwd = "/"
    private var binaryMode = true
    private var passiveSocket: ServerSocket? = null
    private var renameFrom: File? = null
    private var restartOffset = 0L

    fun run() {
        try {
            control.soTimeout = 10 * 60 * 1000
            reader = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(control.getOutputStream(), Charsets.UTF_8))
            reply(220, "PhoneFTP ready")
            var line: String?
            while (!closed && reader.readLine().also { line = it } != null) {
                val raw = line!!.trim()
                if (raw.isEmpty()) continue
                val space = raw.indexOf(' ')
                val cmd = (if (space < 0) raw else raw.substring(0, space)).uppercase(Locale.US)
                val arg = if (space < 0) "" else raw.substring(space + 1).trim()
                if (!handle(cmd, arg)) break
            }
        } catch (_: Exception) {
            // Client went away — normal.
        } finally {
            closeQuietly()
            onClosed(this)
        }
    }

    fun closeQuietly() {
        closed = true
        try { passiveSocket?.close() } catch (_: Exception) {}
        try { control.close() } catch (_: Exception) {}
    }

    /** @return false when the session should end. */
    private fun handle(cmd: String, arg: String): Boolean {
        // Commands allowed before login.
        when (cmd) {
            "USER" -> {
                pendingUser = arg
                anonymous = arg.equals("anonymous", true) || arg.equals("ftp", true)
                loggedIn = false
                return if (anonymous && settings.allowAnonymous) {
                    loggedIn = true
                    reply(230, "Anonymous login OK")
                    true
                } else {
                    reply(331, "Password required for $arg")
                    true
                }
            }
            "PASS" -> {
                val userOk = pendingUser == settings.username
                val passOk = arg == settings.password
                return if (userOk && passOk) {
                    loggedIn = true
                    reply(230, "Login successful")
                    true
                } else {
                    reply(530, "Login incorrect")
                    true
                }
            }
            "QUIT" -> { reply(221, "Goodbye"); return false }
            "SYST" -> { reply(215, "UNIX Type: L8"); return true }
            "FEAT" -> {
                raw("211-Features:\r\n UTF8\r\n SIZE\r\n MDTM\r\n EPSV\r\n211 End\r\n")
                return true
            }
            "OPTS" -> { reply(200, "OK"); return true }
            "NOOP" -> { reply(200, "OK"); return true }
            "AUTH" -> { reply(502, "TLS not supported, use plain FTP only on trusted LANs"); return true }
        }

        if (!loggedIn) {
            reply(530, "Please login with USER and PASS")
            return true
        }

        when (cmd) {
            "PWD", "XPWD" -> reply(257, "\"$cwd\" is the current directory")
            "CWD", "XCWD" -> changeDir(arg)
            "CDUP", "XCUP" -> changeDir("..")
            "TYPE" -> {
                binaryMode = !arg.uppercase(Locale.US).startsWith("A")
                reply(200, "Type set to ${if (binaryMode) "I" else "A"}")
            }
            "MODE" -> reply(200, "OK")
            "STRU" -> reply(200, "OK")
            "ALLO" -> reply(200, "OK")
            "PASV" -> openPasv()
            "EPSV" -> openEpsv()
            "PORT", "EPRT" -> reply(502, "Active mode not supported, use passive mode (PASV/EPSV)")
            "LIST", "NLST" -> list(arg, namesOnly = cmd == "NLST")
            "RETR" -> download(arg)
            "STOR" -> upload(arg, append = false)
            "APPE" -> upload(arg, append = true)
            "DELE" -> deleteFile(arg)
            "RMD", "XRMD" -> removeDir(arg)
            "MKD", "XMKD" -> makeDir(arg)
            "RNFR" -> {
                val f = resolve(arg)
                if (f != null && f.exists()) { renameFrom = f; reply(350, "Ready for RNTO") }
                else reply(550, "File not found")
            }
            "RNTO" -> {
                val from = renameFrom; renameFrom = null
                val to = resolve(arg)
                if (from != null && to != null && from.renameTo(to)) reply(250, "Renamed")
                else reply(550, "Rename failed")
            }
            "SIZE" -> {
                val f = resolve(arg)
                if (f != null && f.isFile) reply(213, f.length().toString())
                else reply(550, "File not found")
            }
            "MDTM" -> {
                val f = resolve(arg)
                if (f != null && f.exists()) {
                    val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                    reply(213, fmt.format(Date(f.lastModified())))
                } else reply(550, "File not found")
            }
            "REST" -> {
                restartOffset = arg.toLongOrNull() ?: 0L
                reply(350, "Restarting at $restartOffset")
            }
            else -> reply(502, "Command not implemented")
        }
        return true
    }

    // ---------------------------------------------------------------- commands

    private fun changeDir(arg: String) {
        val target = virtualResolve(arg)
        val file = target?.let { File(root, it.trimStart('/')) }
        if (target != null && file != null && file.isDirectory) {
            cwd = target
            reply(250, "Directory changed to $cwd")
        } else reply(550, "Directory not found")
    }

    private fun openPasv() {
        val data = openPassiveSocket() ?: return
        val addr = control.localAddress.address
        val p1 = data.localPort / 256
        val p2 = data.localPort % 256
        reply(227, "Entering Passive Mode (${addr[0].toInt() and 0xFF},${addr[1].toInt() and 0xFF}," +
            "${addr[2].toInt() and 0xFF},${addr[3].toInt() and 0xFF},$p1,$p2)")
    }

    private fun openEpsv() {
        val data = openPassiveSocket() ?: return
        reply(229, "Entering Extended Passive Mode (|||${data.localPort}|)")
    }

    private fun openPassiveSocket(): ServerSocket? {
        try { passiveSocket?.close() } catch (_: Exception) {}
        for (port in FtpServer.PASSIVE_PORT_RANGE.shuffled()) {
            try {
                val ss = ServerSocket(port, 1, control.localAddress)
                ss.soTimeout = 60_000
                passiveSocket = ss
                return ss
            } catch (_: Exception) { /* port busy, try next */ }
        }
        reply(425, "Cannot open passive data connection")
        return null
    }

    /** Takes ownership of the pending passive socket (one-shot per RFC). */
    private fun acceptData(): Socket? {
        val ss = passiveSocket ?: run { reply(425, "Use PASV first"); return null }
        passiveSocket = null
        return try {
            val s = ss.accept()
            s.soTimeout = 5 * 60 * 1000
            s
        } catch (_: Exception) {
            reply(425, "Data connection failed / timed out")
            null
        } finally {
            try { ss.close() } catch (_: Exception) {}
        }
    }

    private fun list(arg: String, namesOnly: Boolean) {
        val pathArg = arg.takeUnless { it.startsWith("-") } ?: ""
        val dir = resolve(pathArg.ifEmpty { cwd }) ?: return reply(550, "Invalid path")
        if (!dir.exists()) return reply(550, "Not found")
        val entries: List<File> = when {
            dir.isDirectory -> dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
            else -> listOf(dir)
        }
        reply(150, "Opening data connection for directory list")
        val data = acceptData() ?: return
        data.use { socket ->
            val out = socket.getOutputStream()
            val fmt = SimpleDateFormat("MMM dd HH:mm", Locale.US)
            for (f in entries) {
                val line = if (namesOnly) {
                    f.name
                } else {
                    val perms = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
                    val size = if (f.isDirectory) 0 else f.length()
                    String.format(Locale.US, "%s 1 owner group %12d %s %s", perms, size, fmt.format(Date(f.lastModified())), f.name)
                }
                out.write((line + "\r\n").toByteArray(Charsets.UTF_8))
            }
            out.flush()
        }
        reply(226, "Directory send OK")
    }

    private fun download(arg: String) {
        val file = resolve(arg) ?: return reply(550, "Invalid path")
        if (!file.isFile) return reply(550, "Not a regular file")
        reply(150, "Opening data connection for ${file.name} (${file.length()} bytes)")
        val data = acceptData() ?: return
        try {
            data.use { socket ->
                FileInputStream(file).use { input ->
                    if (restartOffset > 0) input.skip(restartOffset)
                    val out = socket.getOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    out.flush()
                }
            }
            reply(226, "Transfer complete")
        } catch (_: Exception) {
            reply(426, "Transfer aborted")
        } finally {
            restartOffset = 0
        }
    }

    private fun upload(arg: String, append: Boolean) {
        val file = resolve(arg) ?: return reply(550, "Invalid path")
        if (file.isDirectory) return reply(550, "Is a directory")
        file.parentFile?.mkdirs()
        reply(150, "Opening data connection for ${file.name}")
        val data = acceptData() ?: return
        try {
            data.use { socket ->
                FileOutputStream(file, append).use { output ->
                    val input = socket.getInputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                }
            }
            reply(226, "Transfer complete")
        } catch (_: Exception) {
            reply(426, "Transfer aborted")
        }
    }

    private fun deleteFile(arg: String) {
        val f = resolve(arg) ?: return reply(550, "Invalid path")
        if (f.isFile && f.delete()) reply(250, "Deleted") else reply(550, "Delete failed")
    }

    private fun removeDir(arg: String) {
        val f = resolve(arg) ?: return reply(550, "Invalid path")
        if (f.isDirectory && f != root && f.delete()) reply(250, "Directory removed")
        else reply(550, "Remove directory failed (not empty?)")
    }

    private fun makeDir(arg: String) {
        val virtual = virtualResolve(arg) ?: return reply(550, "Invalid path")
        val f = File(root, virtual.trimStart('/'))
        if (f.mkdirs() || f.isDirectory) reply(257, "\"$virtual\" created")
        else reply(550, "Create directory failed")
    }

    // ------------------------------------------------------------------ paths

    /**
     * Normalises a client-supplied path to a virtual path ("/a/b") inside the root,
     * resolving "." and "..". Returns null if it tries to escape the root.
     */
    private fun virtualResolve(arg: String): String? {
        val combined = when {
            arg.isEmpty() -> cwd
            arg.startsWith("/") -> arg
            else -> if (cwd == "/") "/$arg" else "$cwd/$arg"
        }
        val parts = mutableListOf<String>()
        for (part in combined.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return "/" + parts.joinToString("/")
    }

    /** Resolves to a real [File] and double-checks (canonical path) it stays in root. */
    private fun resolve(arg: String): File? {
        val virtual = virtualResolve(arg) ?: return null
        val file = File(root, virtual.trimStart('/'))
        return try {
            val rootPath = root.canonicalPath
            val filePath = if (file.exists()) file.canonicalPath else file.absoluteFile.canonicalPath
            if (filePath == rootPath || filePath.startsWith(rootPath + File.separator)) file else null
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ io

    private fun reply(code: Int, message: String) = raw("$code $message\r\n")

    private fun raw(text: String) {
        writer.write(text)
        writer.flush()
    }
}
