// File: app/src/main/java/com/entropy/hardware/ShizukuBridge.kt
//
// Shizuku 客户端通道：连接状态、应用内请求授权、以 shell 身份执行命令。
//
// 这个类只说"实际读到的东西"，三处判据分开留着，绝不互相推导：
//   installed —— 包管理器里有没有 moe.shizuku.privileged.api。装了不等于在跑。
//   running   —— Shizuku.pingBinder()。binder 活着才谈得上后面的调用。
//   granted   —— Shizuku.checkSelfPermission()。运行中不等于授权给你了。
// 界面上"未安装 / 已安装未运行 / 运行中未授权 / 已授权"四态各自有文案，
// 把它们压成一句"Shizuku 不可用"会让人去重启一个本来正常的服务。
//
// 依赖顺序是硬的：rikka.shizuku 那套静态方法在 binder 还没送达时抛
// IllegalStateException("binder haven't been received")，而 checkSelfPermission、
// requestPermission 只 catch RemoteException，不 catch 它 —— 所以在调用任何一条
// 之前必须先看 pingBinder()。这不是防御性冗余，是这几个方法的真实契约。
//
// 授权走 Shizuku.requestPermission()：弹框由 Shizuku 服务端起（它的 RequestPermissionActivity
// 受 INTERACT_ACROSS_USERS_FULL 保护，本应用自己 startActivity 是起不动的），
// 结果从 OnRequestPermissionResultListener 回来。要让用户自己点，这里不代做决定。
package com.entropy.hardware

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Shizuku 的连接与授权事实。
 *
 * 四个字段是四条独立探针，没有一条能从另一条推出来，所以 State 里原样放着。
 * [versionName] 来自包管理器（Shizuku 自己报的营销版本），[apiVersion] 来自服务端
 * binder（决定我们能用到哪一档 API）—— 两者不是一个数，界面上分别显示，不合并。
 */
data class ShizukuState(
    val installed: Boolean = false,
    /** 包管理器里的 versionName，仅作展示；读不到就空 */
    val versionName: String = "",
    /** binder 在，Shizuku 服务真的跑着 */
    val running: Boolean = false,
    val granted: Boolean = false,
    /** 服务端 API 版本，未连接时 -1 */
    val apiVersion: Int = -1,
    /** 命令执行身份，已授权时应当是 2000（shell）；-1 = 没读到 */
    val uid: Int = -1,
    /** 被拒过一次且 Shizuku 不再弹框。true 时「请求授权」点了也没用，只能去 Shizuku 里给 */
    val deniedPersistent: Boolean = false,
)

/** 发起授权请求这一动作的结果。四态对应四句不同的话，不能都写成"请求失败" */
enum class GrantOutcome { SENT, ALREADY_GRANTED, NOT_INSTALLED, NOT_RUNNING }

object ShizukuBridge {

    const val PKG = "moe.shizuku.privileged.api"

    /** 请求码。Shizuku 只把它原样带回回调，用来确认"这是熵发的那一次" */
    private const val REQUEST_CODE = 1001

    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state

    private var appContext: Context? = null

