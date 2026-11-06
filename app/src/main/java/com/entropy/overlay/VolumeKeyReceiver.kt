// File: app/src/main/java/com/entropy/overlay/VolumeKeyReceiver.kt
//
// 媒体键（含有线耳机/蓝牙遥控上的播放暂停）唤出控制台。
//
// 为什么它不能截获音量键：
//  ACTION_MEDIA_BUTTON 只有在应用是"活跃的媒体会话"时才会送过来，
//  而熵不播音乐，永远不会成为活跃会话。所以这里只处理真正会到达的媒体播放键；
//  音量键的开合检测走 OverlayService 里的 SettingsObserver 那条合法通道。
//  把这个 receiver 写成"音量键拦截"是不诚实的，UI 文案也不会这么承诺。
package com.entropy.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

class VolumeKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        // API 33 起 getParcelableExtra(String, Class) 才不吞类型；旧设备只能走被标记
        // deprecated 的那一个重载 —— 这不是可省的兼容代码，minSdk 是 24。
        val ev: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        if (ev == null) return
        // 只认 up 且非重复：down/up 都处理会一次唤出两次
        if (ev.keyCode != KEYCODE_MEDIA_PLAY_PAUSE || ev.action != KeyEvent.ACTION_UP || ev.repeatCount > 0)
            return
        // 播放/暂停是别人的音乐在放，截了它就是劫持用户；
        // 只有在没有媒体会话占用时才把它当作"唤出控制台"的手势。
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (am.isMusicActive) return
        OverlayService.toggle(context)
    }
}
