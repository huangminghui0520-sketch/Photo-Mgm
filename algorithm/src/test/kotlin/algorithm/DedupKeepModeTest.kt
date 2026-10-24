// algorithm/DedupKeepModeTest.kt —— 重复照片三态保留（B1）与干预聚合（B3）单元测试
package algorithm

import algorithm.model.Cluster
import algorithm.model.Photo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DedupKeepModeTest {

    private fun photo(id: Long, name: String, tMs: Long, lat: Double = 23.25, lng: Double = 113.02) =
        Photo(id, "/src/$name", name, tMs, lat, lng)

    private val t0 = 1_700_000_000_000L

    // ── groupDupContent：同刻同 GPS 合并 ──
    @Test
    fun `同刻同GPS两张合并为一组 代表为主图`() {
        val main = photo(1, "IMG_20261024_084736478.jpg", t0)
        val orig = photo(2, "IMG_20261024_084736479_original.jpg", t0)
        val groups = PhotoIngest.groupDupContent(listOf(main, orig))
        assertEquals(1, groups.size)
        assertEquals(setOf(1L), groups.keys)
        assertEquals(listOf(2L), groups[1]!!.map { it.id })
    }

    @Test
    fun `拍摄时间超出容差不合并`() {
        val a = photo(1, "IMG_20261024_084736478.jpg", t0)
        val b = photo(2, "IMG_20261024_084736479_original.jpg", t0 + 5_000)
        assertTrue(PhotoIngest.groupDupContent(listOf(a, b)).isEmpty())
    }

    @Test
    fun `时间容差内合并 超容差不合并`() {
        val a = photo(1, "IMG_20261024_084736478.jpg", t0)
        val b = photo(2, "IMG_20261024_084736479_original.jpg", t0 + 500)   // 500ms ≤ 1000ms
        val c = photo(3, "IMG_20261024_084736480.jpg", t0 + 2_500)          // 2500ms > 1000ms
        val groups = PhotoIngest.groupDupContent(listOf(a, b, c))
        assertEquals(1, groups.size)   // 仅 a/b 成组，c 独立
        assertEquals(setOf(1L), groups.keys)
        assertEquals(listOf(2L), groups[1]!!.map { it.id })
    }

    @Test
    fun `GPS超过距离阈值不合并`() {
        val a = photo(1, "IMG_20261024_084736478.jpg", t0, 23.251966, 113.028645)
        val b = photo(2, "IMG_20261024_084736479_original.jpg", t0, 23.25, 113.03)   // 约 300m
        assertTrue(PhotoIngest.groupDupContent(listOf(a, b)).isEmpty())
    }

    @Test
    fun `GPS容差内合并`() {
        val a = photo(1, "IMG_20261024_084736478.jpg", t0, 23.251966, 113.028645)
        val b = photo(2, "IMG_20261024_084736479_original.jpg", t0, 23.251970, 113.028642)  // <10m
        assertEquals(1, PhotoIngest.groupDupContent(listOf(a, b)).size)
    }

    @Test
    fun `单份照片不进组`() {
        val a = photo(1, "IMG_20261024_084736478.jpg", t0)
        val b = photo(2, "IMG_20261024_084832562.jpg", t0 + 3_000_000)   // 不同时刻
        assertTrue(PhotoIngest.groupDupContent(listOf(a, b)).isEmpty())
    }

    // ── ★ 2026-10-24 真实数据验证修复：禁止链式传递合并 ──
    @Test
    fun `相邻两个瞬间各自双份 不跨组合并`() {
        // 真实场景（15:37 过车涵洞）：瞬间1 = A(37s 主图)+B(38s original)，
        // 瞬间2 = C(39s 主图)+D(39s original)；B-C 差 1s（容差内）但 A-C 差 2s。
        // 并查集链式会把 4 张并一组 → 仅保留带水印时误删另一瞬间（丢真实照片）。
        // 修复后必须拆成 {A,B} 与 {C,D} 两组。
        val a = photo(1, "IMG_20261024_153737864.jpg", t0)
        val b = photo(2, "IMG_20261024_153737865_original.jpg", t0 + 1_000)
        val c = photo(3, "IMG_20261024_153739389.jpg", t0 + 2_000)
        val d = photo(4, "IMG_20261024_153739391_original.jpg", t0 + 2_000)
        val groups = PhotoIngest.groupDupContent(listOf(a, b, c, d))
        assertEquals(2, groups.size, "相邻两瞬间必须拆成 2 组（禁止链式）")
        assertEquals(setOf(1L, 3L), groups.keys)
        assertEquals(listOf(2L), groups[1]!!.map { it.id })
        assertEquals(listOf(4L), groups[3]!!.map { it.id })
    }

    @Test
    fun `同刻双份EXIF秒差1s仍可合并`() {
        // 真实场景：水印相机双份 EXIF 秒级时间戳可能差 1s（如 08:47:45 主图 + 08:47:46 original），
        // GPS 完全一致 → 仍应合并为一组（严格同秒判定反而漏掉）。
        val a = photo(1, "IMG_20261024_084745941.jpg", t0)
        val b = photo(2, "IMG_20261024_084745943_original.jpg", t0 + 1_000)
        assertEquals(1, PhotoIngest.groupDupContent(listOf(a, b)).size)
    }

    // ── expandForKeepMode：三态展开 ──
    @Test
    fun `仅保留带水印 展开后只剩主图`() {
        val dup = mapOf(1L to listOf(photo(2, "IMG_20261024_084736479_original.jpg", t0)))
        val out = PhotoIngest.expandForKeepMode(listOf(1L, 2L), dup, DedupKeepMode.KEEP_WATERMARKED)
        assertEquals(listOf(1L), out)
    }

    @Test
    fun `仅保留不带水印 展开后只剩original`() {
        val dup = mapOf(1L to listOf(photo(2, "IMG_20261024_084736479_original.jpg", t0)))
        val out = PhotoIngest.expandForKeepMode(listOf(1L, 2L), dup, DedupKeepMode.KEEP_PLAIN)
        assertEquals(listOf(2L), out)
    }

    @Test
    fun `两者都保留 原样返回`() {
        val dup = mapOf(1L to listOf(photo(2, "IMG_20261024_084736479_original.jpg", t0)))
        assertEquals(listOf(1L, 2L), PhotoIngest.expandForKeepMode(listOf(1L, 2L), dup, DedupKeepMode.KEEP_BOTH))
    }

    @Test
    fun `单份照片不参与三态`() {
        val dup = mapOf(1L to listOf(photo(2, "IMG_20261024_084736479_original.jpg", t0)))
        // 事件照片含单份 3 号（自带相机）与重复对 1/2
        val out = PhotoIngest.expandForKeepMode(listOf(1L, 2L, 3L), dup, DedupKeepMode.KEEP_WATERMARKED)
        assertEquals(listOf(1L, 3L), out)
    }

    @Test
    fun `人工标记优先 不受三态过滤`() {
        val dup = mapOf(1L to listOf(photo(2, "IMG_20261024_084736479_original.jpg", t0)))
        val out = PhotoIngest.expandForKeepMode(listOf(1L, 2L), dup, DedupKeepMode.KEEP_WATERMARKED, keepAlways = setOf(2L))
        assertEquals(listOf(1L, 2L), out)
    }

    @Test
    fun `不带水印模式 组内无original时回退主图`() {
        val dup = mapOf(1L to listOf(photo(2, "IMG_20261024_084736480.jpg", t0)))   // 副本也是主图命名
        val out = PhotoIngest.expandForKeepMode(listOf(1L, 2L), dup, DedupKeepMode.KEEP_PLAIN)
        assertEquals(listOf(1L), out)
    }

    @Test
    fun `不带水印模式 同组含original与compressed 优先选original`() {
        // 回归 P3-6：组内既有 _original（无水印原图，语义优先）又有 _compressed（压缩副本）时，
        // 必须按派生分数选 _original（原实现取"列表顺序第一个派生"，_compressed 排在前面时会选错）。
        // 构造 dupList 顺序 = [compressed, original]，旧实现 firstOrNull 会选中 compressed。
        val dup = mapOf(1L to listOf(
            photo(3, "IMG_20261024_084736480_compressed.jpg", t0),
            photo(2, "IMG_20261024_084736479_original.jpg", t0),
        ))
        val out = PhotoIngest.expandForKeepMode(listOf(1L, 2L, 3L), dup, DedupKeepMode.KEEP_PLAIN)
        assertEquals(listOf(2L), out, "KEEP_PLAIN 应保留 _original（分数 1 < compressed 2），与列表顺序无关")
    }

    @Test
    fun `展开保持输入顺序且去重`() {
        val dup = mapOf(1L to listOf(photo(2, "IMG_20261024_084736479_original.jpg", t0)))
        val out = PhotoIngest.expandForKeepMode(listOf(2L, 3L, 1L), dup, DedupKeepMode.KEEP_WATERMARKED)
        assertEquals(listOf(1L, 3L), out)   // 2→1，顺序按输入
    }

    // ── 派生判定 ──
    @Test
    fun `派生判定正确`() {
        assertFalse(isDerivedName("IMG_20261024_084736478.jpg"))
        assertTrue(isDerivedName("IMG_20261024_084736479_original.jpg"))
        assertTrue(isDerivedName("IMG_20261024_084736480_compressed.jpg"))
        assertEquals(0, derivedScore("IMG_20261024_084736478.jpg"))
        assertEquals(1, derivedScore("IMG_20261024_084736479_original.jpg"))
        assertEquals(2, derivedScore("IMG_20261024_084736480_compressed.jpg"))
    }

    // ── ★ 2026-10-24 B4：GPS 无效（缺失/清零）降级判重（真机 ROM 相册脱敏场景）──
    private fun photoNoGps(id: Long, name: String, tMs: Long, lat: Double? = null, lng: Double? = null) =
        Photo(id, "/src/$name", name, tMs, lat, lng)

    @Test
    fun `GPS被清零为0 降级为时间加序号判重并成组`() {
        // 真机证据：GPSLat=0/1,0/1,0/1 + 空 ref → lat=0.0,lng=0.0；文件名毫秒序号 478/479 差 1
        val a = photoNoGps(1, "IMG_20261024_084736478.jpg", t0, 0.0, 0.0)
        val b = photoNoGps(2, "IMG_20261024_084736479_original.jpg", t0, 0.0, 0.0)
        val groups = PhotoIngest.groupDupContent(listOf(a, b))
        assertEquals(1, groups.size)
        assertEquals(setOf(1L), groups.keys)
        assertEquals(listOf(2L), groups[1]!!.map { it.id })
    }

    @Test
    fun `GPS缺失为null 降级判重仍成组`() {
        val a = photoNoGps(1, "IMG_20261024_084736478.jpg", t0)
        val b = photoNoGps(2, "IMG_20261024_084736479_original.jpg", t0 + 1_000)
        assertEquals(1, PhotoIngest.groupDupContent(listOf(a, b)).size)
    }

    @Test
    fun `双方都无派生后缀 降级不判重`() {
        // 手机自带相机照片（无 _original 后缀）与 todayCam 主图时间相邻 → 不并（单份永不误删）
        val a = photoNoGps(1, "IMG_20261024_084736478.jpg", t0)
        val b = photoNoGps(2, "IMG_20261024_084736479.jpg", t0 + 1_000)
        assertTrue(PhotoIngest.groupDupContent(listOf(a, b)).isEmpty())
    }

    @Test
    fun `一方GPS有效一方清零 不降级不判重`() {
        val a = photo(1, "IMG_20261024_084736478.jpg", t0, 23.251966, 113.028645)   // 有效
        val b = photoNoGps(2, "IMG_20261024_084736479_original.jpg", t0, 0.0, 0.0)  // 清零
        assertTrue(PhotoIngest.groupDupContent(listOf(a, b)).isEmpty())
    }

    @Test
    fun `降级时毫秒序号差超过1000不判重`() {
        val a = photoNoGps(1, "IMG_20261024_084736478.jpg", t0)
        val b = photoNoGps(2, "IMG_20261024_084737579_original.jpg", t0)   // 序号差 1101
        assertTrue(PhotoIngest.groupDupContent(listOf(a, b)).isEmpty())
    }

    @Test
    fun `降级不改变有效GPS判重逻辑`() {
        // 有有效 GPS 的照片仍走「时间+GPS 距离」，不误用降级
        val a = photo(1, "IMG_20261024_084736478.jpg", t0, 23.251966, 113.028645)
        val b = photo(2, "IMG_20261024_084736479_original.jpg", t0, 23.251970, 113.028642)
        val c = photo(3, "IMG_20261024_084736480.jpg", t0, 23.25, 113.03)   // 约 300m 远
        val groups = PhotoIngest.groupDupContent(listOf(a, b, c))
        assertEquals(1, groups.size)
        assertEquals(setOf(1L), groups.keys)
        assertEquals(listOf(2L), groups[1]!!.map { it.id })
    }

    // ── B3：干预聚合 ──
    @Test
    fun `CLUSTER_SPAN 同事件聚合为一条`() {
        // 构造：巡查日当天 08:20 左右，事件1 拥有三个相距 >75m 的簇 → 3 对超距 → 聚合为 1 条
        val dayStart = java.time.LocalDate.of(2026, 10, 24).atStartOfDay()
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val t = dayStart + 30_000_000L
        val e = LogEventHelper.event(1, "桥下空间巡查", "K117+430M", t)
        val c1 = Cluster(listOf(photo(1, "IMG_20261024_084736478.jpg", t, 23.29, 113.02)), t, 23.29, 113.02, emptyList())
        val c2 = Cluster(listOf(photo(2, "IMG_20261024_084736479_original.jpg", t, 23.29, 113.05)), t, 23.29, 113.05, emptyList())
        val c3 = Cluster(listOf(photo(3, "IMG_20261024_084736480.jpg", t, 23.29, 113.08)), t, 23.29, 113.08, emptyList())
        val result = EventClassifyEngine(50.0).classify(
            listOf(e), listOf(c1, c2, c3), emptyList(),
            java.time.LocalDate.of(2026, 10, 24), 0)
        val spans = result.interventions.filter { it.kind == Kind.CLUSTER_SPAN }
        assertEquals(1, spans.size)   // 3 簇两两超距（3 对）→ 聚合为 1 条
        assertTrue(spans[0].message.contains("3 组照片 GPS 跨度大"))
        assertEquals(3, spans[0].photoIds.size)
    }
}

/** 测试辅助：构造 LogEvent（简化字段）。 */
private object LogEventHelper {
    fun event(id: Int, type: String, loc: String, startMs: Long): algorithm.model.LogEvent =
        algorithm.model.LogEvent(
            id = id, startTimeMs = startMs, endTimeMs = startMs,
            timePoints = emptyList(), location = loc, eventType = type,
            description = "测试事件 $id",
        )
}
