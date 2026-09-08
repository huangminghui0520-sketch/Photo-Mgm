// PhotoIngest 单元测试（§4.2~§4.4 / §9 测试要点）
package algorithm

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoIngestTest {
    private val day = LocalDate.of(2026, 8, 14)

    private fun c(id: Long, ref: String, name: String) = CandidatePhoto(id, ref, name)

    // ---- 目录白名单 ----

    @Test fun `前缀分隔符匹配`() {
        assertTrue(PhotoIngest.inSourceDir("DCIM/Camera/a.jpg", "DCIM/Camera"))
        assertTrue(PhotoIngest.inSourceDir("DCIM/Camera", "DCIM/Camera"))
        assertTrue(PhotoIngest.inSourceDir("DCIM/Camera/sub/b.jpg", "DCIM/Camera"))
    }

    @Test fun `不误配同名前缀目录`() {
        assertFalse(PhotoIngest.inSourceDir("DCIM/CameraOld/a.jpg", "DCIM/Camera"))
        assertFalse(PhotoIngest.inSourceDir("DCIM/Camera2/a.jpg", "DCIM/Camera"))
        assertFalse(PhotoIngest.inSourceDir("DCIM/CameraExtra/b.jpg", "DCIM/Camera"))
    }

    // ---- 粗筛：白名单 + 日期 + 去重 ----

    @Test fun `粗筛保留日期内且在白名单内的照片`() {
        val candidates = listOf(
            c(1, "DCIM/Camera/IMG_20260814_101530.jpg", "IMG_20260814_101530.jpg"),
            c(2, "DCIM/Camera/IMG_20260815_090000.jpg", "IMG_20260815_090000.jpg"),   // 超出日期
            c(3, "DCIM/CameraOld/IMG_20260814_120000.jpg", "IMG_20260814_120000.jpg"), // 目录越界
            c(4, "Pictures/IMG_20260814_130000.jpg", "IMG_20260814_130000.jpg"),       // 不在白名单
        )
        val out = PhotoIngest.coarseFilter(candidates, listOf("DCIM/Camera"), day..day)
        assertEquals(listOf("IMG_20260814_101530.jpg"), out.map { it.displayName })
    }

    @Test fun `粗筛去重保留首现`() {
        val candidates = listOf(
            c(1, "DCIM/Camera/IMG_20260814_101530.jpg", "IMG_20260814_101530.jpg"),
            c(9, "content://media/IMG_20260814_101530.jpg", "IMG_20260814_101530.jpg"), // 同文件不同引用
        )
        val out = PhotoIngest.coarseFilter(candidates, listOf("DCIM/Camera"), day..day)
        assertEquals(1, out.size)
        assertEquals(1L, out[0].id)
    }

    @Test fun `sourceDirs为空不过滤目录`() {
        val candidates = listOf(
            c(1, "D:/photos/IMG_20260814_101530.jpg", "IMG_20260814_101530.jpg"),
            c(2, "D:/photos/IMG_20260815_090000.jpg", "IMG_20260815_090000.jpg"),
        )
        val out = PhotoIngest.coarseFilter(candidates, emptyList(), day..day)
        assertEquals(1, out.size)
    }

    // ---- EXIF 精读编排 ----

    private class FakeExif : ExifReader {
        override fun readCaptureTimeMs(sourceRef: String): Long? =
            if (sourceRef.contains("good")) 1780000000000L else null
        override fun readGps(sourceRef: String): Pair<Double, Double>? =
            if (sourceRef.contains("good")) 23.1 to 113.2 else null
    }

    @Test fun `精读成功字段齐全`() {
        val p = PhotoIngest.readExif(listOf(c(1, "DCIM/Camera/good.jpg", "a.jpg")), FakeExif())
        assertEquals(1L, p[0].id)
        assertEquals(1780000000000L, p[0].captureTimeMs)
        assertEquals(23.1, p[0].latitude)
        assertEquals(113.2, p[0].longitude)
    }

    @Test fun `精读失败字段置null不中断`() {
        val ps = PhotoIngest.readExif(
            listOf(
                c(1, "DCIM/Camera/bad.jpg", "a.jpg"),
                c(2, "DCIM/Camera/good.jpg", "b.jpg"),
            ),
            FakeExif(),
        )
        assertNull(ps[0].captureTimeMs)
        assertNull(ps[0].latitude)
        assertNull(ps[0].longitude)
        assertEquals(1780000000000L, ps[1].captureTimeMs)  // 前一条失败不中断后续
    }

    @Test fun `按id去重`() {
        val photos = listOf(
            algorithm.model.Photo(5, "a", "1.jpg", null, null, null),
            algorithm.model.Photo(5, "b", "1.jpg", null, null, null),
            algorithm.model.Photo(7, "c", "2.jpg", null, null, null),
        )
        val out = PhotoIngest.dedupById(photos)
        assertEquals(2, out.size)
        assertEquals(listOf(5L, 7L), out.map { it.id })
    }

    // ---- 探针测试：缺陷回归（先 RED 后 GREEN）----

    // ★ 缺陷回归：Android 10+ MediaStore DATA 列返回物理路径（/storage/emulated/0/DCIM/Camera/IMG.jpg），
    //   源目录为相对路径（DCIM/Camera）。inSourceDir 必须同时匹配物理路径（后缀）与相对路径（前缀），
    //   否则粗筛把全部照片过滤掉 → 0 张（用户实测"粗选仍无照片"）。
    @Test fun `物理路径sourceRef匹配相对路径源目录`() {
        assertTrue(PhotoIngest.inSourceDir(
            "/storage/emulated/0/DCIM/Camera/IMG_20260814_101530.jpg", "DCIM/Camera"))
        assertTrue(PhotoIngest.inSourceDir(
            "/storage/emulated/0/DCIM/Camera/sub/IMG_20260814_101530.jpg", "DCIM/Camera"))
        assertTrue(PhotoIngest.inSourceDir(
            "D:/photos/DCIM/Camera/IMG_20260814_101530.jpg", "DCIM/Camera"))
    }

    @Test fun `物理路径sourceRef不误配同名前缀目录`() {
        assertFalse(PhotoIngest.inSourceDir(
            "/storage/emulated/0/DCIM/CameraOld/IMG_20260814_101530.jpg", "DCIM/Camera"))
        assertFalse(PhotoIngest.inSourceDir(
            "/storage/emulated/0/DCIM/CameraExtra/IMG_20260814_101530.jpg", "DCIM/Camera"))
        assertFalse(PhotoIngest.inSourceDir(
            "/storage/emulated/0/Other/DCIM/CameraBackup/IMG_20260814_101530.jpg", "DCIM/Camera"))
    }

    @Test fun `粗筛接受物理路径sourceRef`() {
        val candidates = listOf(
            c(1, "/storage/emulated/0/DCIM/Camera/IMG_20260814_101530.jpg", "IMG_20260814_101530.jpg"),
            c(2, "/storage/emulated/0/DCIM/CameraOld/IMG_20260814_120000.jpg", "IMG_20260814_120000.jpg"),
            c(3, "/storage/emulated/0/DCIM/Camera/IMG_20260815_090000.jpg", "IMG_20260815_090000.jpg"),
        )
        val out = PhotoIngest.coarseFilter(candidates, listOf("DCIM/Camera"), day..day)
        assertEquals(listOf("IMG_20260814_101530.jpg"), out.map { it.displayName })
    }

    @Test fun `PC端无意义id为0时不得按id去重`() {
        val photos = listOf(
            algorithm.model.Photo(0, "a.jpg", "a.jpg", null, null, null),
            algorithm.model.Photo(0, "b.jpg", "b.jpg", null, null, null),
            algorithm.model.Photo(0, "c.jpg", "c.jpg", null, null, null),
        )
        val out = PhotoIngest.dedupById(photos)
        assertEquals(3, out.size, "PC 端 id 均为 0（无意义），去重不得误删到 1 张")
    }

    @Test fun `源目录带尾部斜杠仍匹配`() {
        assertTrue(PhotoIngest.inSourceDir("DCIM/Camera/a.jpg", "DCIM/Camera/"))
        assertTrue(PhotoIngest.inSourceDir("DCIM/Camera/sub/b.jpg", "DCIM/Camera/"))
    }

    @Test fun `跨日加班次日照片在粗筛范围内保留`() {
        // 契约（§4.2）：末条跨日加班（23:30~次日01:00）的次日照片，调用方须传 end=次日；范围内保留、范围外剔除
        val candidates = listOf(
            c(1, "DCIM/Camera/IMG_20260904_003000.jpg", "IMG_20260904_003000.jpg"),
            c(2, "DCIM/Camera/IMG_20260905_080000.jpg", "IMG_20260905_080000.jpg"),
        )
        val out = PhotoIngest.coarseFilter(
            candidates,
            listOf("DCIM/Camera"),
            LocalDate.of(2026, 9, 3)..LocalDate.of(2026, 9, 4),
        )
        assertEquals(listOf("IMG_20260904_003000.jpg"), out.map { it.displayName })
    }
}
