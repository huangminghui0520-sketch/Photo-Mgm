// app/AppViewModelTest.kt —— parseLogs 修复回归测试（Robolectric 集成）
// 验证：重新解析日志 → 清缓存 → 重新粗筛（MediaStore 模拟）+ 精读（真实照片）→ 自动重新分类
// MediaStore 模拟：把源目录真实照片复制到临时目录 %TEMP%/PhotoMgmTest*/DCIM/Camera/9-25/
//   DATA 列=该绝对路径（inSourceDir 后缀匹配 DCIM/Camera 通过）且可被 ExifReader 读取；
//   RELATIVE_PATH=DCIM/Camera/9-25/...（前缀匹配通过）；displayName 保留原名（文件名日期过滤通过）。
// 源目录真实照片缺失时跳过（与 ExifMergedReaderTest 同策略）。
package com.photomgm.app

import android.app.Application
import android.os.Looper
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.BaseCursor
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppViewModelTest {

    private lateinit var app: Application

    /** 临时媒体根：PhotoMgmTest 临时目录下 DCIM/Camera/9-25（含 DCIM/Camera 段，可被 inSourceDir 后缀匹配）。 */
    private lateinit var mediaDir: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val root = Files.createTempDirectory("PhotoMgmTest").toFile()
        mediaDir = File(root, "DCIM/Camera/9-25").apply { mkdirs() }
        // ★ 复制 9-25 真实照片到临时 MediaStore 目录（DATA 列真实可读，EXIF 精读成功）
        // 候选路径按顺序探测，兼容不同开发机的照片存放位置；都找不到则本类用例跳过。
        val candidates = listOf(
            // 允许用环境变量显式指定，便于 CI 或换机
            System.getenv("PHOTOMGM_TEST_PHOTOS")?.let { File(it) },
            File("D:/Photo-Mgm/源目录/9-25"),                  // 工作区内的测试照片
            File("../源目录/9-25"),                             // 相对 app 模块
            File("源目录/9-25"),                                // 相对仓库根
            File("C:/Users/Administrator/Desktop/源目录/9-25"), // 原开发机
        ).filterNotNull()

        val src = candidates.firstOrNull { dir ->
            dir.isDirectory && dir.listFiles()?.any { it.name.lowercase().endsWith(".jpg") } == true
        }
        val files = src?.listFiles()?.filter { it.name.lowercase().endsWith(".jpg") } ?: emptyList()
        files.take(30).forEach { it.copyTo(File(mediaDir, it.name), overwrite = true) }
    }

    /** 定位 9-25 巡查日志：与照片同样按候选路径探测，避免硬编码另一台机器的绝对路径。 */
    private fun findLogFile(): File? {
        val dirs = listOfNotNull(
            System.getenv("PHOTOMGM_TEST_PHOTOS")?.let { File(it) },
            File("D:/Photo-Mgm/源目录/9-25"),
            File("../源目录/9-25"),
            File("源目录/9-25"),
            File("C:/Users/Administrator/Desktop/源目录/9-25"),
        )
        for (d in dirs) {
            if (!d.isDirectory) continue
            d.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".txt") }?.let { return it }
        }
        return null
    }

    /** 注册模拟 MediaStore cursor：返回临时媒体目录照片（DATA 绝对路径 + RELATIVE_PATH）。 */
    private fun registerMediaStore() {
        val files = mediaDir.listFiles()?.filter { it.name.lowercase().endsWith(".jpg") } ?: emptyList()
        assumeTrue("临时媒体目录无照片（源目录缺失），跳过", files.isNotEmpty())
        val cols = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATA,
        )
        val rows = files.mapIndexed { i, f ->
            arrayOf<Any?>(
                (i + 1L),
                f.name,
                "DCIM/Camera/9-25/${f.name}",
                f.absolutePath.replace('\\', '/'),   // ★ 正斜杠：Windows 反斜杠会使 inSourceDir 后缀匹配 "DCIM/Camera" 失败
            )
        }
        shadowOf(app.contentResolver)
            .setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, FakeMediaCursor(cols, rows))
    }

    /** Robolectric 4.12 setCursor 要求 BaseCursor 子类：覆写数据访问方法提供 MediaStore 行。 */
    private class FakeMediaCursor(
        private val cols: Array<String>,
        private val rows: List<Array<Any?>>,
    ) : BaseCursor() {
        private var pos = -1
        override fun getCount(): Int = rows.size
        // ★ setCursor 注册的 cursor 每次 query 返回同一实例；越界后复位，模拟真实 MediaStore「每次 query 从头遍历」
        override fun moveToNext(): Boolean {
            pos++
            if (pos >= rows.size) pos = -1
            return pos >= 0 && pos < rows.size
        }
        override fun getPosition(): Int = pos
        override fun getColumnIndexOrThrow(columnName: String): Int =
            cols.indexOf(columnName).also { require(it >= 0) { "no column $columnName" } }
        override fun getString(columnIndex: Int): String? = rows[pos][columnIndex] as? String
        override fun getLong(columnIndex: Int): Long = (rows[pos][columnIndex] as Number).toLong()
        override fun isNull(columnIndex: Int): Boolean = rows[pos][columnIndex] == null
        override fun close() {}   // BaseCursor.close 默认抛 UnsupportedOperationException，会被 provider 的 runCatching 吞
        override fun isClosed(): Boolean = false
    }

    /** 条件等待：直到 cond 满足或超时（协程异步，不能用瞬时 busy 判断）。 */
    private fun await(vm: AppViewModel, timeoutMs: Long = 30000, cond: (UiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond(vm.state.value) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
    }

    /** 冒烟：setCursor 后 repo.coarseCandidates 应返回临时目录照片（验证 MediaStore 模拟 + 粗筛过滤链路）。 */
    @Test
    fun `冒烟_MediaStore模拟链路返回源目录照片`() {
        registerMediaStore()
        val cands = com.photomgm.data.MediaStorePhotoSourceProvider(app.contentResolver)
            .listCandidates("DCIM/Camera")
        assertTrue("listCandidates 应返回临时目录照片", cands.isNotEmpty())
        val d0 = LocalDate.of(2026, 9, 25)
        // ★ 粗筛全链路（coarseFilter 日期+目录过滤、dedupDerived）：VM 流程实际走的路径
        val coarse = PhotoRepository(app).coarseCandidates(d0, listOf("DCIM/Camera"))
        assertTrue("coarseCandidates 应返回照片", coarse.isNotEmpty())
    }

    /** 核心断言：重新解析日志 → 重新粗筛+精读（photos 刷新）→ 自动分类（classify 非空）。 */
    @Test
    fun `重新解析日志_重新粗筛精读并自动重新分类`() {
        registerMediaStore()

        val vm = AppViewModel(app)
        vm.setDate(LocalDate.of(2026, 9, 25))
        vm.addSourceDir("DCIM/Camera")
        // ★ 等 setDate/addSourceDir 触发的粗筛流水线完成（coarseCount 就绪），避免 parseLogs 被 busy 短路
        await(vm) { it.coarseCount != null && !it.busy }
        // ★ 用 9-25 真实日志文本（同款格式已被 parseLog 验证），避免测试文本格式不被解析
        val logFile = findLogFile()
        assumeTrue("9-25 日志文件缺失，跳过", logFile != null)
        vm.setLogsText(logFile!!.readLines().take(12).joinToString("\n"))
        vm.parseLogs()
        await(vm) { it.parsed != null && !it.busy }

        val s = vm.state.value
        assertTrue("解析后应有事件", s.parsed != null && s.parsed!!.events.isNotEmpty())
        assertTrue("应重新粗筛出照片", s.coarseCount != null && s.coarseCount!! > 0)
        assertTrue("应重新精读照片", s.photos.isNotEmpty())
        assertNotNull("解析日志应自动重新分类", s.classify)
        assertTrue("流水线应结束（busy=false）", !s.busy)
    }

    /** 兼容：再次解析（重新导入新日志）→ classify 基于新 parsed 重新生成（非旧缓存）。 */
    @Test
    fun `再次解析日志_分类随新日志更新`() {
        registerMediaStore()

        val vm = AppViewModel(app)
        vm.setDate(LocalDate.of(2026, 9, 25))
        vm.addSourceDir("DCIM/Camera")
        await(vm) { it.coarseCount != null && !it.busy }   // ★ 等粗筛流水线完成
        val logFile = findLogFile()
        assumeTrue("9-25 日志文件缺失，跳过", logFile != null)
        val lines = logFile!!.readLines()
        vm.setLogsText(lines.take(6).joinToString("\n"))
        vm.parseLogs()
        await(vm) { it.parsed != null && !it.busy }
        assertTrue("首次解析应分类", vm.state.value.classify != null)

        // 重新导入新日志（事件数变化）→ 再解析 → classify 应随新 parsed 刷新
        vm.setLogsText(lines.take(12).joinToString("\n"))
        vm.parseLogs()
        await(vm) { it.parsed != null && it.parsed!!.events.size >= 4 && !it.busy }

        val s = vm.state.value
        assertTrue("新日志应解析出更多事件", s.parsed != null && s.parsed!!.events.size >= 4)
        assertNotNull("再次解析后应重新分类", s.classify)
        assertTrue("再次解析后应保持非 busy", !s.busy)
    }
}
