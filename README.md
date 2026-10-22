# emX

A landscape-first Android launcher that provisions a Debian ARM64 runtime in
Termux/proot-distro and installs the bundled third-party RADV-Xclipse driver
for supported Samsung Xclipse devices. Termux is a required companion; emX is
not a standalone proot runtime.

**Status: early development.** Host tests cover the packaging and command
plumbing only. Install, Vulkan enumeration, and visual rendering remain
hardware-verified claims and should be tested separately. See
`docs/milestone-4-report.md` for the current runtime design, evidence and gaps.

## Current flow

1. emX transfers the bundled driver ZIP over a one-token, loopback-only local
   server. Termux verifies its SHA-256, creates the dedicated `emx-debian`
   container, and installs Debian bookworm plus runtime dependencies.
2. Driver installation mirrors the payload's `install.sh` inside the container.
3. `Check Vulkan` runs `vulkaninfo --summary` and reports whether the Xclipse
   `radv` device was enumerated; this is explicitly separate from a visual
   rendering result.
4. The Linux terminal is opened through Termux's `RUN_COMMAND` API.
5. Optional Termux:X11 tooling can start display `:4` and launch `vkcube`.

The in-app PTY is an Android diagnostic shell, not the Linux shell. Linux
terminal and graphics sessions run in Termux because emX cannot read another
app's private files in the Android sandbox.

## Requirements

- ARM64 device and Termux; Xclipse 920 or Xclipse 530 for the bundled driver.
- Termux setting `allow-external-apps=true`, followed by
  `termux-reload-settings`.
- The Android `RUN_COMMAND` permission granted to emX.
- At least 2 GiB free for Debian, packages, and the driver.
- `proot-distro` 5.x (Termux currently packages 5.10.2), which provides
  `install --name <name> <image-ref>` and `login --shared-tmp --bind`.
- Termux:X11 only for graphical presentation.

Only grant the Termux command permission to software you trust. Its setup
commands can alter the Termux environment and are run only after the user
taps the requested action.

## Build

JDK 17 plus Android SDK 35, build-tools 36.0.0, NDK 28.2.13676358 and
CMake 3.22.1.

```
./gradlew testDebugUnitTest assembleDebug   # host tests + debug APK
./gradlew assembleRelease                   # unsigned unless signing is configured
```

Release signing reads `EMX_KEYSTORE_PATH`, `EMX_KEYSTORE_PASSWORD`,
`EMX_KEY_ALIAS` and `EMX_KEY_PASSWORD`. A partial set fails the build; no key
material belongs in the repository. CI (`.github/workflows/android.yml`) runs
the host tests, builds the debug APK and verifies 16 KB ZIP alignment.

## Roadmap

1. ~~Research and repository setup~~
2. ~~Driver payload in APK plus verified extraction~~
3. ~~Android diagnostic PTY~~
4. Termux/proot-distro setup and handoff (implemented, hardware pending)
5. `vulkaninfo` result parsing (implemented, hardware pending)
6. Termux:X11 cube launcher (implemented, hardware pending)
7. KWin/Wayland and a self-contained proot runtime remain gated on real-device
   results and demand.
8. Steam Big Picture remains speculative.

## License

TBD — see `docs/research.md`. The bundled driver payload is MIT-licensed
(Mesa), with upstream notices preserved inside the ZIP and in
`THIRD_PARTY_NOTICES.md`.
