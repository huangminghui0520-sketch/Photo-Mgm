// app/SettingsStore.kt —— 设置持久化（§8.4 自动保存：设置即存）
package com.photomgm.app

import android.content.Context
import java.time.LocalDate

/** SharedPreferences 轻量持久化：日期 / 多源目录 / 输出目录 / 命名模板 / 日志文本 / 标记。 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("photo_mgm_settings", Context.MODE_PRIVATE)

    var date: LocalDate
        get() = prefs.getString(KEY_DATE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()
        set(v) = prefs.edit().putString(KEY_DATE, v.toString()).apply()

    var sourceDirs: List<String>
        get() = prefs.getStringSet(KEY_DIRS, emptySet())?.toList() ?: emptyList()
        set(v) = prefs.edit().putStringSet(KEY_DIRS, v.toSet()).apply()

    var outputDir: String?
        get() = prefs.getString(KEY_OUTPUT, null)
        set(v) = prefs.edit().putString(KEY_OUTPUT, v).apply()

    var namingTemplate: Int
        get() = prefs.getInt(KEY_TEMPLATE, 1)
        set(v) = prefs.edit().putInt(KEY_TEMPLATE, v).apply()

    var logsText: String
        get() = prefs.getString(KEY_LOGS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LOGS, v).apply()

    /** 人工标记 photoId → 目标事件（§8.3 人工移动） */
    var overrideMap: Map<Long, Int>
        get() = prefs.getString(KEY_OVERRIDE, null)?.let { raw ->
            runCatching {
                raw.split(';').mapNotNull { seg ->
                    val kv = seg.split('=')
                    if (kv.size == 2) kv[0].toLong() to kv[1].toInt() else null
                }.toMap()
            }.getOrElse { emptyMap() }
        } ?: emptyMap()
        set(v) = prefs.edit()
            .putString(KEY_OVERRIDE, v.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()

    /** 台账照片标记：eventId → (照片1, 照片2)；空侧用 null 表示"默认最早+最晚"（§7/§8.2） */
    var markedMap: Map<Int, Pair<Long?, Long?>>
        get() = prefs.getString(KEY_MARKED, null)?.let { raw ->
            runCatching {
                raw.split(';').mapNotNull { seg ->
                    val kv = seg.split('=')
                    if (kv.size != 2) return@mapNotNull null
                    val ids = kv[1].split(',')
                    kv[0].toInt() to (ids.getOrNull(0)?.toLongOrNull() to ids.getOrNull(1)?.toLongOrNull())
                }.toMap()
            }.getOrElse { emptyMap() }
        } ?: emptyMap()
        set(v) = prefs.edit().putString(
            KEY_MARKED,
            v.entries.joinToString(";") { (k, p) -> "${k}=${p.first ?: ""},${p.second ?: ""}" }
        ).apply()

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
