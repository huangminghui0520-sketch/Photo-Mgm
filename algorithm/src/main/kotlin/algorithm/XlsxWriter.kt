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

    /** 列宽（字符）：A:8, B:18, C:24, D:60, E:20, F:20 */
    private val COL_WIDTHS: List<Int> = listOf(8, 18, 24, 60, 20, 20)

    /** 表头行高 / 数据行最小行高（磅）。数据行高随日志内容动态计算，避免长文本撑高行导致照片错位。 */
    private const val HEADER_ROW_H = 25
    private const val DATA_ROW_H_MIN = 81

    /** D列（日志内容）列宽 60 字符；估算每行可容纳约 30 个中文字符（11pt 字体）。 */
    private const val CHARS_PER_LINE = 30
    /** 每行文本约 16 磅 + 上下边距 8 磅。 */
    private const val LINE_HEIGHT_PT = 16
    private const val TEXT_PADDING_PT = 8

    /** 根据日志内容长度动态计算行高：至少容纳照片(81磅)，长文本按行数增加，避免 Excel 自动撑高导致照片错位。 */
    private fun calcRowHeight(description: String?): Int {
        if (description.isNullOrBlank()) return DATA_ROW_H_MIN
        val lines = (description.length + CHARS_PER_LINE - 1) / CHARS_PER_LINE
        val textHeight = lines * LINE_HEIGHT_PT + TEXT_PADDING_PT
        return maxOf(DATA_ROW_H_MIN, textHeight)
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

    private data class Img(val bytes: ByteArray, val col: Int, val row: Int)

    /** 每行照片1/照片2 → 有字节则收集；行号 = 表头(0) + 数据行索引(1-based)。 */
    private fun collectImages(rows: List<LedgerRow>, imageLoader: (String?) -> ByteArray?): List<Img> {
        val out = mutableListOf<Img>()
        rows.forEachIndexed { i, r ->
            listOf(r.photo1Ref to COL_P1, r.photo2Ref to COL_P2).forEach { (ref, col) ->
                val b = ref?.let { runCatching { imageLoader(it) }.getOrNull() }
                if (b != null && b.isNotEmpty()) out.add(Img(b, col, i + 1))
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
                (i + 1).toString(),          // 序号（程序生成）
                r.dateTime,
                r.location,
                r.description,
                fileName(r.photo1Ref),
                fileName(r.photo2Ref),
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
    private fun dataRowXml(r: Int, cells: List<String?>, rowHeight: Int): String {
        val sb = StringBuilder("""<row r="$r" ht="$rowHeight" customHeight="1">""")
        cells.forEachIndexed { i, c ->
            val col = ('A' + i)
            val style = if (i == 3) """ s="1""" else ""
            sb.append("""<c r="$col$r"$style t="inlineStr"><is><t xml:space="preserve">${escape(c ?: "")}</t></is></c>""")
        }
        sb.append("</row>")
        return sb.toString()
    }

    /** 照片列写文件名（sourceRef 最后一段，§7.3）；null → 空单元格。 */
    fun fileName(ref: String?): String? =
        ref?.substringAfterLast('/')?.substringAfterLast('\\')

    private fun drawingXml(imgs: List<Img>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        imgs.forEachIndexed { i, img ->
            val fromCol = img.col; val fromRow = img.row
            val toCol = fromCol + 1; val toRow = fromRow + 1
            sb.append("""<xdr:twoCellAnchor editAs="oneCell"><xdr:from><xdr:col>$fromCol</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>$fromRow</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>$toCol</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>$toRow</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${i + 2}" name="image${i + 1}"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId${i + 1}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:twoCellAnchor>""")
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

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

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
