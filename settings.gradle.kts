// Photo-Mgm 工程设置
// 仓库源按环境切换（注：pluginManagement/dependencyResolutionManagement 块内不能引用脚本局部变量，
// 故直接内联判断）：
//   - 本地/国内：走阿里云镜像（默认，国内网络直连原生地址慢/不可达）
//   - CI（GitHub Actions，自动注入 CI=true）：走 google()/mavenCentral()/gradlePluginPortal() 原生源，
//     美国 runner 直连原生源又快又稳（阿里云镜像对海外访问不稳定，曾导致构建失败）
pluginManagement {
    repositories {
        if (System.getenv("CI") == "true") {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") == "true") {
            google()
            mavenCentral()
        } else {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }
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
