// algorithm/EventClassifyEngine.kt —— §6 事件分类模块
// 窗口规则（2026-10-01 改进：补拍回看 + 终点兜底，替代原纯终点归属）：
//   ① 首条特例：交接班（第一条）吸收 [dayStart, 第二条记录时间) —— 交接班到出车之间的照片单独归集到交接班
//   ② 补拍回看：记录时间 r ≤ t 且 t - r ≤ backfillMs（默认 120s）→ 归该事件（记录时刻之后的补拍照不再推给下一事件）
//   ③ 终点兜底：否则照片归到「记录时间 > 拍摄时间」的第一条日志（先拍后记场景保持不变）
//   ④ 末条闭区间（≥ 末条记录时间 → 末条，含跨日加班次日照片）
// 归属流程（§6.2）：① 簇整体按 representativeTime 归窗口 ② 无GPS单张按时间 ③ 其余 → unmatched
// 人工干预提示（§6.3）：GPS_FAR / CLUSTER_SPLIT / TIME_OVERLAP / CLUSTER_SPAN / NO_PHOTO（提示级，不阻断）
package algorithm

import algorithm.model.Cluster
import algorithm.model.LogEvent
import algorithm.model.Photo
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class Kind { GPS_FAR, CLUSTER_SPLIT, TIME_OVERLAP, CLUSTER_SPAN, NO_PHOTO }

data class Intervention(
    val kind: Kind,
    val message: String,
    val photoIds: List<Long>,
    val relatedEventIds: List<Int> = emptyList(),   // ★ 关联事件 id（用于预览页定位核对）
)

data class ClassifyResult(
    val eventPhotoMap: Map<Int, List<Long>>,   // eventId -> photoIds
    val unmatched: List<Long>,
    val interventions: List<Intervention>,
    val pendingEventIds: List<Int>,            // 待定事件（无时间），照片不自动归入
)

class EventClassifyEngine(private val maxDistanceM: Double = 50.0) {
    companion object {
        /** 补拍回看容差：记录时刻之后 backfillMs 内拍摄的照片仍归该事件（默认 120 秒，设 0 退化为纯终点归属）。 */
        const val DEFAULT_BACKFILL_MS = 120_000L
    }

    fun classify(
        events: List<LogEvent>,
        clusters: List<Cluster>,
        singles: List<Photo>,
        patrolDate: LocalDate,
        splitCount: Int = 0,                    // GPS 层自动拆簇次数 → CLUSTER_SPLIT 提示
        backfillMs: Long = DEFAULT_BACKFILL_MS, // 补拍回看容差（毫秒）
    ): ClassifyResult {
        val zone = ZoneId.systemDefault()
        val sorted = events.sortedBy { it.endTimeMs ?: Long.MAX_VALUE }
        val dayStart = patrolDate.atStartOfDay().atZone(zone).toInstant().toEpochMilli()
        val pending = sorted.filter { it.startTimeMs == null }
        val timed = sorted.filter { it.startTimeMs != null }

        val assigned = LinkedHashMap<Int, MutableList<Long>>()
        sorted.forEach { assigned[it.id] = mutableListOf() }
        val unmatched = mutableListOf<Long>()
        val interventions = mutableListOf<Intervention>()

        // ① 簇整体归属
        for (c in clusters) {
            val t = c.representativeTimeMs
            if (t == null) { unmatched.addAll(c.photos.map { it.id }); continue }
            val target = findWindow(t, timed, dayStart, backfillMs)
            if (target != null) {
                assigned[target.id]!!.addAll(c.photos.map { it.id })
                if (c.outlierPhotos.isNotEmpty()) interventions.add(
                    Intervention(Kind.GPS_FAR, "该组照片 GPS 定位相差较大，可能属于不同事件", c.outlierPhotos.map { it.id }))
                // ★ 簇内最早/最晚照片跨到不同事件 → 提示（簇可能黏连多个事件）
                if (c.photos.size > 1) {
                    val times = c.photos.mapNotNull { it.captureTimeMs }
                    if (times.isNotEmpty()) {
                        val eFirst = findWindow(times.min(), timed, dayStart, backfillMs)
                        val eLast = findWindow(times.max(), timed, dayStart, backfillMs)
                        if (eFirst != null && eLast != null && eFirst.id != eLast.id) {
                            interventions.add(Intervention(
                                Kind.CLUSTER_SPAN,
                                "该组照片时间跨度跨越事件「${eFirst.eventType} → ${eLast.eventType}」，可能包含多个事件，请核对",
                                c.photos.map { it.id }, listOf(eFirst.id, eLast.id)))
                        }
                    }
                }
            } else unmatched.addAll(c.photos.map { it.id })
        }
        // ② 无 GPS 单张按时间
        for (p in singles) {
            val t = p.captureTimeMs
            if (t == null) { unmatched.add(p.id); continue }
            val target = findWindow(t, timed, dayStart, backfillMs)
            if (target != null) assigned[target.id]!!.add(p.id) else unmatched.add(p.id)
        }
        // 时间穿插提示（后一条 start < 前一条 endTime）+ 间隔过近提示（间隔 < 2×backfillMs）
        // ★ 增强：消息明确标注穿插的两个事件（编号/类型/时间区间/地点），供预览页人工核对
        for (i in 1 until timed.size) {
            val prev = timed[i - 1]; val cur = timed[i]
            val prevEnd = prev.endTimeMs ?: prev.startTimeMs!!
            val curStart = cur.startTimeMs!!
            val curEnd = cur.endTimeMs ?: cur.startTimeMs!!
            if (curStart < prevEnd) {
                interventions.add(
                    Intervention(
                        Kind.TIME_OVERLAP,
                        "事件时间穿插：${evtInfo(prev)} 与 ${evtInfo(cur)} 时间重叠，请人工核对照片归属",
                        assigned[cur.id]!!,
                        relatedEventIds = listOf(prev.id, cur.id),
                    ))
            } else if (curEnd - prevEnd < backfillMs * 2) {
                interventions.add(
                    Intervention(
                        Kind.TIME_OVERLAP,
                        "事件时间过近：${evtInfo(prev)} 与 ${evtInfo(cur)} 间隔不足 ${backfillMs * 2 / 1000} 秒，照片归属易混淆，请核对",
                        emptyList(),
                        relatedEventIds = listOf(prev.id, cur.id),
                    ))
            }
        }
        // 事件内多簇中心离散提示（CLUSTER_SPAN）
        for (e in timed) {
            val mine = clusters.filter { c -> c.photos.any { assigned[e.id]!!.contains(it.id) } }
            for (i in 0 until mine.size) for (j in i + 1 until mine.size) {
                if (clusterDistance(mine[i], mine[j]) > maxDistanceM * 1.5) {
                    interventions.add(Intervention(Kind.CLUSTER_SPAN,
                        "事件「${e.eventType}」照片 GPS 跨度大，请核对",
                        (mine[i].photos + mine[j].photos).map { it.id }.distinct()))
                }
            }
        }
        // 自动拆簇提示（§6.3 承诺、§6.4 遗漏 → 完善补回）
        if (splitCount > 0) interventions.add(
            Intervention(Kind.CLUSTER_SPLIT, "已按时间自动拆分 $splitCount 组，请核对", emptyList()))
        // 事件无照片提示（日志提到拍照/取证但照片数为 0 → 可能被误归其他事件）
        for (e in timed) {
            val d = e.description
            if (assigned[e.id]!!.isEmpty() && (d.contains("拍照") || d.contains("取证"))) {
                interventions.add(Intervention(
                    Kind.NO_PHOTO,
                    "事件「${evtInfo(e)}」日志提到拍照/取证但无照片，可能被误归其他事件，请核对",
                    emptyList(), listOf(e.id)))
            }
        }
        // 待定事件提示（人工指定时间后重算）
        if (pending.isNotEmpty()) interventions.add(
            Intervention(
                Kind.TIME_OVERLAP,
                "存在 ${pending.size} 条未记录时间的事件（${pending.map { "事件#${it.id} ${it.eventType}" }.joinToString("、")}），请人工指定时间",
                emptyList(),
                relatedEventIds = pending.map { it.id },
            ))

        return ClassifyResult(
            assigned.mapValues { it.value.toList() },
            unmatched, interventions, pending.map { it.id })
    }

