package io.emx.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import io.emx.core.DriverPayload
import io.emx.ui.MainActivity
import java.util.UUID
import java.util.concurrent.Executors

class RuntimeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var payloadServer: PayloadServer? = null
    private var callback: PendingIntent? = null
    private var operationId: String? = null
    @Volatile private var destroyed = false
    private val timeout = Runnable { stopWaiting("Timed out waiting for Termux. Check its notification and emX permissions; a command may still be running there.") }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("runtime", "Linux setup", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "runtime")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("emX Linux runtime")
            .setContentText("Waiting for the Termux command to complete")
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(100, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(100, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (operationId != null) return START_NOT_STICKY
        val operation = intent?.getStringExtra("operation")
        if (operation !in setOf("setup", "probe", "x11-setup")) {
            stopSelf()
            return START_NOT_STICKY
        }
        val id = UUID.randomUUID().toString()
        operationId = id
        val prefs = RuntimeState.preferences(this)
        val duration = if (operation == "probe") 120_000L else 30 * 60_000L
        prefs.edit().putString("operationId", id).putString("operation", operation)
            .putLong("deadline", System.currentTimeMillis() + duration)
            .putString("status", "Running: $operation").apply()
        if (operation == "setup") prefs.edit().remove("installedDriver").putBoolean("gpuVerified", false).apply()
        if (operation == "probe") prefs.edit().putBoolean("gpuVerified", false).apply()
        RuntimeState.append(this, "Starting $operation in Termux. Downloads can take several minutes; output appears when the command finishes.")
        // Termux fills the result bundle, so this explicit, one-shot callback must be mutable.
        callback = PendingIntent.getBroadcast(this, 0,
            Intent(this, RuntimeResultReceiver::class.java).setData(Uri.parse("emx://runtime/$id")),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
        handler.postDelayed(timeout, duration)
        worker.execute {
            try {
                val arguments = if (operation == "setup") {
                    val bytes = assets.open(DriverPayload.ASSET_PATH).use { it.readBytes() }
                    check(DriverPayload.sha256(bytes.inputStream()) == DriverPayload.ZIP_SHA256) { "Bundled driver checksum mismatch." }
                    val server = PayloadServer(bytes)
                    payloadServer = server
                    if (destroyed) { server.close(); return@execute }
                    listOf(server.url, DriverPayload.ZIP_SHA256, DriverPayload.VERSION)
                } else emptyList()
                handler.post {
                    if (!destroyed) {
                        try {
                            TermuxBridge.dispatch(this, operation!!, true, callback, arguments)
                        } catch (e: Exception) {
                            stopWaiting(e.message ?: "Unable to start Termux.")
                        }
                    }
                }
            } catch (e: Exception) {
                handler.post { if (!destroyed) stopWaiting(e.message ?: "Runtime setup failed.") }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopWaiting(message: String) {
        operationId?.let { RuntimeState.finish(this, it, false, message) }
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopWaiting("Android stopped the setup service. Check the command in Termux before retrying.")
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        callback?.cancel()
        runCatching { payloadServer?.close() }
        worker.shutdownNow()
        operationId?.let {
            RuntimeState.finish(this, it, false, "Setup monitoring stopped. Termux may still be running the command; check there before retrying.")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
