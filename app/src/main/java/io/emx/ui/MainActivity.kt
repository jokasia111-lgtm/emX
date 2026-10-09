package io.emx.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.emx.core.DriverPayload
import io.emx.term.PtyRunner
import io.emx.term.TerminalEmulator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Landscape-first setup screen. Shows device compatibility, driver payload
 * status and a log view that can be swapped for a live terminal (PTY) via a
 * "Start terminal" button; the log/terminal area is toggled back and forth
 * with a small toolbar button.
 *
 * Back key behavior:
 *  - Terminal visible and focused → toggle the soft keyboard (hide if shown,
 *    show if hidden) — the product requirement for the terminal milestone.
 *  - Log view focused (text-entry context) → same toggle.
 *  - Otherwise → default activity navigation.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var logScroller: ScrollView
    private lateinit var statusView: TextView
    private lateinit var actionButton: Button
    private lateinit var startTerminalButton: Button
    private lateinit var toggleViewButton: Button
    private lateinit var terminalHost: LinearLayout
    private var terminalView: TerminalView? = null
    private var ptyRunner: PtyRunner? = null
    private var showLogInsteadOfTerminal = true
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        log("emX ${versionName()} starting")
        logDeviceCheck()
    }

    private fun versionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

    private fun buildLayout(): View {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
        }

        // Left panel: identity + status + action
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(24), 0)
        }
        val title = TextView(this).apply {
            text = "emX"
            textSize = 40f
            setPadding(0, 0, 0, dp(4))
        }
        val subtitle = TextView(this).apply {
            text = "proot Linux on Samsung Xclipse — experimental"
            textSize = 14f
            alpha = 0.7f
            setPadding(0, 0, 0, dp(16))
        }
        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(16))
        }
        actionButton = Button(this).apply {
            text = "Extract driver payload"
            setOnClickListener { extractDriver() }
        }
        startTerminalButton = Button(this).apply {
            text = "Start terminal"
            setOnClickListener { startTerminal() }
        }
        toggleViewButton = Button(this).apply {
            text = "Show log"
            visibility = View.GONE
            setOnClickListener { toggleLogTerminal() }
        }
        left.addView(title)
        left.addView(subtitle)
        left.addView(statusView)
        left.addView(actionButton)
        left.addView(startTerminalButton)
        left.addView(toggleViewButton)

        // Right panel: log view by default; the terminal swaps in over it.
        logScroller = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f)
            isFillViewport = true
        }
        logView = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        logScroller.addView(logView)
        // Tapping the log area shows the soft keyboard (text-entry context);
        // Back toggles it. Selection handles need focusable-in-touch-mode.
        logView.isFocusableInTouchMode = true
        logScroller.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) showKeyboard()
            false
        }

        // The terminal host occupies the same slot as the log scroller; only
        // one of them is visible at a time (toggled by toggleViewButton).
        terminalHost = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f)
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(0xFF000000.toInt())
        }

        root.addView(left)
        root.addView(logScroller)
        root.addView(terminalHost)
        return root
    }

    @Deprecated("Deprecated in API 33, still needed for minSdk 26")
    override fun onBackPressed() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val term = terminalView
        // Terminal is the priority context for the Back-toggles-keyboard product rule.
        if (!showLogInsteadOfTerminal && term != null && term.hasFocus()) {
            if (imm.isAcceptingText && imm.isActive(term)) {
                imm.hideSoftInputFromWindow(term.windowToken, 0)
            } else {
                term.requestFocus()
                imm.showSoftInput(term, InputMethodManager.SHOW_IMPLICIT)
            }
            return
        }
        if (showLogInsteadOfTerminal && logView.hasWindowFocus() && imm.isActive(logView) && logView.hasFocus()) {
            // Text-entry context: Back toggles the soft keyboard.
            if (imm.isAcceptingText) imm.hideSoftInputFromWindow(logView.windowToken, 0)
            else showKeyboard()
            return
        }
        super.onBackPressed()
    }

    private fun showKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        logView.requestFocus()
        imm.showSoftInput(logView, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Launch (or reveal) the terminal over the log area and start the PTY runner. */
    private fun startTerminal() {
        if (terminalView == null) {
            // Create the terminal view and emulator sized for the available space.
            val host = terminalHost
            host.post {
                val width = host.width
                val height = host.height
                val paint = android.graphics.Paint().apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = 14f * resources.displayMetrics.scaledDensity
                }
                val cw = paint.measureText("M")
                val ch = (paint.fontMetrics.descent - paint.fontMetrics.ascent).coerceAtLeast(paint.textSize)
                val cols = (width / cw).toInt().coerceAtLeast(20)
                val rows = (height / ch).toInt().coerceAtLeast(4)
                val emulator = TerminalEmulator(cols, rows)
                val view = TerminalView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    attach(emulator)
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                host.addView(view)
                terminalView = view
                startPtyRunner(view, emulator)
                toggleLogTerminal()
            }
        } else {
            // Already created; just show it (and restart the runner if it died).
            val view = terminalView!!
            if (ptyRunner?.isRunning != true) {
                startPtyRunner(view, view.emulator)
            }
            if (showLogInsteadOfTerminal) toggleLogTerminal()
        }
    }

    /**
     * Start the PTY runner. Runs `/system/bin/sh -i` with a real PTY when
     * `Os.openpty` is available; falls back to pipes when it is not.
     */
    private fun startPtyRunner(view: TerminalView, emulator: TerminalEmulator) {
        val runner = PtyRunner(
            command = listOf("/system/bin/sh", "-i"),
            workingDir = filesDir,
            onOutput = { data, len -> view.feed(data, len) },
            onExit = { code -> log("terminal exited with code $code") }
        )
        ptyRunner = runner
        if (!runner.start()) {
            log("failed to start terminal process")
        } else {
            log("terminal started (${if (runner.usingPty) "PTY" else "pipes"} mode)")
        }
    }

    /** Swap between the log view and the terminal view in the right panel. */
    private fun toggleLogTerminal() {
        showLogInsteadOfTerminal = !showLogInsteadOfTerminal
        if (showLogInsteadOfTerminal) {
            terminalHost.visibility = View.GONE
            logScroller.visibility = View.VISIBLE
            toggleViewButton.text = "Show terminal"
        } else {
            logScroller.visibility = View.GONE
            terminalHost.visibility = View.VISIBLE
            toggleViewButton.text = "Show log"
            terminalView?.requestFocus()
        }
        toggleViewButton.visibility = View.VISIBLE
    }

    private fun logDeviceCheck() {
        val soc = android.os.Build.SOC_MODEL ?: android.os.Build.HARDWARE
        val board = android.os.Build.BOARD
        val device = android.os.Build.DEVICE
        val supportedSocs = setOf("s5e9925", "exynos2200", "s5e8835", "exynos1480")
        val normalized = "$soc $board $device".lowercase()
        val gpuOk = packageManager.hasSystemFeature("android.hardware.vulkan.version")
        val socOk = supportedSocs.any { normalized.contains(it) }
        log("SoC: $soc  board: $board  device: $device")
        log("Vulkan feature: ${if (gpuOk) "present" else "missing"}")
        log(
            if (socOk && gpuOk) "Device check: supported (Xclipse class)"
            else "Device check: not confirmed as supported — driver targets Xclipse 920/530"
        )
        val already = File(filesDir, "drivers/radv-xclipse/${DriverPayload.VERSION}/libvulkan_radeon.so")
        log(
            if (already.exists()) "Driver payload: already extracted at ${already.parent}"
            else "Driver payload: bundled in APK, not yet extracted"
        )
        status()
    }

    private fun status() {
        val f = File(filesDir, "drivers/radv-xclipse/${DriverPayload.VERSION}/libvulkan_radeon.so")
        statusView.text = when {
            f.exists() -> "Driver: extracted (not yet loaded — no runtime yet)"
            else -> "Driver: bundled in APK"
        }
    }

    @SuppressLint("SetWorldReadable")
    private fun extractDriver() {
        actionButton.isEnabled = false
        statusView.text = "Driver: extracting…"
        Thread {
            try {
                val t0 = System.currentTimeMillis()
                assets.open(DriverPayload.ASSET_PATH).use { input ->
                    val digest = DriverPayload.sha256(input)
                    check(digest == DriverPayload.ZIP_SHA256) {
                        "driver zip hash mismatch: $digest"
                    }
                }
                log("payload sha256 verified: ${DriverPayload.ZIP_SHA256.take(16)}…")
                val result = assets.open(DriverPayload.ASSET_PATH).use { input ->
                    DriverPayload.extract(input, File(filesDir, "drivers"))
                }
                log("extracted ${result.entries.size} entries to ${result.dir} " +
                    "in ${System.currentTimeMillis() - t0} ms")
                check(result.entries.size == DriverPayload.EXPECTED_ENTRY_COUNT) {
                    "unexpected driver zip entry count: ${result.entries.size}"
                }
                check(result.entries.any { it.name == "libvulkan_radeon.so" }) { "libvulkan_radeon.so missing" }
                check(result.entries.any { it.name == "radeon_icd.aarch64.json" }) { "ICD json missing" }
                check(result.entries.any { it.name == "NOTICE.txt" }) { "NOTICE.txt missing" }
                for (e in result.entries) {
                    log("  ${"%06o".format(e.unixMode)}  ${"%9d".format(e.size)}  ${e.name}  ${e.sha256.take(12)}…")
                }
                log("driver layout ready; install into Linux rootfs happens at runtime setup (later milestone)")
            } catch (t: Throwable) {
                log("extraction FAILED: ${t.message}")
                log(android.util.Log.getStackTraceString(t))
            }
            runOnUiThread {
                actionButton.isEnabled = true
                actionButton.text = "Re-extract driver payload"
                status()
            }
        }.start()
    }

    override fun onDestroy() {
        ptyRunner?.stop()
        super.onDestroy()
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append("${timeFmt.format(Date())}  $msg\n")
            logScroller.post { logScroller.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
