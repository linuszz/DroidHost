# DroidHost — fork notes / DroidHost 分叉说明

This document records what the DroidHost fork changed relative to upstream DroidDeck, what it
deliberately kept, and what the next rounds of work are.

本文档说明 DroidHost 相对上游 DroidDeck 做了什么改动、刻意保留了什么，以及后续几轮的工作计划。

---

## 1. Why this fork exists / 为什么做这个分叉

Upstream DroidDeck is a SteamOS client for Android: the userland it prepares is a Linux
runtime whose main payload is the Steam client. That is a great fit for games, but the runtime
machinery underneath (proot userland, Wayland compositor, native guest, Proton/Wine, FEX,
x86 emulation, desktop session) is much more general than "Steam".

DroidHost's goal is to expose that machinery as a **general Android host for guest operating
systems**: Linux, Windows, BSD and DOS. The fork exists so the rebrand, the package identity
and the later architecture work can happen without touching upstream's release line, and so
both apps can be installed on one device at the same time.

上游 DroidDeck 面向 Steam 游戏，其 Linux 运行时与 Steam 客户端深度耦合。底层机制本身要通用得多。
DroidHost 想把这套机制开放成通用宿主，目标是跑 Linux / Windows / BSD / DOS。分叉同时保证两个 App
可以在同一台设备上并存。

### Target operating systems

| Guest | Status |
| --- | --- |
| Linux | Inherited and working (userland, desktop, Steam client, flatpak/snap/appimage). |
| Windows | Inherited and working (Proton/Wine, WinNative, FEX for x86). |
| BSD | Next round: needs its own userland and syscall layer, not a Linux one. |
| DOS | Next round: needs a boot path and a front end over the compositor. |

**macOS is not a target.** Apple's EULA allows virtualising macOS only on Apple-branded
hardware, so a macOS guest on an Android phone cannot be shipped here.

**macOS 不在目标范围内**：Apple 的 EULA 只允许在 Apple 品牌硬件上虚拟化 macOS，安卓手机不具备该条件。

---

## 2. What the rename changed / 本轮重命名做了什么

### Application identity

| | Upstream | DroidHost |
| --- | --- | --- |
| Android package | `com.droiddeck.launcher` | `com.droidhost.launcher` |
| Namespace (`app/build.gradle`) | `com.droiddeck.launcher` | `com.droidhost.launcher` |
| Gradle project name | `DroidDeck` | `DroidHost` |
| Standard release variant (`tools/release/variants.txt`) | `com.droiddeck.launcher` | `com.droidhost.launcher` |
| Helper apps | `com.droiddeck.viturev2probe`, `com.droiddeck.viturev2check` | `com.droidhost.viturev2probe`, `com.droidhost.viturev2check` |

Because the package differs, **DroidHost installs beside upstream DroidDeck instead of over
it**. The two apps keep separate data directories, separate preferences and separate
permissions.

源目录同步迁移：`app/src/main/java/com/droidhost`、`app/src/test/java/com/droidhost`、
`tools/viture/viture-v2-{probe,driver-check}/src/com/droidhost`。

### Names that embed the package

These had to move with the package or the app would crash at runtime, so they were renamed
together with it:

- **JNI symbols** — `Java_com_droiddeck_launcher_*` → `Java_com_droidhost_launcher_*`, in the
  C/C++ compositor and the Rust store engines.
- **Vulkan layer names** — `VK_LAYER_DROIDDECK_gpu_load` → `VK_LAYER_DROIDHOST_gpu_load`,
  `VK_LAYER_DROIDDECK_vram_cap` → `VK_LAYER_DROIDHOST_vram_cap`,
  `VK_LAYER_DROIDDECK_xclipse` → `VK_LAYER_DROIDHOST_xclipse`,
  `VK_LAYER_DROIDDECK_vr` → `VK_LAYER_DROIDHOST_vr`. The layer manifest files that carry
  those names were renamed with them.
- **Manifest authorities and actions** — `com.droidhost.launcher.agent`, `.logs`, `.home`,
  `.agent`, `.devicecode`, `.pin`, `.documents`, `.typing`, `.vr_recovery`, `.dualscreen`,
  `.pairingguide`, and the `LAUNCH_GAME` / `XR` actions.
- **Abstract socket namespaces** — `com.droidhost.launcher.bwrap`, `com.droidhost.launcher.root`.
- **Type names** — `DroidDeckTheme`, `DroidDeckHudConfig`, `DroidDeckHudHost`,
  `DroidDeckHudView`, `DroidDeckHudSize`, `DroidDeckHudDock`, `DroidDeckHudHdr`,
  `DroidDeckHudStyle`, `DroidDeckHudPosition`, `DroidDeckMark`, `DroidDeckLogo`,
  `DroidDeckDecoder`, `DroidDeckLowLatencyDecoderFactory`.
- **C/C++ and shared-memory identifiers** — `DROIDDECK_*` macros → `DROIDHOST_*`,
  `DroidDeckXr*` structs → `DroidHostXr*`. The magic values and the shared-memory file names
  are unchanged, so the app and the guest still agree.
- **User-visible text** — every locale under `app/src/main/res/values-*/strings.xml`, plus
  the in-app messages, guest scripts and notifications.

### Build and CI variables

