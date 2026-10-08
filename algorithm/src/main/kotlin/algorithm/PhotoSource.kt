// algorithm/PhotoSource.kt —— §4 照片读取模块：平台无关的核心逻辑 + 平台接入接口
// ★ 纯度：本文件零 Android 依赖；MediaStore / SAF / ExifInterface 等平台实现由 data 层注入。
package algorithm

import algorithm.model.Photo
import java.time.LocalDate

/** 未精读 EXIF 前的候选照片（data 层产出，经纯逻辑粗筛后再精读）。 */
data class CandidatePhoto(
    val id: Long,              // MediaStore _ID（Android）；PC 端可为 0
    val sourceRef: String,     // Android=content uri / 物理路径；PC=文件绝对路径
    val displayName: String,   // 文件名（粗筛依据）
)

/** 平台接入：列出某源目录下的候选照片。Android=MediaStore 查询；PC=目录文件扫描。 */
interface PhotoSourceProvider {
    /** @param sourceDir 已授权的源目录（如 DCIM/Camera），返回其下候选照片。 */
    fun listCandidates(sourceDir: String): List<CandidatePhoto>
}

/** 平台接入：EXIF 精读。Android=androidx ExifInterface；PC=自备实现。读失败返回 null，不抛异常。 */
interface ExifReader {
    /** 拍摄时间（含日期）→ epoch 毫秒；读不到返回 null。 */
    fun readCaptureTimeMs(sourceRef: String): Long?
    /** GPS 坐标 → (纬度, 经度)；读不到返回 null。 */
    fun readGps(sourceRef: String): Pair<Double, Double>?

    /**
     * 合并精读：一次打开资源同时读取 (拍摄时间, GPS)。
     * 默认实现退化为两次独立读取（兼容旧实现/PC 端实现）；
     * 平台实现应覆盖本方法以消除重复打开开销（ExifPhotoReader 一次 ExifInterface 构造）。
     * 语义契约与两个独立方法完全一致：失败字段置 null、互不拖累。
     */
    fun readAll(sourceRef: String): Pair<Long?, Pair<Double, Double>?> =
        readCaptureTimeMs(sourceRef) to readGps(sourceRef)
}

/**
 * §4 照片读取核心编排（纯 Kotlin，可移植 PC）：
 *   多源候选 → 目录白名单 → 文件名日期粗筛 → 去重 → EXIF 精读（接口注入）
 */
object PhotoIngest {
    /**
     * 目录白名单：前缀 + 分隔符匹配。
     * 授权 "DCIM/Camera" 不误配 "DCIM/CameraOld"（边界字符必须是路径分隔符）。
     * ★ 容忍源目录尾部斜杠（"DCIM/Camera/" 与 "DCIM/Camera" 等价）。
     * ★ 缺陷回归：sourceRef 可能是 RELATIVE_PATH（相对路径前缀）或 DATA 物理路径（/storage/…/DCIM/Camera/…），
     *   必须同时匹配两种形式，否则 Android 粗筛把全部照片过滤掉 → 0 张。
     */
    fun inSourceDir(path: String, sourceDir: String): Boolean {
        val dir = sourceDir.trimEnd('/', '\\')
        // ① 相对路径前缀匹配（RELATIVE_PATH：DCIM/Camera/IMG.jpg）
        if (path.startsWith(dir)) {
            if (path.length == dir.length) return true
            val c = path[dir.length]
            return c == '/' || c == '\\'
        }
        // ② 物理路径后缀匹配（DATA：/storage/emulated/0/DCIM/Camera/IMG.jpg）
        val idx = path.lastIndexOf(dir)
        if (idx >= 0) {
            val before = idx - 1
            val after = idx + dir.length
            val beforeOk = before < 0 || path[before] == '/' || path[before] == '\\'
            val afterOk = after >= path.length || path[after] == '/' || path[after] == '\\'
            return beforeOk && afterOk
        }
        return false
    }

