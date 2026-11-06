// File: build.gradle.kts
// 顶层构建脚本：只声明插件版本，具体配置在各 module 内。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
