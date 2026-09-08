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
     * EXIF 精读编排：对粗筛结果逐一精读，组装为 Photo。
     * 读失败字段置 null、不中断（§4.3）。并发优化由 data 层实现，核心保持串行简单可移植。
     */
    fun readExif(candidates: List<CandidatePhoto>, exif: ExifReader): List<Photo> =
        candidates.map { c ->
            val gps = runCatching { exif.readGps(c.sourceRef) }.getOrNull()
            Photo(
                id = c.id,
                sourceRef = c.sourceRef,
                displayName = c.displayName,
                captureTimeMs = runCatching { exif.readCaptureTimeMs(c.sourceRef) }.getOrNull(),
                latitude = gps?.first,
                longitude = gps?.second,
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
