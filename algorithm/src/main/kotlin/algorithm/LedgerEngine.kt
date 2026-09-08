// algorithm/LedgerEngine.kt —— §7 台账与导出模块
// 台账字段（导出Excel 2.0 方案）：日期(yyyy-MM-dd HH:mm 开始时间)/地点(桩号优先)/日志内容/照片1、2(第一张+最后一张)
// ★ 按方案：无标记照片概念，照片固定取事件内最早+最晚，不做特殊处理。
package algorithm

import algorithm.model.LedgerRow
import algorithm.model.LogEvent
import algorithm.model.Photo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LedgerEngine {
    /**
     * 生成台账行（每事件一行）。
     * @param eventPhotoMap eventId -> photoIds（来自 §6 分类结果）
     * @param photoById 照片解析回调（Android=Room，PC=文件列表），返回 null 的照片跳过
     */
    fun buildLedger(
        events: List<LogEvent>,
        eventPhotoMap: Map<Int, List<Long>>,
        photoById: (Long) -> Photo?,
    ): List<LedgerRow> {
        return events.sortedBy { it.endTimeMs ?: Long.MAX_VALUE }.map { e ->
            val photos = (eventPhotoMap[e.id] ?: emptyList())
                .mapNotNull { photoById(it) }.sortedBy { it.captureTimeMs ?: Long.MAX_VALUE }
            LedgerRow(
                dateTime = formatDateTime(e),
                location = e.location,
                description = e.description,
                photo1Ref = photos.firstOrNull()?.sourceRef,   // 第一张（最早）
                photo2Ref = photos.lastOrNull()?.sourceRef,     // 最后一张（最晚）
            )
        }
    }

    /** 日期列：事件开始时间 → "yyyy-MM-dd HH:mm"（含巡查日期；无开始时间 → 空串）。 */
    private fun formatDateTime(e: LogEvent): String {
        val s = e.startTimeMs ?: return ""
        return Instant.ofEpochMilli(s).atZone(ZoneId.systemDefault()).format(DATE_TIME)
    }

    private companion object {
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