    /** binder 到达 / 断开都重读一遍；Sticky 版本保证晚注册也能立刻收到已到达的那次 */
    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshQuietly() }
    private val binderDead = Shizuku.OnBinderDeadListener { refreshQuietly() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST_CODE) {
            refreshQuietly()
            Log.i(TAG, "permission result=$result")
        }
    }

    /**
     * 注册监听。必须在 Application 里调用一次：
     * provider 送达 binder 的时机比 UI 早，晚了就只能等下一次 binder 事件，
     * 用户会看到"Shizuku 明明开着，熵却说没连上"。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
        }.onFailure { Log.w(TAG, "listener register failed", it) }
        refresh()
    }

    /** 装了但没有 binder，和根本没装是两件事，所以安装状态在这里单独读一次 */
    private fun readInstalled(): Pair<Boolean, String> {
        val pm = appContext?.packageManager ?: return false to ""
        return runCatching {
            val pi = pm.getPackageInfo(PKG, 0)
            true to (pi.versionName ?: "")
        }.getOrDefault(false to "")
    }

    /**
     * 重读全部事实。UI 每次进入权限页、每次用户点"重新检测"都调它。
     *
     * 全部包在 pingBinder() 后面：见文件头，未连接时那几条静态调用会抛 ISE。
     */
    fun refresh(): ShizukuState {
        val (installed, name) = readInstalled()
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        var granted = false
        var api = -1
        var uid = -1
        var denied = false
        if (running) {
            granted = runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            api = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
            uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
            // 已授权时这条没有意义，也不该去读它
            if (!granted) denied = runCatching {
                Shizuku.shouldShowRequestPermissionRationale()
            }.getOrDefault(false)
        }
        val s = ShizukuState(installed, name, running, granted, api, uid, denied)
        _state.value = s
        return s
    }

    private fun refreshQuietly() {
        runCatching { refresh() }.onFailure { Log.w(TAG, "refresh", it) }
    }

    /** 打开 Shizuku 本体。没装返回 false，由 UI 决定说什么 */
    fun openManager(context: Context): Boolean {
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(PKG)
        }.getOrNull() ?: return false
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.isSuccess
    }

    /** 向 Shizuku 发起授权请求。弹框出现在 Shizuku 那一侧，结果异步回到 [state] */
    fun requestGrant(): GrantOutcome {
        val s = refresh()
        if (!s.installed) return GrantOutcome.NOT_INSTALLED
        if (!s.running) return GrantOutcome.NOT_RUNNING
        if (s.granted) return GrantOutcome.ALREADY_GRANTED
        return runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .fold(onSuccess = { GrantOutcome.SENT }, onFailure = {
                Log.w(TAG, "requestPermission", it)
                // 调用失败按"没连上"报，不假装弹框出现了
                GrantOutcome.NOT_RUNNING
            })
    }

    /** 执行通道是否可用：连接 + 授权两个条件都成立 */
    fun available(): Boolean {
        val s = _state.value
        return s.running && s.granted
    }

    private fun service(): IShizukuService? {
        if (!available()) return null
        return runCatching {
            IShizukuService.Stub.asInterface(Shizuku.getBinder())
        }.getOrNull()
    }

    /**
     * 以 shell 身份跑一条命令；拿不到通道返回 null。
     *
     * 返回 null 与返回 code != 0 是两件不同的事：前者意味着"这条路不存在"，
     * 调用方要退到别的通道或直接说做不到；后者是命令自己给的答复。
     *
     * 读输出必须是两条线程并发抽：dumpsys 类命令写得比管道缓冲快，
     * 先 waitFor 再读会让对端阻塞在写满的管道上、命令永不退出，本进程这边就吊死了。
     */
    suspend fun exec(cmd: String, timeoutS: Long = 6): ShellRunner.Result? {
        val svc = service() ?: return null
        return withContext(Dispatchers.IO) {
            val p = runCatching { svc.newProcess(arrayOf("sh", "-c", cmd), null, null) }
                .getOrNull() ?: return@withContext null
            val out = AtomicReference("")
            val err = AtomicReference("")
            val tOut = thread(isDaemon = true, name = "entropy-shizuku-out") {
                out.set(runCatching { p.inputStream.readAll() }.getOrDefault(""))
            }
            val tErr = thread(isDaemon = true, name = "entropy-shizuku-err") {
                err.set(runCatching { p.errorStream.readAll() }.getOrDefault(""))
            }
            // 输入端立刻关掉：命令若想读 stdin，让它拿到 EOF，而不是干等到超时
            runCatching { p.outputStream.close() }
            tOut.join(timeoutS * 1000)
            if (tOut.isAlive) {
                runCatching { p.destroy() }
                tErr.join(200)
                ShellRunner.Result(-2, out.get(), "shizuku: timeout after ${timeoutS}s")
            } else {
                tErr.join(200)
                // alive()/exitValue() 都是 binder 调用：EOF 之后进程几乎已经退了，
                // 万一还在跑就如实报 -4，不去猜它下一步会不会退出
                val code = runCatching {
                    if (p.alive()) -4 else p.exitValue()
                }.getOrDefault(-3)
                ShellRunner.Result(code, out.get(), err.get())
            }
        }
    }

    private fun ParcelFileDescriptor.readAll(): String =
        ParcelFileDescriptor.AutoCloseInputStream(this).use { it.readBytes() }.decodeToString()

    private const val TAG = "EntropyShizuku"
}
