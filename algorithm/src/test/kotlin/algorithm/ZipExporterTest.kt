// algorithm/ZipExporterTest.kt —— §7.4 ZIP 导出测试
package algorithm

import algorithm.model.LogEvent
import algorithm.model.Photo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream

class ZipExporterTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 9, 3)
    private fun at(h: Int, m: Int) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun ev(id: Int, start: Long?, end: Long?, loc: String = "K138+700M", type: String = "交安设施巡查") =
        LogEvent(id, start, end, listOfNotNull(start, end), loc, type, "描述$id")

    @TempDir lateinit var tmp: File

    private fun makePhoto(id: Long, name: String, content: String): Pair<Photo, File> {
        val f = File(tmp, name)
        f.writeText(content)
        return Photo(id, f.absolutePath, name, at(8, 30), 23.0, 113.0) to f
    }

    private fun zipNames(zip: File): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) { val e = z.nextEntry ?: break; names += e.name; z.closeEntry() }
        }
        return names
    }

    private fun zipContent(zip: File, path: String): String {
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == path) return z.readBytes().toString(Charsets.UTF_8)
                z.closeEntry()
            }
        }
        error("not found: $path")
    }

    @Test fun `模板一文件夹名=事件类型`() {
        val (p1, _) = makePhoto(1, "a.jpg", "AAA")
        val (p2, _) = makePhoto(2, "b.jpg", "BBB")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out1.zip")
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L, 2L)), emptyList(),
            { id -> if (id == 1L) p1 else p2 }, 0, zip, null) { _, _ -> }
        val names = zipNames(zip)
        assertTrue("交安设施巡查/a.jpg" in names)
        assertTrue("交安设施巡查/b.jpg" in names)
    }

    @Test fun `模板二=日期+日志内容`() {
        val (p1, _) = makePhoto(1, "a.jpg", "AAA")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out2.zip")
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 1, zip, null) { _, _ -> }
        assertEquals(listOf("20260903_描述1/a.jpg"), zipNames(zip))
    }

    @Test fun `模板二日志内容截断与换行合并`() {
        val (p1, _) = makePhoto(1, "a.jpg", "AAA")
        val e = ev(1, at(8, 30), at(10, 0)).copy(
            description = "发现路面障碍物并已清理\n现场通知养护单位跟进处理"
        )
        val zip = File(tmp, "out3.zip")
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 1, zip, null) { _, _ -> }
        // 换行合并为空格；描述 23 字符 < 50 上限，不截断
        assertEquals(
            "20260903_发现路面障碍物并已清理 现场通知养护单位跟进处理/a.jpg",
            zipNames(zip).first()
        )
    }

    @Test fun `extraFiles写入ZIP且跳过断点续传`() {
        val (p1, _) = makePhoto(1, "a.jpg", "AAA")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "outExtra.zip")
        val state = File(tmp, "export_state_extra.json")
        val extra = mapOf("巡查台账_20260903_083000.xlsx" to "XLSX-BYTES-123".toByteArray())
        // 首次：照片 + Excel 都写入
        val r1 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, state, extraFiles = extra) { _, _ -> }
        assertEquals(2, r1.copied)
        val names = zipNames(zip)
        assertTrue("交安设施巡查/a.jpg" in names)
        assertTrue("巡查台账_20260903_083000.xlsx" in names)
        assertEquals("XLSX-BYTES-123", zipContent(zip, "巡查台账_20260903_083000.xlsx"))
        // 再次导出（带 extraFiles）：不得整包跳过（保证台账最新）
        val r2 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, state, extraFiles = extra) { _, _ -> }
        assertEquals(0, r2.skippedFromState)
        assertEquals(2, r2.copied)
    }

    @Test fun `未匹配照片进未分类文件夹`() {
        val (p1, _) = makePhoto(1, "a.jpg", "AAA")
        val (pu, _) = makePhoto(9, "u.jpg", "UUU")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out4.zip")
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), listOf(9L),
            { id -> if (id == 1L) p1 else pu }, 0, zip, null) { _, _ -> }
        assertTrue("未分类/u.jpg" in zipNames(zip))
    }

    @Test fun `重名照片自动加序号`() {
        val (p1, _) = makePhoto(1, "a.jpg", "AAA")
        val (p2, _) = makePhoto(2, "a.jpg", "BBB")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out5.zip")
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L, 2L)), emptyList(),
            { id -> if (id == 1L) p1 else p2 }, 0, zip, null) { _, _ -> }
        val names = zipNames(zip)
        assertTrue("交安设施巡查/a.jpg" in names)
        assertTrue("交安设施巡查/a_1.jpg" in names)
    }

    @Test fun `文件内容正确流式复制`() {
        val (p1, _) = makePhoto(1, "a.jpg", "HELLO-CONTENT-123")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out6.zip")
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, null) { _, _ -> }
        assertEquals("HELLO-CONTENT-123", zipContent(zip, "交安设施巡查/a.jpg"))
    }

    @Test fun `进度回调触发且末值=1`() {
        val (p1, _) = makePhoto(1, "a.jpg", "A")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out7.zip")
        val progresses = mutableListOf<Float>()
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, null) { f, _ -> progresses += f }
        assertTrue(progresses.isNotEmpty())
        assertEquals(1f, progresses.last())
    }

    @Test fun `断点续传上次全部完成后跳过`() {
        val (p1, _) = makePhoto(1, "a.jpg", "A")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out8.zip")
        val state = File(tmp, "export_state.json")
        // 首次导出（写 state）
        val r1 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, state) { _, _ -> }
        assertEquals(1, r1.copied)
        // 再次导出：跳过
        val r2 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, state) { _, _ -> }
        assertEquals(1, r2.skippedFromState)
        assertEquals(0, r2.copied)
    }

    @Test fun `无state文件不跳过`() {
        val (p1, _) = makePhoto(1, "a.jpg", "A")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out9.zip")
        val r = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, null) { _, _ -> }
        assertEquals(1, r.copied)
        assertEquals(0, r.skippedFromState)
    }

    @Test fun `非法文件不中断计入失败`() {
        val e = ev(1, at(8, 30), at(10, 0))
        // photo sourceRef 指向不存在文件
        val missing = Photo(1, File(tmp, "nope.jpg").absolutePath, "nope.jpg", at(8, 30), null, null)
        val zip = File(tmp, "out10.zip")
        val r = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { missing }, 0, zip, null) { _, _ -> }
        assertEquals(1, r.failed)
    }

    @Test fun `断点续传目标集合变化必须重新打包`() {
        // 回归：旧逻辑 targets ⊆ completed 即整包跳过 → 用户移走照片后 ZIP 仍含旧照片；
        // 修复后集合不完全一致必须重新打包
        val (p1, _) = makePhoto(1, "a.jpg", "A")
        val (p2, _) = makePhoto(2, "b.jpg", "B")
        val e = ev(1, at(8, 30), at(10, 0))
        val zip = File(tmp, "out11.zip")
        val state = File(tmp, "export_state2.json")
        // 首次导出 {A,B} → state 记录 {A,B}
        val r1 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L, 2L)), emptyList(),
            { id -> if (id == 1L) p1 else p2 }, 0, zip, state) { _, _ -> }
        assertEquals(2, r1.copied)
        // 用户移走 b.jpg → targets={A}（⊂ completed）→ 不得误跳过，必须重新打包
        val r2 = ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, state) { _, _ -> }
        assertEquals(0, r2.skippedFromState, "集合变化（移走照片）后不得整包跳过")
        assertEquals(1, r2.copied, "必须重新打包出新 ZIP")
        assertTrue("b.jpg" !in zipNames(zip), "新 ZIP 不得再含被移走的照片")
    }

    @Test fun `文件夹名非法字符替换为下划线`() {
        val (p1, _) = makePhoto(1, "a.jpg", "AAA")
        val e = ev(1, at(8, 30), at(10, 0), loc = "K1+000", type = "桥下/施工:测试")
        val zip = File(tmp, "outsanitize.zip")
        ZipExporter().export(listOf(e), mapOf(1 to listOf(1L)), emptyList(),
            { p1 }, 0, zip, null) { _, _ -> }
        // Windows 非法字符 / : 替换为 _
        assertTrue("桥下_施工_测试/a.jpg" in zipNames(zip))
    }
}
