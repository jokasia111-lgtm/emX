package io.emx.term

/** JNI transport for a local Android child, not a remote or Linux-container session. */
object NativePty {
    init {
        System.loadLibrary("emxnative")
    }

    const val CHILD_RUNNING = -65536
    const val WOULD_BLOCK = -11 // Android EAGAIN; read/write have a bounded poll timeout.

    /**
     * Exec an absolute [program] path with a complete [environment] (NAME=value).
     * Success: 0, pidOut={pid,0}, masterOut={fd}. Failure: -1, pidOut={-1,errno}.
     * chdir/exec failures are reported before returning, and failed children reaped.
     */
    external fun forkExec(
        program: String, args: Array<String>, workingDir: String?,
        environment: Array<String>, rows: Int, cols: Int,
        pidOut: IntArray, masterOut: IntArray,
    ): Int

    /** Byte count, 0 for EOF, or negative errno (including [WOULD_BLOCK]). */
    external fun readMaster(master: Int, buf: ByteArray, off: Int, len: Int): Int

    /** Partial byte count or negative errno (including [WOULD_BLOCK]). */
    external fun writeMaster(master: Int, buf: ByteArray, off: Int, len: Int): Int

    /** TIOCSWINSZ also delivers SIGWINCH to the foreground process group. */
    external fun resizeMaster(master: Int, cols: Int, rows: Int): Int
    external fun closeMaster(master: Int)

    /** Only the owner of an unreaped child may call this, never after waitChild succeeds. */
    external fun terminateChild(pid: Int)

    /**
     * Reap exactly [pid]. Normal exit: 0..255; signal: 128+signal; error: negative errno.
     * Blocking by default. With [noHang], [CHILD_RUNNING] is distinct from exit 0.
     */
    external fun waitChild(pid: Int, noHang: Boolean = false): Int
}
