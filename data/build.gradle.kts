// :data 桌面数据接入层（§4.4 PC 版）
// 职责：文件系统扫描候选 + 目录白名单 + EXIF 精读(metadata-extractor) + 内存缓存；
//       仅做平台实现，算法逻辑全在 :algorithm
plugins {
    kotlin("jvm")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation(project(":algorithm"))
    // EXIF 精读（§4.3）：metadata-extractor 纯 Java 库（阿里云镜像可解析）
    implementation("com.drewnoakes:metadata-extractor:2.19.0")

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
