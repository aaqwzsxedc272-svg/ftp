package com.phoneftp.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.phoneftp.FtpSettings
import java.io.File
import com.phoneftp.R
import com.phoneftp.ftp.LocalIp
import com.phoneftp.service.FtpService

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var addressesText: TextView
    private lateinit var rootText: TextView
    private lateinit var portInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var rootInput: EditText
    private lateinit var anonymousCheck: CheckBox
    private lateinit var autoStartCheck: CheckBox

    /** Folder picker (system file picker, "use this folder"). */
    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { /* raw-path access below may still work */ }

            val path = localPathFromTreeUri(uri)
            if (path == null) {
                toast(getString(R.string.folder_pick_failed))
                return@registerForActivityResult
            }
            // The whole picked folder — including every subfolder and file
            // inside it — becomes available over FTP for reading/copying.
            val folder = File(path)
            if (!folder.canRead() || folder.listFiles() == null) {
                // Android is blocking raw file access to this folder: the user
                // needs "All files access" for the server to read it.
                rootInput.setText(path)
                saveSettingsFromUi()
                toast(getString(R.string.folder_needs_access, path))
                requestAllFilesAccess()
                return@registerForActivityResult
            }
            rootInput.setText(path)
            saveSettingsFromUi()
            if (FtpService.isRunning) FtpService.restart(this)
            toast(getString(R.string.folder_picked, path))
        }

    /**
     * Converts a picked folder (SAF tree URI) to a real filesystem path, which is
     * what the FTP server serves. Handles internal storage ("primary"), SD cards
     * (volume id) and the Documents folder ("home"). Returns null for cloud or
     * other non-local providers, which cannot be served as plain files.
     */
    private fun localPathFromTreeUri(uri: Uri): String? {
        return try {
            if (!DocumentsContract.isTreeUri(uri)) return null
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val separator = docId.indexOf(':')
            if (separator < 0) return null
            val volume = docId.substring(0, separator)
            val relative = docId.substring(separator + 1)
            val base = when {
                volume.equals("primary", ignoreCase = true) ->
                    Environment.getExternalStorageDirectory()
                volume.equals("home", ignoreCase = true) ->
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                else -> File("/storage/$volume")
            }
            val folder = if (relative.isEmpty()) base else File(base, relative)
            folder.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private val refreshStatus = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 1_500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        addressesText = findViewById(R.id.addressesText)
        rootText = findViewById(R.id.rootText)
        portInput = findViewById(R.id.portInput)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        rootInput = findViewById(R.id.rootInput)
        anonymousCheck = findViewById(R.id.anonymousCheck)
        autoStartCheck = findViewById(R.id.autoStartCheck)

        val settings = FtpSettings.load(this)
        portInput.setText(settings.port.toString())
        usernameInput.setText(settings.username)
        passwordInput.setText(settings.password)
        rootInput.setText(settings.rootPath)
        anonymousCheck.isChecked = settings.allowAnonymous
        autoStartCheck.isChecked = settings.autoStart
        // By default we share the phone's MAIN storage folder — the one that
        // opens in the Files app. Show its path so it's clear what's shared.
        if (settings.rootPath.isBlank()) {
            rootInput.setText(
                Environment.getExternalStorageDirectory()?.absolutePath ?: ""
            )
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            saveSettingsFromUi()
            FtpService.start(this)
            toast(getString(R.string.starting))
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            FtpService.stop(this)
            toast(getString(R.string.stopped))
        }
        findViewById<Button>(R.id.mainFolderButton).setOnClickListener {
            // Share the main folder that opens in the Files app (internal
            // storage root), with every folder and file inside it.
            val main = Environment.getExternalStorageDirectory()
            if (main == null) {
                toast(getString(R.string.folder_pick_failed))
                return@setOnClickListener
            }
            rootInput.setText(main.absolutePath)
            saveSettingsFromUi()
            if (FtpService.isRunning) FtpService.restart(this)
            if (main.canRead() && main.listFiles() != null) {
                toast(getString(R.string.main_folder_set, main.absolutePath))
            } else {
                // Android blocks reading the storage until the user grants
                // "All files access" — ask for it now.
                toast(getString(R.string.folder_needs_access, main.absolutePath))
                requestAllFilesAccess()
            }
        }
        findViewById<Button>(R.id.pickFolderButton).setOnClickListener {
            // Open the system folder chooser directly on the phone's internal
            // storage, so the user browses the phone's folders and taps
            // "Use this folder" on the one to share. Everything inside it —
            // all subfolders and files — is then readable/copyable over FTP.
            val initialUri = try {
                DocumentsContract.buildTreeDocumentUri(
                    "com.android.externalstorage.documents", "primary:"
                )
            } catch (_: Exception) {
                null
            }
            folderPickerLauncher.launch(initialUri)
        }
        findViewById<Button>(R.id.saveButton).setOnClickListener {
            saveSettingsFromUi()
            if (FtpService.isRunning) FtpService.restart(this)
            toast(getString(R.string.settings_saved))
        }
        // Device line (helps support: e.g. "samsung SM-A127F • Android 13").
        findViewById<TextView>(R.id.deviceText).text = getString(
            R.string.device_info,
            "${Build.MANUFACTURER} ${Build.MODEL}",
            Build.VERSION.RELEASE
        )

        // Samsung (One UI) kills background apps harder than stock Android:
        // show the extra "never sleeping / unrestricted battery" steps only there.
        val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        if (isSamsung) {
            findViewById<TextView>(R.id.samsungText).visibility = android.view.View.VISIBLE
            val appSettingsButton = findViewById<Button>(R.id.appSettingsButton)
            appSettingsButton.visibility = android.view.View.VISIBLE
            appSettingsButton.setOnClickListener {
                // App info page → from there: Battery → Unrestricted.
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        findViewById<Button>(R.id.storagePermissionButton).setOnClickListener { requestAllFilesAccess() }
        findViewById<Button>(R.id.batteryButton).setOnClickListener { requestIgnoreBatteryOptimizations() }

        requestNotificationPermission()

        // Opening the app = server should be up (unless the user turned auto-start off).
        if (settings.autoStart) {
            FtpService.start(this)
            FtpService.scheduleWatchdog(this)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshStatus)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshStatus)
        super.onPause()
    }

    private fun renderStatus() {
        val settings = FtpSettings.load(this)
        statusText.text = if (FtpService.isRunning) {
            getString(R.string.status_running, settings.port)
        } else {
            getString(R.string.status_stopped)
        }
        val ips = LocalIp.getLocalIpAddresses()
        addressesText.text = if (ips.isEmpty()) {
            getString(R.string.no_network)
        } else {
            ips.joinToString("\n") { "ftp://$it:${settings.port}" }
        }
        rootText.text = getString(R.string.serving_folder, settings.resolveRoot(this).absolutePath)
    }

    private fun saveSettingsFromUi() {
        val current = FtpSettings.load(this)
        val port = portInput.text.toString().toIntOrNull()
            ?.coerceIn(1024, 65535) ?: FtpSettings.DEFAULT_PORT
        FtpSettings.save(
            this,
            current.copy(
                port = port,
                username = usernameInput.text.toString().trim()
                    .ifEmpty { FtpSettings.DEFAULT_USERNAME },
                password = passwordInput.text.toString()
                    .ifEmpty { FtpSettings.DEFAULT_PASSWORD },
                rootPath = rootInput.text.toString().trim(),
                allowAnonymous = anonymousCheck.isChecked,
                autoStart = autoStartCheck.isChecked
            )
        )
    }

    // ------------------------------------------------------------ permissions

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            toast(getString(R.string.storage_already_granted))
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
            toast(getString(R.string.battery_already_granted))
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
