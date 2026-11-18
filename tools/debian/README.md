# The Linux runtime (Debian)

DroidHost's Linux runtime: Debian 13 (trixie) for arm64, with KDE's Plasma desktop as a separate download, set up as
SteamOS's desktop mode (Valve's Vapor theme, the Steam Deck wallpaper, "Return to Gaming Mode" and
Steam on the desktop). The app installs it into `files/linuxfs` and runs it under its patched proot.

- `build-debs.sh` (run in a `debian:trixie` container on an arm64 runner) rebuilds two Debian source
  packages: Mesa with `-Dfreedreno-kmds=msm,kgsl`, so Turnip and freedreno reach Android's
  `/dev/kgsl-3d0`, and KWin with `patches/kwin` (nested desktop scale, an explicit render node,
  scroll source/discrete forwarding). Both get a `+droiddeck` version. `patches/mesa` adds KGSL
  UBWC 5/6 (backported from upstream Mesa), the KGSL device numbers for WSI (from
  [Droid-Deck/Drivers](https://github.com/Droid-Deck/Drivers)) and the Zink image-view rebind fix
  ported to Mesa 25.0. Two of the Ubuntu runtime's patches are not needed here: trixie's Mesa
  25.0.7 still gates zs-view creation on the depth/stencil aspect (Ubuntu's 0005 fixes a later
  lazy-view regression), and its KWin 6.3.6 already keeps only the main device's tranches
  (Ubuntu's 0004).
- The runtime's built-in Vulkan driver is DroidHost's DD-Turnip Linux build (`DD_TURNIP_URL` and
  `DD_TURNIP_SHA256` in `base.env`): Debian's Turnip is diverted to `libvulkan_freedreno.so.distrib`
  because it lacks the Adreno 8xx fixes. Zink, EGL and llvmpipe stay Debian's.
- `build.sh` bootstraps the base system with `debootstrap trixie`, verified against the
  `debian-archive-keyring` package (`DEBIAN_MIRROR` in `base.env` selects the mirror), chroots into
  it and runs `setup.sh` twice. The `runtime` stage is everything gaming mode needs; it is packed
  as `<tag>.tar.zst`. The `desktop` stage then adds Plasma on top, and `desktop-pack.py` packs only
  what that stage added or changed as `<tag>-desktop.tar.zst`, so the desktop shares every runtime
  file instead of shipping its own copy. The runtime carries
  `usr/share/droiddeck/desktop-pack.json` (tag, URL, hash, size) naming its matching desktop pack.
- `setup.sh runtime` installs `packages-runtime.txt` without recommends and the `+droiddeck` debs
  from a local repository in `/var/lib/droiddeck/apt` (pinned at 1001 and held), then builds the
  `droiddeck-runtime` package from `overlay-runtime/`. `setup.sh desktop` installs
  `packages-desktop.txt`, Firefox from Mozilla's apt repository (`mozilla.asc`, fingerprint
  35BAA0B33E9EB396F59CA838C0BA5CE6DC6315A3) and builds `droiddeck-desktop` from `overlay-desktop/`.
  Both packages are Protected, depend on `protected-*.txt`, own our files and divert any Debian file
  they replace, so apt neither removes the essentials nor overwrites our settings on upgrade.
- The desktop pack's `usr/share/droiddeck/desktop/files.tsv.gz` lists each path the desktop added
  (N), changed (C) or removed (R), and `originals/` keeps the runtime's copies of changed and removed
  files. Removing the desktop restores those and deletes the added files the user has not changed;
  a runtime update replaces the tree, so when the desktop was installed the update installs the new
  runtime's desktop pack right after it.

At run time:

- `sudo`, `su`, `pkexec` and apt/aptitude (for commands that change the system) go through
  `droiddeck-root`: the app runs them in a separate proot with `-0 --link2symlink`, passing the
  caller's terminal, so dpkg gets root ownership emulation and hard links while the desktop keeps
  the fast path. PackageKit (Discover) and polkit are started the same way by the session's system
  bus (`/usr/share/droiddeck/system-services`).
- `apt/remember` (a `DPkg::Post-Invoke` hook) keeps the packages, sources and keys the user added in
  `~/.local/share/droiddeck/apt`; `apt/restore` (autostarted) puts them back after a runtime update.
- The installer turns the image's hard links into proot's link2symlink layout, and proot shows the
  linked name as `/proc/self/exe` (patch 0020), so Debian's multi-call coreutils work.

Differences from the Ubuntu runtime (`tools/ubuntu`):

- The base comes from `debootstrap` instead of a signed ubuntu-base tarball. debootstrap only
  leaves the plain trixie suite in `/etc/apt/sources.list`, so `setup.sh` rewrites it with trixie,
  trixie-updates and trixie-security (main contrib non-free non-free-firmware), keeping the
  bootstrap mirror; the shipped image carries deb lines only.
- Package names follow trixie: `libdisplay-info2`, `libglycin-1-0`, `libsdl2-2.0-0`,
  `libwlroots-0.18`, `libxml2`, `debian-archive-keyring`; `gnu-coreutils` and
  `libc-gconv-modules-extra` do not exist (coreutils and libc6 cover them). The desktop drops the
  Ubuntu-only packages (kubuntu-settings-desktop, kubuntu-wallpapers, language-selector-common,
  plasma-discover-notifier, plasma-keyboard, plasma-session-wayland, policykit-desktop-privileges,
  ppa-purge, software-properties-qt, alsa-base, fwupd-signed, phonon4settings,
  python3-launchpadlib) and adds `desktop-base`, `maliit-keyboard` and `pipewire-pulse`. Plasma's
  Wayland session ships inside `plasma-workspace` itself on trixie.
- KWin is 6.3 (integer buffer scale), so the nested-scale patch is written against that model
  instead of 6.8's fractional one.

`.github/workflows/build-debian-runtime.yml` builds it and, dispatched with a new revision (`rN`),
publishes the runtime and its desktop pack in linuszz/DroidHost-Components. The release tag is composed
as `<app>-debian<DEBIAN_VERSION>-<revision>` (e.g. `droidhost-debian13-r1`): the app name is
`rootProject.name` from settings.gradle lower-cased, and the Debian major version is `DEBIAN_VERSION` in
base.env, so a future Debian 14 only needs that pin bumped. A `linuxfs-debian.json` catalog entry there
(to be added) will name the release the app installs.
