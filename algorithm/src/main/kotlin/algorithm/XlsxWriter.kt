// algorithm/XlsxWriter.kt —— §7.3 Excel 写入（纯 Kotlin，零第三方依赖，可移植 PC）
// ★ 导出Excel 2.0 方案：6 列（序号/日期/地点/日志内容/照片1/照片2），列宽 A8 B18 C24 D60 E20 F20，
//   表头行高 25、数据行高动态（随日志内容长度），日志内容列自动换行，照片以缩略图嵌入。
// ★ 照片嵌入：imageLoader(ref) 返回图片字节（JPEG/PNG），自动生成 drawing + media + rels；
//   图片锚定到照片1/照片2 所在单元格（E/F 列 × 1 行显示区）。无图片或加载失败 → 照片列仅写文件名。
package algorithm

import algorithm.model.LedgerRow
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object XlsxWriter {
    /** 表头（导出Excel 2.0 方案 §2.1） */
    val HEADERS: List<String> = listOf("序号", "日期", "地点", "日志内容", "照片1", "照片2")

    /** 列宽（字符）：A:8, B:18, C:24, D:60, E:22, F:22
     *  ★ 2026-10-08 修复照片与单元格不匹配：E/F 照片列 20 → 22，
     *    容纳固定宽 3.81cm 的嵌入照片（20 字符在 Excel/WPS 换算下仅 ≈3.7cm，横向溢出）。 */
    private val COL_WIDTHS: List<Int> = listOf(8, 18, 24, 60, 22, 22)

    /** 表头行高 / 数据行最小行高（磅）。数据行高随日志内容动态计算，避免长文本撑高行导致照片错位。 */
    private const val HEADER_ROW_H = 25
    private const val DATA_ROW_H_MIN = 81

    /** D列（日志内容）列宽 60 字符；估算每行可容纳约 30 个中文字符（11pt 字体）。 */
    private const val CHARS_PER_LINE = 30
    /** 每行文本约 16 磅 + 上下边距 8 磅。 */
    private const val LINE_HEIGHT_PT = 16
    private const val TEXT_PADDING_PT = 8

    /** 数据行的单元格：区分数字与文本，避免把所有内容都塞进 inlineStr。 */
    private sealed interface Cell {
        /** styled=true 时套用 s="1"（wrapText），目前仅 D 列日志内容使用。 */
        val styled: Boolean

        /** 数字单元格（无 t 属性，Excel 按数值处理）。 */
        data class Number(val value: String, override val styled: Boolean = false) : Cell

        /** 文本单元格（inlineStr，xml:space="preserve" 保留空白与换行）。 */
        data class Str(val value: String, override val styled: Boolean = false) : Cell
    }

    /** 根据日志内容长度动态计算行高：至少容纳照片(81磅)，长文本按行数增加，避免 Excel 自动撑高导致照片错位。
     *  ★ 2026-10-08 P2-10：按字符宽度权重估算——中文/全角=2 单位、拉丁/数字=1 单位，
     *    解决长 URL/英文编号串被低估而静默裁切的问题；行高另加 10% 缓冲。 */
    private fun calcRowHeight(description: String?): Int {
        if (description.isNullOrBlank()) return DATA_ROW_H_MIN
        fun units(s: String): Int = s.fold(0) { acc, ch ->
            val c = ch.code
            acc + when {
                c in 0x1100..0x11FF -> 2          // Hangul Jamo
                c in 0x2E80..0xA4CF -> 2          // CJK 部首/汉字/假名
                c in 0xAC00..0xD7A3 -> 2          // 韩文音节
                c in 0xF900..0xFAFF -> 2          // CJK 兼容
                c in 0xFE30..0xFE4F -> 2          // 中文标点
                c in 0xFF00..0xFF60 -> 2          // 全角 ASCII
                c in 0xFFE0..0xFFE6 -> 2          // 全角符号
                else -> 1
            }
        }
        // ★ 计入显式换行：只按字符数估算会漏掉 \n，而 customHeight="1" 禁止 Excel 自动撑高，
        //   结果是多行日志被裁切。这里按段累加各段所需行数，再与整段折算值取大。
        val segments = description.split('\n')
        val byNewline = segments.sumOf { seg ->
            maxOf(1, (units(seg) + CHARS_PER_LINE - 1) / CHARS_PER_LINE)
        }
        val byLength = (units(description) + CHARS_PER_LINE - 1) / CHARS_PER_LINE
        val lines = maxOf(byNewline, byLength)
        val textHeight = (lines * LINE_HEIGHT_PT + TEXT_PADDING_PT) * 1.10   // 10% 缓冲防裁切
        return maxOf(DATA_ROW_H_MIN, textHeight.toInt())
    }

    /** 图片锚定：照片1 列=4、照片2 列=5（0-based E/F）；显示 1 列 × 1 行。 */
    private const val COL_P1 = 4
    private const val COL_P2 = 5

    /** 把台账行写入 xlsx 字节流（ZIP 容器 + OOXML 部件）。imageLoader 返回照片字节（可为 null）。 */
    fun write(rows: List<LedgerRow>, out: OutputStream, imageLoader: (String?) -> ByteArray? = { null }) {
        val imgs = collectImages(rows, imageLoader)
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write(contentTypes(imgs).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("_rels/.rels")); z.write(RELS.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("xl/workbook.xml")); z.write(WORKBOOK.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels")); z.write(WORKBOOK_RELS.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); z.write(sheetXml(rows, imgs.isNotEmpty()).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("xl/styles.xml")); z.write(STYLES.toByteArray()); z.closeEntry()
            if (imgs.isNotEmpty()) {
                z.putNextEntry(ZipEntry("xl/worksheets/_rels/sheet1.xml.rels")); z.write(SHEET_RELS.toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("xl/drawings/drawing1.xml")); z.write(drawingXml(imgs).toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("xl/drawings/_rels/drawing1.xml.rels")); z.write(drawingRels(imgs).toByteArray()); z.closeEntry()
                imgs.forEachIndexed { idx, img ->
                    val (ext, _) = imageFormat(img.bytes)
                    z.putNextEntry(ZipEntry("xl/media/image${idx + 1}.$ext")); z.write(img.bytes); z.closeEntry()
                }
            }
        }
    }

    // ---------- 图片收集 ----------

    /** ★ 2026-10-08 增加 rowHeightPt：该图所在数据行的动态行高（磅），用于 drawing 垂直居中。 */
    private data class Img(val bytes: ByteArray, val col: Int, val row: Int, val rowHeightPt: Int)

    /** 每行照片1/照片2 → 有字节则收集；行号 = 表头(0) + 数据行索引(1-based)；行高 = 该行 calcRowHeight（与 sheet 一致）。 */
    private fun collectImages(rows: List<LedgerRow>, imageLoader: (String?) -> ByteArray?): List<Img> {
        val out = mutableListOf<Img>()
        rows.forEachIndexed { i, r ->
            val rowH = calcRowHeight(r.description)
            listOf(r.photo1Ref to COL_P1, r.photo2Ref to COL_P2).forEach { (ref, col) ->
                val b = ref?.let { runCatching { imageLoader(it) }.getOrNull() }
                if (b != null && b.isNotEmpty()) out.add(Img(b, col, i + 1, rowH))
            }
        }
        return out
    }

    /** 按文件头判断格式：JPEG(FF D8 FF) / PNG(89 50 4E 47)；其他按 PNG 兜底。 */
    private fun imageFormat(bytes: ByteArray): Pair<String, String> = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
            "jpg" to "image/jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "png" to "image/png"
        else -> "png" to "image/png"
    }

    // ---------- 部件生成 ----------

    private fun contentTypes(imgs: List<Img>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
        if (imgs.isNotEmpty()) {
            imgs.map { imageFormat(it.bytes).second }.distinct().forEach { ct ->
                sb.append("""<Default Extension="${extOf(ct)}" ContentType="$ct"/>""")
            }
            sb.append("""<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>""")
        }
        sb.append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
        return sb.toString()
    }

    private fun extOf(contentType: String): String = if (contentType.endsWith("png")) "png" else "jpg"

    private fun sheetXml(rows: List<LedgerRow>, hasDrawing: Boolean): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        // 列宽（导出Excel 2.0 方案：A:8 B:18 C:24 D:60 E:20 F:20）
        sb.append("<cols>")
        COL_WIDTHS.forEachIndexed { i, w ->
            val min = i + 1
            sb.append("""<col min="$min" max="$min" width="$w" customWidth="1"/>""")
        }
        sb.append("</cols>")
        sb.append("<sheetData>")
        sb.append(headerRowXml())
        rows.forEachIndexed { i, r ->
            val rowH = calcRowHeight(r.description)
            sb.append(dataRowXml(i + 2, listOf(
                // 序号用数字单元格（不是文本）：否则 Excel 报"数字以文本存储"且无法求和排序
                Cell.Number((i + 1).toString()),
                Cell.Str(r.dateTime),
                Cell.Str(r.location),
                Cell.Str(r.description, styled = true),   // D 列 wrapText
                Cell.Str(fileName(r.photo1Ref).orEmpty()),
                Cell.Str(fileName(r.photo2Ref).orEmpty()),
            ), rowH))
        }
        sb.append("</sheetData>")
        if (hasDrawing) sb.append("""<drawing r:id="rIdDraw1"/>""")
        sb.append("</worksheet>")
        return sb.toString()
    }

    /** 表头行：行高 25 磅。 */
    private fun headerRowXml(): String {
        val sb = StringBuilder("""<row r="1" ht="$HEADER_ROW_H" customHeight="1">""")
        HEADERS.forEachIndexed { i, h ->
            val col = ('A' + i)
            sb.append("""<c r="$col${1}" t="inlineStr"><is><t xml:space="preserve">$h</t></is></c>""")
        }
        sb.append("</row>")
        return sb.toString()
    }

    /** 数据行：行高动态计算（随日志内容长度）；日志内容列（D，索引 3）应用 wrapText 样式（s="1"）。 */
    private fun dataRowXml(r: Int, cells: List<Cell>, rowHeight: Int): String {
        val sb = StringBuilder("""<row r="$r" ht="$rowHeight" customHeight="1">""")
        cells.forEachIndexed { i, cell ->
            val col = ('A' + i)
            val style = if (cell.styled) """ s="1"""" else ""
            when (cell) {
                is Cell.Number ->
                    sb.append("""<c r="$col$r"$style><v>${cell.value}</v></c>""")
                is Cell.Str ->
                    sb.append(
                        """<c r="$col$r"$style t="inlineStr"><is><t xml:space="preserve">""" +
                                escape(cell.value) + "</t></is></c>"
                    )
            }
        }
        sb.append("</row>")
        return sb.toString()
    }

    /** 照片列写文件名（sourceRef 最后一段，§7.3）；null → 空单元格。 */
    fun fileName(ref: String?): String? =
        ref?.substringAfterLast('/')?.substringAfterLast('\\')

    /**
     * 图片锚定：oneCellAnchor + 固定尺寸（EMU）。
     *
     * ★ 为什么不用 twoCellAnchor：
     *   原先锚满"1 列 × 1 行"，而数据行高是动态的（随日志长度增长）。日志超过约 150 字时
     *   行高 > 81pt，图片就被纵向拉伸（300 字 → 168pt → 拉伸 2.07 倍），4:3 取证照片失真。
     *   改用 oneCellAnchor 固定尺寸后，行高再怎么变，图片始终保持 4:3。
     *
     * 尺寸与 DATA_ROW_H_MIN(81pt) 对齐：81pt = 2.8575cm → 照片高 1028700 EMU、
     * 宽 1371600 EMU（4:3），与 ImageUtils 输出的 4:3 缩略图一致。
     * ★ 2026-10-08 修复"照片与单元格不匹配"：行高随日志内容动态增大时，照片固定高度在行顶悬空、
     *   下方大段留白。现按该行实际行高计算 rowOff 偏移，照片在单元格内垂直居中（4:3 不变形）。
     */
    private const val PIC_CX_EMU = 1371600L   // 3.81 cm
    private const val PIC_CY_EMU = 1028700L   // 2.8575 cm
    /** 1 磅 = 1/72 英寸 = 12700 EMU（Excel 标准）。 */
    private const val PT_TO_EMU = 12700L

    private fun drawingXml(imgs: List<Img>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        imgs.forEachIndexed { i, img ->
            // ★ 垂直居中：rowOff = (该行高EMU − 照片高EMU) / 2；行高小于照片高时取 0（顶对齐兜底）
            val rowEmu = img.rowHeightPt.toLong() * PT_TO_EMU
            val rowOff = maxOf(0L, (rowEmu - PIC_CY_EMU) / 2)
            sb.append(
                """<xdr:oneCellAnchor><xdr:from><xdr:col>${img.col}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${img.row}</xdr:row><xdr:rowOff>$rowOff</xdr:rowOff></xdr:from><xdr:ext cx="$PIC_CX_EMU" cy="$PIC_CY_EMU"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${i + 2}" name="image${i + 1}"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId${i + 1}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>"""
            )
        }
        sb.append("</xdr:wsDr>")
        return sb.toString()
    }

    private fun drawingRels(imgs: List<Img>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        imgs.forEachIndexed { i, img ->
            val (ext, _) = imageFormat(img.bytes)
            sb.append("""<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image${i + 1}.$ext"/>""")
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    /**
     * XML 文本转义。
     *
     * ★ 先剔除 XML 1.0 不允许的字符，再做实体转义。
     *   XML 1.0 只允许 #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]。
     *   非法字符（如 \v 0x0B、\f 0x0C，以及 0x00-0x08/0x0E-0x1F）会让 sheet1.xml 无法解析，
     *   Excel 报"文件损坏"——单个字符就能毁掉整个台账。
     *   日志来自用户粘贴（Word/PDF 复制常带这类字符），必须在此兜住。
     */
    private fun escape(s: String): String = stripIllegalXmlChars(s)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** 匹配 XML 1.0 非法字符：除 \t(09) \n(0A) \r(0D) 之外的 C0 控制符、DEL/C1 区间、0xFFFE/0xFFFF。 */
    private val ILLEGAL_XML: Regex =
        Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F\\uFFFE\\uFFFF]")

    private fun stripIllegalXmlChars(s: String): String = ILLEGAL_XML.replace(s, "")

    // ---------- 常量部件 ----------

    private val RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>
    """.trimIndent()

    private val WORKBOOK = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="巡查台账" sheetId="1" r:id="rId1"/></sheets></workbook>
    """.trimIndent()

    private val WORKBOOK_RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>
    """.trimIndent()

    private val SHEET_RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdDraw1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/></Relationships>
    """.trimIndent()

    /** 样式：0=默认；1=wrapText（日志内容列 D 自动换行，垂直顶端对齐）。 */
    private val STYLES = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf></cellXfs></styleSheet>
    """.trimIndent()
}
