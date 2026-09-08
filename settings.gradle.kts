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
// :algorithm = 核心算法模块（纯 Kotlin JVM，零 Android 依赖，可移植 PC）
include(":algorithm")
// :data = Android 数据接入层（MediaStore / SAF / ExifInterface；依赖 :algorithm，仅做平台实现）
include(":data")
// :app = Android UI（Compose 三段式；仅经 AlgorithmApi 单向调用核心）
include(":app")
