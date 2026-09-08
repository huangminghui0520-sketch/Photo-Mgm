// data/ExifTimeParser.kt —— EXIF 时间字符串解析（纯函数，JVM 可单测）
// ExifInterface 的时间格式为 "yyyy:MM:dd HH:mm:ss"（冒号分隔）；部分设备/工具写入 "yyyy-MM-dd HH:mm:ss"
package com.photomgm.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ExifTimeParser {
    private val COLON = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val DASH = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 解析 EXIF 时间文本 → epoch 毫秒（含日期，@param zone 用于本地时区）；失败返回 null。 */
    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val s = text.trim()
        if (s.isEmpty()) return null
        return runCatching {
            // 按日期分隔符判断：位置 4 是 ':'（yyyy:MM:dd）或 '-'（yyyy-MM-dd）；时间部分的冒号不可作判据
            val fmt = if (s.length > 4 && s[4] == ':') COLON else DASH
            LocalDateTime.parse(s, fmt).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
