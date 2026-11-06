// File: app/src/main/java/com/entropy/hardware/TerminalRunner.kt
//
// 命令终端的执行端：把用户敲进去的一行命令跑掉，把 stdout/stderr/退出码原样交回 UI。
//
// 这个类是整个项目里唯一"执行用户原文"的地方，所以边界写在明面上：
//
// 1) 只有拒绝清单，没有"转义后放行"。
//    终端的语义就是把命令交给 shell，任何转义技巧都等于篡改用户要执行的东西。
//    因此不可逆操作（抹根、格盘、往块设备写、重启）一律拒绝执行，
//    而不是"弹个确认框然后照做"——确认框会被连点三次的人习惯性地点掉。
//
// 2) 一行一条。命令串里出现换行就拒：su 的 stdin 是按行读协议的，
//    塞两条进去会让第二条脱离本类的超时控制，变成一条追不到的后台进程。
//
// 3) 每条命令都进硬件记录。
//    终端能改内核状态，出了事故时"谁在什么时候跑了什么"是唯一的自证材料。
package com.entropy.hardware

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一行命令的分类结果。Forbidden 不执行，Caution 要 UI 先让用户确认 */
sealed class Risk {
    object Safe : Risk()
    data class Caution(val why: String) : Risk()
    data class Forbidden(val why: String) : Risk()
}

data class TermEntry(
    val at: Long,
    val cmd: String,
    val asRoot: Boolean,
    val out: String,
    val err: String,
    val code: Int,
    val ms: Long,
) {
    /** 0 是成功；-1/-2/-3 是本类自定义的"没跑成"，不是 shell 返回的 */
    val ok get() = code == 0
}

class TerminalRunner {

    /** 会话内保留的输出条数。再多也没人翻，且每条都可能带几十 KB 输出 */
    private val entries = androidx.compose.runtime.mutableStateOf<List<TermEntry>>(emptyList())
    private val history = androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
    val busy = mutableStateOf(false)

    /** UI 直接绑这个列表，最新一条在最下面（终端的阅读顺序） */
    fun log(): List<TermEntry> = entries.value
    fun commands(): List<String> = history.value

    fun clear() { entries.value = emptyList() }

    // ---------------------------------------------------------------- //
    // 风险判定                                                          //
    // ---------------------------------------------------------------- //

    /**
     * 判定按"能不能恢复"来分，不按"看着危不危险"。
     * 例如 setenforce 0 会降低安全边界，但它可逆、也是排障常用手段，归 Caution；
     * 而 rm -rf / 一旦执行就没有回头路，归 Forbidden。
     */
    fun classify(raw: String): Risk {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return Risk.Forbidden("命令是空的")
        if (cmd.any { it == '\n' || it == '\r' }) {
            return Risk.Forbidden("一行只能一条命令，多条请用 ; 或 && 连接")
        }
        // Regex 上是 containsMatchIn，不是 contains：后者在 kotlin.text 里根本不存在，
        // 报出来的错长得像"参数类型不对"，很容易往 cmd 的类型上去找
        FORBIDDEN.forEach { (re, why) -> if (re.containsMatchIn(cmd)) return Risk.Forbidden(why) }
        CAUTION.forEach { (re, why) -> if (re.containsMatchIn(cmd)) return Risk.Caution(why) }
        return Risk.Safe
    }

    // ---------------------------------------------------------------- //
    // 执行                                                             //
    // ---------------------------------------------------------------- //

    /**
     * 跑一条命令。asRoot=true 时走 su 的 stdin；否则用应用自己的 shell 身份。
     * 超时后 destroyForcibly，并把已经读到的部分照旧交回 —— 半途的输出比什么都没有有用。
     *
     * 调用约定：在 Main 上调用本函数（状态写入发生在这一层），
     * 真正的进程 IO 已经用 withContext(IO) 挪走了。
     */
    suspend fun run(raw: String, asRoot: Boolean, timeoutS: Long = 15): TermEntry {
        val cmd = raw.trim()
        val started = System.currentTimeMillis()
        busy.value = true
        val result = try {
            withContext(Dispatchers.IO) { exec(cmd, asRoot, timeoutS) }
        } catch (e: Exception) {
            Triple(-3, "", e.message ?: e.javaClass.simpleName)
        } finally {
            busy.value = false
        }
        val (code, out, err) = result
        val entry = TermEntry(
            at = started, cmd = cmd, asRoot = asRoot,
            // 在落库前就截断：一条 cat 大文件能吐出几 MB，留在内存里没人翻，
            // 但足以把整个会话的记录拖垮
            out = truncate(stripAnsi(out)), err = truncate(stripAnsi(err)),
            code = code, ms = System.currentTimeMillis() - started,
        )
        entries.value = (entries.value + entry).takeLast(MAX_ENTRIES)
        history.value = (history.value.filter { it != cmd } + cmd).takeLast(MAX_HISTORY)
        Log.d(TAG, "term[$asRoot] $cmd -> $code")
        return entry
    }

