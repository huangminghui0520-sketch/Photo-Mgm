package algorithm

import algorithm.model.LedgerRow
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** 生成真实 xlsx 文件（供 openpyxl / 结构校验），非交付物。 */
class GenRealXlsxTest {
    @Test
    fun gen() {
        val jpg = File("D:/Photo-Mgm/Photo-Mgm/shots/sample.jpg").let { f ->
            if (f.exists()) f.readBytes() else byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        }
        val loader: (String?) -> ByteArray? = { ref -> if (ref != null) jpg else null }
        val rows = listOf(
            LedgerRow("2026-09-03 08:30", "K138+700M（上行）", "交接班、检查装备", "IMG_01.jpg", "IMG_02.jpg"),
            LedgerRow("2026-09-03 09:05", "K145+000M（下行）", "路面障碍物清理 & 交安设施检查", "IMG_03.jpg", null),
            LedgerRow("", "K164+000M", "无时间事件示例 <测试>", null, null),
        )
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(rows, it, loader) }.toByteArray()
        File("D:/Photo-Mgm/Photo-Mgm/shots/export_test.xlsx").writeBytes(bytes)
        println("WROTE ${bytes.size} bytes")
    }
}
