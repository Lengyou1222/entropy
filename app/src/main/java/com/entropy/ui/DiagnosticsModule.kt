// File: app/src/main/java/com/entropy/ui/DiagnosticsModule.kt
//
// "我的"页里的诊断模块：使用情况 / 处理器与指令集 / 兼容性检查 / 常见问题。
//
// 四条规矩，和 SystemRepository 一样：
//  1) 每个数字都是当场读出来的（Build、sysfs、PackageManager、Daemon 心跳），
//     读不到就写"读取失败"，不用 0 或空串冒充"没有"；
//  2) 判定文案不承诺任何没实现的行为 —— 例如"按前台应用自动切档"要使用情况权限，
//     没授予就如实说没授予，不说"稍后生效"；
//  3) 故障排查里提到的按钮名、路径、权限名都要在代码或 adb 里真对得上，
//     说不出处的就不写；
//  4) ABI 与投放判据复用 HardwareBridge.chosenNativeAbi()，不在 UI 里重抄一遍 ——
//     重抄的那份会在下次改构建配置时变成假信息。
package com.entropy.ui

import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.entropy.data.CapabilityKey
import com.entropy.data.EntropyStore
import com.entropy.data.SystemInfo
import com.entropy.data.UiState
import com.entropy.hardware.DeviceProfile
import com.entropy.hardware.HardwareBridge
import com.entropy.hardware.SocFamily
import com.entropy.hardware.ShellRunner
import com.entropy.shared.Proto
import com.entropy.ui.components.CollapsibleHeader
import com.entropy.ui.components.agoText
import com.entropy.ui.components.EntropyMotion
import com.entropy.ui.components.KvRow
import com.entropy.ui.components.NoticeBar
import com.entropy.ui.theme.LocalEntropyColors
import java.io.File

