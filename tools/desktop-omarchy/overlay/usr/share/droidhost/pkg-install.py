#!/usr/bin/env python3
# DroidHost proot-native pacman -S/-U installer. The proot guest user owns the rootfs, so packages
# can be installed by extracting .pkg.tar.zst directly; real pacman's getuid()==0 gate and the
# droiddeck-root LD_PRELOAD (which breaks script exec under sudo) are both bypassed. Resolves deps
# from the sync dbs, downloads from the Arch ARM mirror, extracts with tar, and records local db.
import os
import re
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

SYNC = "/var/lib/pacman/sync"
LOCAL = "/var/lib/pacman/local"
ARCH = "aarch64"
MIRROR = "http://mirror.archlinuxarm.org"


def vercmp_part(a, b):
    sa = re.findall(r"\d+|[a-zA-Z]+", a)
    sb = re.findall(r"\d+|[a-zA-Z]+", b)
    for x, y in zip(sa, sb):
        if x.isdigit() and y.isdigit():
            if int(x) != int(y):
                return 1 if int(x) > int(y) else -1
        elif x.isdigit():
            return 1
        elif y.isdigit():
            return -1
        elif x != y:
            return 1 if x > y else -1
    return 0 if len(sa) == len(sb) else (1 if len(sa) > len(sb) else -1)


def vercmp(a, b):
    def split(v):
        epoch, _, rest = v.rpartition(":") if ":" in v else ("0", "", v)
        ver, _, rel = rest.partition("-")
        return int(epoch or 0), ver, rel
    ea, va, ra = split(a)
    eb, vb, rb = split(b)
    if ea != eb:
        return 1 if ea > eb else -1
    c = vercmp_part(va, vb)
    if c or not ra or not rb:
        return c
    return vercmp_part(ra, rb)


def satisfies(version, op, want):
    if not op:
        return True
    c = vercmp(version, want)
    return {"=": c == 0, ">=": c >= 0, "<=": c <= 0, ">": c > 0, "<": c < 0}[op]


def parse_dep(d):
    m = re.match(r"^([^<>=]+)(>=|<=|=|>|<)?(.*)$", d)
    return m.group(1), m.group(2), m.group(3)


def read_fields(text):
    fields, key = {}, None
    for line in text.split("\n"):
        if line.startswith("%") and line.endswith("%"):
            key = line[1:-1]
            fields[key] = []
        elif line and key:
            fields[key].append(line)
    return fields


def load_sync():
    pkgs, providers, groups = {}, {}, {}
    for repo in ("core", "extra", "alarm"):
        path = os.path.join(SYNC, repo + ".db")
        if not os.path.exists(path):
            continue
        with tarfile.open(path, "r:*") as tf:
            for m in tf.getmembers():
                if not m.isfile() or not m.name.endswith("/desc"):
                    continue
                f = read_fields(tf.extractfile(m).read().decode("utf-8", "replace"))
                name = f.get("NAME", [""])[0]
                if not name:
                    continue
                pkgs[name] = {
                    "repo": repo,
                    "version": f.get("VERSION", [""])[0],
                    "filename": f.get("FILENAME", [""])[0],
                    "depends": f.get("DEPENDS", []),
                    "provides": f.get("PROVIDES", []),
                }
                for p in f.get("PROVIDES", []):
                    providers.setdefault(parse_dep(p)[0], []).append(name)
                for g in f.get("GROUPS", []):
                    groups.setdefault(g, []).append(name)
    return pkgs, providers, groups


def load_local():
    local = {}
    if not os.path.isdir(LOCAL):
        return local
    for entry in os.listdir(LOCAL):
        desc = os.path.join(LOCAL, entry, "desc")
        if os.path.isfile(desc):
            f = read_fields(open(desc, encoding="utf-8", errors="replace").read())
            name = f.get("NAME", [""])[0]
            if name:
                local[name] = f.get("VERSION", [""])[0]
    return local


def resolve(targets, pkgs, providers, groups, local):
    expanded = []
    for t in targets:
        if t in groups:
            expanded.extend(groups[t])
        else:
            expanded.append(t)
    want, seen = expanded, set()
    order = []
    while want:
        dep = want.pop(0)
        name, op, ver = parse_dep(dep)
        if name in local or name in seen:
            continue
        cand = name if name in pkgs else (providers.get(name) or [None])[0]
        if not cand:
            print("warning: unresolved dependency %s" % dep, file=sys.stderr)
            continue
        seen.add(cand)
        order.append(cand)
        want.extend(pkgs[cand]["depends"])
    return order


