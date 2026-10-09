# emX — Milestone 3 report: in-app terminal (PTY) + Back-toggles-keyboard

## Status

Code complete and host-verified; **on-device behavior unverified** (no hardware in
CI). The forkpty mechanism itself was smoke-tested natively on the Linux host:
a forked `sh -i` over a PTY produced a real interactive prompt, local echo and
command output — the same code path `emxpty.c` uses on Android.

## Files

### New
- `app/src/main/c/emxpty.c` — native forkpty shim: fork + exec over a real PTY,
  master fd handed to Java. Read/write/wait/close helpers over JNI.
- `app/src/main/cpp/CMakeLists.txt` — builds `libemxnative.so`.
- `app/src/main/java/io/emx/term/NativePty.kt` — JNI bindings.
- `app/src/main/java/io/emx/term/TerminalEmulator.kt` — pure-Kotlin VT100/ANSI
  emulator: monospace grid, CR/LF/BS/BEL/TAB, VT100 wrap, bounded scrollback,
  DECSC/DECRC, CSI cursor movement (CUU/CUD/CUF/CUB/CUP/CHA/VPA), ED/EL, SGR
  colors (8/16/256/truecolor down-mapped).
- `app/src/main/java/io/emx/ui/TerminalView.kt` — canvas-based View: per-cell
  fg/bg, blinking cursor, soft-keyboard input via `BaseInputConnection`.
- `app/src/main/java/io/emx/term/PtyRunner.kt` — process runner: native forkpty
  first, ProcessBuilder-pipes fallback (CR→LF translation + local CRLF echo).
- `app/src/test/java/io/emx/term/TerminalEmulatorTest.kt` — 17 unit tests.

### Modified
- `MainActivity.kt` — `Start terminal` button swaps the right panel to the
  terminal; `Show log`/`Show terminal` toggle keeps the log accessible; Back
  toggles the IME when the terminal is focused (product rule), falls back to
  the log-view toggle, then default behavior; `onDestroy` stops the runner.
- `app/build.gradle.kts` — externalNativeBuild (cmake), abiFilters
  arm64-v8a + x86_64.
- `app/src/main/java/io/emx/core/DriverPayload.kt` — review fixes: exact
  permission application (POSIX file attributes + verification, fails loudly on
  mismatch), 16-bit central-directory length fields, atomic swap-with-restore
  directory replacement.

## Review fixes over the first subagent draft
1. Removed the reflective `Os.openpty` + `/proc/self/fd` exec path: unsupported
   (hidden API) and unreliable (fd lifecycle across ProcessBuilder spawn).
   Replaced with the native forkpty shim, which is the same approach Termux uses.
2. Pipes fallback now translates Enter (CR→LF) toward the child and echoes
   CRLF locally — without this, commands could never be submitted in fallback
   mode.
3. Extraction now applies exact Unix modes (0644/0755) and fails the
   extraction if the applied mode cannot be verified.

## Verification
- `./gradlew test` — 42/42 pass (17 emulator + extraction suites incl. the
  real bundled payload).
- `./gradlew assembleDebug` — green; `libemxnative.so` present in the APK for
  arm64-v8a and x86_64.
- Native forkpty smoke test on the Linux host: interactive shell confirmed.
- Not verified on Android hardware: IME interaction, Back toggling, actual
  `sh -i` session inside the app, lifecycle under process-death.

## Known limitations
- Scrollback is populated but not gesture-browsable (offset clamped to live view).
- No alternate-screen / scroll regions — full-screen TUI programs will not
  render correctly yet.
- Terminal size is passed once at fork (40x100); no dynamic resize propagation.
