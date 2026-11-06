// File: app/src/main/java/com/entropy/EntropyApp.kt
package com.entropy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.entropy.data.EntropyStore
import com.entropy.hardware.ShizukuBridge

class EntropyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 通知渠道提前建好：OverlayService 前台化时如果没有渠道会直接崩溃（Android 8+）
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            OVERLAY_CHANNEL,
            getString(R.string.overlay_channel),
            // LOW：悬浮控制台的通知不该发声，它本来就是常驻的
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.overlay_channel_desc) })

        // HUD 的常驻通知：同样是 LOW，它只是"服务活着"的凭证，不该出声
        nm.createNotificationChannel(NotificationChannel(
            HUD_CHANNEL,
            getString(R.string.hud_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.hud_channel_desc) })

        // 提醒的意义在于打断，LOW 渠道连声音都给不了，所以单独一条 HIGH。
        // 是否发声/震动由用户在系统通知设置里改，改完能立刻生效 ——
        // 代码里写 enableVibration 的话，用户想静音就得整个功能关掉。
        nm.createNotificationChannel(NotificationChannel(
            HUD_ALERT_CHANNEL,
            getString(R.string.hud_alert_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = getString(R.string.hud_alert_channel_desc) })

        // Shizuku 的监听要在这里就挂上：provider 送达 binder 的时机比第一块 UI 还早，
        // 晚注册就只能等下一次 binder 事件，用户会看到"Shizuku 明明开着，熵却说没连上"。
        // 必须在 EntropyStore 起来之前：store 的初始 detect() 要靠这份状态认出 SHIZUKU 档。
        ShizukuBridge.init(this)

        EntropyStore.get(this).apply { start(); startDaemon() }
    }

    companion object {
        const val OVERLAY_CHANNEL = "entropy_overlay"
        const val HUD_CHANNEL = "entropy_hud"
        const val HUD_ALERT_CHANNEL = "entropy_hud_alert"
        const val RECORD_CHANNEL = "entropy_record"
    }
}
