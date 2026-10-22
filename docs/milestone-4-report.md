# emX — Milestone 4 report: Termux-backed Linux runtime + driver handoff

## Status

Code complete and host-verified. **Nothing in this milestone is proven on
hardware**: no device or AVD was available, so install, Vulkan enumeration and
visual rendering remain unverified. The APK builds, the host test suite passes,
and the CLI formats the runtime script depends on were confirmed against
upstream sources rather than guessed.

## Scope change from the plan

The original milestone 4 was "proot + minimal Debian arm64 rootfs, lifecycle
management". The bundled payload's own `README.txt` states it targets **Termux +
proot-distro Debian/Ubuntu**, and Termux already ships a maintained
`proot-distro` (5.10.2 in termux-packages). Re-implementing a rootfs manager
would duplicate that work and diverge from what the driver expects, so emX now
drives Termux through its public `RUN_COMMAND` API instead. emX is a launcher
and provisioner, not a standalone Linux runtime.

A consequence of the Android sandbox: emX cannot write into Termux's private
files. Two handoffs were designed around that:

- the runtime script travels as the `bash -c` body of the `RUN_COMMAND` intent;
- the driver ZIP is served by a loopback-only, single-token HTTP server that
  Termux downloads with `curl` and verifies against the SHA-256 emX passes as an
  argument.

## Files

### New
- `app/src/main/assets/runtime/termux-runtime.sh` — runtime controller with
  `setup`, `probe`, `shell`, `x11-setup`, `cube`.
- `app/src/main/java/io/emx/runtime/PayloadServer.kt` — loopback driver server.
- `app/src/main/java/io/emx/runtime/TermuxBridge.kt` — `RUN_COMMAND` dispatch,
  install/permission checks.
- `app/src/main/java/io/emx/runtime/RuntimeService.kt` — foreground service that
  waits for the one-shot Termux result.
- `app/src/main/java/io/emx/runtime/RuntimeResultReceiver.kt` — result bundle
  parsing and success-marker checks.
- `app/src/main/java/io/emx/runtime/RuntimeState.kt` — persisted operation,
  status and log state.
- `app/src/main/java/io/emx/runtime/VulkanProbe.kt` — `vulkaninfo --summary`
  GPU-block parser.
- `app/src/main/java/io/emx/term/TerminalKeys.kt` — IME/hardware key to PTY byte
  mapping (UTF-8 text, arrows, Home/End, PgUp/PgDn, Tab, Enter, BS, Ctrl, Alt).
- `.github/workflows/android.yml` — Linux CI: unit tests, debug APK, 16 KB
  alignment check, artifact upload, read-only permissions, no publishing.
- `app/src/test/java/io/emx/runtime/VulkanProbeTest.kt` (9),
  `PayloadServerTest.kt` (4),
  `app/src/test/java/io/emx/term/TerminalEmulatorHistoryTest.kt` (3).

### Modified
- `MainActivity.kt` — workflow UI (connect/help, Install Linux, Check Vulkan,
  Linux terminal, X11 tools, Launch cube, open Termux:X11, Android diagnostic
  shell, log, copy diagnostics), runtime permission launchers, preference-listener
  driven refresh, back callback, edge-to-edge insets, deadline recheck on resume.
- `TerminalView.kt` — input now reaches the child instead of the emulator;
  scrollback browsing with correct bottom-anchored offset, wheel scroll, resize
  callback, IME toggle through `InputMethodManager`.
- `TerminalEmulator.kt` — VT100 auto-wrap delay, bounded scrollback helper,
  history counters, bounded OSC/CSI parsing, strict dimensions.
- `PtyRunner.kt` / `NativePty.kt` / `emxpty.c` — full environment handoff,
  requested window size, `TIOCSWINSZ` resize, non-blocking master I/O, per-launch
  session ownership of fd/PID, child reaping, error reporting from the child
  before exec.
- `DriverPayload.kt` — `applyMode` now fails instead of silently reporting
  success when POSIX attributes are unavailable.
- `AndroidManifest.xml` — INTERNET, foreground-service plus `specialUse` type,
  POST_NOTIFICATIONS, `com.termux.permission.RUN_COMMAND`, `<queries>` for
  `com.termux` and `com.termux.x11`, service and receiver declarations.
- `app/build.gradle.kts` / `build.gradle.kts` — AGP 8.7.3, SDK 35, build-tools
  36.0.0, NDK 28.2.13676358, 16 KB packaging, optional env-var release signing,
  JUnit dependency; duplicate `defaultConfig` block and unused
  `constraintlayout` dependency removed.

## Verification levels are kept separate

The UI reports these independently and none implies the next:

| Level | Evidence |
| --- | --- |
| Packaged | debug APK contains the driver ZIP and `termux-runtime.sh` as assets |
| Transferred | Termux verifies the ZIP against the SHA-256 `sha256sum -c` before unpacking |
| Installed | exact `EMX_SETUP_OK:<digest>` marker in the Termux result stdout |
| Enumerated | `VulkanProbe` matched vendor `0x1002` + `driverName = radv` + `VANGOGHLITE`/`XCLIPSE` in one GPU block |
| Rendered | human confirmation of `vkcube` in Termux:X11; emX never claims this |

A successful `RUN_COMMAND` dispatch is also recorded as *dispatch*, not
*install* — Termux may still reject it (for example `allow-external-apps=false`).

## Upstream formats confirmed rather than assumed

