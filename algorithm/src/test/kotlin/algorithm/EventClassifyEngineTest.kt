// algorithm/EventClassifyEngineTest.kt —— §6 事件分类模块测试
// 契约（★ 终点归属 + 首条特例，用户定稿）：
//   通用：事件B 照片 = [记录A, 记录B) → 归事件B（终点归属）；
//   首条（交接班）吸收 [dayStart, 第二条记录时间)（交接班到出车之间单独归集到交接班）；
//   末条闭区间；无时间事件进待定区不自动归；单张按时间；§6.3 干预提示
package algorithm

import algorithm.model.Cluster
import algorithm.model.LogEvent
import algorithm.model.Photo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class EventClassifyEngineTest {
    private val engine = EventClassifyEngine()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 9, 3)
    private fun at(h: Int, m: Int) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun ev(id: Int, start: Long, end: Long, type: String = "事件$id") =
        LogEvent(id, start, end, listOf(start, end), "K1+000", type, "描述$id")
    private fun photo(id: Long, t: Long?, lat: Double = 23.0, lng: Double = 113.0) =
        Photo(id, "s$id", "img$id.jpg", t, lat, lng)
    private fun cluster(id: Long, t: Long, lat: Double = 23.0, lng: Double = 113.0, outlier: List<Long> = emptyList()) =
        Cluster(listOf(photo(id, t, lat, lng)), t, lat, lng, outlier.map { photo(it, t, lat, lng) })

    // ---- 探针测试（终点归属） ----

    @Test fun `终点归属_记录A到记录B之间的照片归事件B`() {
        // 三事件：E1 记录=08:00，E2 记录=08:15，E3 记录=09:00；照片 08:45 ∈ [08:15,09:00) → 归 E3（事件B）
        val e1 = ev(1, at(8, 0), at(8, 0), "交接班")
        val e2 = ev(2, at(8, 15), at(8, 15), "出车")
        val e3 = ev(3, at(9, 0), at(9, 0), "事件X")
        val c = cluster(11, at(8, 45))
        val r = engine.classify(listOf(e1, e2, e3), listOf(c), emptyList(), date)
        assertTrue(r.eventPhotoMap[3]!!.contains(11L), "08:45 在出车记录与事件X记录之间 → 归事件X（终点归属）")
        assertTrue(r.eventPhotoMap[1]!!.isEmpty())
        assertTrue(r.eventPhotoMap[2]!!.isEmpty())
    }

    @Test fun `边界_照片时间等于本条记录时间归本条之后的下一事件_半开`() {
        // 照片 08:15 == E2 记录时间 → [08:15,09:00) 半开 → 归 E3
        val e1 = ev(1, at(8, 0), at(8, 0), "交接班")
        val e2 = ev(2, at(8, 15), at(8, 15), "出车")
        val e3 = ev(3, at(9, 0), at(9, 0), "事件X")
        val c = cluster(11, at(8, 15))
        val r = engine.classify(listOf(e1, e2, e3), listOf(c), emptyList(), date)
        assertTrue(r.eventPhotoMap[3]!!.contains(11L), "t == E2 记录 → 属 [E2, E3) → 归下一事件")
        assertTrue(r.eventPhotoMap[2]!!.isEmpty())
    }

    // ---- 用户契约：首条交接班吸收到第二条记录 ----

    @Test fun `交接班吸收到出车记录之间的照片`() {
        // 交接班记录 08:00，出车记录 08:15；08:00~08:15 的检车照片 → 归交接班（单独归集）
        val handover = ev(1, at(8, 0), at(8, 0), "交接班")
        val depart = ev(2, at(8, 15), at(8, 15), "出车")
        val c = cluster(11, at(8, 10))
        val r = engine.classify(listOf(handover, depart), listOf(c), emptyList(), date)
        assertTrue(r.eventPhotoMap[1]!!.contains(11L), "交接班到出车之间的照片归交接班")
        assertTrue(r.eventPhotoMap[2]!!.isEmpty())
    }

    @Test fun `首条吸收上班开始到出车记录之间的照片`() {
        // 07:30（早于交接班记录 08:00，≥ 当日 00:00）→ 归交接班
        val handover = ev(1, at(8, 0), at(8, 0), "交接班")
        val depart = ev(2, at(8, 15), at(8, 15), "出车")
        val c = cluster(11, at(7, 30))
        val r = engine.classify(listOf(handover, depart), listOf(c), emptyList(), date)
        assertTrue(r.eventPhotoMap[1]!!.contains(11L), "首条吸收 [00:00, 出车记录)，07:30 归交接班")
    }

    // ---- 扩展 ----

    @Test fun `末条闭区间包含记录后照片`() {
        // 末条（事件X 记录 09:00）之后照片（09:30）→ 归末条
        val e1 = ev(1, at(8, 0), at(8, 0), "交接班")
        val e2 = ev(2, at(8, 15), at(8, 15), "出车")
        val e3 = ev(3, at(9, 0), at(9, 0), "事件X")
        val c = cluster(11, at(9, 30))
        val r = engine.classify(listOf(e1, e2, e3), listOf(c), emptyList(), date)
        assertTrue(r.eventPhotoMap[3]!!.contains(11L), "末条闭区间：t >= 末条记录 → 归末条")
    }

    @Test fun `无GPS单张按时间归属`() {
        val e1 = ev(1, at(8, 0), at(8, 0))
        val e2 = ev(2, at(8, 15), at(8, 15))
        val e3 = ev(3, at(9, 0), at(9, 0))
        val p = photo(21, at(8, 45), 23.0, 113.0)
        val r = engine.classify(listOf(e1, e2, e3), emptyList(), listOf(p), date)
        assertTrue(r.eventPhotoMap[3]!!.contains(21L), "无 GPS 单张按 captureTimeMs 归窗口（08:45 归 E3）")
    }

    @Test fun `无时间照片进未匹配池`() {
        val e1 = ev(1, at(9, 0), at(10, 0))
        val p = photo(21, null)
        val r = engine.classify(listOf(e1), emptyList(), listOf(p), date)
        assertTrue(r.unmatched.contains(21L), "无时间照片不自动归入，进 unmatched 人工处理")
    }

    @Test fun `无时间事件进待定区且照片不自动归入`() {
        val e1 = ev(1, at(9, 0), at(10, 0))
        val pending = LogEvent(3, null, null, emptyList(), "K9+000", "待定", "描述3")
        val c = cluster(11, at(9, 30))   // < E1 记录 10:00 → 归 E1（首条吸收）
        val r = engine.classify(listOf(e1, pending), listOf(c), emptyList(), date)
        assertEquals(listOf(3), r.pendingEventIds, "无时间事件进待定区")
        assertTrue(r.eventPhotoMap[3]!!.isEmpty(), "待定事件照片不自动归入")
        assertTrue(r.eventPhotoMap[1]!!.contains(11L), "照片归有窗口的事件")
    }

    @Test fun `时间穿插产生提示`() {
        val e1 = ev(1, at(9, 0), at(10, 30))
        val e2 = ev(2, at(10, 0), at(11, 0))   // start(10:00) < 前 end(10:30)
        val r = engine.classify(listOf(e1, e2), emptyList(), emptyList(), date)
        assertTrue(r.interventions.any { it.kind == Kind.TIME_OVERLAP }, "穿插需提示人工核对")
    }

    @Test fun `簇含离群照片产生GPS_FAR提示`() {
        val e1 = ev(1, at(9, 0), at(10, 0))
        val c = cluster(11, at(9, 30), outlier = listOf(12L))
        val r = engine.classify(listOf(e1), listOf(c), emptyList(), date)
        assertTrue(r.interventions.any { it.kind == Kind.GPS_FAR && it.photoIds.contains(12L) },
            "簇内离群照片需 GPS_FAR 提示")
    }

    @Test fun `事件内两簇中心离散产生CLUSTER_SPAN提示`() {
        val e1 = ev(1, at(9, 0), at(10, 0))
        val e2 = ev(2, at(10, 0), at(11, 0))
        val c1 = cluster(11, at(10, 30), 23.0000, 113.0)
        val c2 = cluster(12, at(10, 40), 23.0018, 113.0)   // 距 c1 ≈200m > 75m
        val r = engine.classify(listOf(e1, e2), listOf(c1, c2), emptyList(), date)
        assertTrue(r.interventions.any { it.kind == Kind.CLUSTER_SPAN }, "同一事件下两簇中心超阈值需提示核对")
    }

    @Test fun `自动拆簇产生CLUSTER_SPLIT提示`() {
        val e1 = ev(1, at(9, 0), at(10, 0))
        val r = engine.classify(listOf(e1), emptyList(), emptyList(), date, splitCount = 2)
        assertTrue(r.interventions.any { it.kind == Kind.CLUSTER_SPLIT && it.message.contains("2") },
            "GPS 层自动拆簇次数需 CLUSTER_SPLIT 提示")
    }
}
