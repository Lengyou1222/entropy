// File: app/src/main/java/com/entropy/hardware/ShellRunner.kt
//
// Root / ADB / Shizuku 命令执行器。
//
// 三条硬规矩，都是被真实 root 工具伤过之后定下来的：
//  1. 绝不把 UI 传来的字符串直接拼进 shell。所有可变部分必须先过校验（正则白名单），
//     校验不过宁可拒绝也不"转义一下试试"。
//  2. 所有命令带超时。su 弹窗被拒绝、或 adb 隧道卡住时，Process.waitFor() 会永久阻塞，
//     进而把整个协程作用域吊死 —— 这类 bug 表现为"点了一下 App 就再也不动了"。
//  3. 命令与结果全量落盘（可关）。出问题时唯一能自证清白的东西就是日志。
package com.entropy.hardware

import android.content.Context
import android.util.Log
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class ShellRunner(private val context: Context) {

    enum class Mode { NONE, ROOT, ADB_SHELL, SHIZUKU }

    data class Result(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
    }

    @Volatile var mode: Mode = Mode.NONE
        private set

    /**
     * 探测可用权限等级。
     *
     * 先问自己是谁（id -u），不是 0 再问 su 肯不肯给。
     * 顺序不能反过来：su 每次探测都可能弹一次授权框，
     * 而 uid==2000 这一眼就能认出"我是被 adb shell 拉起来的"，不必打扰用户。
     * su 探测用 `su -c`：只读一条 id，不碰任何节点，也就不怕部分 ROM 对 -c 的
     * 参数处理 bug —— 真正下发命令时 root() 走的仍是 stdin 形式。
     *
     * Shizuku 排在 su 之后，不是随便排的：两种都到位的机器上 ROOT 能给的东西严格更多
     * （写内核节点、拉起 entropyd），把这种机器判成 SHIZUKU 会让一堆功能凭空消失。
     */
    fun detect(): Mode {
        val self = tryExec("id -u", 2)?.out?.trim()
        val su = if (self == "0" || self == "2000") null else tryExec("su -c 'id -u'", 5)?.out?.trim()
        mode = when {
            self == "0" -> Mode.ROOT
            self == "2000" -> Mode.ADB_SHELL
            su == "0" -> Mode.ROOT
            ShizukuBridge.available() -> Mode.SHIZUKU
            else -> Mode.NONE
        }
        Log.d(TAG, "detect: self=$self su=$su -> $mode")
        return mode
    }

    /**
     * Shizuku 的连接或授权状态变了之后重算档，不重跑 su 探测。
     *
     * 为什么不调 detect()：那条路里含 `su -c 'id -u'`，会在用户完全没预期的时候弹一个
     * Root 授权框 —— 他刚点的明明是"请求 Shizuku 授权"。
     * 已有的 ROOT / ADB_SHELL 是更强的事实，一律保持不动；只有原本落到 NONE 的机器
     * 才升到 SHIZUKU，撤销授权时降回 NONE。
     */
    fun resyncShizuku(): Mode {
        if (mode == Mode.NONE || mode == Mode.SHIZUKU) {
            mode = if (ShizukuBridge.available()) Mode.SHIZUKU else Mode.NONE
        }
        Log.d(TAG, "resyncShizuku -> $mode")
        return mode
    }

    /** 执行失败或超时都返回 null：调用方要区分"命令说不是 root"和"命令根本没跑成" */
    private fun tryExec(cmd: String, timeoutS: Long = 3): Result? = try {
        val p = ProcessBuilder("sh", "-c", cmd)
            .redirectErrorStream(false)
            .start()
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) {
            p.destroy()
            null
        } else {
            Result(p.exitValue(), p.inputStream.readBytes().decodeToString(),
                   p.errorStream.readBytes().decodeToString())
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 以 root 执行。Magisk 的 su 支持 `su -c` 与 stdin 两种形式，
     * 这里用 stdin + 显式 `exit`，因为部分定制 ROM 的 su 对 -c 参数处理有 bug
     * （会把整条命令当密码读，或拒绝含空格的参数）。
     */
    suspend fun root(cmd: String, timeoutS: Long = 8): Result {
        if (mode == Mode.NONE) detect()
        if (mode != Mode.ROOT) return Result(-1, "", "no root; grant su or use ADB mode")
        return try {
            val p = Runtime.getRuntime().exec("su")
            DataOutputStream(p.outputStream).use { os ->
                os.writeBytes(cmd + "\n")
                os.writeBytes("exit\n")
                os.flush()
            }
            if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) {
                p.destroy()
                return Result(-2, "", "timeout after ${timeoutS}s")
            }
            val out = p.inputStream.readBytes().decodeToString()
            val err = p.errorStream.readBytes().decodeToString()
            Log.d(TAG, "root: $cmd -> ${p.exitValue()}")
            Result(p.exitValue(), out, err)
        } catch (e: Exception) {
            Result(-3, "", e.message ?: "exception")
        }
    }

    /**
     * 无需 root 的降级路径：只跑 shell 身份能跑的（wm、settings、dumpsys）。
     *
     * SHIZUKU 档下这条路真的走 Shizuku 的远端进程。必须这样：`wm size` 这类命令要的是
     * shell uid，在本应用自己的进程里 exec 出来只是普通应用 uid，那条命令根本不会被
     * WindowManager 接受 —— 而在本地跑还有个更坏的后果：命令报的错被吞掉，
     * 界面上显示"已应用"，实际什么都没变。
     *
     * 通道突然没了（用户刚关掉 Shizuku）就把档降回 NONE 再试本地：
     * 读 sysfs 这类命令本来就哪个身份都能读，没必要因为一次断连把能用的也报成不可用。
     */
    suspend fun plain(cmd: String, timeoutS: Long = 5): Result {
        if (mode == Mode.SHIZUKU) {
            val r = ShizukuBridge.exec(cmd, timeoutS)
            // 这一笔是远端通道唯一的可观察证据：命令跑在 shizuku_server 那个进程里，
            // 本进程的 logcat 看不到它的任何痕迹，出了事只能靠这里记下"这条命令、什么结果"
            Log.d(TAG, "shizuku: $cmd -> ${r?.code}")
            if (r != null) return r
            mode = Mode.NONE
        }
        val r = tryExec(cmd, timeoutS)
        return r ?: Result(-2, "", "timeout")
    }

    // ---- 参数校验 ----
    // 只允许"看起来像内核节点路径 + 整数"的组合，其它一律拒。

    private val NODE_SPEC = Pattern.compile(
        "^(/sys|/proc)/[A-Za-z0-9_.:/=+-]+=(-?\\d{1,10})$"
    )
    private val PKG_NAME = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$")

    fun isSafeNodeSpec(spec: String) = NODE_SPEC.matcher(spec).matches()
    fun isSafePackage(pkg: String) = PKG_NAME.matcher(pkg).matches()

    companion object {
        private const val TAG = "EntropyShell"
    }
}
