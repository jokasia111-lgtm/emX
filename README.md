# emX

Minimal, landscape-first Android app that sets up and launches a proot Linux
environment on supported Samsung Xclipse devices (Xclipse 920 / Galaxy S22
series, Xclipse 530 / Galaxy A55 class), with the RADV-Xclipse Vulkan driver
configured inside the Linux runtime.

**Status: early development.** Nothing is claimed to work until it is tested
on hardware. See `docs/research.md` for the architecture, licensing and
milestone plan.

## What is here now

- Landscape setup UI with device check and a log view.
- The RADV-Xclipse glibc driver bundle (Mesa 26.3.0-devel, from
  [JimVulkan/radv-xclipse](https://github.com/JimVulkan/radv-xclipse)) bundled
  as an unmodified APK asset, with SHA-256-verified, mode-preserving,
  atomic extraction into the app's private storage.

## Roadmap

1. ~~Research + repo~~
2. Driver payload in APK + verified extraction (this milestone)
3. In-app terminal (PTY) with Back-toggles-keyboard behavior
4. proot + minimal Debian arm64 rootfs, lifecycle management
5. RADV-Xclipse driver loading inside the runtime (`vulkaninfo`)
6. In-app X server for Linux graphical apps
7. KWin/Wayland (only if the X11/GPU path is proven)
8. Steam Big Picture (gated, unverified)

Verification levels are kept separate: packaged → extracted → loaded →
GPU-tested. Packaging alone proves nothing about on-device behavior.

## License

TBD — see `docs/research.md` (GPL-3.0 is the likely choice if the
Winlator-derived X server is vendored). The bundled driver payload is
MIT-licensed (Mesa) with its notices preserved inside the ZIP and in
`THIRD_PARTY_NOTICES.md`.
