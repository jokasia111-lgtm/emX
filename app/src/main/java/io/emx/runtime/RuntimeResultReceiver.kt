package io.emx.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.emx.core.DriverPayload

class RuntimeResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val prefs = RuntimeState.preferences(context)
        if (prefs.getString("operationId", null) != id) return
        val operation = prefs.getString("operation", "")
        val result = intent.getBundleExtra("result")
        val stdout = result?.getString("stdout").orEmpty()
        val stderr = result?.getString("stderr").orEmpty()
        val error = result?.getString("errmsg").orEmpty()
        val exit = result?.getInt("exitCode", -1) ?: -1
        val apiError = result?.getInt("err", 0) ?: -1
        var success = exit == 0 && apiError == 0
        var message = "Termux $operation: exit=$exit, API error=$apiError\n$stdout\n$stderr\n$error"
        when (operation) {
            "setup" -> {
                success = success && stdout.lineSequence().any { it.trim() == "EMX_SETUP_OK:${DriverPayload.ZIP_SHA256}" }
                if (success) prefs.edit().putString("installedDriver", DriverPayload.ZIP_SHA256).apply()
                message += if (success) "\nLinux and driver installed. Run Check Vulkan next." else "\nSetup was not confirmed. Check the output before retrying."
            }
            "probe" -> {
                success = success && VulkanProbe.hasXclipseRadv(stdout)
                prefs.edit().putBoolean("gpuVerified", success).apply()
                message += if (success) "\nXclipse RADV device enumerated. Rendering is not yet verified." else "\nXclipse RADV was not confirmed. Driver presence alone is not GPU verification."
            }
            "x11-setup" -> {
                success = success && stdout.lineSequence().any { it.trim() == "EMX_X11_TOOLS_OK" }
                prefs.edit().putBoolean("x11Tools", success).apply()
            }
        }
        RuntimeState.finish(context, id, success, message)
        context.stopService(Intent(context, RuntimeService::class.java))
    }
}
