# DroidHost 虚拟机：从发行版运行时到真实 VM

## 现状与目标

今天的「桌面」不是虚拟机。App 装的是 DroidDeck 预置的 **Ubuntu 26.04 运行时**
（`tools/ubuntu/`，tag `ubuntu-r1-rc5`），加一个 Plasma 桌面包（`ubuntu-r1-rc5-desktop`）。
用户看到的「Ubuntu / 就绪」其实是一台**宿主预置的、固定 rootfs 的容器**，
发行版由我们打包分发，用户无法换成自己的系统。

目标：让用户能装上**自己下载的完整 Linux 发行版**，App 负责把它跑起来并把驱动配好。

## 关键约束（决定了方案形态）

| 约束 | 事实 | 影响 |
|---|---|---|
| 内核来自宿主 | proot 不提供内核，guest 跑在 Android 内核上 | 发行版镜像只能是 rootfs，不能是带内核的完整磁盘镜像；不能做真机启动 |
| 单一 rootfs | `LinuxRuntime.rootDir()` 返回 `files/linuxfs` | 多发行版 = 多 rootfs 目录，启动时按 VM 选择 |
| 驱动要自建 | Adreno 8xx 需要 `freedreno-kmds=kgsl` 的 Mesa + DD-Turnip | 上游发行版自带的 Mesa 用不了，必须注入我们编译的驱动 |
| 无 root | App 以普通 uid 运行，靠 `droiddeck-root`（proot `-0`）模拟 | 发行版内的 apt/dnf 只能通过这条通道工作 |
| 包管理各异 | apt / dnf / apk / pacman 各不相同 | 驱动注入必须分发行版准备，不能一套通吃 |

## 总体架构

```
                    ┌─────────────────────────────┐
   用户选择发行版 ──▶│  VmCatalog  (发行版清单)      │
   或导入镜像       │  预置 Ubuntu / Debian / ...  │
                    └──────────────┬──────────────┘
                                   │ 安装任务
                                   ▼
    ┌──────────────────────────────────────────────────┐
    │  1. 取镜像    预置 → 从 DroidDeck-Components 下   │
    │               导入 → 用户本地文件 (ISO/tar/…)     │
    ├──────────────────────────────────────────────────┤
    │  2. 探测      发行版 / 版本 / 包管理器 / 架构      │
    ├──────────────────────────────────────────────────┤
    │  3. 落盘      files/vms/<id>/rootfs/  (独立 rootfs)│
    ├──────────────────────────────────────────────────┤
    │  4. 装驱动    L1 预编译驱动包 → L2 配置注入      │
    ├──────────────────────────────────────────────────┤
    │  5. 装桌面    可选，复用 desktop-kde 打包管线     │
    └──────────────────────────────────────────────────┘
                                   │ 启动
                                   ▼
                    LinuxRuntime.rootDir(vmId) → proot 启动
```

## 一、发行版清单：预置 + 用户导入

两条来源共用同一套后续流程（探测 → 落盘 → 驱动 → 桌面）。

### 预置发行版

沿用现有的组件仓库模式：`DroidDeck-Components` 发布 `linuxfs-<distro>-rN.tar.zst`，
`linuxfs-<distro>.json` 描述清单（沿用 `LinuxRuntimeInstaller.fetchRelease` 的形状）。
预置包天然携带我们编译好的驱动，省掉探测与适配，所以体验最好。

预置清单首版只放 **Ubuntu**（已有 `tools/ubuntu/` 全套管线，零新增成本），
Debian / Fedora / Alpine 随适配进度追加。

### 用户导入

用户自己下载的发行版，以文件形式交给 App。支持的形态：

| 形态 | 说明 | 处理 |
|---|---|---|
| rootfs tarball | `.tar.gz` / `.tar.zst` / `.tar.xz`，含完整 `/usr`、`/etc`、`/bin` | 直接解包，最快 |
| 云镜像 | Ubuntu/Debian 的 cloud image（`.img`） | 解出 rootfs 分区 |
| ISO | 发行版安装镜像 | 挂载/解出 live 文件系统；需要额外工具链 |

导入入口用系统文件选择器（SAF），App 不要求外部存储权限。

**导入限制必须对用户讲清楚**（文案层面）：导入的是「发行版的用户态」，
内核仍由 Android 提供，所以内核模块、systemd 完整初始化、实时内核特性都不可用。
DroidHost 用自己的 session 脚本接管启动（与现在的 `droiddeck-session` 同构）。

## 二、驱动安装：两级策略

「自动化驱动安装」在任意发行版上都不可能一步到位，因为驱动必须匹配发行版的
包管理器与 ABI。方案分两级，自动降级：

### L1 —— 预编译驱动包（首选）

我们为每个已适配发行版编译驱动 `.deb` / `.rpm` / `.apk`：

- **Mesa**：`freedreno-kmds=msm,kgsl`（现有 `tools/ubuntu/build-debs.sh:16` 的做法），
  让 Turnip/freedreno 能访问 Android 的 `/dev/kgsl-3d0`
- **DD-Turnip**：替换发行版自带的 `libvulkan_freedreno.so`（Adreno 8xx 需要它）
- **Zink**：KWin 6.8 移除了 QPainter，软件合成路径不可用，只能走 OpenGL→Zink

安装方式与现有 `setup.sh` 一致：包落到发行版的包管理器，走 diversion 保护
被替换的发行版文件，卸载时可还原。

L1 失败的条件：包管理器不匹配 / 依赖冲突 / glibc 版本过旧 → 落到 L2。

### L2 —— 配置注入（兜底）