| Old | New |
| --- | --- |
| `DROIDDECK_BUILD_LABEL` | `DROIDHOST_BUILD_LABEL` |
| `DROIDDECK_BUILD_TREE_STATE` | `DROIDHOST_BUILD_TREE_STATE` |
| `DROIDDECK_SOURCE_COMMIT` | `DROIDHOST_SOURCE_COMMIT` |
| `DROIDDECK_PR_NUMBER` | `DROIDHOST_PR_NUMBER` |
| `DROIDDECK_PR_TITLE` | `DROIDHOST_PR_TITLE` |
| `DROIDDECK_UPDATE_CATALOG_BRANCH` | `DROIDHOST_UPDATE_CATALOG_BRANCH` |
| `DROIDDECK_CI_APP_ID` (repo variable) | `DROIDHOST_CI_APP_ID` |
| `DROIDDECK_CI_APP_KEY` (repo secret) | `DROIDHOST_CI_APP_KEY` |
| `DROIDDECK_PACKAGE` (tool env var) | `DROIDHOST_PACKAGE` |
| `droiddeck.python`, `droiddeck.vitureSdk`, `droiddeck.uvcDir`, `droiddeck.debugApplicationIdSuffix`, `droiddeck.allowStaleRuntime`, `droiddeck.skipRust` (`local.properties`) | `droidhost.*`, with the old keys still accepted as a fallback |

`DROIDHOST_PACKAGE` also falls back to `DROIDDECK_PACKAGE`, so existing scripts keep working.

### What was deliberately left alone / 刻意保留的部分

These names are not ours to change, or they are baked into an artifact that already exists on
devices:

| Kept | Why |
| --- | --- |
| `DroidDeck-Proton`, `Droid-Deck/DroidDeck-Proton` | An upstream build product with its own release line. |
| `Droid-Deck/DroidDeck`, `Droid-Deck/DroidDeck-CI`, `Droid-Deck/DroidDeck-Components` | Upstream repositories that host the runtime components, Proton builds and catalogues DroidHost still downloads. |
| `droiddeck-esync`, `droiddeck-fsync`, `droiddeck-ntsync`, `droiddeck-session`, `droiddeck-bwrap`, `droiddeck-root`, `droiddeck-clipboard`, `droiddeck-glprobe`, `droiddeck-vkbridge-smoke` | Guest-side executables installed by the prepared Linux runtime. They exist on devices already prepared by upstream, so renaming them breaks those sessions. |
| `~/.cache/droiddeck/`, `/root/.local/share/droiddeck/ctl/`, `/usr/share/droiddeck/` | Guest filesystem layout from the same prepared runtime. |
| `libdroiddeckstores.so`, `libdroiddeckwayland.so`, `droiddeck_viture`, `droiddeck_uvc` | Native library names, referenced from both the CMake targets and `System.loadLibrary`. |
| `tools/droiddeckctl` | The tool's file name and guest install path are fixed; only its default package and env var changed. |
| `docs/releases/*.md`, `docs/progress-log.md` | Historical record of upstream releases, which really were called DroidDeck. |
| `artwork/` | Upstream brand assets. DroidHost has no mark of its own yet. |
| `From: DroidDeck` in `tools/proot/patches` and `tools/gamescope/patches` | Patch authorship metadata. |

---

## 3. Known gaps / 已知缺口

- **No DroidHost release channel yet.** The in-app updater still reads the catalogue from
  `Droid-Deck/DroidDeck-CI`. The catalogue is keyed by Android package name, so no upstream
  entry matches `com.droidhost.launcher` and the app will not offer an update. That is the
  safe failure mode, but it means DroidHost cannot update itself until it publishes its own
  catalogue. This needs a `DroidHost-CI` repository and a matching `REPO`/`CI_REPO` pair in
  `AppUpdates.kt`.
- **The runtime components still come from upstream.** `DroidDeck-Components` hosts the Linux
  userland, the desktop KDE package, the Windows components, FEX and the Proton builds. DroidHost
  consumes them as-is for now.
- **CI needs its own credentials.** Set `DROIDHOST_CI_APP_ID` (repository variable) and
  `DROIDHOST_CI_APP_KEY` (repository secret) in the fork's settings before the workflows that
  publish artefacts can run.
- **The session model still assumes a Linux guest with Steam in it.** Making the guest OS a
  first-class choice — a session-mode abstraction over the runtime, the compositor and the
  launcher — is the main architectural work of the next round.
- **Branding.** No DroidHost logo, banner or social preview yet. The upstream artwork in
  `artwork/` still shows the DroidDeck wordmark and is not used on the front page.
- **Nothing has been compiled.** This round's changes were verified statically (package
  declarations against their directories, JNI symbols against their Kotlin declarations,
  Gradle/manifest/variant agreement), not by a build: the machine doing the rename had no JDK
  and no Android SDK.

---

## 4. Attribution and licence / 归属与许可

DroidDeck is licensed under the **GNU General Public License v3.0**. DroidHost is a derivative
work and is distributed under the same licence. Upstream's copyright notices, attribution and
licence texts are preserved in the sources, in `LICENSE` and in the credits section of
`README.md`. Anyone redistributing DroidHost must keep those notices.

DroidDeck is by the Droid-Deck project and its contributors. Upstream's repository is
`Droid-Deck/DroidDeck`; this fork keeps it as the `upstream` remote, so upstream's ongoing
fixes remain reachable:

```bash
git remote -v
# origin    https://github.com/linuszz/DroidHost.git
# upstream  https://github.com/Droid-Deck/DroidDeck.git
```

DroidDeck is GPL-3.0。DroidHost 作为衍生作品同样以 GPL-3.0 发布，上游的版权声明、归属说明与许可证
文本全部原样保留；分发时不得删除。上游仓库保留为 `upstream` remote，上游的修复随时可以拉取。
