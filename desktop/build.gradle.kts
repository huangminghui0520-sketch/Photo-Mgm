// :desktop —— Photo-Mgm PC 桌面版（Compose for Desktop / JVM）
// 双栏工作台：全局工具栏 + 日志左栏 + 照片右栏 + 设置抽屉
// 约束：UI 仅经 AlgorithmApi 单向调用核心（§2.4/§8）；设置/解析结果/标记自动保存（§8.4）
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation(project(":algorithm"))
    implementation(project(":data"))

    // Compose Desktop（Material3 + Desktop 窗口）
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

compose.desktop {
    application {
        mainClass = "com.photomgm.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "PhotoMgm"
            packageVersion = "1.0.0"
            description = "高速公路巡查照片分类（PC 桌面版）"
            vendor = "Photo-Mgm"
        }

        // 云电脑/无物理显示环境也能以非 headless 启动（Xvfb 下可冒烟验证）
        jvmArgs += listOf("-Djava.awt.headless=false")
    }
}
