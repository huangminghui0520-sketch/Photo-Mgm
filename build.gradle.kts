// Photo-Mgm 根工程：仅做插件版本统一声明，不含任何实际构建逻辑
// PC 桌面版（Compose for Desktop / JVM）——原 Android 三个模块改造为桌面工程
plugins {
    // Kotlin 版本全局统一；Kotlin 插件本体从阿里云 gradle-plugin 镜像解析
    kotlin("jvm") version "2.0.21" apply false
    // Compose Compiler（Kotlin 2.0 起随 Kotlin 版本走）
    kotlin("plugin.compose") version "2.0.21" apply false
    // Compose Multiplatform / Desktop 插件（从阿里云镜像解析）
    id("org.jetbrains.compose") version "1.7.0" apply false
}