    /**
     * 粗筛：白名单过滤 → 文件名日期区间过滤 → 按物理路径（sourceRef）去重（保序，保留首现）。
     * @param sourceDirs 空列表 = 不按目录过滤（PC 全目录扫描场景）。
     */
    fun coarseFilter(
        candidates: List<CandidatePhoto>,
        sourceDirs: List<String>,
        range: ClosedRange<LocalDate>,
    ): List<CandidatePhoto> {
        val seen = HashSet<String>()
        val out = mutableListOf<CandidatePhoto>()
        for (c in candidates) {
            if (sourceDirs.isNotEmpty() && sourceDirs.none { inSourceDir(c.sourceRef, it) }) continue
            if (!FileNameDateFilter.isInRange(c.displayName, range.start, range.endInclusive)) continue
            if (!seen.add(c.sourceRef)) continue
            out.add(c)
        }
        return out
    }

    /**
     * 派生文件去重（2026-10-01 新增）：同一照片存在主图 + 压缩版/原图备份等多份派生文件时，
     * 按「基名（去掉派生后缀与扩展名）」分组，每组保留最高优先级版本。
     * 优先级：主图（无派生后缀）> _original > _compressed > 其余派生后缀。
     * 修复场景：9-25 实例 124 个文件 = 62 张主图 + 62 个 _compressed 派生文件，
     * 派生文件无 EXIF 时间/GPS 全部进「未分类」，且与主图重复。
     * @return Pair(去重后候选, 合并组数)（合并组数供 UI 提示「已合并 N 组派生文件」）
     */
    fun dedupDerived(candidates: List<CandidatePhoto>): Pair<List<CandidatePhoto>, Int> {
        // 派生后缀：_compressed/_original/_edited/_modified 等（紧跟扩展名之前，可叠加如 _original_compressed）
        val derivedRe = Regex("""(_(?:compressed|original|edited|modified|optimized|resized|缩略|压缩|原图))+(?=\.[^.]+$)""", RegexOption.IGNORE_CASE)

        /** 基名 = 文件名去掉全部派生后缀与扩展名（_original_compressed 与主图归同一基名）。 */
        fun baseName(name: String): String {
            val stem = derivedRe.replace(name, "")
            val dot = stem.lastIndexOf('.')
            return if (dot >= 0) stem.substring(0, dot) else stem
        }

        /** 派生优先级分数：越小越优先。主图=0 < 仅_original=1 < 仅_compressed=2 < _original_compressed=3。 */
        fun score(name: String): Int {
            val lower = name.lowercase()
            var s = 0
            if (lower.contains("_original")) s += 1
            if (lower.contains("_compressed")) s += 2
            return s
        }

        val best = LinkedHashMap<String, CandidatePhoto>()
        var merged = 0
        for (c in candidates) {
            val key = baseName(c.displayName)
            val prev = best[key]
            if (prev == null) {
                best[key] = c
            } else if (score(c.displayName) < score(prev.displayName)) {
                best[key] = c; merged++
            } else {
                merged++
            }
        }
        return best.values.toList() to merged
    }

    /**
     * EXIF 精读编排：对粗筛结果逐一精读，组装为 Photo。
     * 读失败字段置 null、不中断（§4.3）。并发优化由 data 层实现，核心保持串行简单可移植。
     */
    fun readExif(candidates: List<CandidatePhoto>, exif: ExifReader): List<Photo> =
        candidates.map { c ->
            val (t, g) = runCatching { exif.readAll(c.sourceRef) }.getOrNull() ?: (null to null)
            Photo(
                id = c.id,
                sourceRef = c.sourceRef,
                displayName = c.displayName,
                captureTimeMs = t,
                latitude = g?.first,
                longitude = g?.second,
            )
        }

    /**
     * 精读后按 MediaStore _ID 去重（MediaStore 与 SAF 可能以不同 uri 指向同一文件；_ID 唯一）。
     * ★ 仅对 id>0（真实 _ID）去重；id<=0（PC 端无意义默认 0）全部保留，避免静默丢照片。
     */
    fun dedupById(photos: List<Photo>): List<Photo> {
        val seen = HashSet<Long>()
        // id<=0 直接保留（谓词 true）；id>0 按首次出现去重
        return photos.filter { it.id <= 0 || seen.add(it.id) }
    }
}
