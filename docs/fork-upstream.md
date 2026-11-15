# 与上游 DroidDeck 的关系

DroidHost 是 [Droid-Deck/DroidDeck](https://github.com/Droid-Deck/DroidDeck) 的 fork。
上游持续开发，本 fork 在其之上做裁剪与扩展。**任何时候都要能拉取上游、复用上游代码**，
所以改动落在哪里，是这个仓库的一条硬规矩。

## 远程与分支

```
origin    https://github.com/linuszz/DroidHost.git     ← 本 fork，推送到这里
upstream  https://github.com/Droid-Deck/DroidDeck.git   ← 上游，只拉取
```

`main` 跟踪 `upstream/main`：一次 `git pull` 拉的是上游。

```bash
git fetch upstream
git log --oneline upstream/main..main     # 本 fork 领先上游的提交
git log --oneline main..upstream/main     # 上游领先本 fork、我们还没拿到的提交
```

**推送**：本 fork 的 `main` 跟踪上游，但推送必须走 `origin`。

```bash
git push origin main
```

若 `git config branch.main.pushRemote` 未设置，`git push` 会推向上游仓库。
上游仓库的写入权限本 fork 没有，推送会被拒绝，但这属于误操作风险，
建议显式设置一次：

```bash
git config branch.main.pushRemote origin
```

## 分叉的形状

改名提交（`com.droiddeck.launcher` → `com.droidhost.launcher`）触及 1881 个文件，
那是合并成本的主要来源；它已经发生，改动的是包名出现的地方，
上游若改到同一行才会冲突，否则三方合并能自动处理。

真正的 fork 层是**新增文件**，上游没有它们，因此永远不会冲突：

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/droidhost/launcher/vm/Vm.kt` | 发行版清单与机器模型 |
| `app/src/main/java/com/droidhost/launcher/vm/VmRuntime.kt` | 每台机器自己的 rootfs 布局 |
| `app/src/main/java/com/droidhost/launcher/vm/VmProbe.kt` | 读 rootfs 得出发行版/架构/包管理器 |
| `app/src/main/java/com/droidhost/launcher/vm/VmUnpacker.kt` | rootfs 压缩包解包（含路径穿越防护） |
| `app/src/main/java/com/droidhost/launcher/vm/VmImport.kt` | 导入流程：解包 → 探测 → 确认 → 登记 |
| `app/src/main/java/com/droidhost/launcher/vm/VmStage.kt` | 导入阶段的名字（vm 层不写文案） |
| `app/src/main/java/com/droidhost/launcher/ui/VmHome.kt` | 桌面页的机器界面 |
| `app/src/main/java/com/droidhost/launcher/ui/VmImportDialog.kt` | 新建机器与导入向导 |
| `app/src/main/java/com/droidhost/launcher/App.kt` | 应用入口 |
| `app/src/test/java/com/droidhost/launcher/ui/VmHomeTest.kt` | 机器界面测试 |
| `app/src/test/java/com/droidhost/launcher/vm/VmRuntimeTest.kt` | rootfs 布局与机器存储测试 |
| `app/src/test/java/com/droidhost/launcher/vm/VmProbeTest.kt` | 探测测试 |
| `app/src/test/java/com/droidhost/launcher/vm/VmUnpackerTest.kt` | 解包测试 |

```bash
git diff --diff-filter=A --name-only upstream/main..main   # 新增：fork 层
git diff --diff-filter=M --name-only upstream/main..main   # 修改：合并成本
```

## 改动的规矩

1. **新功能先落进 fork 层。** 新增文件优先，放进 `com.droidhost.launcher.vm` 这类
   fork 独有的包里。上游没有这些文件，合并时不可能冲突。
2. **改上游文件时只做增量。** 加新方法、加新重载，不改既有方法的签名与语义。
   例如 `LinuxRuntime.rootDir(context)` 保持原样，要按机器选 rootfs 就用新增的
   `rootDir(context, machineId)` 重载。
3. **既有方法一律委托给新重载。** 这样上游的调用点一行都不用改，
   合并时上游新增的调用点自动走新的实现。
4. **改动自包含。** 一行只做一件事（`LinuxRuntime.rootDir(this)` → `sessionRootfs()`），
   这样上游改同一文件的别处时，三方合并不会把两处改动搅在一起。
5. **命名分层。** 本 fork 自己产出的东西叫 DroidHost；指向上游或第三方、
   运行时里的名字、设备上 fork 未创建的文件，保持原名
   （`droiddeck-desktop`、`kwin_wayland_wrapper`、`.droiddeck-pkg-*`）。

## 拉上游的流程

```bash
git fetch upstream
git log --oneline upstream/main..main      # 先看自己领先多少
git merge upstream/main                    # 上游来了，合进来
```

合并后必做：

```bash
# 定向单测（整包 testDebugUnitTest 会跑满一小时并拖垮 WSL）
./gradlew :app:testDebugUnitTest --tests 'com.droidhost.launcher.ui.*' --console=plain

# 构建并装机验证
bash tools/build_local.sh
```

冲突集中在改名触及的文件上。若冲突文件里有本 fork 的功能改动，
按上面的规矩把改动挪进 fork 层，让上游那部分保持干净。

## 本 fork 改过的上游文件（合并时重点看）

这些是改名之外，fork 真正动过的地方：

- `runtime/LinuxRuntime.java` —— GPU 节点与 KGSL（`bindGpuNode`、`guestRenderNode`、
  `kgslOnly`），以及机器 rootfs 的重载
- `session/SessionService.kt` —— 桌面包的图形环境与 `sessionRootfs()`
- `ui/NavTabs.kt` —— 去掉库 tab
- `ui/DesktopPage.kt` —— 桌面页改为机器管理页
- `ui/FrontEndScreen.kt`、`ui/FrontEndSetup.kt`、`ui/LibraryPage.kt` —— 裁剪商店与库页
- `core/AppUiPrefs.kt` —— 首页默认为桌面页
- `app/src/main/cpp/waylandcomp/src/wl_dmabuf.c` —— 反馈真实的渲染节点
- `tools/linuxfs/desktop/droiddeck-desktop` —— 桌面启动脚本
- `tools/ubuntu/build-debs.sh` —— Mesa 的 KGSL 支持
