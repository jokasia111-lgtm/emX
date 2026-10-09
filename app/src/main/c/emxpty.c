/*
 * emX — minimal PTY support for the in-app terminal.
 *
 * Java's ProcessBuilder cannot hand a child an arbitrary fd for stdin/stdout,
 * and android.system.Os.openpty is hidden from the public SDK. This shim does
 * in native code what Termux does: allocate a PTY pair, fork, wire the slave
 * onto the child's fds 0/1/2, and exec the requested program. The parent
 * keeps the master fd and reads/writes it directly.
 *
 * Deliberately tiny: no terminal-size negotiation beyond an initial
 * TIOCSWINSZ, no signal handling beyond defaults, no setsid beyond
 * what forkpty gives (it calls setsid + makes the slave controlling tty).
 */
#include <jni.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>
#include <pty.h>

/* Protects fork/exec from concurrent Java calls (fork is process-wide). */
static pthread_mutex_t g_fork_lock = PTHREAD_MUTEX_INITIALIZER;

/*
 * Class:     io_emx_term_NativePty
 * Method:    forkExec
 * Signature: (Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;II[I)I
 *
 * argv[0] = program, rest = arguments (argv[1..]). workingDir may be NULL.
 * rows/cols set the initial PTY window size. pidOut[0] receives the child pid,
 * masterOut[0] the master fd (an int, suitable for Os.read/Os.write via
 * reflection-free int fds is not possible in Java, so we expose read/write
 * helpers below that operate on the master fd).
 *
 * Returns 0 on success, -1 on error (with errno in pidOut[1]).
 */
JNIEXPORT jint JNICALL
Java_io_emx_term_NativePty_forkExec(JNIEnv *env, jclass cls,
                                   jstring jProgram, jobjectArray jArgs,
                                   jstring jWorkingDir,
                                   jint rows, jint cols,
                                   jintArray pidOut, jintArray masterOut) {
    (void) cls;
    const char *program = (*env)->GetStringUTFChars(env, jProgram, NULL);
    jsize argc = jArgs ? (*env)->GetArrayLength(env, jArgs) : 0;
    char **argv = calloc(argc + 2, sizeof(char *));
    if (!argv || !program) goto fail;

    argv[0] = strdup(program);
    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, jArgs, i);
        argv[i + 1] = strdup((*env)->GetStringUTFChars(env, s, NULL));
    }

    const char *cwd = NULL;
    if (jWorkingDir) {
        cwd = (*env)->GetStringUTFChars(env, jWorkingDir, NULL);
    }

    struct winsize ws = { (unsigned short) rows, (unsigned short) cols, 0, 0 };
    int master = -1;
    pid_t pid = -1;

    pthread_mutex_lock(&g_fork_lock);
    pid = forkpty(&master, NULL, NULL, &ws);
    if (pid == 0) {
        /* Child: fds 0/1/2 are already the PTY slave. */
        if (cwd) chdir(cwd);
        execv(program, argv);
        /* exec failed — nothing sane to report to; die loudly. */
        _exit(127);
    }
    pthread_mutex_unlock(&g_fork_lock);

    if (pid < 0) goto fail;

    jint pidArr[2] = { (jint) pid, 0 };
    (*env)->SetIntArrayRegion(env, pidOut, 0, 2, pidArr);
    jint masterArr[1] = { master };
    (*env)->SetIntArrayRegion(env, masterOut, 0, 1, masterArr);

    /* free the strings we duplicated */
    for (jsize i = 0; i < argc + 1; i++) free(argv[i]);
    free(argv);
    (*env)->ReleaseStringUTFChars(env, jProgram, program);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jWorkingDir, cwd);
    return 0;

fail:
    if (argv) { for (jsize i = 0; i < argc + 1; i++) free(argv[i]); free(argv); }
    if (program) (*env)->ReleaseStringUTFChars(env, jProgram, program);
    return -1;
}

/* Read up to len bytes from the PTY master. Returns count, or -1/errno. */
JNIEXPORT jint JNICALL
Java_io_emx_term_NativePty_readMaster(JNIEnv *env, jclass cls, jint master, jbyteArray buf, jint off, jint len) {
    (void) env; (void) cls;
    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    ssize_t n = read(master, data + off, len);
    (*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);
    return (jint) n;
}

/* Write len bytes to the PTY master. Returns count written. */
JNIEXPORT jint JNICALL
Java_io_emx_term_NativePty_writeMaster(JNIEnv *env, jclass cls, jint master, jbyteArray buf, jint off, jint len) {
    (void) env; (void) cls;
    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    ssize_t n = write(master, data + off, len);
    (*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);
    return (jint) n;
}

/* Close the master fd. */
JNIEXPORT void JNICALL
Java_io_emx_term_NativePty_closeMaster(JNIEnv *env, jclass cls, jint master) {
    (void) env; (void) cls;
    close(master);
}

/* Reap the child if it has exited; returns pid, 0 if still running, -1 on error. */
JNIEXPORT jint JNICALL
Java_io_emx_term_NativePty_waitChild(JNIEnv *env, jclass cls, jint pid) {
    (void) env; (void) cls;
    int status = 0;
    pid_t r = waitpid((pid_t) pid, &status, WNOHANG);
    if (r == 0) return 0;
    if (r < 0) return -1;
    if (WIFEXITED(status)) return 128 + WEXITSTATUS(status); /* convention: 128+code */
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return r;
}
