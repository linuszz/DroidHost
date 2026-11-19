# Flatpak 宿主选择（可切换运行时）＋「内置应用」入口

状态：已实施（核心层 870a5addb；设置选择器＋「内置应用」区＋FlatpakHost 单测随本次提交）。架构背景见 [flatpak.md](flatpak.md)。

## 目标与已定决策

- **A**：设置 → 概览 增加「Flatpak 运行时」选择器，允许把 flatpak 的宿主 rootfs 从「共享运行时」切到「某个兼容的 VM」。
- **B**：新建虚拟机页增加「内置应用」区，放在「内置发行版」上方，Flatpak 作为其中一个条目。
- 李纳斯已拍板：
  1. 默认宿主＝**自建 Ubuntu 26.04 共享运行时**，不重新引进上游 Arch rootfs。
  2. 「flatpak 应用装进所选宿主 rootfs、切换宿主后另一处的应用看不到（数据仍在原处）」的模型 **接受**；选择栏里每个宿主除资格筛选外，还要**明确标出是否已装过 Flatpak**，便于甄别。
  3. **等 Arch 落地再动代码**，本文档为先行技术设计。

## 现状（实证锚点）

- flatpak 硬绑共享运行时：`FlatpakManager` 所有操作走 `LinuxRuntime.rootDir(context)`（`runtime/FlatpakManager.kt:25/48/62`），无宿主选择。
- 「在 VM 自带 rootfs 里跑命令」的管道**已存在**：`GuestCommand.run` 长重载带 `root: File?`，`root=null` 才回退共享（`runtime/GuestCommand.kt:27-29,45`）。
- 宿主 rootfs 取法可复用：`VmRuntime.rootFor(context, machineId)`＝有自己 rootfs 用自己的，否则共享（`vm/VmRuntime.kt:42-43`）。
- **flatpak shim 由 app 运行时注入任何 rootfs**：`SessionFiles.stage` 把 `droiddeck-bwrap / droiddeck-flatpak / droiddeck-flatpak-setup / droiddeck-flatpak-run` 从 assets 铺进它启动的 rootfs（`session/SessionFiles.kt:158-161`）。共享或 VM 一视同仁。
- flatpak 二进制：Ubuntu/Debian 镜像 apt 预装（`tools/ubuntu/packages-runtime.txt`、`tools/debian/packages-runtime.txt`）＋installed 标记（`tools/ubuntu/setup.sh:142`、`tools/debian/setup.sh:160`）。
- 设置-概览已有「运行时」分组（`ui/FrontEndSetup.kt:397`，tab 0）。
- 新建虚拟机选择器 `VmSourceDialog`（`ui/VmImportDialog.kt:58`）：现「内置发行版」(`vm_source_preset`，:86)＋「导入」两段，按 `VmFamily` 列发行版。
- fork **没有**「Linux apps (beta)」门控：`setup_linux_apps` 串在所有 strings.xml 存在但 Kotlin 零引用（上游遗留死串）；`DesktopPage` 商店 hero 按钮无条件显示。门控因此改为「有合格宿主才显示」。
- 发行版引擎：Ubuntu/Debian/Arch 均 `engine=true`（Arch 已 flip，36e6a10e3，`vm/Vm.kt:36`）。

## 核心模型：`FlatpakHost`

新增 fork 层对象 `runtime/FlatpakHost.kt`：

- 偏好：`host`＝`null`（共享运行时）或某个 `machineId`。独立 SharedPreferences（如 `"flatpak"`），不与 `VmStore` 耦合。
- 解析：`rootfs(context) = VmRuntime.rootFor(context, hostMachineId(context))`。
- **默认 `null` → 与现状逐字节一致**：不选就不变，老用户零感知。
- 回退：所选 machine 被删 / 无自己 rootfs → 回退共享运行时，并清掉偏好。
- 对外：`hostMachineId(context)` / `setHost(context, machineId?)` / `rootfs(context)`。

## `FlatpakManager` 参数化

- 把内部 `LinuxRuntime.rootDir(context)` 的引用统一换成 `FlatpakHost.rootfs(context)`。**公开 context-based API 签名不变**，消费方零改动自动跟随当前宿主。
- `runGuest` 改走 `GuestCommand.run` 长重载，`root=FlatpakHost.rootfs(context)`（管道已存在）。
- 为选择器增加 **rootfs 显式探针**（不改现有签名）：`readyIn(root)` / `installedAppsIn(root)` / `appCountIn(root)`，供 UI 逐个候选宿主探测状态。

## 消费方波及面

- **自动跟随**（内部解析，无需改）：`StoreState.refresh`（`store/StoreState.kt:53-54`）、`UserApps`（`runtime/UserApps.kt:96/418/426`）、`StorageState`（`storage/StorageState.kt:246/252`）、`Library`。
- **需显式对齐**：
  - **启动路径**：`FrontEndActivity.onFlatpakApp`（`FrontEndActivity.kt:1365-1371`）及 `onUserApp` 的 flatpak 类，起 MODE_RUN 会话时补 `EXTRA_MACHINE = FlatpakHost.hostMachineId(context)`。`SessionActivity:724` 已把 `EXTRA_MACHINE` 读进 `SessionState.machineId`（MODE_RUN 也读），`:2377` 传入会话。**待实测**：MODE_RUN 在 VM rootfs 下 rootfs 选择跟随 machineId，且 flatpak env（`FLATPAK_BWRAP`、bwrap socket）同样铺设。
  - **存储扫描**：`StorageScan.kt:264-266` 扫描的 rootfs 应为 flatpak 宿主。