def fix_hardlinks(tarpath):
    # proot forbids hard links (EPERM), so tar drops them -- gcc/cc/automake etc.
    # ship their main binary as a hard link. Recreate those as symlinks to the
    # real file tar already extracted.
    with tarfile.open(tarpath, "r:*") as tf:
        for m in tf.getmembers():
            if not m.islnk():
                continue
            dest = "/" + m.name.lstrip("./")
            target = "/" + m.linkname.lstrip("./")
            if os.path.lexists(dest):
                continue
            try:
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                os.symlink(target, dest)
            except OSError:
                pass


def extract_pkg(tarpath):
    subprocess.run(["tar", "-xf", tarpath, "-C", "/", "--no-same-owner",
                    "--exclude", ".INSTALL", "--exclude", ".MTREE",
                    "--exclude", ".BUILDINFO", "--exclude", ".PKGINFO"],
                   check=False)
    fix_hardlinks(tarpath)


def record_local(name, version):
    d = os.path.join(LOCAL, "%s-%s" % (name, version))
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "desc"), "w") as f:
        f.write("%%NAME%%\n%s\n\n%%VERSION%%\n%s\n\n%%ARCH%%\n%s\n\n" % (name, version, ARCH))


def install_pkg(name, info):
    url = "%s/%s/%s/%s" % (MIRROR, ARCH, info["repo"], info["filename"])
    tmp = tempfile.mktemp(suffix=".pkg.tar.zst")
    print(":: downloading %s" % name)
    urllib.request.urlretrieve(url, tmp)
    extract_pkg(tmp)
    os.remove(tmp)
    record_local(name, info["version"])
    print(":: installed %s %s" % (name, info["version"]))


def install_local(path):
    name = ver = None
    with tarfile.open(path, "r:*") as tf:
        for m in tf.getmembers():
            if m.name in (".PKGINFO", "./.PKGINFO"):
                for line in tf.extractfile(m).read().decode("utf-8", "replace").split("\n"):
                    if "=" in line:
                        k, _, v = line.partition("=")
                        k = k.strip()
                        if k == "pkgname":
                            name = v.strip()
                        elif k == "pkgver":
                            ver = v.strip()
                break
    if not name or not ver:
        print("warning: no .PKGINFO in %s" % path, file=sys.stderr)
        return False
    print(":: installing local %s" % name)
    extract_pkg(path)
    record_local(name, ver)
    print(":: installed %s %s" % (name, ver))
    return True


def refresh_sync():
    # The bundled sync dbs age out against the rolling mirror (stale FILENAME ->
    # 404). Pull current dbs so resolved filenames match what the mirror hosts.
    os.makedirs(SYNC, exist_ok=True)
    for repo in ("core", "extra", "alarm"):
        url = "%s/%s/%s/%s.db" % (MIRROR, ARCH, repo, repo)
        dst = os.path.join(SYNC, repo + ".db")
        try:
            urllib.request.urlretrieve(url, dst)
        except Exception as e:
            print("warning: could not refresh %s.db: %s" % (repo, e), file=sys.stderr)


ARG_OPTS = {"--config", "--root", "--dbpath", "--cachedir", "--gpgdir",
            "--hookdir", "--logfile", "--arch", "--print-format"}


def parse_args(argv):
    targets, upgrade = [], False
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--":
            targets.extend(argv[i + 1:])
            break
        if a in ARG_OPTS:
            i += 2
            continue
        if a.startswith("--"):
            i += 1
            continue
        if a.startswith("-") and len(a) > 1:
            letters = a[1:]
            if "U" in letters:
                upgrade = True
            if letters[-1] in "br":
                i += 2
                continue
            i += 1
            continue
        targets.append(a)
        i += 1
    return targets, upgrade


def main():
    targets, upgrade = parse_args(sys.argv[1:])
    if upgrade or any(os.path.isfile(t) for t in targets):
        n = 0
        synced = False
        for t in targets:
            if os.path.isfile(t):
                if install_local(t):
                    n += 1
                continue
            if not synced:
                refresh_sync()
                synced = True
            pkgs, providers, groups = load_sync()
            local = load_local()
            for name in resolve([t], pkgs, providers, groups, local):
                install_pkg(name, pkgs[name])
                n += 1
        print(":: proot install complete (%d packages)" % n)
        return
    refresh_sync()
    pkgs, providers, groups = load_sync()
    local = load_local()
    order = resolve(targets, pkgs, providers, groups, local)
    # Explicit targets are always (re)extracted, like pacman -S reinstalling an
    # up-to-date package; this also repairs hard-link binaries on a re-run.
    for t in targets:
        nm = parse_dep(t)[0]
        cand = nm if nm in pkgs else (providers.get(nm) or [None])[0]
        if cand and cand not in order:
            order.append(cand)
    for name in order:
        install_pkg(name, pkgs[name])
    print(":: proot install complete (%d packages)" % len(order))


if __name__ == "__main__":
    main()
