# Photo-Mgm · 高速公路巡查照片分类 App

面向路政巡查场景的照片分类工具：根据巡查日志（含时间、事件、地点），结合照片 GPS 与拍摄时间，自动将照片归类到对应巡查事件，并支持人工调整、台账导出与压缩包交付。

## 模块结构

| 模块 | 职责 | 依赖 |
|------|------|------|
| `:algorithm` | 核心算法（纯 Kotlin JVM，零 Android 依赖，可移植 PC/飞牛OS） | 无 |
| `:data` | Android 数据接入层（MediaStore / SAF / ExifInterface） | `:algorithm` |
| `:app` | Android UI（Compose Material3，仅经 `AlgorithmApi` 单向调用核心） | `:algorithm`, `:data` |

## 核心算法能力（:algorithm）

- 日志解析：时间 / 地点（桩号+方向）/ 事件类型 / 巡查人员 / 车牌，黄金样本 `收费公路巡查日志`
- GPS 分组：50 米内合并为一组，离群拆簇，跨日按完整时间戳
- 事件分类：按日志时间窗口把照片归入事件（首条日志起点归属 / 后续终点归属），支持人工移动兜底
- 台账生成：每事件一行（日期/地点/日志内容/照片首张+末张），导出 Excel（OOXML 手写，无第三方依赖）
- 压缩包导出：按「事件类型」或「日期+事件类型+日志内容」建文件夹，照片 + 台账 Excel 打包 ZIP

## 构建

国内网络环境，所有远程仓库已配置阿里云/腾讯云镜像，无需额外配置。

```bash
# 单元测试（algorithm 209 用例）
gradle :algorithm:test

# 构建 APK（debug / release 均已启用 R8 混淆与资源裁剪）
gradle :app:assembleDebug
gradle :app:assembleRelease
```

产物：`app/build/outputs/apk/debug/app-debug.apk` 或 `app/build/outputs/apk/release/app-release.apk`

> 正式签名：release 构建会读取本地 `keystore/keystore.properties`（不入库）；缺失时回退 debug 签名，保证任何环境可构建。

## 使用

完整操作流程见 [docs/USAGE.md](docs/USAGE.md)。

## 技术栈

- Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.10.2（wrapper 走腾讯云镜像）
- Jetpack Compose Material3 · Coil · SharedPreferences 持久化
- Android minSdk 26 / targetSdk 34 · Java 17
