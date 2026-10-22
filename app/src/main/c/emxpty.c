/* Local Android PTY transport. All allocation/JNI work happens before fork. */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHILD_RUNNING (-65536)
#define IO_TIMEOUT_MS 50
#define IO_BUFFER_SIZE 8192

static pthread_mutex_t g_fork_lock = PTHREAD_MUTEX_INITIALIZER;

static char *copy_string(JNIEnv *env, jstring value) {
    if (!value) { errno = EINVAL; return NULL; }
    const char *chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (!chars) { errno = ENOMEM; return NULL; }
    char *copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, value, chars);
    if (!copy) errno = ENOMEM;
    return copy;
}

static void free_strings(char **strings) {
    if (!strings) return;
    for (char **p = strings; *p; ++p) free(*p);
    free(strings);
}

static int copy_array(JNIEnv *env, jobjectArray source, char **dest) {
    jsize count = source ? (*env)->GetArrayLength(env, source) : 0;
    for (jsize i = 0; i < count; ++i) {
        jstring value = (jstring) (*env)->GetObjectArrayElement(env, source, i);
        dest[i] = copy_string(env, value);
        if (value) (*env)->DeleteLocalRef(env, value);
        if (!dest[i]) return -1;
    }
    return 0;
}

/* This is used only by the owner, before the child has been reaped. */
static void terminate_child(pid_t pid) {
    if (pid <= 0) return;
    kill(-pid, SIGKILL); /* the shell's process group */
    kill(pid, SIGKILL);  /* also works before forkpty's setsid completes */
}

static void reap_child(pid_t pid) {
    while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
}

JNIEXPORT jint JNICALL
Java_io_emx_term_NativePty_forkExec(JNIEnv *env, jobject self,
                                  jstring jProgram, jobjectArray jArgs,
                                  jstring jWorkingDir, jobjectArray jEnvironment,
                                  jint rows, jint cols,
                                  jintArray pidOut, jintArray masterOut) {
    (void) self;
    if (!pidOut || !masterOut || (*env)->GetArrayLength(env, pidOut) < 2 ||
        (*env)->GetArrayLength(env, masterOut) < 1) return -1;

    int error = EINVAL;
    int master = -1;
    int error_pipe[2] = {-1, -1};
    pid_t pid = -1;
    char **argv = NULL;
    char **envp = NULL;
    char *cwd = NULL;
    if (rows <= 0 || rows > 65535 || cols <= 0 || cols > 65535 || !jProgram)
        goto cleanup;

    jsize argc = jArgs ? (*env)->GetArrayLength(env, jArgs) : 0;
    jsize envc = jEnvironment ? (*env)->GetArrayLength(env, jEnvironment) : 0;
    argv = calloc((size_t) argc + 2, sizeof(char *));
    envp = calloc((size_t) envc + 1, sizeof(char *));
    if (!argv || !envp) { error = ENOMEM; goto cleanup; }
    argv[0] = copy_string(env, jProgram);
    if (!argv[0] || copy_array(env, jArgs, argv + 1) < 0 ||
        copy_array(env, jEnvironment, envp) < 0) {
        error = errno;
        goto cleanup;
    }
    if (jWorkingDir) {
        cwd = copy_string(env, jWorkingDir);
        if (!cwd) { error = errno; goto cleanup; }
    }

    struct winsize ws = {(unsigned short) rows, (unsigned short) cols, 0, 0};
    pthread_mutex_lock(&g_fork_lock);
    /* pipe2 needs API 28; CLOEXEC is set before fork so execve still closes them. */
    if (pipe(error_pipe) < 0 ||
        fcntl(error_pipe[0], F_SETFD, FD_CLOEXEC) != 0 ||
        fcntl(error_pipe[1], F_SETFD, FD_CLOEXEC) != 0) {
        error = errno;
        close(error_pipe[0]);
        close(error_pipe[1]);
        pthread_mutex_unlock(&g_fork_lock);
        goto cleanup;
    }
    pid = forkpty(&master, NULL, NULL, &ws);
    if (pid == 0) {
        close(error_pipe[0]);
        /* Do not inherit the VM's blocked/ignored terminal signals. */
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, NULL);
        struct sigaction action;
        memset(&action, 0, sizeof(action));
        action.sa_handler = SIG_DFL;
        sigemptyset(&action.sa_mask);
        const int signals[] = {SIGINT, SIGQUIT, SIGTERM, SIGHUP, SIGPIPE,
                               SIGCHLD, SIGTSTP, SIGTTIN, SIGTTOU, SIGWINCH};
        for (size_t i = 0; i < sizeof(signals) / sizeof(signals[0]); ++i)
            sigaction(signals[i], &action, NULL);
        if (!cwd || chdir(cwd) == 0) execve(argv[0], argv, envp);
        int child_error = errno;
        ssize_t result;
        do { result = write(error_pipe[1], &child_error, sizeof(child_error)); }
        while (result < 0 && errno == EINTR);
        _exit(127);
    }
    error = errno;
    if (pid > 0 && (fcntl(master, F_SETFD, FD_CLOEXEC) < 0 ||
                     fcntl(master, F_SETFL, O_NONBLOCK) < 0)) {
        error = errno;
        terminate_child(pid);
        reap_child(pid);
        pid = -1;
    }
    pthread_mutex_unlock(&g_fork_lock);
    close(error_pipe[1]);
    error_pipe[1] = -1;
    if (pid < 0) goto cleanup;

    /* EOF means exec closed this CLOEXEC fd; a payload means chdir/exec failed. */
    int child_error = 0;
    ssize_t n;
    do { n = read(error_pipe[0], &child_error, sizeof(child_error)); }
    while (n < 0 && errno == EINTR);
    if (n != 0) {
        error = n > 0 ? child_error : errno;
        terminate_child(pid);
        reap_child(pid);
        pid = -1;
        goto cleanup;
    }
    error = 0;

