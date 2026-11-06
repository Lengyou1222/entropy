// File: app/src/main/java/com/entropy/update/UpdateChecker.kt
//
// 检查更新与下载安装。
//
// 为什么走 GitHub Releases 而不是自建服务器：这个项目的分发物只有这一个 APK，
// releases/latest 是匿名可读的公开接口，不用签名、不用 token、不用养一台机器。
// 代价是两个：匿名限流 60 次/小时每 IP（所以只在启动时查一次，不轮询），
// 以及下载域名会被个别网络掐断（失败只报原因，不重试刷屏）。
//
// 全程只有这一个文件出网，读的是 owner/repo 由 BuildConfig.UPDATE_REPO 钉死的仓库。
package com.entropy.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.system.Os
import androidx.core.content.FileProvider
import com.entropy.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** releases/latest 里我们真正要用的那几项 */
data class Release(
    val tag: String,
    /** 从 tag 或资产名里剥出来的三段版本号，比较用的是它 */
    val version: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val notes: String,
    val pageUrl: String,
)

sealed class CheckResult {
    /** 远端不比本机新，包括"仓库还没发过 Release"之外的所有正常情况 */
    data class UpToDate(val remote: String) : CheckResult()
    data class Available(val release: Release) : CheckResult()
    /** 没查到。原因写得能看懂：断网、限流、仓库不存在、Release 里没 APK */
    data class Failed(val reason: String) : CheckResult()
}

object UpdateChecker {

    /** 本机版本名，比较与展示都用它 */
    val currentVersion: String get() = BuildConfig.VERSION_NAME
    val currentCode: Int get() = BuildConfig.VERSION_CODE
    val repo: String get() = BuildConfig.UPDATE_REPO

    private const val CONNECT_MS = 8000
    private const val READ_MS = 10000
    private val THREE_PART = Regex("\\d+\\.\\d+\\.\\d+")

    /**
     * 三段版本号按数字逐段比。
     * 不用字符串比较："1.10.0" 在字典序里比 "1.9.0" 小，这种错要到发版那天才会被发现。
     * 非数字尾巴（-beta 之类）按 0 处理，比不动就当相等。
     */
    fun compare(a: String, b: String): Int {
        val x = a.trim().removePrefix("v").split('.')
        val y = b.trim().removePrefix("v").split('.')
        for (i in 0 until maxOf(x.size, y.size)) {
            val n = x.getOrElse(i) { "0" }.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            val m = y.getOrElse(i) { "0" }.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            if (n != m) return n.compareTo(m)
        }
        return 0
    }

    /** 阻塞调用，必须在 IO 线程上跑 */
    fun fetchLatest(): CheckResult {
        val conn = try {
            (URL("https://api.github.com/repos/$repo/releases/latest").openConnection()
                as HttpURLConnection).apply {
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                // GitHub API 没有 User-Agent 直接 403，这条不是可选项
                setRequestProperty("User-Agent", "entropy-app/$currentVersion")
                setRequestProperty("Accept", "application/vnd.github+json")
                instanceFollowRedirects = true
            }
        } catch (e: Exception) {
            return CheckResult.Failed("解析不到 ${repo} 的地址：${e.javaClass.simpleName}")
        }

        val body = try {
            val code = conn.responseCode
            when {
                code == 404 -> return CheckResult.Failed("$repo 还没有发布过任何 Release")
                code == 401 || code == 403 ->
                    return CheckResult.Failed("GitHub 拒绝访问（匿名限流 60 次/小时），稍后再手动查")
                code in 200..299 -> conn.inputStream.bufferedReader().use { it.readText() }
                else -> return CheckResult.Failed("GitHub 返回 HTTP $code")
            }
        } catch (e: Exception) {
            return CheckResult.Failed("没连上 GitHub：${e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }

        val json = try { JSONObject(body) } catch (e: Exception) {
            return CheckResult.Failed("Release 响应读不出结构：${e.javaClass.simpleName}")
        }
        val tag = json.optString("tag_name").ifBlank { json.optString("name") }
        val assets = json.optJSONArray("assets")
        val count = assets?.length() ?: 0
        var apkName = ""
        var apkUrl = ""
        var apkSize = 0L
        for (i in 0 until count) {
            val a = assets!!.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkName = name
                apkUrl = a.optString("browser_download_url")
                apkSize = a.optLong("size", 0L)
                break
            }
        }
        if (apkUrl.isBlank()) return CheckResult.Failed("这个 Release 里没有 APK 资产")

        // tag 是首选来源；tag 打成日期式的那种，退回从资产名 entropy-v1.2.3.apk 里抠
        val version = listOf(tag.removePrefix("v"), apkName.removePrefix("entropy-v"))
            .firstOrNull { THREE_PART.containsMatchIn(it) }
            ?.let { THREE_PART.find(it)!!.value } ?: tag

        val release = Release(
            tag = tag,
            version = version,
            apkUrl = apkUrl,
            apkName = apkName,
            apkSize = apkSize,
            notes = json.optString("body").ifBlank { "这个 Release 没写说明" },
            pageUrl = json.optString("html_url").ifBlank { "https://github.com/$repo/releases" },
        )
        return if (compare(release.version, currentVersion) > 0) CheckResult.Available(release)
        else CheckResult.UpToDate(release.version)
    }

    /** 下载目录。放在 filesDir 而不是 cacheDir：装到一半进程被杀，下次进来还能接着装 */
    fun updateDir(ctx: Context): File = File(ctx.filesDir, "update").apply { mkdirs() }

    /**
     * 阻塞下载到 filesDir/update/，边下边回调进度（0..1，拿不到长度时恒为 0）。
     * 先落 .part 再改名：中途断掉的半截包不会被当成可用的安装包。
     */
    fun download(ctx: Context, release: Release, onProgress: (Float) -> Unit): File {
        val dir = updateDir(ctx)
        // 旧包先清掉：一个 APK 三兆，攒上几版就是白占的存储
        dir.listFiles { f -> f.name.endsWith(".apk") || f.name.endsWith(".part") }
            ?.forEach { runCatching { it.delete() } }

        val target = File(dir, release.apkName)
        val part = File(dir, release.apkName + ".part")
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            setRequestProperty("User-Agent", "entropy-app/$currentVersion")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("下载返回 HTTP $code")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                    out.flush()
                    Os.fsync(out.fd)
                }
            }
            if (total > 0 && part.length() != total)
                throw IllegalStateException("包不完整：收到 ${part.length()}，应为 $total")
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("下载文件改名失败")
            return target
        } catch (e: Exception) {
            runCatching { part.delete() }
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /** 系统能不能让我们发起安装（Android 8+ 的"安装未知应用"授权） */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            ctx.packageManager.canRequestPackageInstalls()

    fun installIntent(ctx: Context, apk: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk),
                "application/vnd.android.package-archive",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** 去系统设置里给"安装未知应用"放行 */
    fun unknownSourcesIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