不碰发行版的包系统，只做「配置 + 文件旁挂」：

1. 注入环境变量（复用 `SessionService.kt:932-937` 已有通道）：
   `MESA_LOADER_DRIVER_OVERRIDE=zink`、`GALLIUM_DRIVER=zink`、
   `KWIN_RENDER_NODES=<guest render node>`、`LIBGL_KOPPER_DRI2`
2. 旁挂 DRI 节点描述（`LinuxRuntime.bindGpuNode` 已在做，生成假的
   `/dev/dri/renderD0` 与 sysfs 条目）
3. 若发行版自带可用的 Mesa（自带 kgsl 支持或软件渲染可用），直接用

L2 的代价：没有硬件加速时只能软件渲染（llvmpipe），桌面会慢但**能跑**。

### 探测矩阵

安装时先探测发行版，决定走哪一级：

```
检测 /etc/os-release → ID / ID_LIKE / VERSION_ID
检测包管理器        → apt / dnf / apk / pacman / 无
检测架构            → aarch64（必须，其他架构直接拒绝）
检测现有 GPU 栈    → 已有 libvulkan_freedreno.so? 已有 Mesa 版本?
匹配已知组合        → 是：L1 驱动包；否：L2 配置注入
```

探测结果要在 UI 上**明说**，例如「Debian 13：未适配，将以软件渲染运行（性能较低）」，
而不是让用户安装完才发现。

## 三、存储布局与多 VM

现在只有一个 rootfs（`LinuxRuntime.DIR = "linuxfs"`）。要支持多发行版，得改成：

```
files/
  linuxfs/                 ← 现在的默认运行时，保持不变（兼容已有用户）
  vms/
    <vmId>/
      rootfs/              ← 该 VM 的独立 rootfs
      meta.json            ← 发行版、来源、驱动等级、创建时间
```

`LinuxRuntime.rootDir(context)` 需要增加一个「当前 VM」的概念：
启动会话时按 `SessionState` 里的 `vmId` 选 rootfs。

`VmStore` 现在只存一台机器（`Vm.kt:41-63`），要扩成多台；
`VmMachine` 加 `vmId`、`source`（预置/导入）、`driverTier` 等字段。

**注意**：VM 数量上限建议先保持 1（现有 UI 文案已如此说明 `vm_one_only`），
先把「换发行版」做实，多台并行留到后面 —— proot 每台都要一份 rootfs，
设备存储是硬约束。

## 四、界面体验

桌面 tab 现在是「一张卡 + 启动/删除」。真实 VM 需要一个向导。

### 结构

桌面 tab 下新增一个 **「新建虚拟机」** 入口，分两条路径：

```
桌面 tab
├── 我的虚拟机
│   ├── Ubuntu 26.04  [就绪]      ← 现有卡片，升级为可显示驱动状态
│   └── …
└── ＋ 新建虚拟机
    ├── 从预置发行版安装   → 发行版列表 → 确认 → 安装
    └── 导入自己的发行版   → 选文件 → 探测结果 → 确认 → 安装
```

### 新建向导（导入路径）

| 步骤 | 内容 | 用户要做的决定 |
|---|---|---|
| 1 选择来源 | 两张卡片：预置 / 导入 | 来源 |
| 2 选文件 | 系统文件选择器 | 挑镜像文件 |
| 3 探测结果 | 发行版、版本、架构、包管理器、**将采用的驱动等级** | 看得懂的结论 |
| 4 桌面与资源 | 装哪个桌面（Plasma / LXQt / 仅终端）、磁盘上限 | 资源 |
| 5 安装 | 进度：下载/解包 → 探测 → 驱动 → 桌面 → 完成 | — |

### 状态机

现有的 `VmStatus`（`ui/VmHome.kt:38`）需要扩展，把驱动状态纳入：

```
ABSENT → NEW → INSTALLING → READY → RUNNING
                     └→ DEGRADED   （L2 软件渲染，桌面仍可用）
```

`DEGRADED` 是新增态，卡片上要有明确标识，不能和 `READY` 混为一谈。

### 卡片信息

每张 VM 卡片应显示：发行版名 + 版本、驱动等级徽标（GPU / 软件）、磁盘占用、
上次启动时间。删除确认文案要说清「删的是这台机器的 rootfs，用户数据一并消失」——
这与现在「删除桌面环境、系统镜像保留」是**两种不同的删除语义**，必须区分。

## 五、分阶段落地

| 阶段 | 内容 | 风险 |
|---|---|---|
| P1 | 多 rootfs 布局 + `rootDir(vmId)` + `VmStore` 多实例 | 中：影响面广（`rootDir` 在多处被调用） |
| P2 | 导入向导 + rootfs tarball 导入 + 探测 | 中 |
| P3 | L1 驱动包（Ubuntu 已有，扩展到 Debian） | 中：依赖 arm64 CI |
| P4 | L2 配置注入兜底 | 低 |
| P5 | 桌面包按 VM 独立安装/卸载（复用 `desktop-pack.py` 清单机制） | 中 |

P1 是所有后续的前提，且不改变用户可见行为，可以独立验证。

## 六、开放问题

1. **ISO 导入**要不要做？rootfs tarball 已经能覆盖「用户自行下载完整发行版」的诉求，
   ISO 需要挂载/解包工具链，成本明显更高。
2. **磁盘上限**是否要做？导入的发行版可能自带 10GB 镜像，实际占用远大于此。
3. **Windows / BSD / DOS** 在这个架构下都需要各自的根文件系统与驱动路线，
   本轮只做 Linux 家族。
