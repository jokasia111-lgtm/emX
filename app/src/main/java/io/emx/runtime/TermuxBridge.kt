package io.emx.runtime

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object TermuxBridge {
    const val PACKAGE = "com.termux"
    const val X11_PACKAGE = "com.termux.x11"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val HOME = "/data/data/com.termux/files/home"

    fun isInstalled(context: Context, packageName: String = PACKAGE): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun dispatch(
        context: Context,
        operation: String,
        background: Boolean,
        callback: PendingIntent? = null,
        extraArguments: List<String> = emptyList(),
    ) {
        check(isInstalled(context)) { "Install and open Termux before connecting emX." }
        check(hasPermission(context)) { "Grant emX permission to run commands in Termux." }
        val script = context.assets.open("runtime/termux-runtime.sh").bufferedReader().use { it.readText() }
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            component = ComponentName(PACKAGE, "$PACKAGE.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS",
                (listOf("-c", script, "emx-runtime", operation) + extraArguments).toTypedArray())
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "emX: $operation")
            callback?.let { putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", it) }
        }
        check(context.startService(intent) != null) { "Termux command service is unavailable." }
    }
}