    /**
     * 窗口归属（2026-10-01 改进：补拍回看 + 终点兜底）：
     *   - 首条（交接班）吸收 [dayStart, 第二条记录时间)：交接班到出车之间的照片单独归集到交接班
     *   - 补拍回看：最近的记录时间 r ≤ t 且 t - r ≤ backfillMs → 归该事件（记录时刻之后的补拍照归回原事件，
     *     修复「记录后 1 秒~几分钟的取证照被推给下一事件」的错位；backfillMs=0 退化为纯终点归属）
     *   - 终点兜底：照片归到「记录时间 > 拍摄时间」的第一条日志（先拍后记场景保持原行为）
     *   - 末条闭区间：t ≥ 末条记录时间 → 末条（含跨日加班次日照片）
     *   - t < dayStart（异常早）→ null（unmatched）
     */
    private fun findWindow(t: Long, events: List<LogEvent>, dayStart: Long, backfillMs: Long): LogEvent? {
        if (t < dayStart) return null
        val second = events.getOrNull(1)?.let { it.endTimeMs ?: it.startTimeMs!! }
        if (second != null && t < second) return events[0]
        // 补拍回看：记录时间 ≤ t 的最近一条事件（events 已按记录时间升序）
        var last: Pair<LogEvent, Long>? = null
        for (e in events) {
            val r = e.endTimeMs ?: e.startTimeMs!!
            if (r <= t) {
                last = e to r
            } else {
                val lb = last
                if (lb != null && t - lb.second <= backfillMs) return lb.first
                return e   // 终点兜底：第一条「记录时间 > 拍摄时间」的日志
            }
        }
        // 末条闭区间：全部记录时间 ≤ t
        val lb = last
        return if (lb != null && t - lb.second <= backfillMs) lb.first else events.last()
    }

    /** 事件摘要（编号/类型/时间区间/地点），用于干预提示明确标注核对对象。 */
    private fun evtInfo(e: LogEvent): String {
        val zone = ZoneId.systemDefault()
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        fun t(ms: Long?): String = ms?.let {
            java.time.Instant.ofEpochMilli(it).atZone(zone).format(fmt)
        } ?: "无时间"
        val loc = e.location?.takeIf { it.isNotBlank() } ?: "未定位"
        return "事件#${e.id} ${e.eventType} ${t(e.startTimeMs)}~${t(e.endTimeMs)} $loc"
    }

    /** 两簇中心距离（哈弗辛）；任一中心缺失 → MAX_VALUE（提示跨度大）。 */
    private fun clusterDistance(a: Cluster, b: Cluster): Double {
        val lat1 = a.centerLat ?: return Double.MAX_VALUE
        val lng1 = a.centerLng ?: return Double.MAX_VALUE
        val lat2 = b.centerLat ?: return Double.MAX_VALUE
        val lng2 = b.centerLng ?: return Double.MAX_VALUE
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return R * 2 * atan2(sqrt(h), sqrt(1 - h))
    }
}
