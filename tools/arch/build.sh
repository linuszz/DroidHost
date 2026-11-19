#!/bin/bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$(realpath -m "${1:?out dir}")
runtime_id=${2:?runtime id, e.g. droidhost-arch-arm64-r1}
source "$here/base.env"
source "$here/../desktop-kde/mesa.env"
fetch() { curl -fsSL --proto '=https' --proto-redir '=https' --retry 6 --retry-delay 5 --retry-all-errors -o "$1" "$2"; }
work=$(mktemp -d); root=$work/root
trap 'sudo rm -rf "$work"' EXIT
mkdir -p "$root" "$out" "$work/db" "$work/pkgs"

base=$work/${ALARM_BASE_URL##*/}
fetch "$base" "$ALARM_BASE_URL"
fetch "$work/base.sig" "$ALARM_BASE_URL.sig"
fetch "$work/base.md5" "$ALARM_BASE_URL.md5"
gpgv --status-fd 1 --keyring "$here/../desktop-kde/archlinuxarm-builder.gpg" "$work/base.sig" "$base" 2>/dev/null \
  | grep -Eq "^\[GNUPG:\] VALIDSIG .* $ALARM_BUILDER_KEY\$"
(cd "$work" && md5sum -c base.md5)
echo "$ALARM_BASE_SHA256  $base" | sha256sum -c - >/dev/null
sudo tar -xzf "$base" -C "$root" --numeric-owner

# The desktop closure and its pacman database must describe this exact rootfs, so the
# refreshed package list travels with the base artifact instead of the committed snapshot.
python3 - "$root" "$out/runtime-packages.refreshed.txt" <<'EOF'
import os, sys
root, out = sys.argv[1:3]
rows = []
local = os.path.join(root, "var/lib/pacman/local")
for entry in sorted(os.listdir(local)):
    desc = os.path.join(local, entry, "desc")
    if not os.path.isfile(desc):
        continue
    fields, key = {}, None
    for line in open(desc, encoding="utf-8", errors="replace"):
        line = line.rstrip("\n")
        if line.startswith("%") and line.endswith("%"):
            key = line.strip("%"); fields[key] = []
        elif line == "":
            key = None
        elif key:
            fields[key].append(line)
    name = fields.get("NAME", [None])[0]
    version = fields.get("VERSION", [None])[0]
    if name and version:
        rows.append((name, version))
with open(out, "w") as f:
    f.write("".join("%s %s\n" % r for r in sorted(rows)))
print("runtime: %d pacman records" % len(rows))
EOF

for repo in core extra alarm; do
  fetch "$work/db/$repo.db" "$ALARM_MIRROR/$repo/$repo.db"
  mkdir -p "$work/db/x_$repo" && tar -xzf "$work/db/$repo.db" -C "$work/db/x_$repo"
done
python3 "$here/../desktop-kde/closure.py" "$work/db" "$out/runtime-packages.refreshed.txt" "$here/gpu-seeds.txt" > "$work/gpu.txt"
echo "gpu: $(wc -l < "$work/gpu.txt") packages beyond the base tarball"
while read -r entry sum; do
  file=${entry#*/}
  url=$ALARM_MIRROR/$entry
  [ "$file" != "$DESKTOP_MESA_PACKAGE" ] || url=$DESKTOP_MESA_URL
  fetch "$work/pkgs/$file" "$url"
  fetch "$work/pkg.sig" "$url.sig"
  if ! gpgv --status-fd 1 --keyring "$here/../desktop-kde/archlinuxarm-builder.gpg" "$work/pkg.sig" "$work/pkgs/$file" 2>/dev/null \
      | grep -Eq "^\[GNUPG:\] VALIDSIG .* $ALARM_BUILDER_KEY\$"; then
    echo "$file: no valid Arch Linux ARM Build System signature" >&2; exit 1
  fi
  if [ "$(sha256sum "$work/pkgs/$file" | cut -d' ' -f1)" != "$sum" ]; then
    echo "$file: sha256 differs from the repository database's" >&2; exit 1
  fi
  # The desktop pack's pacman-db.py records these at install; the base carries the files only.
  sudo tar -xf "$work/pkgs/$file" -C "$root" --numeric-owner \
    --exclude=.PKGINFO --exclude=.MTREE --exclude=.INSTALL --exclude=.BUILDINFO --exclude=.CHANGELOG
done < "$work/gpu.txt"

sudo cp -a "$here/../linuxfs/overlay/." "$root/"
sudo chown -R 0:0 "$root/usr/local" "$root/usr/bin"
sudo rm -f "$root/etc/resolv.conf"
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' | sudo tee "$root/etc/resolv.conf" >/dev/null

{
  cat "$out/runtime-packages.refreshed.txt"
  awk '{print $1}' "$work/gpu.txt" | sed -E 's#^[a-z]+/##; s/-(aarch64|any)\.pkg\.tar\.(xz|zst)$//; s/-([^-]*)-([^-]*)$/ \1-\2/'
} > "$out/$runtime_id.packages.txt"

# DroidHost's VmUnpacker rejects members with a leading "./"; list top-level entries explicitly.
sudo tar -C "$root" --numeric-owner -cf "$out/$runtime_id.tar" $(ls -A "$root")
sudo chown "$(id -u):$(id -g)" "$out/$runtime_id.tar" "$out/$runtime_id.packages.txt" "$out/runtime-packages.refreshed.txt"
ls -l "$out"
