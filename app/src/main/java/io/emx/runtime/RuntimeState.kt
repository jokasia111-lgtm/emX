package io.emx.runtime

import android.content.Context
import android.content.SharedPreferences
import io.emx.core.DriverPayload

object RuntimeState {
    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("runtime", Context.MODE_PRIVATE)

    fun ready(context: Context): Boolean =
        preferences(context).getString("installedDriver", null) == DriverPayload.ZIP_SHA256

    fun append(context: Context, message: String) {
        val prefs = preferences(context)
        prefs.edit().putString("log", (prefs.getString("log", "") + "\n" + message).takeLast(48000)).apply()
    }

    fun finish(context: Context, id: String, success: Boolean, message: String) {
        val prefs = preferences(context)
        if (prefs.getString("operationId", null) != id) return
        prefs.edit().remove("operationId").remove("operation")
            .putString("status", if (success) "Completed" else "Needs attention").apply()
        append(context, message)
    }
}
