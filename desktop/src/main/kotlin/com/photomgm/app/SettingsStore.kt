// desktop/.../SettingsStore.kt —— 设置持久化（§8.4 自动保存：设置即存）
// PC 版用 java.util.Properties 写入本地配置文件（用户主目录 ~/.photomgm/settings.properties）
package com.photomgm.app

import java.io.File
import java.time.LocalDate
import java.util.Properties

/** Properties 轻量持久化：日期 / 多源目录 / 输出目录 / 命名模板 / 日志文本 / 标记。
 *  key/value 语义与 Android SharedPreferences 版完全一致，仅存储载体不同。 */
class SettingsStore {
    private val file: File = File(
        System.getProperty("user.home"),
        ".photomgm/settings.properties",
    )
    private val props = Properties()

    init {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists()) file.inputStream().use { props.load(it) }
        }
    }

    private fun get(key: String): String? = props.getProperty(key)
    private fun set(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        flush()
    }

    private fun flush() = runCatching {
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "Photo-Mgm desktop settings") }
    }

    var date: LocalDate
        get() = get(KEY_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()
        set(v) = set(KEY_DATE, v.toString())

    var sourceDirs: List<String>
        get() = get(KEY_DIRS)?.split('|')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = set(KEY_DIRS, v.joinToString("|"))

    var outputDir: String?
        get() = get(KEY_OUTPUT)
        set(v) = set(KEY_OUTPUT, v)

    var namingTemplate: Int
        get() = get(KEY_TEMPLATE)?.toIntOrNull() ?: 1
        set(v) = set(KEY_TEMPLATE, v.toString())

    var logsText: String
        get() = get(KEY_LOGS) ?: ""
        set(v) = set(KEY_LOGS, v)

    /** 人工标记 photoId → 目标事件（§8.3 人工移动） */
    var overrideMap: Map<Long, Int>
        get() = parseOverride(get(KEY_OVERRIDE))
        set(v) = set(KEY_OVERRIDE, v.entries.joinToString(";") { "${it.key}=${it.value}" })

    /** 台账照片标记：eventId → (照片1, 照片2)；空侧用 null 表示"默认最早+最晚"（§7/§8.2） */
    var markedMap: Map<Int, Pair<Long?, Long?>>
        get() = parseMarked(get(KEY_MARKED))
        set(v) = set(KEY_MARKED, v.entries.joinToString(";") { (k, p) -> "${k}=${p.first ?: ""},${p.second ?: ""}" })

    private fun parseOverride(raw: String?): Map<Long, Int> =
        raw?.split(';')?.mapNotNull { seg ->
            val kv = seg.split('=')
            if (kv.size == 2) kv[0].toLongOrNull()?.let { it to (kv[1].toIntOrNull() ?: return@mapNotNull null) }
            else null
        }?.toMap() ?: emptyMap()

    private fun parseMarked(raw: String?): Map<Int, Pair<Long?, Long?>> =
        raw?.split(';')?.mapNotNull { seg ->
            val kv = seg.split('=')
            if (kv.size != 2) return@mapNotNull null
            val ids = kv[1].split(',')
            kv[0].toIntOrNull()?.let { eid ->
                eid to (ids.getOrNull(0)?.toLongOrNull() to ids.getOrNull(1)?.toLongOrNull())
            }
        }?.toMap() ?: emptyMap()

    private companion object {
        const val KEY_DATE = "date"
        const val KEY_DIRS = "source_dirs"
        const val KEY_OUTPUT = "output_dir"
        const val KEY_TEMPLATE = "naming_template"
        const val KEY_LOGS = "logs_text"
        const val KEY_OVERRIDE = "photo_override"
        const val KEY_MARKED = "photo_marked"
    }
}
