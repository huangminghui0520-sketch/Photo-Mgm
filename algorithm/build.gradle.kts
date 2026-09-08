// :algorithm 核心算法模块 —— 纯 Kotlin JVM（零 Android 依赖），可移植 PC / 飞牛OS
plugins {
    kotlin("jvm")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
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

// 日志解析诊断入口：gradle :algorithm:runLogParser
// 验证自己的日志：--args="--file=D:/path/log.txt"
tasks.register<JavaExec>("runLogParser") {
    group = "verification"
    description = "运行日志解析诊断，输出解析报告（默认黄金样本，--file= 可指定日志文件）"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("algorithm.LogParserReport")
}
