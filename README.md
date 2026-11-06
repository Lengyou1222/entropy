# 熵 / Entropy

Android 上的性能调度与悬浮控制台。给游戏和重负载应用做**频率上限、温控熔断、帧率记录、插帧调度**，
所有操作都在一个可以拖到屏幕任何位置的浮窗里完成；没有 root 也能装能用，能力按授权等级递降而不是直接不可用。

项目主页（下载、截图、版本说明）：<https://Lengyou1222.github.io/entropy>

---

## 它能做什么

**悬浮控制台**——左侧大面板装六节调节（CPU/GPU 上限、温度线、档位、帧率上限、插帧、调度策略），
右侧小面板紧贴屏幕右缘，装信息、我的、开关与 CPU 排行。小球形态可拖动、双击切档、点面板外收起。

**灵动岛**——固定在屏幕顶部、不可拖动的一小块常驻显示，显示项可多选，它同时是"安全显示"的载体。

**按应用调速**——每个应用一份帧率档案；开发模式页可直接写 `/sys` 频率上限，
调度器会 honor 显式给出的 CPU/GPU 上限，而不是自己做主。

**帧率记录**——记录页画帧率曲线，给最低值与最高值，可按单个应用归档。

**高帧率录屏**——`MediaProjection` 采集，帧率跟随屏幕当前真实刷新率（上限 240 FPS），码率可手动指定。

**防录屏**——给熵自己的浮窗、右侧小面板、灵动岛与音量键引导加 `FLAG_SECURE`。
录屏与截屏拿不到这几层的内容，合成结果里对应区域渲染成黑块。

**音量键唤出**——音量加键打开控制台、音量减键关闭（可观察行为，不拦截 `KeyEvent`，按键照常调音量）。

**多授权等级**——无授权 / ADB 授权 / Shizuku / root（含 Magisk 模块），
能力按等级递降而不是直接不可用；App 会如实说明当前处在哪一级、少了什么。

**十七套配色 + 明暗主题 + 可改高亮色**，樱吹雪是默认方案。

---

## 下载与安装

Releases 页直接下 `entropy-v<版本号>.apk`：
<https://github.com/Lengyou1222/entropy/releases/latest>

只有 arm 产物（`arm64-v8a` / `armeabi-v7a`）——原生守护进程是 Native 的，x86 手机上没有对应的调度节点。

安装后应用内检查一次悬浮窗权限即可使用；要摸 `/sys` 频率与刷新率，需要下面任一通道。

| 通道 | 怎么给 | 能拿到什么 |
| --- | --- | --- |
| 无授权 | 直接装 | 记录、录屏、浮窗、读硬件信息；写频率只读展示 |
| ADB | `adb install -g` 或 `adb shell appops grant com.entropy WRITE_SECURE_SETTINGS` | 刷新率与安全设置类写入 |
| Shizuku | 应用内点"请求授权" | 以 shell 身份执行，`WRITE_SECURE_SETTINGS` 那条权限不再必要 |
| root / Magisk | 应用内"一键运行 entropyd"，或刷 `magisk/` 模块 | 原生守护进程全量能力：eBPF 调度、极限探测、熔断 |

---

## 构建

```bash
bash scripts/build-daemon.sh        # 先编 native：产出 daemon/out/<abi>/
bash scripts/build-apk.sh           # 一条命令出 release + debug APK 到 dist/
```

`build-apk.sh` 会把源码同步到一个纯 ASCII 的影子目录（默认 `~/entropy-build/1.0.0`）再交给 Gradle。
原因不是讲究，是 AGP 在 Windows 上于 `apply plugin` 阶段直接拒绝非 ASCII 工程路径，
而 `android.overridePathCheck=true` 只关掉检查、NDK 与 aapt2 在 GBK 代码页下仍会把路径读成乱码。
改使用者自己的目录结构来迁就工具链不合理，所以复制一份出去编，源码目录保持原样。

改过 `daemon/` 下任何文件，**先删 `daemon/out/` 再编**——`build-apk.sh` 只搬运产物，
不会感知 native 源文件的变化，留着旧目录会得到一个"编过了但没带上改动"的 APK。

其它脚本：

```bash
bash scripts/build-ebpf.sh          # eBPF 调度程序
bash scripts/build-magisk.sh        # 打包 Magisk 模块
bash scripts/bump-version.sh minor  # 版本号 +0.01（见下）
```

要求：JDK 17、Android SDK 35、NDK（`build-daemon.sh` 用 clang 直接编，不依赖 CMake 路径）、Gradle 8.13（wrapper 已带）。

---

## 版本号方案

`versionName` 是三段式 `a.b.c`，递进规则：

| 更新类型 | 命令 | 变化 | 例 |
| --- | --- | --- | --- |
| 小更新 | `bump-version.sh minor` | `+0.01` | `1.0.9` 进位成 `1.1.0` |
| 中等更新 | `bump-version.sh medium` | `+0.1` | `1.9.x` 进位成 `2.0.0` |
| 大更新 | `bump-version.sh major` | `+1.0` | `1.x.x` 变 `2.0.0` |

