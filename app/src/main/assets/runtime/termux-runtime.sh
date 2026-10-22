set -euo pipefail
export PATH="/data/data/com.termux/files/usr/bin:$PATH"
export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PD_FORCE_NO_COLORS=1
umask 077
operation=$1
container=emx-debian
state="$HOME/.local/share/emx"
icd=/usr/share/vulkan/icd.d/radeon_xclipse_icd.aarch64.json

fail() { printf '%s\n' "$*" >&2; exit 1; }
guest() { proot-distro login "$container" --shared-tmp -- "$@"; }
require_runtime() {
    test -f "$state/owned-container" || fail 'Run Install Linux in emX first.'
    command -v proot-distro >/dev/null || fail 'Install proot-distro in Termux first.'
    guest /bin/test -f /etc/emx-runtime-version || fail 'Linux setup is incomplete. Run Install Linux again.'
}

case "$operation" in
setup)
    test "$(uname -m)" = aarch64 || fail 'The bundled driver requires an ARM64 device.'
    url=$2
    digest=$3
    version=$4
    test "${#digest}" = 64 || fail 'Driver checksum is invalid.'
    case "$url" in http://127.0.0.1:*) ;; *) fail 'Invalid local payload address.' ;; esac
    mkdir -p "$state"
    printf '%s\n' 'Installing Termux runtime packages...'
    pkg install -y proot-distro curl unzip util-linux
    command -v proot-distro >/dev/null || fail 'proot-distro installation failed.'
    command -v flock >/dev/null || fail 'util-linux installation failed; flock is required.'
    exec 9>"$state/setup.lock"
    flock -n 9 || fail 'An emX installation is already running in Termux.'
    available=$(df -P "$HOME" | tail -n 1 | tr -s ' ' | cut -d' ' -f4)
    case "$available" in ''|*[!0-9]*) available=0 ;; esac
    test "$available" -ge 2097152 || fail 'At least 2 GiB of free space is required for Linux setup.'
    work=$(mktemp -d "$state/install.XXXXXXXX")
    trap 'rm -rf -- "$work"' EXIT
    printf '%s\n' 'Receiving the bundled driver from emX...'
    curl --fail --silent --show-error --noproxy '*' --connect-timeout 10 --max-time 120 "$url" -o "$work/driver.zip"
    printf '%s  %s\n' "$digest" "$work/driver.zip" | sha256sum -c -
    unzip -q "$work/driver.zip" -d "$work/payload"
    root="$PREFIX/var/lib/proot-distro/containers/$container"
    legacy="$PREFIX/var/lib/proot-distro/installed-rootfs/$container"
    if test ! -f "$state/owned-container"; then
        test ! -e "$root" && test ! -e "$legacy" || fail 'A container named emx-debian already exists. emX will not overwrite it.'
        printf '%s\n' emx-debian > "$state/owned-container"
    fi
    if test ! -d "$root" && test ! -d "$legacy"; then
        printf '%s\n' 'Installing Debian bookworm (network download)...'
        proot-distro install --name "$container" debian:bookworm
    fi
    proot-distro login "$container" --shared-tmp --bind "$work/payload:/opt/emx-payload" -- /bin/sh -s -- "$version" <<'GUEST'
set -eu
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates libvulkan1 vulkan-tools libstdc++6 libxcb-dri3-0 libxcb1 libx11-xcb1 libxcb-present0 libxcb-xfixes0 libxcb-sync1 libxcb-randr0 libxcb-shm0 libxshmfence1 x11-utils
mkdir -p /usr/lib/xclipse /usr/share/vulkan/icd.d /usr/share/doc/emx-driver
install -m 644 /opt/emx-payload/libvulkan_radeon.so /usr/lib/xclipse/libvulkan_radeon.so.new
mv /usr/lib/xclipse/libvulkan_radeon.so.new /usr/lib/xclipse/libvulkan_radeon.so
install -m 644 /opt/emx-payload/radeon_icd.aarch64.json /usr/share/vulkan/icd.d/radeon_xclipse_icd.aarch64.json
install -m 644 /opt/emx-payload/NOTICE.txt /usr/share/doc/emx-driver/NOTICE.txt
install -m 644 /opt/emx-payload/README.txt /usr/share/doc/emx-driver/README.txt
printf '%s\n' 'export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/radeon_xclipse_icd.aarch64.json' > /etc/profile.d/emx-vulkan.sh
printf '%s\n' "$1" > /etc/emx-runtime-version
GUEST
    printf 'EMX_SETUP_OK:%s\n' "$digest"
    ;;
probe)
    require_runtime
    guest /usr/bin/env VK_ICD_FILENAMES="$icd" RADV_XCLIPSE_LOG=1 /usr/bin/vulkaninfo --summary
    ;;
shell)
    require_runtime
    exec proot-distro login "$container" --shared-tmp -- /usr/bin/env VK_ICD_FILENAMES="$icd" /bin/bash -l
    ;;
x11-setup)
    require_runtime
    pkg install -y x11-repo
    pkg install -y termux-x11-nightly
    printf '%s\n' EMX_X11_TOOLS_OK
    ;;
cube)
    require_runtime
    command -v termux-x11 >/dev/null || fail 'Run Install X11 tools in emX first.'
    display=:4
    socket="$PREFIX/tmp/.X11-unix/X4"
    test ! -e "$socket" || fail 'Display :4 is already in use. Close the previous emX graphics session first.'
    termux-x11 "$display" -nolisten tcp &
    server=$!
    trap 'kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true' EXIT
    printf '%s\n' 'Starting display :4. Open Termux:X11 using emX. Ctrl+C here stops this graphics session.'
    ready=0
    for attempt in {1..20}; do
        kill -0 "$server" 2>/dev/null || fail 'Termux:X11 exited before the display was ready.'
        if guest /usr/bin/env DISPLAY="$display" xdpyinfo >/dev/null 2>&1; then ready=1; break; fi
        sleep 0.25
    done
    test "$ready" = 1 || fail 'Termux:X11 did not become ready. Check the companion app is installed.'
    guest /usr/bin/env DISPLAY="$display" VK_ICD_FILENAMES="$icd" /usr/bin/vkcube
    ;;
*) fail 'Unknown emX runtime operation.' ;;
esac
