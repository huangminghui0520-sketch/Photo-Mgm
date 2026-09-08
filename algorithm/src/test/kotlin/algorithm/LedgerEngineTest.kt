// algorithm/LedgerEngineTest.kt —— §7 台账模块测试（导出Excel 2.0 方案）
// 契约：每事件一行 / 日期列 yyyy-MM-dd HH:mm（开始时间）/ 地点桩号优先 / 日志内容 /
//       照片1=第一张(最早)、照片2=最后一张(最晚)，无标记概念 / 按 endTime 排序
package algorithm

import algorithm.model.LogEvent
import algorithm.model.Photo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class LedgerEngineTest {
    private val engine = LedgerEngine()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 9, 3)
    private fun at(h: Int, m: Int) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun atDate(d: Int, h: Int, m: Int) =
        LocalDate.of(2026, 9, d).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun ev(id: Int, start: Long?, end: Long?, loc: String = "K138+700M", type: String = "事件$id") =
        LogEvent(id, start, end, listOfNotNull(start, end), loc, type, "描述$id")
    private fun photo(id: Long, t: Long?, src: String = "img$id.jpg") = Photo(id, src, src, t, 23.0, 113.0)
    private fun byId(photos: List<Photo>) = { id: Long -> photos.find { it.id == id } }

    @Test fun `日期列为yyyy-MM-dd HHmm`() {
        val e = ev(1, at(10, 5), at(10, 30))
        val rows = engine.buildLedger(listOf(e), emptyMap(), byId(emptyList()))
        assertEquals("2026-09-03 10:05", rows[0].dateTime)
    }

    @Test fun `无开始时间日期列为空`() {
        val e = ev(1, null, null)
        val rows = engine.buildLedger(listOf(e), emptyMap(), byId(emptyList()))
        assertEquals("", rows[0].dateTime)
    }

    @Test fun `照片默认取最早和最晚各一张`() {
        val e = ev(1, at(10, 0), at(10, 30))
        val photos = listOf(photo(1, at(10, 20)), photo(2, at(10, 0)), photo(3, at(10, 10)))
        val rows = engine.buildLedger(listOf(e), mapOf(1 to listOf(1L, 2L, 3L)), byId(photos))
        assertEquals("img2.jpg", rows[0].photo1Ref, "照片1=第一张（最早）")
        assertEquals("img1.jpg", rows[0].photo2Ref, "照片2=最后一张（最晚）")
    }

    @Test fun `单张照片时照片1照片2相同`() {
        val e = ev(1, at(10, 0), at(10, 30))
        val photos = listOf(photo(7, at(10, 10)))
        val rows = engine.buildLedger(listOf(e), mapOf(1 to listOf(7L)), byId(photos))
        assertEquals("img7.jpg", rows[0].photo1Ref)
        assertEquals("img7.jpg", rows[0].photo2Ref)
    }

    @Test fun `地点与日志内容取自事件`() {
        val e = ev(1, at(10, 0), at(10, 30), loc = "K138+700M收费站")
        val rows = engine.buildLedger(listOf(e), emptyMap(), byId(emptyList()))
        assertEquals("K138+700M收费站", rows[0].location)
        assertEquals("描述1", rows[0].description)
    }

    @Test fun `按endTime升序输出`() {
        val e1 = ev(1, at(9, 0), at(10, 0))
        val e2 = ev(2, at(10, 0), at(11, 0))
        val e3 = ev(3, at(8, 0), at(9, 0))
        val rows = engine.buildLedger(listOf(e1, e2, e3), emptyMap(), byId(emptyList()))
        assertEquals("2026-09-03 08:00", rows[0].dateTime)
        assertEquals("2026-09-03 09:00", rows[1].dateTime)
        assertEquals("2026-09-03 10:00", rows[2].dateTime)
    }

    @Test fun `无照片事件照片列为空`() {
        val e = ev(1, at(10, 0), at(10, 30))
        val rows = engine.buildLedger(listOf(e), mapOf(1 to listOf(99L)), byId(emptyList()))
        assertNull(rows[0].photo1Ref)
        assertNull(rows[0].photo2Ref)
    }

    @Test fun `跨日事件日期按实际开始日期`() {
        // 09-03 23:30 开始，09-04 01:00 结束 → 日期列显示 09-03 23:30
        val e = ev(1, atDate(3, 23, 30), atDate(4, 1, 0))
        val rows = engine.buildLedger(listOf(e), emptyMap(), byId(emptyList()))
        assertEquals("2026-09-03 23:30", rows[0].dateTime)
    }
}
