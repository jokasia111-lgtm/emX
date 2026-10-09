package io.emx.term

/**
 * Minimal JNI bridge to the native PTY shim (app/src/main/c/emxpty.c).
 *
 * Exists because Java cannot hand ProcessBuilder an arbitrary fd for a
 * child's stdin/stdout, and `android.system.Os.openpty` is hidden from the
 * public SDK. The native side uses forkpty(3): the parent keeps the master
 * fd; the child gets the slave as fds 0/1/2 and execs directly.
 */
object NativePty {
    init {
        System.loadLibrary("emxnative")
    }

    /**
     * Fork + exec [program] with [args] over a fresh PTY. On success fills
     * [pidOut] with {pid, 0} and [masterOut] with {masterFd}.
     * Returns 0 on success, -1 on failure.
     */
    external fun forkExec(
        program: String, args: Array<String>, workingDir: String?,
        rows: Int, cols: Int,
        pidOut: IntArray, masterOut: IntArray,
    ): Int

    /** Read from the PTY master fd. Returns byte count or -1 (errno). */
    external fun readMaster(master: Int, buf: ByteArray, off: Int, len: Int): Int

    /** Write to the PTY master fd. Returns byte count or -1 (errno). */
    external fun writeMaster(master: Int, buf: ByteArray, off: Int, len: Int): Int

    external fun closeMaster(master: Int)

    /**
     * Reap [pid] if it has exited (WNOHANG). Returns 0 if still running,
     * 128+exitCode on exit, 128+signal on signal death, -1 on error.
     */
    external fun waitChild(pid: Int): Int
}
