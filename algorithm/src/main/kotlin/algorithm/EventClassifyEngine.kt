// algorithm/EventClassifyEngine.kt —— §6 事件分类模块
// 窗口规则（★ 终点归属 + 首条特例，用户定稿）：
//   通用：事件B 照片 = [记录A, 记录B) → 归事件B（终点归属，照片归到「记录时间 > 拍摄时间」的第一条日志）
//   首条特例：交接班（第一条）吸收 [dayStart, 第二条记录时间) —— 交接班到出车之间的照片单独归集到交接班
//   末条闭区间（≥ 末条记录时间 → 末条，含跨日加班次日照片）
// 归属流程（§6.2）：① 簇整体按 representativeTime 归窗口 ② 无GPS单张按时间 ③ 其余 → unmatched
// 人工干预提示（§6.3）：GPS_FAR / CLUSTER_SPLIT / TIME_OVERLAP / CLUSTER_SPAN（提示级，不阻断）
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

enum class Kind { GPS_FAR, CLUSTER_SPLIT, TIME_OVERLAP, CLUSTER_SPAN }

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
    fun classify(
        events: List<LogEvent>,
        clusters: List<Cluster>,
        singles: List<Photo>,
        patrolDate: LocalDate,
        splitCount: Int = 0,                    // GPS 层自动拆簇次数 → CLUSTER_SPLIT 提示
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
            val target = findWindow(t, timed, dayStart)
            if (target != null) {
                assigned[target.id]!!.addAll(c.photos.map { it.id })
                if (c.outlierPhotos.isNotEmpty()) interventions.add(
                    Intervention(Kind.GPS_FAR, "该组照片 GPS 定位相差较大，可能属于不同事件", c.outlierPhotos.map { it.id }))
            } else unmatched.addAll(c.photos.map { it.id })
        }
        // ② 无 GPS 单张按时间
        for (p in singles) {
            val t = p.captureTimeMs
            if (t == null) { unmatched.add(p.id); continue }
            val target = findWindow(t, timed, dayStart)
            if (target != null) assigned[target.id]!!.add(p.id) else unmatched.add(p.id)
        }
        // 时间穿插提示（后一条 start < 前一条 endTime）
        // ★ 增强：消息明确标注穿插的两个事件（编号/类型/时间区间/地点），供预览页人工核对
        for (i in 1 until timed.size) {
            val prev = timed[i - 1]; val cur = timed[i]
            val prevEnd = prev.endTimeMs ?: prev.startTimeMs!!
            if (cur.startTimeMs!! < prevEnd) interventions.add(
                Intervention(
                    Kind.TIME_OVERLAP,
                    "事件时间穿插：${evtInfo(prev)} 与 ${evtInfo(cur)} 时间重叠，请人工核对照片归属",
                    assigned[cur.id]!!,
                    relatedEventIds = listOf(prev.id, cur.id),
                ))
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
     * 窗口归属（★ 终点归属 + 首条特例，用户定稿）：
     *   - 首条（交接班）吸收 [dayStart, 第二条记录时间)：交接班到出车之间的照片单独归集到交接班
     *   - 通用终点归属：照片归到「记录时间 > 拍摄时间」的第一条日志（事件B = [记录A, 记录B) 归事件B）
     *   - 末条闭区间：t ≥ 末条记录时间 → 末条（含跨日加班次日照片）
     *   - t < dayStart（异常早）→ null（unmatched）
     */
    private fun findWindow(t: Long, events: List<LogEvent>, dayStart: Long): LogEvent? {
        if (t < dayStart) return null
        val second = events.getOrNull(1)?.let { it.endTimeMs ?: it.startTimeMs!! }
        if (second != null && t < second) return events[0]
        for (e in events) {
            val r = e.endTimeMs ?: e.startTimeMs!!
            if (t < r) return e
        }
        return events.last()
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
