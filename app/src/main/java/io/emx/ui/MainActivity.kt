package io.emx.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.emx.runtime.RuntimeService
import io.emx.runtime.RuntimeState
import io.emx.runtime.TermuxBridge
import io.emx.term.PtyRunner
import io.emx.term.TerminalEmulator

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroller: ScrollView
    private lateinit var terminalHost: LinearLayout
    private lateinit var setupButton: Button
    private lateinit var probeButton: Button
    private lateinit var shellButton: Button
    private lateinit var x11Button: Button
    private lateinit var cubeButton: Button
    private var terminalView: TerminalView? = null
    private var ptyRunner: PtyRunner? = null
    private var pendingOperation: String? = null
    private val arm64 get() = Build.SUPPORTED_ABIS.contains("arm64-v8a")
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val termuxPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val operation = pendingOperation
        pendingOperation = null
        if (granted && operation != null) startOperation(operation)
        else log("Termux command permission was not granted. Enable it in emX app settings to connect.")
        refresh()
    }
    private val terminalBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { terminalView?.toggleKeyboard() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(this, terminalBack)
        RuntimeState.preferences(this).registerOnSharedPreferenceChangeListener(this)
        if (savedInstanceState == null) {
            val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
            log("emX · ${Build.MODEL} · $soc · ${Build.SUPPORTED_ABIS.joinToString()}\nTermux-backed Linux runtime; the in-app shell is Android diagnostics only.")
        }
        refresh()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left + dp(12), bars.top + dp(12), bars.right + dp(12), maxOf(bars.bottom, ime.bottom) + dp(12))
            insets
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(16), 0)
        }
        controls.addView(TextView(this).apply { text = "emX"; textSize = 32f })
        controls.addView(TextView(this).apply { text = "Linux + Xclipse Vulkan · experimental"; textSize = 13f })
        statusView = TextView(this).apply { textSize = 13f; setPadding(0, dp(12), 0, dp(12)) }
        controls.addView(statusView)
        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            setOnClickListener { action() }
            controls.addView(this)
        }
        button("Connect Termux / help") { showHelp() }
        setupButton = button("1. Install Linux") {
            AlertDialog.Builder(this).setTitle("Install Linux in Termux?")
                .setMessage("Downloads Debian bookworm and packages (allow at least 2 GiB free). Creates the dedicated emx-debian container and installs the bundled third-party RADV driver. Existing unrelated containers are not modified. This version requires Termux; it is not a standalone Linux runtime.")
                .setNegativeButton("Cancel", null).setPositiveButton("Install") { _, _ -> requestOperation("setup") }.show()
        }
        probeButton = button("2. Check Vulkan") { requestOperation("probe") }
        shellButton = button("Open Linux terminal (Termux)") { requestOperation("shell") }
        x11Button = button("3. Install X11 tools") {
            AlertDialog.Builder(this).setTitle("Install Termux:X11 tools?")
                .setMessage("Adds Termux's X11 package repository and installs termux-x11-nightly. The separate Termux:X11 Android app must also be installed.")
                .setNegativeButton("Cancel", null).setPositiveButton("Install") { _, _ -> requestOperation("x11-setup") }.show()
        }
        cubeButton = button("4. Launch Vulkan cube") { requestOperation("cube") }
        button("Open Termux:X11 display") { openPackage(TermuxBridge.X11_PACKAGE) }
        button("Android diagnostic shell") { showAndroidTerminal() }
        button("Show setup log") { showLog() }
        button("Copy diagnostics") {
            val text = statusView.text.toString() + "\n" + RuntimeState.preferences(this).getString("log", "")
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("emX diagnostics", text))
        }
        val left = ScrollView(this).apply { addView(controls) }
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.4f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logScroller = ScrollView(this).apply { addView(logView); isFillViewport = true }
        terminalHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        right.addView(logScroller, LinearLayout.LayoutParams(-1, -1))
        right.addView(terminalHost, LinearLayout.LayoutParams(-1, -1))
        root.addView(right, LinearLayout.LayoutParams(0, -1, 0.6f))
        return root
    }

    private fun showHelp() {
        val instructions = "Install Termux and Termux:X11 from their official sources, then open Termux once.\n\nIn ~/.termux/termux.properties set:\nallow-external-apps=true\n\nRun termux-reload-settings and return to emX. Grant the run-command permission when prompted. Only grant this permission to apps you trust: it permits commands inside Termux.\n\nSetup uses the current proot-distro OCI CLI (--name); update Termux packages if your version does not support it. Linux and graphics sessions run in Termux, not the Android diagnostic shell. Termux may need its documented background/display permissions to show a new session."
        AlertDialog.Builder(this).setTitle("Connect Termux").setMessage(instructions)
            .setPositiveButton("Open Termux") { _, _ -> openPackage(TermuxBridge.PACKAGE) }
            .setNeutralButton("Copy instructions") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Termux setup", instructions))
            }.setNegativeButton("Close", null).show()
    }

    private fun requestOperation(operation: String) {
        if (!TermuxBridge.isInstalled(this)) { showHelp(); return }
        if (!TermuxBridge.hasPermission(this)) {
            pendingOperation = operation
            termuxPermission.launch(TermuxBridge.PERMISSION)
            return
        }
        startOperation(operation)
    }

    private fun startOperation(operation: String) {
        try {
            if (operation == "shell" || operation == "cube") {
                TermuxBridge.dispatch(this, operation, false)
                log("$operation requested in Termux. Check its session for errors; dispatch does not prove it started. For graphics, open Termux:X11; Ctrl+C in the Termux session stops the cube and its X server.")
            } else {
                if (RuntimeState.preferences(this).contains("operationId")) return
                ContextCompat.startForegroundService(this, Intent(this, RuntimeService::class.java).putExtra("operation", operation))
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                showLog()
            }
        } catch (e: Exception) {
            log("Could not start $operation: ${e.message}")
        }
    }

    private fun openPackage(name: String) {
        val intent = packageManager.getLaunchIntentForPackage(name)
        if (intent == null) log("$name is not installed or has no launcher. Install the official app first.")
        else runCatching { startActivity(intent) }.onFailure { log("Cannot open $name: ${it.message}") }
    }

    private fun showAndroidTerminal() {
        logScroller.visibility = View.GONE
        terminalHost.visibility = View.VISIBLE
        terminalBack.isEnabled = true
        val view = terminalView ?: TerminalView(this).also {
            it.attach(TerminalEmulator(80, 24))
            it.isFocusableInTouchMode = true
            it.onInput = { bytes -> ptyRunner?.write(bytes) }
            it.onResize = { cols, rows -> ptyRunner?.resize(cols, rows) }
            terminalHost.addView(it, LinearLayout.LayoutParams(-1, -1))
            terminalView = it
        }
        if (ptyRunner?.isRunning != true) {
            ptyRunner?.stop()
            ptyRunner = PtyRunner(
                command = listOf("/system/bin/sh", "-i"), workingDir = filesDir,
                env = mapOf("TERM" to "xterm-256color"),
                onOutput = { data, len -> view.feed(data, len) },
                onExit = { code -> runOnUiThread { log("Android diagnostic shell exited: $code") } },
            ).also {
                if (!it.start()) log("Unable to start Android diagnostic shell.")
                else it.resize(view.emulator.width(), view.emulator.height())
            }
        }
        view.requestFocus()
    }

    private fun showLog() {
        terminalHost.visibility = View.GONE
        logScroller.visibility = View.VISIBLE
        terminalBack.isEnabled = false
        WindowCompat.getInsetsController(window, logScroller).hide(WindowInsetsCompat.Type.ime())
    }

    private fun refresh() {
        val prefs = RuntimeState.preferences(this)
        val busy = prefs.contains("operationId")
        val ready = RuntimeState.ready(this)
        val gpu = ready && prefs.getBoolean("gpuVerified", false)
        statusView.text = buildString {
            appendLine(if (TermuxBridge.isInstalled(this@MainActivity)) "Termux: installed" else "Termux: not installed")
            appendLine(if (TermuxBridge.hasPermission(this@MainActivity)) "Command permission: granted" else "Command permission: required")
            appendLine(if (arm64) "Architecture: ARM64" else "Architecture: unsupported by bundled driver")
            appendLine(if (ready) "Linux: installed (last setup)" else "Linux: setup required")
            appendLine(if (gpu) "Vulkan: Xclipse RADV enumerated (last check)" else "Vulkan: not verified by emX")
            appendLine("Rendering: verify visually with the cube")
            append(prefs.getString("status", "Ready for setup"))
        }
        setupButton.isEnabled = arm64 && !busy
        probeButton.isEnabled = ready && !busy
        shellButton.isEnabled = ready && !busy
        x11Button.isEnabled = ready && !busy
        cubeButton.isEnabled = gpu && !busy && prefs.getBoolean("x11Tools", false) && TermuxBridge.isInstalled(this, TermuxBridge.X11_PACKAGE)
        val output = prefs.getString("log", "").orEmpty()
        if (logView.text.toString() != output) {
            logView.text = output
            logScroller.post { logScroller.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = RuntimeState.preferences(this)
        val id = prefs.getString("operationId", null)
        if (id != null && System.currentTimeMillis() > prefs.getLong("deadline", 0)) {
            RuntimeState.finish(this, id, false, "No result arrived before the deadline. Check Termux before retrying.")
            stopService(Intent(this, RuntimeService::class.java))
        }
        refresh()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) = refresh()

    private fun log(message: String) = RuntimeState.append(this, message)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        RuntimeState.preferences(this).unregisterOnSharedPreferenceChangeListener(this)
        ptyRunner?.stop()
        super.onDestroy()
    }
}