- 宿主切换后调 `StoreState.refresh(context)` 重载商店状态。

## 宿主资格＋「已装 Flatpak」指示（决策 2）

shim 既由 app 注入任何 rootfs，VM 宿主的 flatpak 能力只取决于**镜像是否带 flatpak 二进制**。

- **资格筛选**（可被选为宿主）：
  - 共享运行时：已安装即可选（默认）。
  - VM：`VmStore.all` 已装 ＋ `VmRuntime.ownRootfs != null` ＋ rootfs 内 `usr/bin/flatpak` 存在。
  - Arch 现 `engine=true`，但 flatpak 走 pacman（与 Ubuntu/Debian 的 apt 不同）尚未验证；二进制探针自然排除无 `usr/bin/flatpak` 的宿主，无需特判。
- **每宿主状态探针**（对 root 显式）：
  - `present(root)`＝`usr/bin/flatpak` 存在。
  - `ready(root)`＝现 `ready()` 等价：二进制 ＋ `var/cache/droiddeck-flatpak/installed` ＋ flathub remote。
  - `appCount(root)`＝`installedApps(root).size`。
- **指示文案**（选择栏每行一个徽标）：
  - `appCount>0` → 「已装 N 个应用」
  - `ready` 且无应用 → 「已初始化」
  - `present` 未 `ready` → 「未初始化」
  - 无二进制 → 「不支持」（不可选）
- 每行＝宿主名（「共享运行时 · Ubuntu 26.04」/「Ubuntu 26.04 · VM」/「Debian 13 · VM」）＋上述 Flatpak 状态徽标。

## A：设置-概览选择器

- 位置：`FrontEndSetup.kt` tab 0 概览，「运行时」分组（:397）附近新增「Flatpak 运行时」`SettingsGroup`。
- 列出合格宿主（含共享运行时），单选；选中写 `FlatpakHost.setHost`；每行带 Flatpak 状态徽标。
- 门控：有合格宿主才显示（`candidates` 非空）；fork 无 beta 开关（见「现状」）。
- 切换后 `StoreState.refresh(context)`。

## B：新建虚拟机「内置应用」区

- 位置：`VmSourceDialog`（`VmImportDialog.kt:58`），在 `vm_source_preset` 头（:86）**上方**插入「内置应用」头＋hint＋Flatpak 行。
- Flatpak 行：显示当前宿主＋Flatpak 状态；点击 → 进 flatpak 商店（`StorePage`，用当前宿主）；宿主未初始化则先走 `StoreState.setup`。
- 门控：`FlatpakHost.anyEligible(ctx)`（共享运行时已装，或某 VM rootfs 带 flatpak 二进制）。
- `DesktopPage.kt:247` 的 hero 商店按钮**保留**为快捷入口；主发现路径改为「新建虚拟机 → 内置应用 → Flatpak」。

## 默认 / 迁移 / 回退

- 默认共享运行时，零行为变化。
- 所选 VM 被删 → 回退共享＋清偏好。
- **不迁移**已装 flatpak 应用：数据留在原 rootfs，切换宿主＝换一套（符合已确认模型）。

## 测试（CI-only，本机无 SDK）

- `FlatpakHost`：默认共享 / 选 VM / 无 rootfs 回退 / 删除回退 / 资格筛选（`FlatpakHostTest`，已落地）。
- `FlatpakManager`：rootfs 参数化后 `ready`/`installedApps` 对各 root 的结果。
- 资格筛选＋状态指示逻辑。
- UI：选择器渲染宿主＋状态；新建页「内置应用」区位于「内置发行版」上方。
- 启动路径 `EXTRA_MACHINE` 对齐（intent extra 可单测）。

## Arch 协调＋落地顺序（已完成）

- Arch r1 flip 已落地（36e6a10e3）：`Vm.kt` builtins ARCH ＋ chevron 守卫（`VmImportDialog.kt:118`）。「内置应用」区与 chevron 守卫同文件共存，无冲突。
- 实施顺序（已执行）：`FlatpakHost` ＋ `FlatpakManager` 参数化（含探针）＋启动路径对齐（核心层 870a5addb）→ 设置选择器＋新建「内置应用」区＋strings＋`FlatpakHostTest`（本次提交）→ CI。

## 风险 / 待验证

- MODE_RUN 在 VM rootfs 的 flatpak env（bwrap socket / `FLATPAK_BWRAP`）铺设需设备实测。
- Debian VM 的 flatpak 兼容需设备实证（构建与 Ubuntu 同模式，预期可用）。
- Arch flatpak（pacman）待其 release ＋ 单独验。
- flatpak Firefox 的沙箱症结在 **app 侧 bwrap 层**（结构性），换宿主 rootfs 不保证修复（见前轮结论）。