    private fun exec(cmd: String, asRoot: Boolean, timeoutS: Long): Triple<Int, String, String> {
        // su 用 stdin 而不是 -c：部分定制 ROM 的 su 对 -c 的参数处理有 bug，
        // 判据与 ShellRunner.root() 保持一致，别在两个地方走出两套行为。
        val pb = if (asRoot) ProcessBuilder("su") else ProcessBuilder("sh", "-c", cmd)
        val p = pb.start()
        if (asRoot) {
            p.outputStream.bufferedWriter().use { w ->
                w.write(cmd); w.newLine(); w.write("exit"); w.newLine(); w.flush()
            }
        } else {
            p.outputStream.close()
        }
        // 两条流都要边读边等：只读 stdout 而 stderr 缓冲区写满时，
        // 子进程会在写 stderr 上阻塞，父进程在 waitFor 上阻塞，两边一起吊死。
        val out = StringBuilder(); val err = StringBuilder()
        val t1 = reader(p.inputStream, out).also { it.start() }
        val t2 = reader(p.errorStream, err).also { it.start() }
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) {
            p.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
        }
        t1.join(min(2000L, timeoutS * 1000)); t2.join(min(2000L, timeoutS * 1000))
        val code = if (p.isAlive) -1 else p.exitValue()
        return Triple(code, out.toString(), err.toString())
    }

    /** destroy 之后流会抛 IOException，这里必须吞掉：线程带着未捕获异常退出会刷一屏 logcat */
    private fun reader(input: java.io.InputStream, sink: StringBuilder): Thread = Thread {
        runCatching { val s = input.readBytes().decodeToString(); synchronized(sink) { sink.append(s) } }
    }

    /** 长输出留这么多字符，超出的部分提示字符数而不是静默裁掉 */
    private fun truncate(s: String, max: Int = MAX_CHARS): String =
        if (s.length <= max) s else s.take(max) + "\n…（还有 ${s.length - max} 字符未显示）"

    private fun stripAnsi(s: String): String = ANSI.replace(s, "")

    private companion object {
        const val TAG = "EntropyTerm"
        const val MAX_ENTRIES = 200
        const val MAX_HISTORY = 50
        const val MAX_CHARS = 24_000

        val ANSI = Regex("\u001B\\[[0-9;?]*[a-zA-Z]")

        val FORBIDDEN = listOf(
            Regex("""(^|[;&|]\s*)rm\s+(-[a-zA-Z]*\s+)*-?[rRf]+\s*/(\s|$)""") to "会抹掉整个根目录，拒绝执行",
            Regex("""rm\s+(-[a-zA-Z]+\s+)*-?[rR]+\s+/(system|vendor|data|proc|sys|dev)(\s|$)""") to "会递归删除系统分区，拒绝执行",
            Regex("""\bmkfs(\.|\s)""") to "格式化文件系统不可恢复，拒绝执行",
            Regex("""\bdd\b.*\bof=/dev/""") to "直接写块设备，拒绝执行",
            Regex(""">\s*/dev/block/""") to "重定向写块设备，拒绝执行",
            Regex("""\breboot\b|\bshutdown\b""") to "重启/关机请在本机物理操作，拒绝执行",
            Regex("""kill\s+-9\s+1\b""") to "杀掉 init 等于重启，拒绝执行",
            Regex("""\bpm\s+(uninstall|clear)\b""") to "卸载/清数据不在性能排障范围内，拒绝执行",
            Regex("""\bchmod\b.*\s-R\s+/(system|vendor)""") to "递归改系统分区权限会破坏校验，拒绝执行",
        )

        val CAUTION = listOf(
            Regex("""\bsetenforce\b""") to "会改动 SELinux 强制模式，影响范围超出本应用",
            Regex("""\bsetprop\b""") to "改系统属性可能被其它进程立刻读到",
            Regex("""\becho\b.*/sys/""") to "直接写内核节点，厂商温控可能不接受",
            Regex("""\bpkill\b|\bkillall\b""") to "杀进程可能把正在记录的东西一起带走",
            Regex("""\brm\b""") to "删除文件，先确认路径",
            Regex("""\bsettings\s+put\b""") to "写系统设置，对所有应用生效",
            Regex("""\bwm\s+(size|density)\b""") to "改分辨率/密度，重启前会影响整机显示",
        )
    }
}
