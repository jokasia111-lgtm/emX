# emX — Research Notes (Phase 1)

Minimal, landscape-first Android app that sets up and launches a proot Linux
environment on supported Samsung Xclipse devices, with the RADV-Xclipse Vulkan
driver configured inside the Linux runtime. Steam Big Picture is a later,
gated milestone.

Status: **research phase**. Everything below separates confirmed facts from
assumptions and unknowns. No on-device testing has happened yet.

---

## 1. Attached driver ZIP — inspection results (CONFIRMED)

Artifact: `radv-xclipse-proot-glibc-26.3.0-devel-dc5f6bc4e3.zip`
SHA-256: `c041ac31e18165442574deb305846119a3d239cee6b781f249dd4467683ecd29`

| Entry | Size | Unix mode | SHA-256 (first 32) |
|---|---:|---|---|
| `libvulkan_radeon.so` | 19,736,848 | 0644 | `5cdcba5c3d6fd9c254fb4f4cdc31877c` |
| `radeon_icd.aarch64.json` | 185 | 0644 | `3bebae8dd3dd7593fdb5908d257d69cf` |
| `install.sh` | 398 | 0755 | `ce8ce91d7214a1eb877d1711613f7857` |
| `README.txt` | 1,991 | 0644 | `4e3d6f4829fb89dfba29a92ec065501e` |
| `NOTICE.txt` | 18,902 | 0644 | `24a48a32300f94cb582a9b245ea34475` |

