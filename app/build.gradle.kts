// File: app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// build.gradle.kts 里 `java` 是 Gradle 的 java 扩展访问器，会把 java.util 包名遮住，
// 所以必须 import 之后直接用 Properties()
import java.util.Properties

android {
    namespace = "com.entropy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.entropy"
        // Android 7.0 起可用。Compose 最低支持 21，但悬浮窗/媒体会话这类
        // 我们真正依赖的能力在 24 之后才有稳定语义。
        minSdk = 24
        targetSdk = 35
        versionCode = 10303
        versionName = "1.3.3"

        // 更新通道：GitHub 上"哪个仓库的 Releases"算官方源。
        // 公开仓库的 releases/latest 匿名可读，所以应用里不需要带任何 token。
        // 想指到别处（比如 fork 或测试仓库）用 -Pentropy.updateRepo=owner/repo 覆盖，
        // 不改源码 —— 实测"有新版本"那条分支就靠这个。
        buildConfigField(
            "String", "UPDATE_REPO",
            "\"" + providers.gradleProperty("entropy.updateRepo")
                .getOrElse("Lengyou1222/entropy") + "\""
        )

        // 只出 arm 产物：Daemon 是 Native 的，x86 手机上没有对应的调度节点
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // 签名材料读 keystore.properties（与 entropy.keystore 一起被 gitignore）。
    // 读不到就退回 debug 签名，而不是让构建失败：别人 clone 下来照样能出包，
    // 只是那份包不能覆盖安装正式签名的包。
    val releaseSigning = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        val p = Properties().apply { f.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = rootProject.file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true; buildConfig = true }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    // 「我的」页那三段视频走的是 assets.openFd() + setDataSource(fd, offset, length)，
    // 这条路要求文件在 APK 里是原样躺着的。aapt2 默认的免压列表只有 png/so/zip 那一类，
    // mp4 不在里面；一旦被 deflate，openFd 当场抛 "probably compressed"，
    // 那一页就只剩一句解码失败 —— 而且换机器还未必复现，因为压缩结果取决于打包参数。
    androidResources { noCompress += "mp4" }
}

// ------------------------------------------------------------------ //
// Native 产物入 APK                                                     //
//                                                                     //
// Daemon 不是 JNI 库，而是一棵独立的可执行文件树，所以不走 externalNativeBuild：  //
// AGP 只会把 .so 打进 APK，可执行文件要自己搬到 assets 里，由 App 在授权后     //
// 释放到 /data/local/tmp（见 HardwareBridge.stageBinaries）。              //
//                                                                     //
// 构建顺序：scripts/build-daemon.sh 先编出 daemon/out/<abi>/，这里只做搬运。   //
// 目录不存在时任务空跑不报错 —— 没有 native 产物的 APK 仍然可用，App 会退回     //
// Root 直写路径并在界面上如实说明降级。                                       //
// ------------------------------------------------------------------ //
listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
    val stage = tasks.register<Copy>("stageDaemon$abi") {
        group = "entropy"
        description = "复制 $abi 的 native 产物到 assets/native/$abi"
        from(rootProject.file("daemon/out/$abi"))
        include("entropyd", "entropy-monitor", "entropy-freezer", "libentropy_hook.so")
        into(layout.projectDirectory.dir("src/main/assets/native/$abi"))
        duplicatesStrategy = DuplicatesStrategy.FAIL
    }
    // preBuild 是所有编译的入口锚点，挂在它上面能保证 assemble/install 都带上最新二进制
    tasks.named("preBuild").configure { dependsOn(stage) }
}

dependencies {
    // Compose BOM 统一版本，避免 ui/material/foundation 之间版本错配
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    androidTestImplementation(bom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    // 底部导航的七页路由；与 Compose BOM 2024.12.01（compose 1.7.6）匹配
    implementation("androidx.navigation:navigation-compose:2.8.5")
    // 硬件信息与授权列表在 ViewModel 里取，UI 不直接做 IO
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // OverlayService 要作为 SavedStateRegistryOwner 提供给 ComposeView
    implementation("androidx.savedstate:savedstate:1.2.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Shizuku 官方客户端。provider 那个 artifact 只提供一个 ContentProvider 声明
    // （authority ${applicationId}.shizuku），Shizuku 靠它找到本应用、并在自己的权限页里列出来；
    // api 提供 binder 与授权请求。两者都要，只加 api 的话 Shizuku 看不见这个客户端。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
