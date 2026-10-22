package io.emx.term

import android.util.Log
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * Runs a local Android process over a native PTY, or merged stdout/stderr pipes.
 * Each launch has its own immutable handles. Only its reader owns native fd I/O,
 * signalling and reaping; stop/write/resize never operate on a possibly reused fd/PID.
 * Callbacks run on worker threads. onExit runs once, after cleanup, even after stop.
 */
class PtyRunner(
    private val command: List<String> = listOf("/system/bin/sh", "-i"),
    private val workingDir: File? = null,
    private val env: Map<String, String> = emptyMap(),
    private val onOutput: (ByteArray, Int) -> Unit,
    private val onExit: (Int) -> Unit = {},
) {
    companion object {
        private const val TAG = "emX.PtyRunner"
    }

    private data class Size(val cols: Int, val rows: Int)

    private class Session(
        val pid: Int = -1,
        val master: Int = -1,
        val process: Process? = null,
        @Volatile var size: Size,
    ) {
        @Volatile var stopping = false
        val input = LinkedBlockingQueue<ByteArray>()
        val wakeup = Object()
        val isPty get() = master >= 0
    }

    private val stateLock = Any()
    private var session: Session? = null
    private var size = Size(80, 24)

    val isRunning: Boolean get() = synchronized(stateLock) { session?.stopping == false }
    val usingPty: Boolean get() = synchronized(stateLock) { session?.isPty == true }

    /** Start once; a stopping session remains reserved until cleanup/onExit. */
    fun start(): Boolean = synchronized(stateLock) {
        if (session != null) return false
        var launched: Session? = null
        try {
            require(command.isNotEmpty() && command[0].isNotBlank()) { "Empty command" }
            require(command.none { '\u0000' in it }) { "NUL in command" }
            val environment = terminalEnvironment(System.getenv(), env, size.cols, size.rows)
            launched = try {
                startNative(environment)
            } catch (error: LinkageError) {
                Log.w(TAG, "Native PTY unavailable; using pipes", error)
                startPipes(environment)
            } catch (error: Exception) {
                Log.w(TAG, "Native PTY launch failed; using pipes", error)
                startPipes(environment)
            }
            val active = checkNotNull(launched)
            session = active
            Thread({ runSession(active) }, "emX-terminal-reader").start()
            true
        } catch (error: Exception) {
            // A thread-start failure must not orphan a child or its master fd.
            launched?.let { active ->
                if (active.isPty) {
                    NativePty.terminateChild(active.pid)
                    NativePty.closeMaster(active.master)
                    NativePty.waitChild(active.pid)
                } else {
                    active.process?.destroyForcibly()
                    closePipes(active)
                }
            }
            session = null
            Log.e(TAG, "Process start failed", error)
            false
        }
    }

    private fun startNative(environment: Map<String, String>): Session {
        val pid = IntArray(2)
        val master = intArrayOf(-1)
        check(NativePty.forkExec(
            command[0], command.drop(1).toTypedArray(), workingDir?.absolutePath,
            environment.map { (key, value) -> "$key=$value" }.toTypedArray(),
            size.rows, size.cols, pid, master,
        ) == 0) { "forkExec errno=${pid[1]}" }
        return Session(pid = pid[0], master = master[0], size = size)
    }

    private fun startPipes(environment: Map<String, String>): Session {
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        workingDir?.let(builder::directory)
        builder.environment().apply { clear(); putAll(environment) }
        return Session(process = builder.start(), size = size)
    }

    private fun runSession(active: Session) {
        val code = if (active.isPty) runNative(active) else runPipes(active)
        synchronized(stateLock) {
            if (session === active) session = null
        }
        try {
            onExit(code)
        } catch (error: Exception) {
            Log.w(TAG, "Exit callback failed", error)
        }
    }

    private fun emit(data: ByteArray, count: Int = data.size) {
        // Callers may retain this array. Never publish the reusable read buffer.
        onOutput(data.copyOf(count), count)
    }

    private fun runNative(active: Session): Int {
        var exitCode = NativePty.CHILD_RUNNING
        var appliedSize = active.size
        var pending: ByteArray? = null
        var written = 0
        var eof = false
        val buffer = ByteArray(8192)
        try {
            while (!active.stopping) {
                val requestedSize = active.size
                if (requestedSize != appliedSize) {
                    check(NativePty.resizeMaster(active.master, requestedSize.cols, requestedSize.rows) == 0) {
                        "PTY resize failed"
                    }
                    appliedSize = requestedSize
                }
                if (exitCode == NativePty.CHILD_RUNNING && !eof) {
                    if (pending == null) { pending = active.input.poll(); written = 0 }
                    pending?.let { bytes ->
                        val n = NativePty.writeMaster(active.master, bytes, written, bytes.size - written)
                        if (n != NativePty.WOULD_BLOCK) {
                            check(n > 0) { "PTY write failed: $n" }
                            written += n
                            if (written == bytes.size) pending = null
                        }
                    }
                }
                val n = if (eof) 0 else NativePty.readMaster(active.master, buffer, 0, buffer.size)
                when {
                    n > 0 -> emit(buffer, n)
                    n == 0 -> eof = true
                    n != NativePty.WOULD_BLOCK -> error("PTY read failed: $n")
                }
                if (exitCode == NativePty.CHILD_RUNNING) {
                    exitCode = NativePty.waitChild(active.pid, noHang = true)
                }
                // Drain final bytes even when the child has already exited.
                if (exitCode != NativePty.CHILD_RUNNING && n <= 0) break
                if (eof) synchronized(active.wakeup) {
                    if (!active.stopping) active.wakeup.wait(50)
                }
            }
        } catch (error: Exception) {
            if (!active.stopping) Log.w(TAG, "PTY I/O failed", error)
        } finally {
            // No other thread signals or reaps this pid. A reaped pid is never killed.
            if (exitCode == NativePty.CHILD_RUNNING) NativePty.terminateChild(active.pid)
            NativePty.closeMaster(active.master)
            if (exitCode == NativePty.CHILD_RUNNING) exitCode = NativePty.waitChild(active.pid)
            active.input.clear()
        }
        return exitCode
    }

    private fun runPipes(active: Session): Int {
        val process = checkNotNull(active.process)
        // Pipe writes can block, so neither keyboard input nor stop blocks the UI.
        val writer = Thread({
            try {
                while (!active.stopping) {
                    val bytes = active.input.take()
                    process.outputStream.write(pipeInput(bytes))
                    process.outputStream.flush()
                    emit(pipeEcho(bytes))
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                if (!active.stopping) Log.w(TAG, "Pipe write failed", error)
            }
        }, "emX-terminal-writer")
        var code = -1
        try {
            writer.start()
            val buffer = ByteArray(8192)
            while (!active.stopping) {
                val n = process.inputStream.read(buffer)
                if (n < 0) break
                if (n > 0) emit(buffer, n)
            }
            code = process.waitFor()
        } catch (error: Exception) {
            if (!active.stopping) Log.w(TAG, "Pipe read failed", error)
        } finally {
            active.stopping = true
            process.destroyForcibly()
            writer.interrupt()
            closePipes(active)
            runCatching { writer.join() }
            if (code == -1) code = runCatching { process.waitFor() }.getOrDefault(-1)
            active.input.clear()
        }
        return code
    }

    private fun closePipes(active: Session) {
        active.process?.let { process ->
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    /** Queue owned bytes for the child; only pipe fallback supplies local echo. */
    fun write(data: ByteArray) {
        if (data.isEmpty()) return
        synchronized(stateLock) {
            session?.takeUnless { it.stopping }?.input?.offer(data.copyOf())
        }
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /** cols, rows, in that order. Also remembers the size before the next start. */
    fun resize(cols: Int, rows: Int) {
        require(cols in 1..65535 && rows in 1..65535) { "Invalid terminal size" }
        synchronized(stateLock) {
            size = Size(cols, rows)
            session?.size = size
        }
    }

    /** Nonblocking and idempotent. The session worker owns final cleanup/reaping. */
    fun stop() {
        val active = synchronized(stateLock) {
            session?.takeUnless { it.stopping }?.also { it.stopping = true }
        } ?: return
        synchronized(active.wakeup) { active.wakeup.notifyAll() }
        // Process tracks its own identity; unlike a raw PID it cannot kill a reused PID.
        active.process?.destroyForcibly()
    }
}

internal fun terminalEnvironment(
    inherited: Map<String, String>, overrides: Map<String, String>, cols: Int, rows: Int,
): Map<String, String> = (inherited + mapOf("TERM" to "xterm") + overrides +
    mapOf("COLUMNS" to cols.toString(), "LINES" to rows.toString())).also { environment ->
    require(environment.all { (key, value) ->
        key.isNotEmpty() && '=' !in key && '\u0000' !in key && '\u0000' !in value
    }) { "Invalid environment entry" }
}

/** Pipes have no terminal line discipline; Enter must be LF rather than CR. */
internal fun pipeInput(data: ByteArray): ByteArray =
    ByteArray(data.size) { if (data[it] == 13.toByte()) 10 else data[it] }

internal fun pipeEcho(data: ByteArray): ByteArray {
    val result = ByteArray(data.size + data.count { it == 13.toByte() })
    var offset = 0
    for (byte in data) {
        result[offset++] = byte
        if (byte == 13.toByte()) result[offset++] = 10
    }
    return result
}
