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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Landscape-first setup screen. Shows device compatibility, driver payload
 * status and a log view. The Android Back key toggles the soft keyboard
 * when the log/terminal area has focus (text-entry context); otherwise
 * standard navigation behavior applies.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var logScroller: ScrollView
    private lateinit var statusView: TextView
    private lateinit var actionButton: Button
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
        left.addView(title)
        left.addView(subtitle)
        left.addView(statusView)
        left.addView(actionButton)

        // Right panel: log view (the future terminal area)
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

        root.addView(left)
        root.addView(logScroller)
        return root
    }

    override fun onBackPressed() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (logView.hasWindowFocus() && imm.isActive(logView) && logView.hasFocus()) {
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

    private fun log(msg: String) {
        runOnUiThread {
            logView.append("${timeFmt.format(Date())}  $msg\n")
            logScroller.post { logScroller.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
