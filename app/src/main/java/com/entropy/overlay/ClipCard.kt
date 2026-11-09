// File: app/src/main/java/com/entropy/overlay/ClipCard.kt
//
// 「我的」页那块影像：一段循环播放的视频，没有标题，也没有按钮。
//
// 三个不是随手写下的决定：
//
// 1) 视频在 APK 的 assets 里，读法是 AssetFileDescriptor + setDataSource(fd, offset, length)。
//    这条路要求 mp4 在包里是原样存放的，所以 app/build.gradle.kts 专门给它免了压
//    （aapt2 默认免压列表里只有 png/so/zip 那一类，mp4 不在其中）。万一哪天免压被去掉，
//    openFd 会直接抛"probably compressed"，这里接住并把原因显示出来，不会静默留一块黑的。
//
// 2) 画在 TextureView 而不是 SurfaceView 上。SurfaceView 是在窗口后面另开一层、
//    在这块窗上打一个洞，浮窗里那个洞会把底下的游戏画面透出来 —— 看着就像播放器坏了。
//    TextureView 走正常视图层，跟着窗口一起合成，圆角与半透明都照常生效。
//
// 3) 没有开关，也就没有状态：进来就播，播完一轮接着下一轮。
//    放不出来就说放不出来，跟温度那套"读不到就写读不到"是同一条规矩。
package com.entropy.overlay

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.entropy.ui.theme.LocalEntropyColors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 三段素材。不标给用户看的名字：这一块从头到尾没有说过它是哪一段，
 * 上一次抽到哪段、这段拍的是什么，都不该出现在界面上。
 */
private val Clips = listOf(
    "videos/clip_1.mp4",
    "videos/clip_2.mp4",
    "videos/clip_3.mp4",
)

/**
 * 三段素材都是 854x480，画面框按 16:9 给：
 * TextureView 默认把解码出来的帧拉满视图尺寸，框的比例错了就是变形。
 */
private val ClipRatio = 16f / 9f

/**
 * 轮到第几段。放在文件级而不是 rememberSaveable 里：
 * 这个游标的意义是"下一次进来是另一段"，进程活着就接着往下数，
 * 存进 sp 反而会让用户以为顺序被记住了 —— 它没有，也不需要。
 */
private var cursor = 0

/**
 * 一次只显示一段，按 1 → 2 → 3 → 1 的顺序轮。
 *
 * 不随机：随机的话连着两次进来可能是同一段，看起来像"换不了"，
 * 而轮着来是有规律的，翻几页就能自己发现规律。
 * 换段发生在重新进这一页的时候（AnimatedContent 离场那一帧复用旧组合，
 * remember 不会重跑，所以切页签不算翻篇）。
 */
@Composable
internal fun ClipCard() {
    val ctx = LocalContext.current
    val entropy = LocalEntropyColors.current
    val path = remember { Clips[cursor++ % Clips.size] }
    var note by remember { mutableStateOf<String?>(null) }
    val surfaceOf = remember { mutableStateOf<Surface?>(null) }
    val player = remember { MediaPlayer() }
    // prepareAsync 的回调是 native 层-posted 的：onDispose 跑完之后它照样会到一次。
    // 没有这道闸，回调里的 start() 就打在已经 release 的播放器上 —— 那是 native 崩溃，
    // Java 侧的 runCatching 接不住。用 AtomicBoolean 而不是快照状态：它不在组合里读，
    // 不该为一次生命周期标记建立重组依赖。
    val released = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        player.setOnPreparedListener { if (!released.get()) runCatching { it.start() } }
        player.setOnErrorListener { _, what, extra ->
            if (!released.get()) note = "这段解码失败（what=$what extra=$extra）"
            true
        }
        onDispose {
            released.set(true)
            runCatching {
                player.setOnPreparedListener(null)
                player.setOnErrorListener(null)
                player.setSurface(null)
                player.reset()
                player.release()
            }
            surfaceOf.value?.let { s -> runCatching { s.release() } }
            surfaceOf.value = null
        }
    }

    LaunchedEffect(path, surfaceOf.value) {
        val s = surfaceOf.value
        if (s == null) {
            // 切页签或收起面板会销毁纹理。播放器不知道这件事，会继续解码并往一个死表面上
            // 推帧：画面没了，解码器和那份功耗还在。停在这里，等表面回来再重新 prepare。
            runCatching { player.stop() }
            return@LaunchedEffect
        }
        note = null
        runCatching {
            player.reset()
            ctx.assets.openFd(path).use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            player.setSurface(s)
            player.isLooping = true
            player.prepareAsync()
        }.onFailure {
            note = "这段没播出来：${it.message ?: it.javaClass.simpleName}"
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ClipRatio)
            .clip(RoundedCornerShape(10.dp))
            .background(entropy.onScrim.copy(alpha = 0.06f))
            .border(1.dp, entropy.onScrim.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
    ) {
        AndroidView(modifier = Modifier.matchParentSize(), factory = { c ->
            TextureView(c).apply {
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        surfaceOf.value = Surface(st)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        // 返回 true 是把 SurfaceTexture 交回给 TextureView 自己回收，
                        // 那我们包出来的那个 Surface 就得在这一刻自己放掉：
                        // 不放的话每翻一次页就漏一个 native 表面，翻几十次就是几十份显存。
                        surfaceOf.value?.let { s -> runCatching { s.release() } }
                        surfaceOf.value = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        })
        note?.let {
            Text(it, color = entropy.warn, fontSize = 9.sp, maxLines = 2,
                modifier = Modifier.padding(9.dp))
        }
    }
}
