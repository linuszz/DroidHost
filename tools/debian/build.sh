#!/bin/bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
debs=$(realpath "${1:?directory with the DroidHost-built .deb files}")
out=$(realpath -m "${2:?out dir}")
runtime_id=${3:?runtime id, e.g. debian-r1}
[ "$(uname -m)" = aarch64 ] || { echo 'the Debian runtime needs a native arm64 runner' >&2; exit 1; }
command -v debootstrap >/dev/null || { echo 'debootstrap and debian-archive-keyring are required' >&2; exit 1; }
source "$here/base.env"
work=$(mktemp -d)
root=$work/root
cleanup() {
  for m in dev/pts dev proc sys; do sudo umount "$root/$m" 2>/dev/null || true; done
  sudo rm -rf "$work"
}
trap cleanup EXIT
mkdir -p "$root" "$out"
sudo debootstrap --arch=arm64 --keyring=/usr/share/keyrings/debian-archive-keyring.gpg trixie "$root" "$DEBIAN_MIRROR"
sudo cp /etc/resolv.conf "$root/etc/resolv.conf"
for m in dev dev/pts proc sys; do sudo mount --bind "/$m" "$root/$m"; done
sudo mkdir -p "$root/tmp/droiddeck/debs"
sudo cp -r "$here/." "$root/tmp/droiddeck/src"
sudo cp "$debs"/*.deb "$root/tmp/droiddeck/debs/"
base_url=${DESKTOP_BASE_URL:-https://github.com/Droid-Deck/DroidDeck-Components/releases/download/$runtime_id}
desktop_id=$runtime_id-desktop
stage() {
  sudo chroot "$root" /bin/bash /tmp/droiddeck/src/setup.sh /tmp/droiddeck/debs "$runtime_id" "$1"
}
seal() {
  for m in dev/pts dev proc sys; do sudo umount "$root/$m"; done
  sudo rm -f "$root/etc/resolv.conf"
  printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' | sudo tee "$root/etc/resolv.conf" >/dev/null
}
unseal() {
  sudo cp /etc/resolv.conf "$root/etc/resolv.conf"
  for m in dev dev/pts proc sys; do sudo mount --bind "/$m" "$root/$m"; done
}

stage runtime
seal
sudo mv "$root/tmp/droiddeck" "$work/src-keep"
sudo chroot "$root" dpkg-query -W -f '${Package} ${Version}\n' > "$out/$runtime_id.packages.txt"
sudo tar -C "$root" --numeric-owner -cf "$work/runtime.tar" .
sudo python3 "$here/desktop-pack.py" manifest "$root" "$work/runtime.manifest"
unseal
sudo mv "$work/src-keep" "$root/tmp/droiddeck"
stage desktop
seal
sudo rm -rf "$root/tmp/droiddeck"
sudo chroot "$root" dpkg-query -W -f '${Package} ${Version}\n' > "$out/$desktop_id.packages.txt"
sudo python3 "$here/desktop-pack.py" pack "$root" "$work/runtime.manifest" "$work/runtime.tar" "$work/desktop.tar" "$work/meta"
sudo zstd -q -19 -T0 --rm "$work/desktop.tar" -o "$out/$desktop_id.tar.zst"
sudo chown "$(id -u):$(id -g)" "$out/$desktop_id.tar.zst"
sha256sum "$out/$desktop_id.tar.zst" | cut -d' ' -f1 > "$out/$desktop_id.sha256"

mkdir -p "$work/stamp"
python3 "$here/desktop-pack.py" describe "$out/$desktop_id.tar.zst" "$base_url/$desktop_id.tar.zst" "$desktop_id" \
  "$work/stamp/usr/share/droiddeck/desktop-pack.json"
sudo tar -C "$work/stamp" --numeric-owner --owner=0 --group=0 -rf "$work/runtime.tar" ./usr/share/droiddeck/desktop-pack.json
sudo zstd -q -19 -T0 --rm "$work/runtime.tar" -o "$out/$runtime_id.tar.zst"
sudo chown "$(id -u):$(id -g)" "$out/$runtime_id.tar.zst"
sha256sum "$out/$runtime_id.tar.zst" | cut -d' ' -f1 > "$out/$runtime_id.sha256"
ls -l "$out"