cleanup:
    if (error_pipe[0] >= 0) close(error_pipe[0]);
    if (error_pipe[1] >= 0) close(error_pipe[1]);
    free_strings(argv);
    free_strings(envp);
    free(cwd);
    if (error && master >= 0) { close(master); master = -1; }
    if (!(*env)->ExceptionCheck(env)) {
        jint pid_result[2] = {error ? -1 : (jint) pid, error};
        jint master_result = master;
        (*env)->SetIntArrayRegion(env, pidOut, 0, 2, pid_result);
        (*env)->SetIntArrayRegion(env, masterOut, 0, 1, &master_result);
    }
    return error ? -1 : 0;
}

static int valid_buffer(JNIEnv *env, jbyteArray buf, jint off, jint len) {
    if (!buf || off < 0 || len < 0) return 0;
    jsize size = (*env)->GetArrayLength(env, buf);
    return off <= size && len <= size - off;
}

static int await_fd(int fd, short events) {
    struct pollfd pfd = {fd, events, 0};
    int result;
    do { result = poll(&pfd, 1, IO_TIMEOUT_MS); } while (result < 0 && errno == EINTR);
    if (result < 0) return -errno;
    if (!result) return -EAGAIN;
    if (pfd.revents & POLLNVAL) return -EBADF;
    return 0; /* read/write also handles POLLHUP/POLLERR, draining pending bytes */
}

/* Returns bytes, 0 at EOF (including Linux PTY EIO), or negative errno.
 * A short timeout lets the owner observe stop requests without closing an fd
 * concurrently with read, which can otherwise access a reused descriptor. */
JNIEXPORT jint JNICALL
Java_io_emx_term_NativePty_readMaster(JNIEnv *env, jobject self, jint master,
                                    jbyteArray buf, jint off, jint len) {
    (void) self;
    if (!valid_buffer(env, buf, off, len)) return -EINVAL;
    if (!len) return 0;
    int ready = await_fd(master, POLLIN);
    if (ready < 0) return ready;
    jbyte data[IO_BUFFER_SIZE];
    size_t size = len < IO_BUFFER_SIZE ? (size_t) len : IO_BUFFER_SIZE;
    ssize_t n;
    do { n = read(master, data, size); } while (n < 0 && errno == EINTR);
    if (n < 0) return errno == EIO ? 0 : -errno;
    /* Copy back explicitly: JNI_ABORT on GetByteArrayElements loses reads. */
    if (n > 0) (*env)->SetByteArrayRegion(env, buf, off, (jsize) n, data);
    return (*env)->ExceptionCheck(env) ? -ENOMEM : (jint) n;
}

JNIEXPORT jint JNICALL
Java_io_emx_term_NativePty_writeMaster(JNIEnv *env, jobject self, jint master,
                                     jbyteArray buf, jint off, jint len) {
    (void) self;
    if (!valid_buffer(env, buf, off, len)) return -EINVAL;
    if (!len) return 0;
    int ready = await_fd(master, POLLOUT);
    if (ready < 0) return ready;
    jbyte data[IO_BUFFER_SIZE];
    jsize size = len < IO_BUFFER_SIZE ? len : IO_BUFFER_SIZE;
    (*env)->GetByteArrayRegion(env, buf, off, size, data);
    if ((*env)->ExceptionCheck(env)) return -ENOMEM;
    ssize_t n;
    do { n = write(master, data, (size_t) size); } while (n < 0 && errno == EINTR);
    return n < 0 ? -errno : (jint) n;
}

JNIEXPORT jint JNICALL
Java_io_emx_term_NativePty_resizeMaster(JNIEnv *env, jobject self, jint master,
                                      jint cols, jint rows) {
    (void) env; (void) self;
    if (cols <= 0 || cols > 65535 || rows <= 0 || rows > 65535) return -EINVAL;
    struct winsize ws = {(unsigned short) rows, (unsigned short) cols, 0, 0};
    int result;
    do { result = ioctl(master, TIOCSWINSZ, &ws); } while (result < 0 && errno == EINTR);
    return result < 0 ? -errno : 0;
}

JNIEXPORT void JNICALL
Java_io_emx_term_NativePty_closeMaster(JNIEnv *env, jobject self, jint master) {
    (void) env; (void) self;
    /* Never retry close on EINTR: Linux has already released the descriptor. */
    if (master >= 0) close(master);
}

JNIEXPORT void JNICALL
Java_io_emx_term_NativePty_terminateChild(JNIEnv *env, jobject self, jint pid) {
    (void) env; (void) self;
    terminate_child((pid_t) pid);
}

JNIEXPORT jint JNICALL
Java_io_emx_term_NativePty_waitChild(JNIEnv *env, jobject self, jint pid, jboolean noHang) {
    (void) env; (void) self;
    if (pid <= 0) return -EINVAL;
    int status;
    pid_t result;
    do { result = waitpid((pid_t) pid, &status, noHang ? WNOHANG : 0); }
    while (result < 0 && errno == EINTR);
    if (result == 0) return CHILD_RUNNING;
    if (result < 0) return -errno;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -ECHILD;
}
