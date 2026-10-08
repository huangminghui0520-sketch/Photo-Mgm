// algorithm/LedgerEngine.kt —— §7 台账与导出模块
// 台账字段（导出Excel 2.0 方案）：日期(yyyy-MM-dd HH:mm 开始时间)/地点(桩号优先)/日志内容/照片1、2
//
// 照片1/照片2 的取值规则（2026-10-07 修订）：
//   1. 人工标记优先：marked[eventId].first → 照片1、.second → 照片2
//   2. 标记为空的那一侧回退到默认：照片1 = 事件内拍摄时间最早，照片2 = 最晚
//   3. 仅有拍摄时间的照片参与"最早/最晚"选择——时间未知的照片无法定位时序；
//      若事件内全部照片都没有时间，才退回按列表顺序取首/末
//
// ★ 修订说明：原实现没有 marked 入参，且注释写着"无标记照片概念"，
//   导致 UI 上「设为台账」有角标反馈、导出却静默忽略标记。现按 SettingsStore
//   既有语义（null 侧=恢复默认）接通。
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
     *
     * @param events 事件列表（已解析的巡查日志）
     * @param eventPhotoMap eventId -> photoIds（来自 §6 分类结果，应已合并人工移动）
     * @param photoById 照片解析回调；返回 null 的照片跳过
     * @param marked 人工台账标记：eventId -> (照片1, 照片2)；某一侧为 null 表示该侧用默认（最早/最晚）
     */
    fun buildLedger(
        events: List<LogEvent>,
        eventPhotoMap: Map<Int, List<Long>>,
        photoById: (Long) -> Photo?,
        marked: Map<Int, Pair<Long?, Long?>> = emptyMap(),
    ): List<LedgerRow> {
        return events.sortedBy { it.endTimeMs ?: Long.MAX_VALUE }.map { e ->
            // 按分类结果顺序解析照片（保留列表顺序，作为"全无时间"时的兜底次序）
            val photos = (eventPhotoMap[e.id] ?: emptyList()).mapNotNull { photoById(it) }
            val (p1, p2) = pickLedgerPhotos(photos, marked[e.id])

            LedgerRow(
                dateTime = formatDateTime(e),
                location = e.location,
                description = e.description,
                photo1Ref = p1?.sourceRef,
                photo2Ref = p2?.sourceRef,
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

        /**
         * 选出该事件的照片1/照片2。
         *
         * @param photos 该事件的照片（保持分类结果顺序）
         * @param mark 人工标记 (照片1, 照片2)；null 表示未标记
         */
        fun pickLedgerPhotos(photos: List<Photo>, mark: Pair<Long?, Long?>?): Pair<Photo?, Photo?> {
            if (photos.isEmpty()) return null to null

            val byId = photos.associateBy { it.id }
            // 人工标记：只接受确实属于该事件的照片 id，其余（失效/跨事件）忽略并回退默认
            val markedP1 = mark?.first?.let { byId[it] }
            val markedP2 = mark?.second?.let { byId[it] }

            // 自动候选：仅有拍摄时间的照片能参与"最早/最晚"
            val timed = photos.filter { it.captureTimeMs != null }
                .sortedBy { it.captureTimeMs }

            val autoP1: Photo?
            val autoP2: Photo?
            if (timed.isNotEmpty()) {
                autoP1 = timed.first()
                autoP2 = timed.last()
            } else {
                // 该事件内全部照片都没有拍摄时间：退回列表顺序（此时"最早/最晚"无意义）
                autoP1 = photos.first()
                autoP2 = photos.last()
            }

            return (markedP1 ?: autoP1) to (markedP2 ?: autoP2)
        }
    }
}
