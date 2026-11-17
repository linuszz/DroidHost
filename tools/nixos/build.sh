#!/bin/bash
# Builds the NixOS machine image on a native arm64 runner with nix installed.
#
# The configuration is evaluated against the nixos-26.05 release channel (the pin: the release
# channel only takes backports). The tarball it produces holds the whole closure plus the
# nix-path-registration manifest; /etc, /bin and /usr are filled by the activation script, which
# this runs offline in a chroot so the device never has to.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$(realpath -m "${1:?out dir}")
runtime_id=${2:?runtime id, e.g. nixos-r1}
[ "$(uname -m)" = aarch64 ] || { echo 'the NixOS runtime needs a native arm64 runner' >&2; exit 1; }
command -v nix-build >/dev/null || { echo 'nix is required' >&2; exit 1; }
work=$(mktemp -d)
root=$work/root
# Activation adds sub-mounts under /dev and /run (dev/shm, dev/mqueue, run/keys), so a plain
# umount of the bind mount stays busy. Tear down deepest-first; --one-file-system then keeps rm
# from ever following a still-live bind mount into the host.
unmount_root() {
  sudo awk -v p="$root/" 'index($2, p) == 1 { print $2 }' /proc/mounts | sort -r |
    while read -r m; do sudo umount "$m" 2>/dev/null || true; done
}
cleanup() {
  unmount_root
  sudo rm -rf --one-file-system "$work"
}
trap cleanup EXIT
mkdir -p "$root" "$out"

toplevel=$(nix-build '<nixpkgs/nixos>' -A system -I "nixos-config=$here/configuration.nix" --no-out-link)
nix-build '<nixpkgs/nixos>' -A config.system.build.tarball -I "nixos-config=$here/configuration.nix" --out-link "$work/tarball"
# --out-link is a symlink into the store; find does not follow it without -L.
archive=$(find -L "$work/tarball" -type f -name '*.tar.*' | head -n 1)
[ -n "$archive" ] || { echo 'the tarball build left no archive' >&2; exit 1; }
tar -xf "$archive" -C "$root"
# The tarball carries only the store. Create the FHS skeleton activation populates — /etc above
# all, since setup-etc.pl and the users/hashes snippets write into it — plus the bind-mount points.
mkdir -p "$root"/{etc,var/lib,var/log,tmp,root,home,run,bin,usr} "$root/dev/pts" "$root/proc" "$root/sys"
for m in dev dev/pts proc sys; do sudo mount --bind "/$m" "$root/$m"; done

# The activation the container's init would run at boot, run once here instead. Its shebangs
# point into the store, which the tarball already holds, so it needs no FHS of its own.
sudo chroot "$root" "$toplevel/activate"

# The guest's /bin and /usr/bin hold only the FHS shim symlinks, not coreutils, so a bare chroot
# shell cannot find ln/mkdir/nix-channel. Put the system path on PATH for the follow-up commands.
guest() {
  sudo chroot "$root" /bin/sh -c "export PATH='$toplevel/sw/bin:$toplevel/sw/sbin'; $1"
}
guest "ln -sfn '$toplevel' /run/current-system"
guest "ln -sfn '$toplevel' /run/booted-system"
guest "mkdir -p /nix/var/nix/profiles && ln -sfn '$toplevel' /nix/var/nix/profiles/system"
# Register the closure in the guest's nix database, and give root a channel to install from.
guest "NIX_REMOTE= '$toplevel/sw/bin/nix-store' --load-db < /nix-path-registration"
guest "NIX_REMOTE= '$toplevel/sw/bin/nix-channel' --add https://nixos.org/channels/nixos-26.05 nixpkgs"

# proot runs the guest as the app's uid, which owns every unpacked file: the store directories
# must let their owner add paths, or nix inside the machine cannot install anything. The files
# themselves stay read-only, as the store intends.
sudo find "$root/nix/store" "$root/nix/var" -type d -exec chmod u+w {} +

# Seal the mounts away before packing, and leave a resolv.conf the terminal can use.
unmount_root
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' | sudo tee "$root/etc/resolv.conf" >/dev/null

nix-store -q --requisites "$toplevel" | sed 's|^/nix/store/[a-z0-9]\{32\}-||' | sort -u > "$out/$runtime_id.packages.txt"

# The markers the app gates on. DesktopCatalog.desktopInstalled wants usr/bin/kwin_wayland (the
# activation shim), the legacy marker and a pack json whose tag matches it; VmDesktop skips the
# apt fitting when its own marker is there. The desktop is baked, so the pack describes no
# downloadable archive: an empty url and checksum keep a reinstall from fetching the wrong thing.
desktop_id=$runtime_id-desktop
stamp=$work/stamp
mkdir -p "$stamp/usr/share/droiddeck"
printf '%s\n' "$desktop_id" > "$stamp/.droiddeck-pkg-desktop-kde"
printf 'installed %s\n' "$(date +%s%3N)" > "$stamp/usr/share/droiddeck/desktop-installed"
cat > "$stamp/usr/share/droiddeck/desktop-pack.json" <<JSON
{
  "tag": "$desktop_id",
  "url": "",
  "sha256": "",
  "size": 0
}
JSON

# DroidHost's VmUnpacker rejects members with a leading "./"; list top-level entries explicitly.
sudo tar -C "$root" --numeric-owner -cf "$work/runtime.tar" $(ls -A "$root")
sudo tar -C "$stamp" --numeric-owner --owner=0 --group=0 -rf "$work/runtime.tar" \
  usr/share/droiddeck/desktop-pack.json usr/share/droiddeck/desktop-installed .droiddeck-pkg-desktop-kde
sudo zstd -q -19 -T0 --rm "$work/runtime.tar" -o "$out/$runtime_id.tar.zst"
sudo chown "$(id -u):$(id -g)" "$out/$runtime_id.tar.zst"
sha256sum "$out/$runtime_id.tar.zst" | cut -d' ' -f1 > "$out/$runtime_id.sha256"
ls -l "$out"
