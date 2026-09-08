// algorithm/XlsxWriterTest.kt —— §7.3 Excel 写入测试（导出Excel 2.0 方案）
package algorithm

import algorithm.model.LedgerRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class XlsxWriterTest {
    private fun row(date: String = "2026-09-03 08:30",
                    loc: String = "K138+700M", desc: String = "护栏检查 & 修复",
                    p1: String? = "DCIM/Camera/IMG_20260903_083001.jpg",
                    p2: String? = null) =
        LedgerRow(date, loc, desc, p1, p2)

    private fun entries(bytes: ByteArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out += e.name to z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    @Test fun `xlsx包含全部OOXML部件`() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(listOf(row()), it) }.toByteArray()
        val names = entries(bytes).map { it.first }
        assertTrue("[Content_Types].xml" in names)
        assertTrue("_rels/.rels" in names)
        assertTrue("xl/workbook.xml" in names)
        assertTrue("xl/_rels/workbook.xml.rels" in names)
        assertTrue("xl/worksheets/sheet1.xml" in names)
        assertTrue("xl/styles.xml" in names)
    }

    @Test fun `表头6列与数据行写入sheet`() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(listOf(row()), it) }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        for (h in listOf("序号", "日期", "地点", "日志内容", "照片1", "照片2")) {
            assertTrue(sheet.contains("<t xml:space=\"preserve\">$h</t>"), "表头缺 $h")
        }
        assertTrue(sheet.contains("2026-09-03 08:30"))
        assertTrue(sheet.contains("K138+700M"))
        assertTrue(sheet.contains("护栏检查 &amp; 修复"))
    }

    @Test fun `序号为程序生成行号`() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(), row(), row()), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        // 数据行首列 = 1/2/3（A2/A3/A4）
        assertTrue(sheet.contains("""<c r="A2" t="inlineStr"><is><t xml:space="preserve">1</t></is></c>"""))
        assertTrue(sheet.contains("""<c r="A3" t="inlineStr"><is><t xml:space="preserve">2</t></is></c>"""))
        assertTrue(sheet.contains("""<c r="A4" t="inlineStr"><is><t xml:space="preserve">3</t></is></c>"""))
    }

    @Test fun `列宽与行高符合方案`() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(listOf(row()), it) }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        // 列宽 A:8 B:18 C:24 D:60 E:20 F:20
        for ((min, w) in listOf(1 to 8, 2 to 18, 3 to 24, 4 to 60, 5 to 20, 6 to 20)) {
            assertTrue(sheet.contains("""<col min="$min" max="$min" width="$w" customWidth="1"/>"""),
                "列宽 $min 应为 $w")
        }
        // 行高：表头 25、数据 81（4:3 照片高 2.85cm）
        assertTrue(sheet.contains("""<row r="1" ht="25" customHeight="1">"""), "表头行高 25")
        assertTrue(sheet.contains("""<row r="2" ht="81" customHeight="1">"""), "数据行高 81")
    }

    @Test fun `日志内容列应用wrapText样式`() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(listOf(row()), it) }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        // D2 单元格应用样式 1（wrapText）
        assertTrue(sheet.contains("""<c r="D2" s="1" t="inlineStr">"""), "日志内容列 D2 应带 s=1")
        val styles = entries(bytes).first { it.first == "xl/styles.xml" }.second
        assertTrue(styles.contains("""wrapText="1""""), "styles.xml 应有 wrapText 对齐")
    }

    @Test fun `照片列写文件名最后一段`() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(p1 = "DCIM/Camera/IMG_20260903_083001.jpg",
                p2 = "D:/Pics/2026/IMG_0002.png")), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(sheet.contains("IMG_20260903_083001.jpg"))
        assertTrue(sheet.contains("IMG_0002.png"))
        assertTrue(!sheet.contains("DCIM/Camera/"))
    }

    @Test fun `特殊字符XML转义`() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(desc = "护栏 & 修复 <损坏> \"引号\" '撇号'")), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(sheet.contains("护栏 &amp; 修复 &lt;损坏&gt; &quot;引号&quot; &apos;撇号&apos;"))
        assertTrue(!sheet.contains("& 修复 <"))
    }

    @Test fun `空照片列输出空单元格`() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(listOf(row(p1 = null, p2 = null)), it) }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        // 表头 6 列 + 数据 6 列 = 12 个单元格（含 2 空照片列）
        assertEquals(12, Regex("""<c r="[A-F]""").findAll(sheet).count())
    }

    // ---- ★ §7.3 照片嵌入（锚定 E/F 列，0-based 4/5） ----

    @Test fun `嵌入照片时生成media与drawing部件`() {
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0xFF.toByte(), 0xD9.toByte())
        val loader: (String?) -> ByteArray? = { ref -> if (ref != null) jpg else null }
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(p1 = "a.jpg", p2 = "b.jpg")), it, loader)
        }.toByteArray()
        val names = entries(bytes).map { it.first }
        assertTrue("xl/media/image1.jpg" in names, "照片1 media 部件")
        assertTrue("xl/media/image2.jpg" in names, "照片2 media 部件")
        assertTrue("xl/drawings/drawing1.xml" in names, "drawing 部件")
        assertTrue("xl/drawings/_rels/drawing1.xml.rels" in names, "drawing rels")
        assertTrue("xl/worksheets/_rels/sheet1.xml.rels" in names, "sheet rels")
        val ct = entries(bytes).first { it.first == "[Content_Types].xml" }.second
        assertTrue(ct.contains("""<Default Extension="jpg" ContentType="image/jpeg"/>"""))
        assertTrue(ct.contains("""<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>"""))
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(sheet.contains("""<drawing r:id="rIdDraw1"/>"""), "sheet 引用 drawing")
        val drawing = entries(bytes).first { it.first == "xl/drawings/drawing1.xml" }.second
        assertTrue(drawing.contains("""<xdr:from><xdr:col>4</xdr:col>"""), "照片1 锚定第 5 列(E)")
        assertTrue(drawing.contains("""<xdr:from><xdr:col>5</xdr:col>"""), "照片2 锚定第 6 列(F)")
        assertTrue(drawing.contains("""<xdr:row>1</xdr:row>"""), "数据行 1 锚定")
    }

    @Test fun `PNG照片按png扩展名与ContentType写入`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01)
        val loader: (String?) -> ByteArray? = { ref -> if (ref != null) png else null }
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(p1 = "a.png")), it, loader)
        }.toByteArray()
        val names = entries(bytes).map { it.first }
        assertTrue("xl/media/image1.png" in names, "PNG media 扩展名")
        val ct = entries(bytes).first { it.first == "[Content_Types].xml" }.second
        assertTrue(ct.contains("""<Default Extension="png" ContentType="image/png"/>"""))
        val rels = entries(bytes).first { it.first == "xl/drawings/_rels/drawing1.xml.rels" }.second
        assertTrue(rels.contains("""Target="../media/image1.png""""))
    }

    @Test fun `无照片字节时不生成drawing与media部件`() {
        val loader: (String?) -> ByteArray? = { null }
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(p1 = "a.jpg", p2 = "b.jpg")), it, loader)
        }.toByteArray()
        val names = entries(bytes).map { it.first }
        assertTrue("xl/drawings/drawing1.xml" !in names)
        assertTrue("xl/media/image1.jpg" !in names)
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(!sheet.contains("<drawing"))
    }
}
