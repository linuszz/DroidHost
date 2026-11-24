#!/bin/bash
set -euo pipefail
out=${1:?out dir}
here=$(cd "$(dirname "$0")" && pwd)
kde=$here/../desktop-kde
# FULL=1 builds the standalone distribution: closure resolves against an EMPTY runtime file with
# base-seeds.txt (the whole ALARM base + DE + GPU seeds), the overlay ships every package, pacman's
# local db records all of them, and os-release/the repo db carry the droidhost-omarchy identity.
# The ALARM tarball still lays the base rootfs (.INSTALL pre-initialization). FULL=0 (default)
# keeps the incremental pack for fallback comparison.
full=${FULL:-0}
case $full in 0|1) ;; *) echo "FULL must be 0 or 1" >&2; exit 1 ;; esac
if [ "$full" = 1 ]; then
  runtime_seeds=$here/base-seeds.txt
  distro=droidhost-omarchy
else
  runtime_seeds=$here/seeds.txt
  distro=desktop-omarchy
fi
# Omarchy is the Hyprland DE plus omarchy's Quickshell shell/theme/config layer. The compositor
# base is identical to desktop-hyprland: Hyprland nests through aquamarine's Wayland backend and
# renders via GBM-on-renderD0 + zink/Turnip, inheriting the KDE pipeline's mesa pin. The shell
# (quickshell + Qt6) and the whole Hyprland suite live in Arch extra (Arch builder signed), so the
# closure resolves them from core/extra/alarm exactly like hyprland — no external repo, no omarchy
# CDN, no self-built quickshell fork. omarchy v4.0.4's shell.qml has no OmarchyLoadPatch pragma
# (dev HEAD/quattro only), so Arch ARM stock quickshell 0.3.2 runs it.
source "$kde/mesa.env"
mirror=${ALARM_MIRROR:-https://fl.us.mirror.archlinuxarm.org/aarch64}
case $mirror in https://*) ;; *) echo "ALARM_MIRROR must be https" >&2; exit 1 ;; esac
builder=68B3537F39A313B3E574D06777193F152BDBE6A6
fetch() { curl -fsSL --proto '=https' --proto-redir '=https' --retry 6 --retry-delay 5 --retry-all-errors -o "$1" "$2"; }
work=$(mktemp -d); mkdir -p "$out" "$work/db" "$work/root"
pkgs=${PKG_CACHE:-$work/pkgs}; mkdir -p "$pkgs"
trap 'rm -rf "$work"' EXIT

for repo in core extra alarm; do
  fetch "$work/db/$repo.db" "$mirror/$repo/$repo.db"
  mkdir -p "$work/db/x_$repo" && tar -xzf "$work/db/$repo.db" -C "$work/db/x_$repo"
done

if [ "$full" = 1 ]; then
  : > "$work/runtime-empty.txt"
  runtime_file=$work/runtime-empty.txt
else
  runtime_file=$here/runtime-packages.txt
fi
python3 "$kde/closure.py" "$work/db" "$runtime_file" "$runtime_seeds" > "$work/pkglist.txt"
if [ "$full" = 1 ]; then
  echo "omarchy distro: $(wc -l < "$work/pkglist.txt") packages (full closure over the ALARM base)"
else
  echo "omarchy: $(wc -l < "$work/pkglist.txt") packages beyond the runtime's own"
fi

while read -r entry sum; do
  file=${entry#*/}
  url=$mirror/$entry
  [ "$file" != "$DESKTOP_MESA_PACKAGE" ] || url=$DESKTOP_MESA_URL
  [ -s "$pkgs/$file" ] || fetch "$pkgs/$file" "$url"
  fetch "$work/pkg.sig" "$url.sig"
  if ! gpgv --status-fd 1 --keyring "$kde/archlinuxarm-builder.gpg" "$work/pkg.sig" "$pkgs/$file" 2>/dev/null \
      | grep -Eq "^\[GNUPG:\] VALIDSIG .* $builder\$"; then
    echo "$file: no valid Arch Linux ARM Build System signature" >&2; rm -f "$pkgs/$file"; exit 1
  fi
  if [ "$(sha256sum "$pkgs/$file" | cut -d' ' -f1)" != "$sum" ]; then
    echo "$file: sha256 differs from the repository database's" >&2; rm -f "$pkgs/$file"; exit 1
  fi
  tar -xf "$pkgs/$file" -C "$work/root" --no-same-owner --no-same-permissions \
    --exclude=.PKGINFO --exclude=.MTREE --exclude=.INSTALL --exclude=.BUILDINFO --exclude=.CHANGELOG
done < "$work/pkglist.txt"

# Rebuild aquamarine from source with four KGSL/nested fixes (aquamarine-nested.patch), identical to
# desktop-hyprland: the shell does not touch aquamarine, so the Hyprland r1 base is reused verbatim.
# Arch aquamarine 0.16.0 assumes a desktop DRM/KMS stack and breaks on the nested Wayland backend over
# a KGSL render node: (1) wl_registry_bind hardcodes versions ignoring the host's advertised v1 ->
# clamp every global to min(supported, advertised); (2) reopenDRMNode bails on a DRM lease errno other
# than EINVAL/EOPNOTSUPP -> fall back to opening the render node on any lease failure; (3)
# CGBMAllocator::create hard-fails when DRM_CAP_PRIME is not advertised (KGSL/Turnip still exports via
# gbm_bo_get_fd_for_plane) -> make the check advisory; (4) the allocator ctor assigns a possibly-NULL
# gbm backend name / drmGetDeviceNameFromFd2 result to std::string (strlen SEGV) -> guard both.
# Cross-compile against the just-extracted aarch64 sysroot and overwrite the Arch package's library; a
# host hyprwayland-scanner generates the protocol bindings. Both use std::format, so they need gcc-14.
aq_ver=0.16.0
aq_sha256=42339c3096c9de6582c0d71bc86b7e63334a3faf6a2b917d385ad093caf66c3c
scanner_rev=62e62c1ca23da17612c6890d4ad2064f575643db
aq=$work/aq; mkdir -p "$aq/host"
hwdata_desc=$(find "$work/db/x_core" -maxdepth 1 -type d -name 'hwdata-*' | head -1)
hwdata_file=$(awk '/%FILENAME%/{getline; print; exit}' "$hwdata_desc/desc")
hwdata_sum=$(awk '/%SHA256SUM%/{getline; print; exit}' "$hwdata_desc/desc")
fetch "$work/hwdata.pkg" "$mirror/core/$hwdata_file"
[ "$(sha256sum "$work/hwdata.pkg" | cut -d' ' -f1)" = "$hwdata_sum" ] \
  || { echo "hwdata sha256 mismatch" >&2; exit 1; }
tar -xf "$work/hwdata.pkg" -C "$work/root" --no-same-owner --no-same-permissions \
  --exclude=.PKGINFO --exclude=.MTREE --exclude=.INSTALL --exclude=.BUILDINFO --exclude=.CHANGELOG
fetch "$aq/aquamarine.tar.gz" "https://github.com/hyprwm/aquamarine/archive/v$aq_ver.tar.gz"
[ "$(sha256sum "$aq/aquamarine.tar.gz" | cut -d' ' -f1)" = "$aq_sha256" ] \
  || { echo "aquamarine source sha256 mismatch" >&2; exit 1; }
tar -xzf "$aq/aquamarine.tar.gz" -C "$aq"
patch -d "$aq/aquamarine-$aq_ver" -p1 < "$here/aquamarine-nested.patch"
fetch "$aq/scanner.tar.gz" "https://github.com/hyprwm/hyprwayland-scanner/archive/$scanner_rev.tar.gz"
tar -xzf "$aq/scanner.tar.gz" -C "$aq"
cmake -S "$aq/hyprwayland-scanner-$scanner_rev" -B "$aq/scanner-build" -G Ninja \
  -DCMAKE_C_COMPILER=gcc-14 -DCMAKE_CXX_COMPILER=g++-14 \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$aq/host"
cmake --build "$aq/scanner-build"
cmake --install "$aq/scanner-build"
cat > "$aq/toolchain.cmake" <<EOF
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(CMAKE_C_COMPILER aarch64-linux-gnu-gcc-14)
set(CMAKE_CXX_COMPILER aarch64-linux-gnu-g++-14)
set(CMAKE_SYSROOT $work/root)
set(CMAKE_FIND_ROOT_PATH $work/root)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
set(ENV{PKG_CONFIG_LIBDIR} "$work/root/usr/lib/pkgconfig:$work/root/usr/share/pkgconfig")
set(ENV{PKG_CONFIG_SYSROOT_DIR} "$work/root")
EOF
export PATH="$aq/host/bin:$PATH"
cmake -S "$aq/aquamarine-$aq_ver" -B "$aq/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$aq/toolchain.cmake" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_PREFIX_PATH="$aq/host" \
  -DOpenGL_GL_PREFERENCE=GLVND -DBUILD_TESTING=OFF
cmake --build "$aq/build" --target aquamarine
DESTDIR="$aq/install" cmake --install "$aq/build"
rm -f "$work/root/usr/lib/libaquamarine.so"*
cp -a "$aq/install/usr/lib/libaquamarine.so"* "$work/root/usr/lib/"
rm -rf "$work/root/usr/share/hwdata" "$work/root/usr/share/pkgconfig/hwdata.pc"

# Vendor omarchy v4.0.4's shell/theme/config layer (MIT (c) Omacom). Pin the stable tag's commit, not
# dev HEAD (quattro needs a patched quickshell). The source is a checksummed tarball, no git clone.
# Only qt612-qualify-theme-color.patch is carried: Qt 6.12's QtQuick exports a Color singleton that
# shadows omarchy's qs.Commons Color, drawing the whole shell black; the patch qualifies it to
# Upstream.Color. It is omacom/mobile's patch (MIT (c) 2026 Omacom, same pin) rebased by DroidHost onto
# pristine v4.0.4 as a zero-offset diff. moarchy's port-4x.patch (its own phone-UI plugins) is NOT
# carried — DroidHost runs upstream's Hyprland coupling on Adreno, like moarchy's pkgrel>=3 target.
omarchy_ref=c668141e9c42b13c80c9ca4ea108e11708c5e8a5
omarchy_sha256=8cc6b1d9d903c606600395e3b3d80ad9f15bd3ab78f0f19b0f49041b1df82013
om=$work/omarchy; mkdir -p "$om"
fetch "$om/omarchy.tar.gz" "https://github.com/omacom/omarchy/archive/$omarchy_ref.tar.gz"
[ "$(sha256sum "$om/omarchy.tar.gz" | cut -d' ' -f1)" = "$omarchy_sha256" ] \
  || { echo "omarchy source sha256 mismatch" >&2; exit 1; }
tar -xzf "$om/omarchy.tar.gz" -C "$om"
omsrc=$om/omarchy-$omarchy_ref
patch -d "$omsrc" -p1 --fuzz=0 < "$here/qt612-qualify-theme-color.patch"
# moarchy's prepare() guards: a hunk that landed at an offset writes a .orig (--backup-if-mismatch),
# so read that signal rather than trusting --fuzz=0 (which bounds fuzz, not offset). A moved upstream
# must fail the build, not ship a hunk somewhere it was never aimed at.
stray=$(find "$omsrc" \( -name '*.orig' -o -name '*.rej' \) -print)
if [ -n "$stray" ]; then
  echo "omarchy: a qt612 hunk did not apply cleanly; upstream moved under it:" >&2
  echo "$stray" >&2; exit 1
fi
# No Quickshell.I3 (sway-era leftover that cannot connect -> bar renders but never populates).
if grep -rqE 'Quickshell\.I3|\bI3\.' "$omsrc/shell/"; then
  echo "omarchy: a Quickshell.I3 reference survived:" >&2
  grep -rlE 'Quickshell\.I3|\bI3\.' "$omsrc/shell/" >&2; exit 1
fi
# Upstream's own Hyprland coupling must survive (>=5 files import Quickshell.Hyprland at this pin).
hypr=$(grep -rlE 'import Quickshell\.Hyprland' "$omsrc/shell/" | wc -l)
if [ "$hypr" -lt 5 ]; then
  echo "omarchy: only $hypr shell files import Quickshell.Hyprland; expected at least 5" >&2; exit 1
fi
# /usr/share/omarchy is the path upstream hardcodes (OMARCHY_PATH). install/ is IN: a dozen runtime
# omarchy-* scripts source install/helpers (browser-policy etc.); leaving it out breaks theme-set.
mkdir -p "$work/root/usr/share/omarchy"
for d in shell config default themes applications migrations install; do
  [ -d "$omsrc/$d" ] && cp -a "$omsrc/$d" "$work/root/usr/share/omarchy/"
done
for f in version icon.png icon.txt logo.svg logo.txt LICENSE; do
  [ -f "$omsrc/$f" ] && install -Dm644 "$omsrc/$f" "$work/root/usr/share/omarchy/$f"
done
# bin/ -> /usr/bin (where upstream's package puts it and its sudoers.d entries name by absolute path).
# The DroidHost overlay then shadows omarchy-launch-shell / omarchy-system-lock (see overlay/).
mkdir -p "$work/root/usr/bin"
for f in "$omsrc"/bin/*; do
  [ -f "$f" ] && install -Dm755 "$f" "$work/root/usr/bin/$(basename "$f")"
done
install -Dm644 "$omsrc/etc/profile.d/omarchy.sh" "$work/root/etc/profile.d/omarchy.sh"
install -Dm440 "$omsrc/etc/sudoers.d/omarchy-theme-browser" "$work/root/etc/sudoers.d/omarchy-theme-browser"
# Omarchy's icon font: the bar's logo glyph (ue900) comes from it; without it the glyph is tofu.
if [ -f "$omsrc/default/fonts/omarchy/omarchy.ttf" ]; then
  install -Dm644 "$omsrc/default/fonts/omarchy/omarchy.ttf" "$work/root/usr/share/fonts/omarchy/omarchy.ttf"
fi
# Web-app artwork into an icon theme (applications/icons is read by no icon theme). hicolor/256x256/apps
# with upstream's safe_icon_name transform: lowercase, runs of non-alphanumerics to single dashes.
if [ -d "$omsrc/applications/icons" ]; then
  icon_dir=$work/root/usr/share/icons/hicolor/256x256/apps
  mkdir -p "$icon_dir"
  for f in "$omsrc"/applications/icons/*.png; do
    [ -f "$f" ] || continue
    icon_name=$(basename "$f" .png | tr '[:upper:]' '[:lower:]' | sed 's/[^[:alnum:]]\+/-/g; s/^-//; s/-$//')
    [ -n "$icon_name" ] || continue
    install -m644 "$f" "$icon_dir/$icon_name.png"
  done
fi

# yay (AUR helper) for the Install > AUR menu. yay-bin ships a prebuilt aarch64 binary with libalpm
# statically linked (NEEDED is only libc/libresolv), so the guest needs no Go toolchain and there is
# no libalpm soname to match against the guest's pacman. Pinned + checksummed like the other vendored
# sources. The AUR build toolchain (gcc/make/fakeroot...) is NOT seeded here -- build.sh strips dev
# headers below -- it is bootstrapped on first use by overlay/usr/local/bin/omarchy-pkg-aur-install.
yay_ver=13.1.0
yay_sha256=be9bde82b0ae601365bf38515a4bfe60840a0b9945994cc8631a974c0492aa2c
fetch "$work/yay.tar.gz" "https://github.com/Jguer/yay/releases/download/v$yay_ver/yay_${yay_ver}_aarch64.tar.gz"
[ "$(sha256sum "$work/yay.tar.gz" | cut -d' ' -f1)" = "$yay_sha256" ] \
  || { echo "yay binary sha256 mismatch" >&2; exit 1; }
tar -xzf "$work/yay.tar.gz" -C "$work"
install -Dm755 "$work/yay_${yay_ver}_aarch64/yay" "$work/root/usr/bin/yay"

# Shared pacman wrappers/hooks and sudo/su come from the KDE overlay: cp -a on the Linux builder
# preserves their exec bits and symlinks (sudoedit -> sudo, masked hooks -> /dev/null). The omarchy
# overlay then adds only the DE-specific files (hyprland.lua, skel, shell.json, launch shim, stubs).
mkdir -p "$work/root/usr/share/droiddeck" "$work/root/usr/local/bin"
cp -a "$kde/overlay/usr/share/droiddeck/pacman" "$work/root/usr/share/droiddeck/"
cp -a "$kde/overlay/usr/local/bin/pacman" "$kde/overlay/usr/local/bin/su" \
      "$kde/overlay/usr/local/bin/sudo" "$kde/overlay/usr/local/bin/sudoedit" "$work/root/usr/local/bin/"
cp -a "$here/overlay/." "$work/root/"
mkdir -p "$work/root/usr/lib/droiddeck"
${CC_AARCH64:-aarch64-linux-gnu-gcc} -shared -fPIC -O2 -Wall -Wextra \
  -o "$work/root/usr/lib/droiddeck/libdroiddeck-root.so" "$kde/root/root.c" -ldl
${STRIP_AARCH64:-aarch64-linux-gnu-strip} --strip-unneeded "$work/root/usr/lib/droiddeck/libdroiddeck-root.so"
python3 "$kde/pacman-db.py" "$work/db" "$pkgs" "$work/pkglist.txt" "$runtime_file" \
  "$here/mirror.txt" "${DESKTOP_OMARCHY_RELEASE_TAG:-}" "$work/root" "$out/repo"

if [ "$full" = 1 ]; then
  # The distribution identifies itself, not Arch. etc/os-release is the filesystem package's
  # symlink to usr/lib/os-release; overwrite the target and keep that layout.
  version_id=$(date -u -d "@${SOURCE_DATE_EPOCH:-$(date -u +%s)}" +%Y%m%d)
  cat > "$work/root/usr/lib/os-release" <<EOF
NAME="DroidHost Omarchy"
PRETTY_NAME="DroidHost Omarchy (Arch ARM)"
ID=droidhost-omarchy
ID_LIKE=arch
VERSION_ID=$version_id
EOF
  ln -sfn ../usr/lib/os-release "$work/root/etc/os-release"
  # New distribution, new db name. pacman-db.py is shared with KDE and stays untouched, so rename
  # its output here and follow through the conf section and the wrapper copies in THIS rootfs.
  mv "$out/repo/droiddeck.db" "$out/repo/droidhost.db"
  share=$work/root/usr/share/droiddeck/pacman
  mv "$share/droiddeck.db" "$share/droidhost.db"
  sed -i 's/^\[droiddeck\]$/[droidhost]/' "$share/pacman.conf"
  sed -i 's/droiddeck\.db/droidhost.db/g' "$share/prepare" "$share/remember" "$share/restore"
fi

rm -rf "$work/root/usr/share/doc" "$work/root/usr/share/info" \
       "$work/root/usr/share/gtk-doc" "$work/root/usr/include" "$work/root/usr/lib/pkgconfig" \
       "$work/root/usr/lib/cmake" "$work/root/usr/share/help"
find "$work/root/usr/share/locale" -mindepth 1 -maxdepth 1 -type d ! -name 'en*' -exec rm -rf {} + 2>/dev/null || true
find "$work/root/usr/lib" -name '*.a' -delete
chmod -R u+rwX "$work/root"

sed 's#^[a-z]*/##' "$work/pkglist.txt" > "$out/$distro.packages.txt"
tar -C "$work/root" --zstd -cf "$out/$distro.tar.zst" .
sha256sum "$out/$distro.tar.zst" | awk '{print $1}' > "$out/$distro.sha256"
ls -l "$out/$distro.tar.zst"