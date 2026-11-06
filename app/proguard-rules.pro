# File: app/proguard-rules.pro
#
# 规则刻意保持极少。R8 在 Android 项目里造成的事故绝大多数来自"顺手 keep 一大片"，
# 而这里的反射面本来就小：Compose 与 coroutines 都自带 consumer rules，
# Manifest 里的 Activity/Service/Receiver 由 AGP 自动 keep。
# 真正需要人管的只有下面两件事。

# 崩溃栈必须能反解。本项目最要紧的现场证据就是 Daemon 之外的 Kotlin 崩溃：
# 丢掉行号等于让 bug 报告不可用。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# EntropyStore 是进程级单例，UI、OverlayService、VolumeKeyReceiver 三处都靠
# EntropyStore.get() 拿到同一份 StateFlow。字段被改名不会出错（没有反射），
# 但被裁掉会让"悬浮窗改了档位、主界面不同步"这种逻辑静默失效，
# 所以整类保留成员名，代价只有几百字节映射表。
-keepclassmembers class com.entropy.data.EntropyStore { public *; }
-keepclassmembers class com.entropy.data.Prefs { <methods>; }

# 与 C 侧逐字节对齐的协议编解码：这些类的字段顺序就是二进制契约，
# 一旦 R8 重排/合并字段，Kotlin 侧写出的帧会和 entropy_ipc.h 不一致，
# 表现是"偶尔读到乱码频率"。绝不能让它参与优化。
-keep class com.entropy.shared.Proto$* { *; }
-keepclassmembers class com.entropy.shared.Proto { *; }

# JNI 没有（App 不直接调 native），但 ShellRunner 里的 Runtime.exec 与
# su 交互依赖异常栈信息定位，保留异常类型链。
-keepattributes Exceptions,InnerClasses

# Shizuku 客户端不需要额外规则，理由记下来免得有人再补一遍：
# provider 在 Manifest 里，AGP 自动 keep；BinderContainer 的 Parcelable.Creator
# 由 provider.aar 自带的 consumer rules 保住；IShizukuService 那条链全是直调，
# 库里的反射只指向 android.os.* 这类框架类，不参与裁剪。
