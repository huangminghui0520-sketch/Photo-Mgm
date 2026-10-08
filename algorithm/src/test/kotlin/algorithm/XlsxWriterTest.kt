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
        // 数据行首列 = 1/2/3（A2/A3/A4），且为数字单元格（无 t 属性）
        assertTrue(sheet.contains("""<c r="A2"><v>1</v></c>"""), "A2 应为数字 1")
        assertTrue(sheet.contains("""<c r="A3"><v>2</v></c>"""), "A3 应为数字 2")
        assertTrue(sheet.contains("""<c r="A4"><v>3</v></c>"""), "A4 应为数字 3")
    }

    @Test fun `列宽与行高符合方案`() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(listOf(row()), it) }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        // 列宽 A:8 B:18 C:24 D:60 E:22 F:22（★ 2026-10-08 E/F 20→22：容纳 3.81cm 嵌入照片，防横向溢出）
        for ((min, w) in listOf(1 to 8, 2 to 18, 3 to 24, 4 to 60, 5 to 22, 6 to 22)) {
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

    /**
     * ★ XML 非法控制字符必须被剔除。
     *
     * 背景：escape 原先只转义 5 个实体，不过滤控制字符。日志从 Word/PDF 粘贴常带 \v(0x0B)/\f(0x0C)，
     * 而 XML 1.0 完全禁止这些码点 —— 一个字符就让 sheet1.xml 无法解析、Excel 报"文件损坏"，
     * 整个台账打不开（与历史上的 `s="1` 缺陷同级）。
     */
    @Test fun `日志含XML非法控制字符时仍为良构XML`() {
        val dirty = "巡查\u000B发现\u000C护栏\u0000损坏\u001F，已\u0007处理"
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(desc = dirty)), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        // 必须能被解析（这一步就验证了非法字符已被剔除）
        javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }.newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(sheet)))
        // 合法内容要保留，非法控制字符要消失
        assertTrue(sheet.contains("巡查") && sheet.contains("护栏") && sheet.contains("处理"), "应保留可见文字")
        assertTrue(!sheet.contains('\u000B'), "不应残留 \\u000B")
        assertTrue(!sheet.contains('\u000C'), "不应残留 \\u000C")
        assertTrue(!sheet.contains('\u0000'), "不应残留 \\u0000")
        assertTrue(!sheet.contains('\u001F'), "不应残留 \\u001F")
    }

    /** 序号必须是数字单元格（无 t 属性），否则 Excel 报"数字以文本存储"且无法排序求和。 */
    @Test fun `序号列为数字单元格而非文本`() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(), row()), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(sheet.contains("""<c r="A2"><v>1</v></c>"""), "A2 应为数字单元格")
        assertTrue(sheet.contains("""<c r="A3"><v>2</v></c>"""), "A3 应为数字单元格")
        assertTrue(!sheet.contains("""<c r="A2" t="inlineStr">"""), "序号不应再是 inlineStr 文本")
    }

    /** 行高估算必须计入显式换行：customHeight=1 禁止 Excel 自动撑高，估算偏小会裁切多行日志。 */
    @Test fun `多行日志的行高大于单行短日志`() {
        fun rowHeightOf(desc: String): Int {
            val bytes = ByteArrayOutputStream().also {
                XlsxWriter.write(listOf(row(desc = desc)), it)
            }.toByteArray()
            val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
            return Regex("""<row r="2" ht="(\d+)"""").find(sheet)!!.groupValues[1].toInt()
        }
        // 10 个换行、每段很短：按字符数折算只需 1 行，按换行需 10 行
        val multiline = (1..10).joinToString("\n") { "短" }
        val hMulti = rowHeightOf(multiline)
        val hShort = rowHeightOf("短")
        println("ROWHEIGHT short=$hShort multiline=$hMulti")
        assertTrue(
            hMulti > hShort,
            "含 10 个换行的日志行高($hMulti) 应显著大于单行短日志($hShort)",
        )
        // 10 行 × 16pt + 8pt = 168pt
        assertTrue(hMulti >= 168, "行高应不小于 10 行文本所需(168pt)，实际 $hMulti")
    }

    /**
     * ★ 图片必须固定尺寸（oneCellAnchor + a:ext），不能随行高拉伸。
     *
     * 背景：原先用 twoCellAnchor 锚满"1 列 × 1 行"，而数据行高随日志长度增长。
     * 日志超过约 150 字时行高 > 81pt，图片被纵向拉伸（300 字 → 168pt → 2.07 倍），
     * 4:3 取证照片失真。改为 oneCellAnchor 固定尺寸后，行高怎么变图片都是 4:3。
     */
    @Test fun `图片锚定为固定尺寸不随行高拉伸`() {
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0xFF.toByte(), 0xD9.toByte())
        val loader: (String?) -> ByteArray? = { ref -> if (ref != null) jpg else null }

        fun drawingOf(desc: String): Pair<Int, String> {
            val bytes = ByteArrayOutputStream().also {
                XlsxWriter.write(listOf(row(p1 = "a.jpg", p2 = "b.jpg", desc = desc)), it, loader)
            }.toByteArray()
            val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
            val h = Regex("""<row r="2" ht="(\d+)"""").find(sheet)!!.groupValues[1].toInt()
            return h to entries(bytes).first { it.first == "xl/drawings/drawing1.xml" }.second
        }

        val (hShort, dShort) = drawingOf("护栏检查")
        val (hLong, dLong) = drawingOf("巡".repeat(300))

        // 前提：两次行高确实不同（否则这个用例证明不了什么）
        assertEquals(81, hShort, "短描述行高应为 81pt")
        assertTrue(hLong > 81, "300 字描述行高应 >81pt，实际 $hLong")

        // 断言 1：用 oneCellAnchor（固定尺寸），不是 twoCellAnchor（随行高拉伸）
        assertTrue(dShort.contains("<xdr:oneCellAnchor>"), "应使用 oneCellAnchor")
        assertTrue(!dShort.contains("<xdr:twoCellAnchor"), "不应再用 twoCellAnchor")

        // 断言 2：尺寸由 a:ext 显式给出，且 4:3（1371600 × 1028700 EMU）
        assertTrue(
            dShort.contains("""<xdr:ext cx="1371600" cy="1028700"/>"""),
            "应有固定尺寸 a:ext（4:3）",
        )

        // 断言 3：核心——行高差一倍，锚定尺寸必须完全一致（图片不随行高变化）
        val extShort = Regex("""<xdr:ext cx="\d+" cy="\d+"/>""").find(dShort)?.value
        val extLong = Regex("""<xdr:ext cx="\d+" cy="\d+"/>""").find(dLong)?.value
        assertEquals(extShort, extLong, "行高变化时图片尺寸必须保持不变")
        // 断言 4：drawing XML 良构
        javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }.newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(dLong)))
    }

    /**
     * ★ 后置条件：整个数据行必须是良构 XML。
     *
     * 背景：样式属性曾写成 `""" s="1"""`（缺前导空格），`$r$style` 直接拼接后闭合引号
     * 被并入属性值，产出 `<c r="D2" s="1 t="inlineStr">` —— 畸形 XML，整个 xlsx 部件
     * 损坏。当时只断言"是否含 s=1"，而畸形输出恰好也含该子串，于是漏判。
     * 此用例直接解析 XML，可捕获任意属性拼接错误。
     */
    @Test fun `数据行输出为良构XML`() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(listOf(row(desc = "巡查发现护栏损坏，已通知养护单位处理"), row()), it)
        }.toByteArray()
        val sheet = entries(bytes).first { it.first == "xl/worksheets/sheet1.xml" }.second
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }.newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(sheet)))
        val cells = doc.getElementsByTagName("c")
        var dStyled = 0
        for (i in 0 until cells.length) {
            val c = cells.item(i) as org.w3c.dom.Element
            if (c.getAttribute("r").startsWith("D") && c.getAttribute("r") != "D1") {
                if (c.getAttribute("s") == "1") dStyled++
            }
        }
        assertEquals(2, dStyled, "两条数据行的 D 列都应带样式 s=1")
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
