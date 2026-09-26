// Photo-Mgm 工程设置
// 国内网络环境：所有远程仓库一律走阿里云镜像（优先），禁止 google()/mavenCentral()/gradlePluginPortal() 原生地址
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        mavenLocal()
    }
}

rootProject.name = "Photo-Mgm"
// :algorithm = 核心算法模块（纯 Kotlin JVM，零平台依赖，Android/PC 共用）
include(":algorithm")
// :data = 桌面数据接入层（文件系统扫描 / metadata-extractor EXIF / 缓存；依赖 :algorithm，仅做平台实现）
include(":data")
// :desktop = PC 桌面 UI（Compose Desktop 双栏工作台；仅经 AlgorithmApi 单向调用核心）
include(":desktop")
