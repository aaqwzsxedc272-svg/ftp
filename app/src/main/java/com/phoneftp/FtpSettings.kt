package com.phoneftp

import android.content.Context
import android.os.Environment
import java.io.File

/** User-configurable settings, persisted in SharedPreferences. */
data class FtpSettings(
    val port: Int = DEFAULT_PORT,
    val username: String = DEFAULT_USERNAME,
    val password: String = DEFAULT_PASSWORD,
    val allowAnonymous: Boolean = false,
    val autoStart: Boolean = true,
    /** Empty = automatic (all-files access ? shared storage root : app-private folder). */
    val rootPath: String = ""
) {
    fun resolveRoot(context: Context): File {
        val root = when {
            rootPath.isNotBlank() -> File(rootPath)
            // Default: share the phone's MAIN storage folder — the one that
            // opens when you open the Files app (/storage/emulated/0), with
            // all its folders (DCIM, Download, Documents, ...) and files.
            // Needs "All files access" to be readable on Android 11+; the UI
            // prompts for it.
            else -> Environment.getExternalStorageDirectory()
                ?: context.getExternalFilesDir(null)
                ?: context.filesDir
        }
        if (!root.exists()) root.mkdirs()
        return root
    }

    companion object {
        const val DEFAULT_PORT = 2121
        const val DEFAULT_USERNAME = "ftpuser"
        const val DEFAULT_PASSWORD = "change-me"

        private const val PREFS = "phone_ftp_settings"
        private const val KEY_MANUALLY_STOPPED = "manually_stopped"

        fun isAllFilesAccessGranted(): Boolean =
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()

        fun load(context: Context): FtpSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return FtpSettings(
                port = p.getInt("port", DEFAULT_PORT).coerceIn(1024, 65535),
                username = p.getString("username", DEFAULT_USERNAME)?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_USERNAME,
                password = p.getString("password", DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD,
                allowAnonymous = p.getBoolean("allow_anonymous", false),
                autoStart = p.getBoolean("auto_start", true),
                rootPath = p.getString("root_path", "") ?: ""
            )
        }

        fun save(context: Context, settings: FtpSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("port", settings.port)
                .putString("username", settings.username)
                .putString("password", settings.password)
                .putBoolean("allow_anonymous", settings.allowAnonymous)
                .putBoolean("auto_start", settings.autoStart)
                .putString("root_path", settings.rootPath)
                .apply()
        }

        /** True when the user explicitly pressed Stop (auto-restart must respect that). */
        fun isManuallyStopped(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MANUALLY_STOPPED, false)

        fun setManuallyStopped(context: Context, stopped: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_MANUALLY_STOPPED, stopped)
                .apply()
        }
    }
}
