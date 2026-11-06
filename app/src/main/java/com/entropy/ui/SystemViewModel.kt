// File: app/src/main/java/com/entropy/ui/SystemViewModel.kt
//
// "权限"页与"我的"页共用一个 ViewModel。
//
// 为什么不各建一个：这两页要读的是同一批 root 会话产物
// （su 的横幅、/data/adb 的目录、模块表、授权表）。
// 一页一个 ViewModel 意味着每次进页面都要重新起 2~3 个 su 进程，
// 而每次 su 都是一次策略检查 —— 用户看到的就是一闪而过的空白。
// 合成一个之后，页面切换只是同一个 StateFlow 的两次订阅。
package com.entropy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.entropy.data.Capability
import com.entropy.data.EntropyStore
import com.entropy.data.GrantedApp
import com.entropy.data.KernelModule
import com.entropy.data.PermFacts
import com.entropy.data.SuManager
import com.entropy.data.SuStatus
import com.entropy.data.SystemInfo
import com.entropy.data.SystemRepository
import com.entropy.hardware.ShizukuState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SystemUiState(
    val loading: Boolean = false,
    val su: SuStatus = SuStatus(SuManager.NONE, "", "", 0, readable = false),
    val modules: List<KernelModule> = emptyList(),
    val grantedApps: List<GrantedApp> = emptyList(),
    val capabilities: List<Capability> = emptyList(),
    val info: SystemInfo? = null,
    val shizuku: ShizukuState = ShizukuState(false),
    val facts: PermFacts = PermFacts(),
    /** 只在"读不到"时给一句话说明。空列表和"没权限看"是两回事，必须区分 */
    val notice: String = "",
)

class SystemViewModel(app: Application) : AndroidViewModel(app) {

    private val store = EntropyStore.get(app)
    private val repo = SystemRepository(app, store.bridge.shell)

    private val _state = MutableStateFlow(SystemUiState())
    val state: StateFlow<SystemUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val su = repo.suStatus()
            val shizuku = repo.shizuku()
            // shell.mode 要在这里读：suStatus() 内部已经 detect 过一次，
            // 再单独调 detect() 会多弹一次授权框
            val facts = repo.permFacts(shizuku).copy(root = su.granted)
            store.updatePermFacts(facts)
            val info = SystemUiState(
                loading = false,
                su = su,
                modules = repo.modules(),
                grantedApps = repo.grantedApps(su.manager),
                capabilities = repo.capabilities(),
                info = repo.systemInfo(),
                shizuku = shizuku,
                facts = facts,
                notice = when {
                    !su.granted ->
                        "未拿到 Root。下面的内核模块与授权列表是读不到的空表，不等于机器上没有模块。" +
                            "在 Root 管理器里授予本机 su 后即可显示。"
                    !su.readable -> "Root 已授权，但 /data/adb 读取失败（su 实现或 SELinux 限制）"
                    su.manager != SuManager.KERNELSU && su.granted ->
                        "授权应用列表目前只解析 KernelSU 的 .allowlist；Magisk 的授权表是 SQLite，未读取。"
                    else -> ""
                }
            )
            _state.value = info
        }
    }
}