- `proot-distro install -n/--name <image-ref>` and
  `login <name> --shared-tmp --bind A:B -- cmd` — read from the current Python
  CLI parser in `termux/proot-distro`; termux-packages pins version 5.10.2.
- `vulkaninfo --summary` emits per-GPU blocks headed `GPU0:` with
  `vendorID`, `deviceName` and `driverName` key/value lines — read from
  `DumpSummaryGPU` in KhronosGroup/Vulkan-Tools at the tag Debian bookworm
  packages (1.3.204). The parser also accepts a spaced `GPU 0:` header.
- Driver install mirrors the payload's own `install.sh`:
  `/usr/lib/xclipse/libvulkan_radeon.so` and
  `/usr/share/vulkan/icd.d/radeon_xclipse_icd.aarch64.json`, with `NOTICE.txt`
  and `README.txt` preserved under `/usr/share/doc/emx-driver`.
- Termux result bundle keys (`stdout`, `stderr`, `errmsg`, `exitCode`, `err`)
  and `com.termux.RUN_COMMAND_*` extras match the Termux constants.

## Bugs fixed while getting this to build

1. `pipe2()` is API 28+; the NDK target is API 26, so the child-error pipe now
   uses `pipe()` + `fcntl(FD_CLOEXEC)` set before the fork.
2. The emulator lacked VT100 auto-wrap delay: printing into the last column
   advanced the cursor past the line end, so `x\n` at `cols = 1` scrolled a
   second, blank line per character and inflated the history counter.
3. `flock` and `df` checks ran *before* `pkg install util-linux`, so a clean
   Termux could fail on a missing `flock`; package installation now precedes
   locking, and the free-space probe no longer depends on `awk`.
4. `applyMode` could return success when the file mode could not be read.
5. JNI reads used `ReleaseByteArrayElements` with `JNI_ABORT`, discarding the
   bytes just read from the PTY master.
6. Terminal input was rendered locally instead of being written to the child.

## Build evidence

From a clean tree (`clean testDebugUnitTest assembleDebug assembleRelease
lintDebug`, Gradle 8.9 / AGP 8.7.3 / JDK 17, Windows host):

- Build: `BUILD SUCCESSFUL`, 111 tasks, exit code 0.
- Host tests: **37 completed, 0 failed, 2 skipped** — the skips are the two
  POSIX-permission assertions skipped on Windows via `Assume`; CI runs them on
  Linux.
- Debug APK 7,805,550 bytes; unsigned release APK 7,081,654 bytes.
- `zipalign -c -P 16 -v` : both `libemxnative.so` entries report `OK`
  (arm64-v8a at 2,736,128, x86_64 at 2,752,512) and `Verification successful`.
- APK contents: `lib/arm64-v8a/libemxnative.so`, `lib/x86_64/libemxnative.so`,
  `assets/driver/radv-xclipse-proot-glibc-26.3.0-devel-dc5f6bc4e3.zip`,
  `assets/runtime/termux-runtime.sh`.
- `lintDebug`: no errors; remaining warnings are `SdCardPath` false positives on
  Termux paths, the deliberately landscape `screenOrientation`, deprecated
  launcher-icon shape, unused color resources and newer-dependency notices.
- `bash -n` on `termux-runtime.sh`: clean.
- `assembleRelease` with no signing environment produces
  `app-release-unsigned.apk` and never falls back to the debug key; a partial
  variable set fails `validateReleaseSigningEnvironment`.

## Not verified

- Any Termux interaction: `RUN_COMMAND` acceptance, `allow-external-apps`,
  permission prompt behavior, session visibility.
- `proot-distro install -n emx-debian debian:bookworm` actually resolving and
  unpacking, and the guest `apt-get` dependency set installing.
- Driver loading, `vulkaninfo` output on Xclipse, `VK_ICD_FILENAMES` handling
  under proot, `/dev/dri/renderD128` access.
- Termux:X11 presentation of `vkcube`, DRI3 path, display `:4` socket sharing.
- The Android PTY at runtime: JNI binding, resize propagation, IME behavior,
  Back-toggles-keyboard, cleanup under process death.
- Foreground-service acceptance on Android 14/15 with `specialUse`.

## Security and safety decisions

- Payload server binds `127.0.0.1` only, requires the exact
  `GET /<256-bit-token>/driver.zip HTTP/1.1` request, rejects any `Origin`
  header, sets `no-store`, and revokes the token when the service stops.
- The Termux command permission is requested only when the user taps an action,
  and the UI states plainly that this permission lets emX run commands in
  Termux.
- Setup refuses to touch an existing `emx-debian` it did not create; container
  ownership is recorded before creation.
- Disk space, ARM64 architecture, checksum length and payload URL are validated
  before any package or rootfs download.
- No keystore, key material or license file was invented; `LICENSE` stays `TBD`
  pending the upstream-X-server question in `docs/research.md`.

## Known limitations

- Requires Termux; not a self-contained runtime.
- `probe` waits 2 minutes and setup 30; a slow first `vulkaninfo` under proot
  can exceed the probe deadline, which is reported as "no result before the
  deadline", not as a failure of the driver.
- No uninstall or container-cleanup action exists yet.
- The guest script assumes root inside `emx-debian` (proot-distro default).
- `DriverPayload.extract()` is currently exercised only by host tests; the
  Termux path streams the verified ZIP directly. Extraction stays for the gated
  standalone-runtime milestone rather than being deleted.
- No alternate screen or scroll regions in the emulator, so full-screen TUI
  programs still will not render correctly.
