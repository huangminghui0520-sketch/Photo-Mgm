// data/src/test 桌面数据层集成验证（本地测试样本）
package com.photomgm.data

import algorithm.AlgorithmApi
import algorithm.PhotoIngest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 桌面数据接入层端到端验证（§4 移植验收）：
 * FilePhotoSourceProvider 扫描 → ExifPhotoReader 精读 → PhotoIngest 粗筛/去重
 * → AlgorithmApi GPS分组/分类/台账 → 导出 Excel 字节流。
 * 不依赖 Android；用 ImageIO + 手工注入 EXIF 不可行（EXIF 写入需 exiftool），
 * 故此处用无 EXIF 照片验证"文件名日期粗筛 + 无GPS照片走 singles"链路。
 */
class DesktopIntegrationTest {

    @TempDir
    lateinit var tmp: File

    private fun makePhoto(dir: File, name: String): File {
        val img = BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.drawString(name, 40, 300)
        g.dispose()
        val out = File(dir, name)
        ImageIO.write(img, "jpg", out)
        return out
    }

    @Test
    fun `扫描-粗筛-精读-去重 全链路`() {
        val srcDir = File(tmp, "photos").apply { mkdirs() }
        // 当日文件名 → 应通过文件名日期粗筛
        repeat(3) { makePhoto(srcDir, "IMG_20260926_${(8 + it)}${"0"}${it + 1}.jpg") }
        // 非图片文件应被排除
        File(srcDir, "note.txt").writeText("x")

        val provider = FilePhotoSourceProvider()
        val cands = provider.listCandidates(srcDir.absolutePath)
        assertEquals(3, cands.size, "应扫描到 3 张图片，排除非图片")

        // 粗筛：当日日期
        val coarse = PhotoIngest.coarseFilter(cands, listOf(srcDir.absolutePath),
            LocalDate.of(2026, 9, 26)..LocalDate.of(2026, 9, 26))
        assertEquals(3, coarse.size)

        // 精读（无 EXIF → captureTime null，GPS null）
        val photos = PhotoIngest.readExif(coarse, ExifPhotoReader())
        assertEquals(3, photos.size)
        assertTrue(photos.all { it.sourceRef.startsWith(srcDir.absolutePath) })

        // 去重
        val dedup = PhotoIngest.dedupById(photos)
        assertEquals(3, dedup.size)
    }

    @Test
    fun `解析日志并导出Excel字节流`() {
        val logText = """
            2026-09-26 08:00 巡查开始
            2026-09-26 08:15 K100+500 交通事故处置：护栏受损
            2026-09-26 09:00 K102 抛洒物：轮胎皮清理
        """.trimIndent()
        val parsed = AlgorithmApi.parseLog(logText, LocalDate.of(2026, 9, 26))
        assertTrue(parsed.events.isNotEmpty())

        val srcDir = File(tmp, "photos2").apply { mkdirs() }
        repeat(2) { makePhoto(srcDir, "IMG_20260926_090${it + 1}.jpg") }
        val cands = FilePhotoSourceProvider().listCandidates(srcDir.absolutePath)
        val photos = PhotoIngest.readExif(cands, ExifPhotoReader())
        val dedup = PhotoIngest.dedupById(photos)

        val g = AlgorithmApi.gpsGroup(dedup)
        val c = AlgorithmApi.classify(parsed.events, g.clusters, g.singles,
            LocalDate.of(2026, 9, 26), g.splitCount)
        assertNotNull(c)

        // 台账
        val photoById: (Long) -> algorithm.model.Photo? = { id -> dedup.find { it.id == id } }
        val rows = AlgorithmApi.buildLedger(parsed.events, c.eventPhotoMap, photoById)
        assertTrue(rows.isNotEmpty())

        // 导出 Excel 字节流
        val bos = ByteArrayOutputStream()
        AlgorithmApi.exportLedgerXlsx(rows, bos) { null }
        assertTrue(bos.size() > 0)
        val header = String(bos.toByteArray(), Charsets.UTF_8)
        assertTrue(header.contains("PK"), "应为 OOXML zip 容器（PK 头）")
    }
}
