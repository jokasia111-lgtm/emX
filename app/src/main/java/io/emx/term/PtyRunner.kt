package io.emx.term

import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process runner that provides a pseudo-terminal (PTY) to the child.
 *
 * Strategy (attempted in order, first that works wins):
 *  1. Native forkpty shim (NativePty / emxpty.c): fork + exec over a real
 *     PTY. The child gets the slave as fds 0/1/2, so `/system/bin/sh -i`
 *     believes it is interactive: line editing, colors, signals all work.
 *  2. Plain `ProcessBuilder` pipes (no PTY) if the native library fails to
 *     load for any reason. Line-editing and color are absent; Enter is
 *     translated CR→LF on the way to the child and echoed as CRLF locally.
 *
 * The parent owns the PTY master fd. A reader thread drains it and delivers
 * bytes to [onOutput]. Keystrokes from [write] are forwarded to the child.
 */
class PtyRunner(
    private val command: List<String> = listOf("/system/bin/sh", "-i"),
    private val workingDir: File? = null,
    private val env: Map<String, String> = emptyMap(),
    private val onOutput: (ByteArray, Int) -> Unit,
    private val onExit: (Int) -> Unit = {}
) {
    companion object {
        private const val TAG = "emX.PtyRunner"
        private val CR = 0x0D.toByte()
        private val LF = 0x0A.toByte()
    }

    private val running = AtomicBoolean(false)
    private var process: Process? = null
    private var masterIn: InputStream? = null
    private var masterOut: OutputStream? = null
    private var readerThread: Thread? = null
    /** Native forkpty state: child pid and master fd, -1 when unused. */
    private var nativePid: Int = -1
    private var nativeMaster: Int = -1
    /** True when the PTY (rather than pipes) is backing this runner. */
    var usingPty: Boolean = false
        private set

    /** Start the child. Returns true on success. */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        return try {
            startWithNativePty()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "native PTY path failed, falling back to pipes: ${t.message}")
            try {
                startWithPipes()
                true
            } catch (t2: Throwable) {
                Log.e(TAG, "process start failed", t2)
                running.set(false)
                false
            }
        }
    }

    /** Preferred path: native forkpty (see emxpty.c). */
    private fun startWithNativePty() {
        val native = try {
            NativePty
        } catch (t: Throwable) {
            throw UnsupportedOperationException("native lib unavailable: ${t.message}")
        }
        val pidOut = IntArray(2)
        val masterOutArr = IntArray(1)
        val rc = native.forkExec(
            command[0], command.drop(1).toTypedArray(), workingDir?.absolutePath,
            40, 100, pidOut, masterOutArr,
        )
        check(rc == 0) { "forkExec failed" }
        nativePid = pidOut[0]
        nativeMaster = masterOutArr[0]
        masterIn = NativeMasterInputStream(native, nativeMaster)
        masterOut = NativeMasterOutputStream(native, nativeMaster)
        usingPty = true
        startReaderThread()
    }

    /** InputStream reading the PTY master through JNI. */
    private class NativeMasterInputStream(
        private val native: NativePty,
        private val master: Int,
    ) : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            val n = native.readMaster(master, b, 0, 1)
            return if (n <= 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = native.readMaster(master, b, off, len)
            return if (n <= 0) -1 else n
        }
    }

    /** OutputStream writing the PTY master through JNI. */
    private class NativeMasterOutputStream(
        private val native: NativePty,
        private val master: Int,
    ) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))

        override fun write(b: ByteArray, off: Int, len: Int) {
            var written = 0
            while (written < len) {
                val n = native.writeMaster(master, b, off + written, len - written)
                check(n > 0) { "writeMaster failed" }
                written += n
            }
        }
    }

    /** Fallback: plain pipes (no PTY). */
    private fun startWithPipes() {
        val pb = ProcessBuilder(command)
        workingDir?.let { pb.directory(it) }
        if (env.isNotEmpty()) pb.environment().putAll(env)
        process = pb.start()
        masterIn = process!!.inputStream
        masterOut = process!!.outputStream
        usingPty = false
        startReaderThread()
    }

    private fun startReaderThread() {
        val input = masterIn!!
        readerThread = Thread({
            val buf = ByteArray(8192)
            try {
                while (running.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) onOutput(buf, n)
                }
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "read failed: ${t.message}")
            } finally {
                running.set(false)
                val code = when {
                    process != null -> runCatching { process!!.waitFor() }.getOrDefault(0)
                    nativePid > 0 -> runCatching { NativePty.waitChild(nativePid) }.getOrDefault(0)
                    else -> 0
                }
                onExit(code)
            }
        }, "emX-pty-reader").apply { start() }
    }

    /**
     * Forward user keystrokes to the child; echo locally when there is no PTY.
     *
     * In pipe (non-PTY) mode the child has no terminal line discipline, so a
     * raw CR (0x0D) would never submit a command — the shell expects LF.
     * We translate CR to LF on the way in, and echo CR+LF back to the display.
     */
    fun write(data: ByteArray) {
        val out = masterOut ?: return
        try {
            val payload = if (usingPty) data else ByteArray(data.size) { i ->
                if (data[i] == CR) LF else data[i]
            }
            out.write(payload)
            out.flush()
            if (!usingPty) {
                // No PTY means no terminal echo from the child; do it locally,
                // rendering Enter as CRLF so the display advances a line.
                val echoed = data.flatMap { b ->
                    if (b == CR) listOf(CR, LF) else listOf(b)
                }.toByteArray()
                onOutput(echoed, echoed.size)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "write failed: ${t.message}")
        }
    }

    fun write(text: String) = write(text.toByteArray())

    /** Stop the child and release resources. Idempotent. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { process?.destroy() }
        runCatching { masterIn?.close() }
        runCatching { masterOut?.close() }
        if (nativeMaster >= 0) runCatching { NativePty.closeMaster(nativeMaster) }
        if (nativePid > 0) {
            // SIGKILL the child if still alive; the reader thread's waitChild
            // call in the exit path will reap it.
            runCatching { android.system.Os.kill(nativePid, 9) }
        }
        nativeMaster = -1
        nativePid = -1
    }

    val isRunning: Boolean get() = running.get()
}

private fun shellQuote(s: String): String =
    if (s.none { it in " \t\"'\\$`" }) s else "'${s.replace("'", "'\\''")}'"