@Composable
fun DiagnosticsModule(store: EntropyStore, vm: SystemViewModel) {
    // 建立"偏好被改过"的读依赖，理由同 PanelConsole：prefs 存的是普通值，不是快照状态
    store.prefsTick.intValue
    val ctx = LocalContext.current
    val state by store.state.collectAsState()
    val s by vm.state.collectAsState()
    val entropy = LocalEntropyColors.current
    val scheme = MaterialTheme.colorScheme
    val prof = store.bridge.profile

    var usageOpen by rememberSaveable { mutableStateOf(true) }
    var cpuOpen by rememberSaveable { mutableStateOf(true) }
    var compatOpen by rememberSaveable { mutableStateOf(false) }
    var faqOpen by rememberSaveable { mutableStateOf(false) }
    var openFaq by rememberSaveable { mutableIntStateOf(-1) }

    val usage = usageRows(store, state, s.info)
    val cpu = cpuRows(ctx, store.bridge, prof)
    val checks = compatChecks(ctx, store.bridge, prof, s.info, state.path,
        s.capabilities.associate { it.key to it.granted })
    val missing = checks.count { it.ok == false && it.required }

    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("诊断与兼容", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(
            "下面四组的数字全部来自本机读取与 Daemon 心跳，读不到的项一律标「读取失败」。" +
                "报障时把「兼容性检查」和「处理器与指令集」两组截图带上，通常一眼就能定位。",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )

        CollapsibleHeader("使用情况", usageOpen, usage.size) { usageOpen = !usageOpen }
        Group(usageOpen) { Column(Modifier.fillMaxWidth()) { usage.forEach { KvRow(it.k, it.v, it.warn) } } }

        CollapsibleHeader("处理器与指令集", cpuOpen, cpu.size) { cpuOpen = !cpuOpen }
        Group(cpuOpen) { Column(Modifier.fillMaxWidth()) { cpu.forEach { KvRow(it.k, it.v, it.warn) } } }

        CollapsibleHeader("兼容性检查", compatOpen, checks.size) { compatOpen = !compatOpen }
        Group(compatOpen) {
            Column(Modifier.fillMaxWidth()) {
                checks.forEach { CheckRow(it) }
                if (missing > 0) {
                    Spacer(Modifier.height(6.dp))
                    NoticeBar("有 $missing 项必需条件未满足，功能会按各行写的范围降级运行，不会崩。",
                        entropy.tempWarm)
                }
            }
        }

        CollapsibleHeader("常见问题与解决方法", faqOpen, Faqs.size) { faqOpen = !faqOpen }
        Group(faqOpen) {
            Column(Modifier.fillMaxWidth()) {
                Faqs.forEachIndexed { i, f ->
                    FaqEntry(f, openFaq == i) { openFaq = if (openFaq == i) -1 else i }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * 折叠组的展开动画。
 * 用 expandVertically 而不是直接 if 判断：这一组收起时下面还跟着三组长列表，
 * 硬切会让整页内容瞬间跳位，看起来像 App 卡了一下。
 */
@Composable
private fun Group(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = EntropyMotion.fadeEnter() + expandVertically(
            tween(EntropyMotion.NORMAL, easing = EntropyMotion.Ease)),
        exit = EntropyMotion.fadeExit() + shrinkVertically(tween(EntropyMotion.FAST))
    ) { content() }
}

/** 一行读数。warn=true 表示这一项处于"降级/异常"状态，值要加粗提示 */
private data class Reading(val k: String, val v: String, val warn: Boolean = false)

private fun usageRows(store: EntropyStore, state: UiState, info: SystemInfo?): List<Reading> {
    val prefs = store.prefs
    val hb = state.heartbeat
    return buildList {
        add(Reading("执行路径", when (state.path) {
            HardwareBridge.Path.DAEMON -> "Daemon（独立熔断线程在跑）"
            HardwareBridge.Path.ROOT_SHELL -> "Root 直写（无熔断线程，上限压在 85%）"
            HardwareBridge.Path.READONLY -> "只读（只能看，改不了任何节点）"
        }, warn = state.path != HardwareBridge.Path.DAEMON))
        add(Reading("当前档位", when (state.profile) {
            Proto.PROFILE_GAME -> "游戏极速"
            Proto.PROFILE_POWERSAVE -> "极致省电"
            else -> "日常均衡"
        }))
        add(Reading("悬浮控制台",
            "${store.overlayForm.value.label} · 小球 ${"%.2fx".format(store.ballScale.value)}"))
        add(Reading("主题 / 警示色",
            "${store.themeMode.value.label} · %08X".format(prefs.accentColor)))
        add(Reading("帧率上限",
            if (prefs.fpsLimit == 0) "跟随系统" else "${prefs.fpsLimit} FPS"))
        add(Reading("触控采样率", "${prefs.touchHz} Hz"))
        add(Reading("渲染后端", rendererLabel(prefs.renderer)))
        add(Reading("分辨率缩放",
            listOf("原生", "3/4", "2/3", "1/2").getOrElse(prefs.resPreset) { "原生" }))
        add(Reading("画质", "锐度 ${prefs.sharpness} 档 · 清晰度 ${prefs.clarity} 档"))
        add(Reading("温控策略",
            "软上限 %.1f℃ · 熔断线 %.1f℃".format(prefs.softTempMc / 1000f, prefs.fuseTempMc / 1000f)))
        add(Reading("后台冻结",
            "${state.frozenApps} 个 · 累计省 %.1fs CPU".format(state.savedCpuMs / 1000f)))
        add(Reading("entropyd", when {
            state.path != HardwareBridge.Path.DAEMON -> "未运行"
            hb != null -> "已运行 ${fmtUptime(hb.uptimeS)} · ${daemonStateLabel(hb.state)}"
            else -> "在跑，本端还没收到心跳"
        }, warn = state.path != HardwareBridge.Path.DAEMON))
        add(Reading("entropyd CPU",
            hb?.let { "%.2f%%".format(it.cpuPermille / 10.0) } ?: "无心跳",
            // 150‰ 与 Daemon 自检预算是同一个数（safety_fuse.h 的 ENT_SELF_CPU_BUDGET_PERMILLE）
            warn = (hb?.cpuPermille ?: 0) > 150))
        add(Reading("最近一次重下档", hb?.let { agoText(it.lastReapplyAgoS) } ?: "—"))
        add(Reading("本应用进程",
            info?.let { "UID ${it.uid} · PID ${it.pid}" } ?: "读取中"))
        add(Reading("最近一次回执", state.lastMessage.ifEmpty { "—" }))
    }
}

private fun cpuRows(ctx: Context, bridge: HardwareBridge, prof: DeviceProfile): List<Reading> {
    val chosen = bridge.chosenNativeAbi()
    val supported = Build.SUPPORTED_ABIS.toList()
    // 官方用来判断"这台机器靠不靠翻译层跑 native"的属性；空或 "0" 才是原生执行
    val nb = SocFamily.prop("ro.dalvik.vm.native.bridge")
    val translated = nb.isNotEmpty() && nb != "0"
    val app = runCatching { ctx.applicationInfo }.getOrNull()
    // ApplicationInfo.primaryCpuAbi 从 Android 9 起进了隐藏 API 名单，SDK 35 编译不过。
    // 这里能公开拿到的只有解压出来的 lib 目录，本项目的二进制不打进 lib/，
    // 所以"目录里没有 .so"才是正确答案，不是读取失败 —— 别为了凑一行 ABI 去反射隐藏字段。
    val libDir = app?.nativeLibraryDir.orEmpty()
    val packagedSos = runCatching { libDir.let { File(it).list()?.sorted() } }.getOrNull()
    return buildList {
        add(Reading("SoC 家族", prof.family.label))
        add(Reading("SoC 型号", prof.socModel.ifEmpty { "读取失败" }))
        add(Reading("CPU 簇 / 核心",
            "${prof.cpuClusters.size} 簇 · ${Runtime.getRuntime().availableProcessors()} 核"))
        prof.cpuClusters.forEachIndexed { i, c ->
            add(Reading("簇 $i ${c.name}",
                "核 ${c.cores.first}~${c.cores.last} · ${prof.clusterRange(c)} · ${c.availableKhz.size} 档 OPP"))
        }
        add(Reading("本机指令集", supported.joinToString(" · ")))
        add(Reading("64 位", Build.SUPPORTED_64_BIT_ABIS.joinToString(" · ").ifEmpty { "无" }))
        add(Reading("32 位", Build.SUPPORTED_32_BIT_ABIS.joinToString(" · ")
            .ifEmpty { "无 —— 纯 64 位系统很常见，不影响本应用" }))
        add(Reading("APK 里的 native 库",
            // 目录不存在与目录为空是同一件事：这个 APK 没往 lib/ 放东西
            if (packagedSos.isNullOrEmpty()) "无 —— 二进制走 assets/native/<abi>/ 投放，不落 lib/"
            else packagedSos.joinToString(" · ")))
        add(Reading("本项目提供的 native", bridge.nativeAbis.joinToString(" · ")))
        add(Reading("将投放给 entropyd 的", chosen + when {
            supported.contains(chosen) -> "（本机可解析）"
            else -> "（本机不支持，一键运行会在投放阶段失败）"
        }, warn = !supported.contains(chosen)))
        add(Reading("native 执行方式",
            if (translated) "转译运行（$nb）" else "原生执行"))
        add(Reading("转译的代价", when {
            translated -> "Daemon 自测 CPU 约为原生机的 5 倍，自检预算已按 15% 放宽"
            else -> "不适用（本机原生执行）"
        }))
    }
}

/**
 * 一项兼容性条件。
 * ok=null 是"只报事实、没有通过与否"的信息行（cgroup 版本这类），
 * 硬套 true/false 会显示成一个永远绿的假"通过"。
 */
private data class CompatCheck(
    val name: String,
    val verdict: String,
    val ok: Boolean?,
    val required: Boolean,
    val hint: String,
)

private fun compatChecks(
    ctx: Context,
    bridge: HardwareBridge,
    prof: DeviceProfile,
    info: SystemInfo?,
    path: HardwareBridge.Path,
    caps: Map<CapabilityKey, Boolean>,
): List<CompatCheck> {
    val minSdk = runCatching { ctx.applicationInfo.minSdkVersion }.getOrDefault(24)
    val hasArm = Build.SUPPORTED_ABIS.any { it in bridge.nativeAbis }
    return listOf(
        CompatCheck("系统版本",
            info?.let { "Android ${it.android}（API ${it.sdk}）" } ?: "读取中",
            ok = info?.let { it.sdk >= minSdk }, required = true,
            hint = "本包要求 API $minSdk 以上。低于此值在安装阶段就会被拒，装不上而不是装了不能用。"),
        CompatCheck("悬浮窗权限",
            verdictOf(caps[CapabilityKey.OVERLAY]),
            ok = caps[CapabilityKey.OVERLAY], required = true,
            hint = "没有它开不了控制台。去「权限」页点「显示在其他应用上层」授予。"),
        // init() 里先 detect() 拿到 su，再 ensureDaemon() 拉起进程，中间有几秒
        // path 还停在 READONLY。只看 path 会把"su 已授予、正在拉起"报成"未授权"，
        // 和同屏那行 Shell 身份 ROOT 自相矛盾 —— 报障时这种自相矛盾最误导人。
        CompatCheck("Root / entropyd", when {
            path == HardwareBridge.Path.DAEMON -> "已授权 · Daemon 通道"
            path == HardwareBridge.Path.ROOT_SHELL -> "已授权 · 直写通道"
            bridge.shell.mode == ShellRunner.Mode.ROOT -> "已授权 · 正在初始化"
            else -> "未授权"
        },
            ok = path != HardwareBridge.Path.READONLY ||
                bridge.shell.mode == ShellRunner.Mode.ROOT,
            required = true,
            hint = "只读模式下只剩看硬件信息。也可用 ADB 拉起 entropyd，功能与 Root 等价。"),
        CompatCheck("ARM 指令集",
            Build.SUPPORTED_ABIS.joinToString(" · "),
            ok = hasArm, required = true,
            hint = "native 只编了 arm64-v8a / armeabi-v7a。两者都没有的机器（纯 RISC-V、早期 x86）跑不了 Daemon。"),
        CompatCheck("热区可读", "${prof.thermalZones.size} 个",
            ok = prof.thermalZones.isNotEmpty(), required = true,
            hint = "温控与熔断都以热区读数为判据。一个都读不到时熔断会拒绝抬频，这是设计如此。"),
        CompatCheck("写入安全设置", verdictOf(caps[CapabilityKey.SECURE_SETTINGS]),
            ok = caps[CapabilityKey.SECURE_SETTINGS], required = false,
            hint = "只影响刷新率模式。签名级权限，应用内没有申请通道，只能 adb pm grant；" +
                "但这条不是唯一路 —— Shizuku 授权之后，那条 settings 写入由远端 shell 身份执行，" +
                "本应用自己有没有这项权限都不影响结果。"),
        CompatCheck("使用情况访问", verdictOf(caps[CapabilityKey.USAGE_STATS]),
            ok = caps[CapabilityKey.USAGE_STATS], required = false,
            hint = "只影响识别当前前台应用。没有它档位要手动切。"),
        CompatCheck("cgroup 版本", if (prof.hasCgroupV2) "v2" else "v1",
            ok = null, required = false,
            hint = "后台真·暂停的实现路径。v1 也能冻结，只是走另一套接口。"),
        CompatCheck("eBPF / BTF", if (prof.bpfSupported) "有 vmlinux BTF" else "无，退回 schedstat 采样",
            ok = null, required = false,
            hint = "只影响调度信号源的精度，不影响能不能用。"),
        CompatCheck("GPU 频率节点", "${prof.gpuNodes.size} 个可用",
            ok = prof.gpuNodes.isNotEmpty(), required = false,
            hint = "一个都没有时 GPU 频率只能显示不能调 —— 各家节点命名互不兼容。"),
        CompatCheck("Shell 身份", bridge.shell.mode.name,
            ok = null, required = false,
            hint = "ROOT=拿到了 su；ADB_SHELL=以普通 adb shell 身份运行；" +
                "SHIZUKU=本应用进程仍是普通 uid，但命令交给 Shizuku 的远端进程执行，" +
                "那边的身份取决于 Shizuku 怎么被拉起（adb 起是 shell 2000，root 起是 0）；" +
                "NONE=还没探测过。报障时这一行最有用。"),
    )
}

private fun verdictOf(granted: Boolean?): String = when (granted) {
    true -> "已授予"
    false -> "未授予"
    null -> "未读取"
}

@Composable
private fun CheckRow(c: CompatCheck) {
    val scheme = MaterialTheme.colorScheme
    val entropy = LocalEntropyColors.current
    val dot = when (c.ok) {
        true -> entropy.tempCool
        null -> scheme.onSurfaceVariant
        false -> if (c.required) scheme.error else entropy.tempWarm
    }
    val tag = when {
        c.ok == true -> "通过"
        c.ok == null -> "信息"
        c.required -> "缺"
        else -> "可选"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 7.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
            // 三段都必须带权重：原先只有名字有 weight，结论/判据裸着，
            // 在横屏那种"高度很富、宽度很紧"的窗口里，一行长结论会把整行顶出屏幕，
            // 看起来就像文字被切掉半截。fill=false 让短的那侧不白白占位。
            Text(c.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                color = scheme.onSurface, modifier = Modifier.weight(1f, fill = false),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(c.verdict, style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface, modifier = Modifier.weight(1.4f, fill = false),
                textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(tag, style = MaterialTheme.typography.labelSmall, color = dot,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(32.dp), textAlign = TextAlign.Center)
        }
        Text(c.hint, style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 21.dp, top = 2.dp))
    }
}

@Composable
private fun FaqEntry(f: Faq, expanded: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.q, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                color = scheme.onSurface, modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant)
        }
        // 展开必须是动画，不能是 if 直接插入：这一条下面还压着五条问答，
        // 硬插会把它们瞬间推下去，读起来像页面自己抖了一下
        AnimatedVisibility(
            visible = expanded,
            enter = EntropyMotion.fadeEnter() + expandVertically(
                tween(EntropyMotion.NORMAL, easing = EntropyMotion.Ease)),
            exit = EntropyMotion.fadeExit() + shrinkVertically(tween(EntropyMotion.FAST))
        ) {
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(6.dp))
                Text("原因：${f.cause}", style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                f.fix.forEachIndexed { i, step ->
                    Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
        }
    }
}

/** 一条问答。cause 说清"为什么会这样"，fix 是能照着做下去的步骤 */
private data class Faq(val q: String, val cause: String, val fix: List<String>)

private val Faqs: List<Faq> by lazy {
    listOf(
        Faq(
            "点「开启悬浮控制台」没反应，小球不出现",
            "绝大多数是「显示在其他应用上层」没授予。addView 会抛异常，服务捕获后如实停掉自己，" +
                "所以现象就是点了没动静，而不是弹一个错误框。",
            listOf(
                "到「权限」页，点「显示在其他应用上层」那一行去授予。",
                "在系统设置里允许本应用，然后回到 App。",
                "再点一次开启。仍不出来就不要再反复点，看下一步。",
                "检查「熵」的通知有没有被系统折叠 —— 前台服务没有通知会被系统直接杀掉。",
            )
        ),
        Faq(
            "小球能显示，但游戏里点不到它",
            "小球窗口收起态只和球本身一样大，展开后的卡片也只有 196dp 宽。" +
                "落在这个矩形之外的触摸会直接透传给游戏，这是为了不挡操作区故意做成这样的，不是失灵。",
            listOf(
                "瞄准球的中心按，别按边缘。",
                "被游戏 UI 挡住时单指按住把球拖到空白角落。",
                "不方便点的时候，音量加键打开控制台、音量减键收起。",
                "需要大面积操作就切「左侧控制台」，它只占左边约四成屏宽，右半屏照常透传。",
            )
        ),
        Faq(
            "悬浮面板里的滑杆拖不动",
            "滑杆只在左侧控制台里提供。小球展开卡的外层挂着拖拽 + 捏合手势检测器，" +
                "它会先消费掉横向移动，滑杆放在里面必然拖不动。",
            listOf(
                "双击小球，或点展开卡右下角的「控制台」切过去。",
                "在控制台里拖滑杆：拖动过程中只有数字在变，松手才下发。",
                "小球自身的大小不用进面板，双指捏合就能改。",
            )
        ),
        Faq(
            "「一键运行 entropyd」点了没反应，或一直显示只读",
            "三种可能：没拿到 Root；二进制没能投放到 /data/local/tmp；投放成功但进程没起来。" +
                "三者表现完全一样，所以要分开确认。",
            listOf(
                "先点「请求 Root」，到 Root 管理器里允许本应用 —— 弹窗只由管理器决定，App 里不会自己弹。",
                "再点一次「一键运行」，这一版会重新释放并拉起二进制。",
                "看面板「entropyd 与 Root」那张卡最下面那行回执，它写的是具体哪一步失败。",
                "完全没 Root 就走 ADB：「开发」页的「ADB 命令」按钮会给出能直接粘贴的完整命令。",
            )
        ),
        Faq(
            "entropyd 明明在跑，界面却显示未运行",
            "带 ARM 转译（houdini / ndk_translation）的机型上，/proc/<pid>/cmdline 的第一项是翻译器本身" +
                "而不是 entropyd，pidof 按 cmdline 匹配就会漏判。现在改成回退读 pid 文件并以 /proc/<pid> 为准。",
            listOf(
                "先看「处理器与指令集」里「native 执行方式」是不是「转译运行」。",
                "是的话确认 /data/local/tmp/entropy.pid 存在，且里面的 PID 在 /proc 下能找到目录。",
                "两处都正常却仍报未运行，把这一组和「执行路径」两行截图报上来。",
            )
        ),
        Faq(
            "改了档位但频率没变，或提示「当前无权限」",
            "档位要写进 cpufreq 节点，只读路径写不进去。另一种是厂商 thermal 在你抬完之后又改回去，" +
                "这不算失败 —— 守护进程会在下一拍重新下档。",
            listOf(
                "先确认执行路径不是「只读」。",
                "等 1~2 秒再看读数，Daemon 是 4Hz 回报，不是即时刷新。",
                "读数一直不动，用「调度档位」里的「重发」再压一次。",
                "频繁被改回说明厂商温控在管这件事，把软上限往下调 2~3℃ 比对抗有效。",
            )
        ),
        Faq(
            "帧率上限设了没效果",
            "SurfaceFlinger 的帧率属性受 SELinux 限制，非系统应用改不动；就算改成了，也要目标应用重启才读得到新值。",
            listOf(
                "确认执行路径是 Daemon（Root 直写这条路径改不了它）。",
                "下发后把目标游戏彻底杀掉再进一次。",
                "仍然无效就是这台机器的 sepolicy 拦住了，App 只能如实报「未生效」。",
            )
        ),
        Faq(
            "刷新率点了不生效",
            "写 secure settings 需要 WRITE_SECURE_SETTINGS，这是签名级权限，应用内没有任何申请通道。" +
                "另一条路是 Shizuku：授权后这条写入由远端 shell 身份执行，不需要那个权限。",
            listOf(
                "装了 Shizuku 的话：在「权限」页选到 Shizuku / ADB 那一档，点「请求 Shizuku 授权」，" +
                    "在它弹出的确认页里允许。",
                "电脑连 adb，执行 adb shell pm grant com.entropy android.permission.WRITE_SECURE_SETTINGS",
                "回「权限」页点刷新，本机能力里的「写入安全设置」应变成「已授予」。",
                "再点刷新率档位。",
            )
        ),
        Faq(
            "画质增强（锐度 / 清晰度）没有变化",
            "这两项写的是厂商后处理节点，路径各家不同，本项目试的是 /proc/mtkclear/clear_level 一类。" +
                "节点不存在时不会报错，只会如实说未生效。",
            listOf(
                "看回执是不是「画质增强未生效：该机型没有暴露后处理节点」。",
                "是的话这台机器就没有可写入口，改用系统自带的画质增强，本项在你的机器上无解。",
                "换个数值再试是没有用的 —— 节点不存在和数值无关。",
            )
        ),
        Faq(
            "红色「安全熔断」横幅一直挂着不消失",
            "熔断锁定由 Daemon 的 1Hz 心跳决定，锁定期 120 秒。这里以前做过「或运算粘住」，" +
                "结果解锁之后红条还留着；现在只跟随真实状态。",
            listOf(
                "先等满 120 秒，期间让设备自然散热，别继续抬频。",
                "还在就看横幅里的原因：写「Daemon 连接中断」是掉线，不是真的在熔断。",
                "掉线就点一次「一键运行」重连，重连成功会把这句过期原因清掉。",
                "反复触发熔断说明软上限偏高，把它下调 3~5℃ 比反复重试有效。",
            )
        ),
        Faq(
            "「开始极限探测」点了没反应",
            "探测要主动抬频并连续读数，只有 Daemon 能做 —— 只读和 Root 直写两条路径都会被直接拒绝。",
            listOf(
                "确认执行路径是 Daemon。",
                "确认当前不在熔断锁定期内，锁定期间禁止抬频。",
                "探测中途会短时发热，属于正常；随时可以点「中止」，中止后会自动还原。",
            )
        ),
        Faq(
            "灵动岛能不能拖到别的位置",
            "不能，位置固定在屏幕顶部居中，横向跟着刘海/挖孔的安全区走。" +
                "它可以拖动时最容易出现的事故是被拖到状态栏图标或游戏摇杆上，" +
                "而它的职责是「一眼能扫到的结论」，位置必须每次都在同一个地方。",
            listOf(
                "点一下展开明细，长按直接关闭这一轮显示。",
                "嫌它占地方就收起，收起态只有一条 32dp 高的胶囊。",
                "横屏、竖屏、不同分辨率都会自动重算宽高，不需要手动摆位。",
            )
        ),
        Faq(
            "浅色主题下面板文字被游戏画面冲淡",
            "面板底板是半透明的（alpha 0.9），亮场景确实会透上来一点。这是为了保住「看得见游戏」付的代价。",
            listOf(
                "在控制台左侧「外观」页签里选「深色」。",
                "需要完全不被干扰时把面板收成小球，温度读数照样在。",
            )
        ),
    )
}

private fun rendererLabel(code: Int): String = when (code) {
    Proto.RENDERER_SKIA -> "Skia (skia)"
    Proto.RENDERER_GLES -> "OpenGL ES 3.2 (skiagl)"
    Proto.RENDERER_VULKAN -> "Vulkan 1.3 (vk)"
    else -> "系统默认"
}

private fun daemonStateLabel(state: Int): String = when (state) {
    0 -> "空闲"
    1 -> "调度中"
    2 -> "探测中"
    3 -> "熔断锁定"
    else -> "未知"
}

private fun fmtUptime(sec: Long): String =
    if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
    else "%d:%02d".format(sec / 60, sec % 60)
