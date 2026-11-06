// File: app/src/main/java/com/entropy/update/UpdateFlow.kt
//
// 检查更新的界面状态。
//
// 单独一个对象而不是塞进 EntropyStore：下载要跨页面活着。
// 用 Activity 的 scope 或 viewModelScope 的话，用户在"我的"页点的检查，
// 切到首页就断了，进度条会永远停在半截 —— 所以这里自带一个进程级 scope。
package com.entropy.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

object UpdateFlow {

    enum class Phase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }

    val phase = mutableStateOf(Phase.IDLE)
    val release = mutableStateOf<Release?>(null)
    val progress = mutableFloatStateOf(0f)
    val note = mutableStateOf("")
    /** 上次真正完成检查的时刻，界面用它说明"这是什么时候查的" */
    val checkedAt = mutableLongStateOf(0L)
    /** 手动查才报失败原因；启动自动查失败只默默写进 note，不打扰人 */
    val manual = mutableStateOf(false)
    private val apk = mutableStateOf<File?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    /** 这一版已经选过"继续使用当前版本"，同一进程里不再弹第二次 */
    private var dismissedTag: String? = null

    /** auto = 启动时那一次静默检查 */
    fun check(ctx: Context, auto: Boolean) {
        if (phase.value == Phase.CHECKING || phase.value == Phase.DOWNLOADING) return
        manual.value = !auto
        phase.value = Phase.CHECKING
        job?.cancel()
        job = scope.launch {
            val result = UpdateChecker.fetchLatest()
            checkedAt.value = System.currentTimeMillis()
            when (result) {
                is CheckResult.UpToDate -> {
                    release.value = null
                    phase.value = Phase.UP_TO_DATE
                    note.value = "已是最新：远端 ${result.remote}，本机 ${UpdateChecker.currentVersion}"
                }
                is CheckResult.Available -> {
                    release.value = result.release
                    progress.floatValue = 0f
                    // 上一次进程里下完但没装的包还在盘上：直接回到"待安装"，
                    // 不要让用户为了同一个版本再下一次网。
                    val local = localApk(ctx, result.release)
                    if (local != null) {
                        markReady(local, "这个版本的安装包本机已有，直接安装即可")
                    } else {
                        phase.value = Phase.AVAILABLE
                        note.value = "发现新版本 ${result.release.version}（本机 ${UpdateChecker.currentVersion}）"
                    }
                }
                is CheckResult.Failed -> {
                    release.value = null
                    phase.value = Phase.FAILED
                    note.value = result.reason
                }
            }
        }
    }

    /**
     * 弹窗该不该出现。整个"发现 → 下载 → 待安装"期间都算数，
     * 只在用户明确点了"继续使用当前版本"之后（或这版已经跳跳过）才收起来 ——
     * 下载完就自动消失的话，用户再没有第二个入口去装那个包了。
     */
    fun shouldDialog(): Boolean {
        val r = release.value ?: return false
        if (r.tag == dismissedTag) return false
        return when (phase.value) {
            Phase.AVAILABLE, Phase.DOWNLOADING, Phase.READY, Phase.FAILED -> true
            else -> false
        }
    }

    /** 用户点了"继续使用当前版本" */
    fun dismiss() {
        dismissedTag = release.value?.tag
    }

    fun startDownload(ctx: Context) {
        val r = release.value ?: return
        if (phase.value == Phase.DOWNLOADING) return
        localApk(ctx, r)?.let {
            markReady(it, "这个版本的安装包本机已有，直接安装即可")
            return
        }
        phase.value = Phase.DOWNLOADING
        progress.floatValue = 0f
        note.value = "正在下载 ${r.apkName}"
        job?.cancel()
        job = scope.launch {
            try {
                val f = UpdateChecker.download(ctx, r) { progress.floatValue = it }
                markReady(f, "已下载 ${(f.length() / 1024 / 1024).coerceAtLeast(1)} MB，点安装覆盖升级")
            } catch (e: Exception) {
                phase.value = Phase.FAILED
                progress.floatValue = 0f
                note.value = "下载没完成：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** 盘上这个版本的包是不是完整的一份。只认 Release 里报的那个文件名与大小 */
    private fun localApk(ctx: Context, r: Release): File? =
        File(UpdateChecker.updateDir(ctx), r.apkName).takeIf { r.apkSize > 0 && it.length() == r.apkSize }

    private fun markReady(f: File, message: String) {
        apk.value = f
        progress.floatValue = 1f
        phase.value = Phase.READY
        note.value = message
    }

    /**
     * 发起安装。返回 false 表示系统还没给"安装未知应用"的放行，
     * 调用方要去设置页要权限 —— 这一步不能替用户点，装不装必须由他确认。
     */
    fun install(ctx: Context): Boolean {
        val f = apk.value ?: return false
        if (!f.exists() || !UpdateChecker.canInstall(ctx)) return false
        runCatching { ctx.startActivity(UpdateChecker.installIntent(ctx, f)) }
            .onFailure {
                phase.value = Phase.FAILED
                note.value = "系统安装器没接住这个包：${it.javaClass.simpleName}"
            }
            .onSuccess { note.value = "已交给系统安装器，在弹出的确认页里点继续安装" }
        return true
    }

    fun openReleasePage(ctx: Context) {
        val url = release.value?.pageUrl?.takeIf { it.isNotBlank() }
            ?: "https://github.com/${UpdateChecker.repo}/releases"
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