`versionCode` 由版本名算出：`a*10000 + b*100 + c`（`1.1.0` → `10100`）。
这样两个数永远同源，不会出现"版本名涨了、versionCode 没涨，覆盖安装被系统拒"的错配。

脚本只改 `app/build.gradle.kts` 里 `versionCode` / `versionName` 两行，其余字节不动，
所以 `git diff` 里看到的就是这次改动本身。

---

## 应用内检查更新

每次冷启动查一次 GitHub `releases/latest`，发现新版本就弹窗：**下载更新**或**继续使用当前版本**。
选继续以后这一版不再重复打扰；改主意了去 设置（我的页右上角齿轮）→ 检查更新，那里能手动查、看上次结果、
下载与安装。

几个刻意的取舍：

- **不带任何凭据。** 公开仓库的 `releases/latest` 匿名可读，应用里没有 token、没有账号，也就没有可以泄的东西。
- **不轮询。** 匿名限流 60 次/小时每 IP，所以只有启动那一次和你在设置页手动点的那一次会发请求。
- **失败要说清楚。** 断网、被限流、仓库还没发过 Release、Release 里没有 APK 资产——这四种是不同的事，
  提示就写四种不同的话，不合并成一句"检查失败"。启动自动查的失败只在设置页记一笔，不弹窗拦路。
- **装不装由系统说了算。** 包下完落在应用私有目录，经 `FileProvider` 换成 `content://` 交给系统的安装确认页。
  没有"安装未知应用"放行时，熵把你送到那一页，回来再试一次——它不会替你点确认。

想指到别的仓库（fork、测试仓库）实测这条链路，不改源码：

```bash
bash scripts/build-apk.sh -Pentropy.updateRepo=your-name/your-repo
```

---

## 发布

```bash
bash scripts/publish-github.sh --dry-run   # 只打印要做的事，不碰远端
bash scripts/publish-github.sh             # 提交、推送，并给出 Release / Pages 的步骤
```

这个脚本是给仓库所有者自己跑的：推送、发 Release、改 Pages 都是对外可见且撤销代价高的动作，
凭据不该经过任何第三方。所以它只做能安全自动化的部分——提交前硬检查暂存区里没有 `*.keystore` /
`keystore.properties`（私库一旦进了 git 历史，删掉提交也拿不回来），以及在有 `gh` 登录态或
`GH_TOKEN` 时代你建 Release。没有这些凭据时它不想办法绕过，而是把网页上要点哪几步打印出来。

APK 不进 git（`dist/` 已忽略），它作为 Release 的资产挂上去——应用内下载读的就是那个资产。
项目主页在 `docs/`，Pages 指向 `/docs` 即可。

---

## 安全边界

这些是这个项目刻意保留的约束，不是待清理的技术债：

- **不改游戏文件。** UE4 的 `*.ini`、`config.lua` 一律不碰。
- **不注入游戏进程。** 腾讯 ACE / TpGuard 会把跨进程注入当成外挂信号，这条线一步都不越。
  插帧与调度走的是内核侧的调度接口，而不是往目标进程里塞代码。
- **不伪造温度。** 读到什么报什么。
- **95 ℃ 熔断上限在 App、Daemon、CLI 三处各钳制一次。** 三处独立，任何一处被绕过都还有下一道。
  上限数字在 `shared/protocol/entropy_ipc.h` 与 `Proto.FUSE_MAX_MC` 同源。
  读数口径是所有热区的最大值——真实机型上占住那个位置的通常是 SoC / GPU 结温，
  负载起来 60~80 ℃ 是常态，所以默认熔断线 85 ℃、软上限 70 ℃、过热提醒线 60 ℃；
  越过熔断线只做一件事：把调度全量还原成系统默认，不会去顶硬件保护。
- **守护进程的节点写入有白名单。** `on_node` 只接受 `/sys/` 与 `/proc/` 开头的路径、拒绝 `..`、值必须是整型；
  `peer_allowed` 的 UID 门保留——不是任何本地进程都能连上来写频率。
- **包名进 shell 前先过正则。** `^[A-Za-z0-9._:-]{1,128}$`，含 shell 元字符的一律拒绝。
- **不带 `android:appCategory="game"` 发布。** 那会让系统按游戏进程对待熵自己，语义是错的。

---

## 目录结构

```
app/        Compose 前端：七页导航 + 设置页 + 浮窗（控制台 / 灵动岛 / 音量键引导）+ 录屏 + 更新
daemon/     原生守护进程：调度、eBPF、极限探测、温控熔断、IPC 服务、CLI 工具
shared/     App 与 daemon 共用的 IPC 协议头
magisk/     Magisk 模块（service.sh + sepolicy 规则）
scripts/    构建、打包、版本号、发布
docs/       GitHub Pages 项目主页
```

## 许可与免责

软件按现状提供。它改的是 CPU/GPU 频率上限、调度参数与刷新率——持续顶高负载会加快电池损耗、
抬高机身温度、影响系统稳定性。熔断是保护措施，不是保证。
刷入或运行前请自行确认设备状况，出现问题的责任在使用者一侧。