### What it is
- A glibc Linux userspace build of the RADV Vulkan driver (Mesa 26.3.0-devel
  with JimVulkan's Xclipse changes), targeting Termux + proot-distro
  environments on Samsung Xclipse GPUs.
- `libvulkan_radeon.so`: ELF64, AArch64, DYN (shared object). Vulkan ICD
  `api_version` 1.4.358. Build ID `1f282270fe3e48b3679a80b625dff97ca366ef8e`.
- ICD JSON points at the absolute path `/usr/lib/xclipse/libvulkan_radeon.so`
  and is installed by `install.sh` to
  `/usr/share/vulkan/icd.d/radeon_xclipse_icd.aarch64.json` (inside the distro).
- Upstream: [JimVulkan/radv-xclipse](https://github.com/JimVulkan/radv-xclipse)
  (releases v1.x, Mesa 26.3.0-devel). The Android (bionic) builds of the same
  source are packaged for emulators/Winlator-style loaders; **this ZIP is the
  glibc/proot variant**.

### Runtime requirements (from README.txt — supplier claims until tested)
- glibc >= 2.29 (Debian 11+, Ubuntu 20.04+). Confirmed from ELF
  `GLIBC_2.29` symbol version requirement (also `GLIBCXX_3.4.21`, so a matching
  libstdc++ is needed).
- `libvulkan1` (Vulkan loader) plus X11/xcb libs: `libxcb-dri3`, `libxcb`,
  `libX11-xcb`, `libxcb-present`, `libxcb-xfixes`, `libxcb-sync`,
  `libxcb-randr`, `libxcb-shm`, `libxshmfence` (all listed as `DT_NEEDED`).
- Opens `/dev/dri/renderD128` directly; proot-distro binds `/dev` by default.
- Supported GPUs (upstream README): Xclipse 920 (Exynos 2200, Galaxy S22
  series) and Xclipse 530 (Exynos 1480, e.g. Galaxy A55). Other Xclipse
  models not compatible.
- Presentation: DRI3 via Termux:X11 (fastest) or automatic CPU-copy fallback
  (PutImage) for X servers without DRI3; `MESA_VK_WSI_DEBUG=sw` forces it.
- Diagnostics: `RADV_XCLIPSE_LOG=1|2` environment variables.

### Licensing (CONFIRMED — redistribution is permitted)
- Driver: MIT (Mesa) with embedded components: libdrm (MIT), Expat (MIT),
  zlib (Zlib), xxHash (BSD-2), radix sort (BSD-2), SoftFloat3 (BSD-3), BLAKE3
  (Apache-2.0), C11 threads (BSL-1.0). All texts present in `NOTICE.txt`.
- Conclusion: the ZIP **may be legally redistributed** inside an APK provided
  `NOTICE.txt` (and README) are preserved alongside it. We bundle the original,
  unmodified ZIP as an APK asset and preserve all five entries verbatim.
- Attribution note: not affiliated with Samsung/AMD/Mesa; Xclipse changes
  © 2026 JimVulkan, MIT.

---

## 2. Minimum viable architecture (HYPOTHESIS to validate)

```
┌──────────────────────── emX Android app (Kotlin) ────────────────────────┐
│  Landscape setup UI → downloads/extracts rootfs + proot to private dir   │
│  In-app terminal view (PTY) ← display+input for the Linux shell           │
│  In-app X server (Winlator-derived, from harbor-fable) ← later milestone  │
├──────────────────────────────────────────────────────────────────────────┤
│  proot (arm64) + minimal glibc rootfs (Debian 11+ arm64)                  │
│  RADV-Xclipse driver installed at /usr/lib/xclipse + ICD json              │
│  VK_ICD_FILENAMES → radeon_xclipse_icd.aarch64.json                        │
│  GPU: /dev/dri/renderD128 (direct, no Android bionic loader)              │
└──────────────────────────────────────────────────────────────────────────┘
```

Key decisions and rationale:
- **proot + glibc rootfs**, not bionic: the supplied driver is a glibc build;
  it expects a distro userspace. proot gives us that without root.
- **Driver runs inside proot**, never loaded on the Android/bionic side. No
  shim needed (harbor-fable's bionic shim approach is *not* our path).
- **Terminal milestone first**: an in-app PTY terminal view proves the
  display/input bridge with zero X11 complexity.
- **In-app X server** (from harbor-fable, Winlator-derived) as the graphics
  bridge for the first graphics milestone: single APK, no external app
  dependency (Termux:X11 requires the Termux app + companion package —
  awkward for a standalone emX). X11 clients inside proot connect via Unix
  socket bound into the rootfs.
- **KWin/Wayland**: deferred. Only if the X11 path, GPU access, and input
  paths are proven first. Documented fallback: X11 without DRI3 (CPU-copy
  presentation) per the driver README.

### What we can reuse and under what license
| Component | Source | License | Notes |
|---|---|---|---|
| RADV-Xclipse glibc driver | attached ZIP | MIT + permissive set | Bundle as-is with NOTICE |
| In-app X server, XConnector, renderer | harbor-fable-0402 (vendored from Winlator) | **GPL-3.0 (Winlator code)** | harbor-fable is MIT overall but the `com/winlator/*` files are GPL-3; reusing them makes those parts of emX GPL-3 |
| adrenotools driver loader | harbor-fable/cpp/adrenotools | Apache-2.0 (Winlator upstream) | Not needed for the glibc-in-proot path — harbor-fable uses it for its **bionic** RADV build |
| proot (binary) | termux/proot | GPL-2.0+ (verify exact) | Ship as jniLibs-style executable |
| proot-distro scripts | termux/proot-distro | GPL-3.0-only | If we reuse scripts, emX scripts must be GPL-3; simplest is writing our own minimal bootstrap |
| Debian/Ubuntu arm64 rootfs | distro images | Various (DFSG) | Download on first run, not bundled in APK |

Clarification on harbor-fable-0402 (user-provided): it runs a **bionic** (Android-native)
RADV build loaded through a Vulkan shim on the Android side, plus a Winlator-derived
in-app X server, to run Wine. emX's path is different: the **glibc** driver runs inside
proot against the distro's own Vulkan loader, no shim. What emX can take from it is the
in-app X server (display/input bridge) and the asset-download/extract/verify pipeline
patterns — not the bionic driver-loading code.

Decision: emX app code will be written from scratch; if we vendor the
Winlator-derived X server (likely, milestone 5+), emX must be licensed
**GPL-3.0** at least for those parts — simplest is GPL-3.0 for the whole
repo. Flagged for user decision.

### Hardware and Android-version constraints
- Devices: Galaxy S22 series (Xclipse 920) and Galaxy A55 class (Xclipse 530)
  only, per upstream support table. Everything else is out of scope.
- Android version gate for GPU access: `/dev/dri/renderD128` access from an
  untrusted app domain is **not guaranteed across Android versions**. The
  bionic RADV community reports Android 14+ ("Android 14+, custom driver
  loader" for the bionic package). The glibc/proot variant's Android floor is
  **UNKNOWN** — must be tested on-device. Note that render-node access from
  apps has historically been SELinux-blocked on many builds; Termux:X11's
  virglrenderer/DRI3 path suggests it works in Termux's app context on some
  devices/versions.
- proot uses ptrace; Android 12+ "phantom process killer" / "Restrict child
  processes" can kill our process tree (DroidDeck documents the same issue and
  the `adb shell device_config put activity_manager max_phantom_processes
  2147483647` workaround). We must document this as a first-run setup step.
- Executables cannot be exec'd from the app's writable home on Android 10+
  (targetSdk 29+). Standard solution: ship executables inside the APK's
  native lib dir (extracted to `nativeLibraryDir`, which is executable), or
  extract to app data dir only on targetSdk <= 28. emX will use the
  **jniLibs approach**: `libproot.so`-named executables in `jniLibs/arm64-v8a`.

---

## 3. Driver storage in APK and runtime extraction (PLAN)

- Store the **original, unmodified ZIP** at
  `app/src/main/assets/driver/radv-xclipse-proot-glibc-26.3.0-devel-dc5f6bc4e3.zip`.
- On first launch (and after app updates), extract into a versioned private
  directory: `filesDir/drivers/radv-xclipse/26.3.0-devel-dc5f6bc4e3/`.
- Extraction contract:
  - Verify SHA-256 of the ZIP before extraction; verify per-file hashes
    after extraction.
  - Preserve modes (install.sh 0755, others 0644).
  - Write to a `.tmp` staging dir, then atomically rename into place.
  - Never execute `install.sh` on the Android host side — it is meant for
    the distro root. Instead, the app installs the driver into the proot
    rootfs at the documented paths (`/usr/lib/xclipse/`,
    `/usr/share/vulkan/icd.d/`) during rootfs provisioning, mirroring
    install.sh exactly.
- Verification levels (kept separate):
  1. Packaged: ZIP present in APK, hashes match.
  2. Extracted: layout + modes + hashes correct in private dir.
  3. Loaded: distro's `vulkaninfo --summary` sees the ICD and enumerates
     the Xclipse GPU (requires device).
  4. GPU-tested: `vkcube` renders via the chosen display path (requires
     device).

## 4. Biggest risks / unresolved questions

1. **GPU node access** (highest risk): can the emX app's UID actually open
   `/dev/dri/renderD128` under the device's SELinux policy? Unverified. The
   bionic ecosystem (Winlator/harbor-fable) accesses KGSL/DRM through loaders
   — their path differs. Smallest experiment: on-device test app that opens
   the render node and runs `vulkaninfo` inside proot.
2. **X server licensing**: Winlator-derived code is GPL-3.0; emX license
   decision needed before vendoring.
3. **DRI3 vs CPU-copy presentation** through the Winlator-style X server:
   Winlator's X server does not implement DRI3 (unverified — check
   extensions). Without DRI3, frames go over the socket by CPU copy (works,
   slower). Termux:X11 supports DRI3 but drags in Termux as a dependency.
4. **Phantom process killer** on Android 12+: user-facing setup step or
   automated fix needed.
5. **Rootfs size**: a Debian arm64 minimal rootfs is ~150-300MB compressed;
   first-run download + checksum verification needed (APK stays small).
6. **proot performance**: syscall interception is slow (ptrace); acceptable
   for terminal/setup, may matter for graphics.
7. **Steam later**: native ARM64 Steam client + gamescope + KWin nesting are
   all unproven on Xclipse; strictly gated behind the graphics milestones.

## 5. Milestones

| # | Milestone | Verification |
|---|---|---|
| 1 | Research doc (this file), repo `emX` created | pushed |
| 2 | Minimal landscape app + driver ZIP in APK + verified extraction | APK contents inspected, extraction unit-checked on host; on-device first-launch marked unverified |
| 3 | In-app terminal view running a local Android shell via PTY; Back toggles keyboard | on-device |
| 4 | proot + minimal rootfs bootstrapped from the app; shell inside distro; lifecycle management | on-device |
| 5 | Driver installed into rootfs; `vulkaninfo` enumerates Xclipse GPU | on-device |
| 6 | In-app X server: display an X11 app (e.g. xterm) from inside proot, input works | on-device |
| 7 | KWin/Wayland only if 5+6 proven; else document X11 fallback | on-device |
| 8 | Steam Big Picture (gated) | on-device |
| 9 | CI: APK build automation once milestone 2+ works locally | CI green |

## Sources

- [JimVulkan/radv-xclipse](https://github.com/JimVulkan/radv-xclipse) and its
  [releases](https://github.com/JimVulkan/radv-xclipse/releases)
- [sebialex1/harbor-fable-0402](https://github.com/sebialex1/harbor-fable-0402)
  (in-app X server, adrenotools, asset pipeline; MIT + vendored GPL-3 Winlator code)
- [termux/proot-distro](https://github.com/termux/proot-distro) (GPL-3.0-only)
- [termux/termux-x11](https://github.com/termux/termux-x11) (DRI3 presentation
  reference, Android 8+)
- [Droid-Deck/DroidDeck](https://github.com/Droid-Deck/DroidDeck) (gamescope +
  proot + in-app compositor reference; GPL-3.0; Adreno-only — Xclipse
  explicitly unsupported there, which is why emX exists)
- Driver bundle README.txt / NOTICE.txt (attached ZIP, inspected directly)
