// app/AppViewModel.kt —— UI 状态 + 编排（§8）
// ★ 核心调用一律经 AlgorithmApi 单向调用（§2.4）；本类只做状态聚合与平台侧编排
package com.photomgm.app

import algorithm.AlgorithmApi
import algorithm.ClassifyResult
import algorithm.CandidatePhoto
import algorithm.ParsedLog
import algorithm.model.Photo
import algorithm.model.LedgerRow
import algorithm.model.LogEvent
import android.app.Application
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

data class UiState(
    val date: LocalDate = LocalDate.now(),
    val sourceDirs: List<String> = emptyList(),
    val outputDir: String? = null,
    val namingTemplate: Int = 1,          // 0=事件类型，1=月日+巡查日志内容（默认1）
    val coarseCount: Int? = null,          // 粗筛「已筛出 N 张」
    val logsText: String = "",
    val parsed: ParsedLog? = null,
    val photos: List<Photo> = emptyList(),
    val classify: ClassifyResult? = null,
    val ledger: List<LedgerRow>? = null,
    val busy: Boolean = false,
    /** ★ 2026-10-07 P2：流水线分阶段进度文案（busy 时在进度条下方显示；完成/失败置 null） */
    val progressStage: String? = null,
    val message: String? = null,
    /** 人工移动：photoId → 目标事件（§8.3），持久化在 SettingsStore */
    val overrideMap: Map<Long, Int> = emptyMap(),
    /** 台账照片标记：eventId → (照片1,照片2)，null 侧=默认最早+最晚（§7/§8.2） */
    val marked: Map<Int, Pair<Long?, Long?>> = emptyMap(),
    /** 导出进度 0..1；null=无进行中导出（§8.2 进度条） */
    val exportProgress: Float? = null,
    /** 导出完成信息（§7.4 完成页「成功N/失败M」） */
    val exportMessage: String? = null,
    /** 最近一次导出文件（用户可见位置：content:// 或本地路径），供「打开」入口 */
    val lastExport: String? = null,
)

/**
 * 缩略图预加载上限。
 * 超过这个数量就放弃预加载、交回事件卡片的惰性加载：
 * 一次解码上百张小图会长时间占用内存与主线程，收益不如让用户滚动时按需解码。
 */
private const val THUMB_PRELOAD_MAX = 500

/**
 * ★ 2026-10-08 P1-4：台账缩略图解码并发数（CPU 密集）。
 * 单张解码峰值约 2MB（已采样 800×600），4 并发瞬时 ≈ 8MB，安全；真机可调。
 */
private const val THUMB_DECODE_CONCURRENCY = 4

/**
 * ★ 构造函数必须**只有 (Application) 一个参数**。
 *
 * 原因：框架的 `AndroidViewModelFactory` 通过反射查找恰好为 `(Application)` 的构造函数。
 * 一旦增加第二个参数（即使它有默认值），工厂就找不到构造函数，抛出
 * `RuntimeException: Cannot create an instance of class AppViewModel`，
 * 而 MainActivity 第一行就是 `viewModel()` —— 结果是打开即闪退（2026-10-07 实际踩过）。
 *
 * 需要注入依赖时，用次级构造函数或工厂方法，**不要**动这个签名。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val repo = PhotoRepository(app)

    /**
     * 缩略图缓存。默认接 Coil 全局单例（见 [CoilThumbnailCache]）。
     *
     * ★ `internal` setter 仅供单元测试注入假实现以验证"先清后缓存"的顺序——
     *   因为构造函数签名不能被扩展（见上方注释），这是唯一安全的注入点。
     */
    internal var thumbCache: ThumbnailCache = CoilThumbnailCache(app)

    /**
     * ★ 供平台组件获取 applicationContext（§2.4 核心不碰平台，本类负责提供）。 */
    fun getApp(): Application = getApplication()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** ★ 自动流水线任务句柄：日期/源目录变更后触发，新变更取消旧任务，避免并发扫描。 */
    private var pipelineJob: Job? = null

    /** ★ 单日照片缓存（进程内）：只保留**当前巡查日期**一份，换日期即覆盖（2026-10-07 采纳建议）。
     *   跨进程重启由 SettingsStore 状态缓存兜底。 */
    private data class DatePhotoCache(val date: LocalDate, val photos: List<Photo>, val coarseCount: Int)
    private var photoCache: DatePhotoCache? = null

    init {
        _state.update {
            it.copy(
                date = store.date, sourceDirs = store.sourceDirs,
                outputDir = store.outputDir, namingTemplate = store.namingTemplate,
                logsText = store.logsText, overrideMap = store.overrideMap,
                marked = store.markedMap,
                // ★ 2026-10-07 需求：打开 App 从缓存恢复关闭前的事件列表 + 分类 + 照片，
                //   恢复后 coarseCount 非 null → ensurePipeline 视为已粗筛而跳过，不重跑流水线
                parsed = store.parsedCache,
                classify = store.classifyCache,
                photos = store.photosCache,
                coarseCount = store.coarseCountCache,
            )
        }
        // ★ 2026-10-07 防线一：恢复的照片**异步强制校验**——进程重启期间照片可能被删除/新增
        //   （ContentObserver 收不到），靠这里发现：实时粗筛 ID 集合不一致 → 清照片/分类缓存 → 自动重扫。
        //   parsed（事件列表）与照片无关，保留。
        viewModelScope.launch(Dispatchers.IO) {
            val s0 = state.value
            if (s0.sourceDirs.isEmpty() || s0.photos.isEmpty()) return@launch
            val latest = repo.coarseCandidatesFresh(s0.date, s0.sourceDirs)
            if (latest.map { it.id }.toSet() != s0.photos.map { it.id }.toSet()) {
                store.clearStateCache()
                _state.update {
                    it.copy(photos = emptyList(), coarseCount = null,
                        classify = null, exportMessage = null)
                }
                autoPipeline()
            }
        }
    }

    // ---------- 设置（§8.4 设置即存） ----------
    /**
     * ★ 巡查日期变更：自动触发 粗筛→精读（无需手动点击）；同时清空下游避免旧分类配新日期导出错乱。
     * 清粗筛缓存（§4.4）→ 确保从 MediaStore 重新查询最新照片，再自动重新匹配。
     *
     * ★ 同时清空缩略图缓存：换日期后上一批照片的缩略图已无用，且会白占内存额度。
     *   顺序为「清缓存 → 读取照片信息 → 预缓存新缩略图」，见 clearThumbnailCache / autoPipeline。
     *
     * ★ 2026-10-07（P1）：日期变更 = 旧解析/分类缓存全部失效（事件时间戳基于旧日期）。
     *   内存 parsed 一并清空——界面回到"该日期尚未解析日志"，不残留旧日期事件；
     *   日期照片缓存（datePhotoCache）保留，切回该日期秒恢复、不重扫。
     */
    fun setDate(d: LocalDate) {
        // ★ 日期没变就不重跑：避免重复选同一天时清掉刚缓存的缩略图、导致界面闪一下重新解码
        if (d == store.date) return
        store.date = d
        repo.clearCache()
        clearThumbnailCache()
        store.clearStateCache()
        _state.update {
            it.copy(date = d, parsed = null, classify = null, ledger = null, exportMessage = null,
                photos = emptyList(), coarseCount = null)
        }
        autoPipeline()
    }

    /** ★ 源目录变更：自动触发 粗筛→精读（照片集合已变，清空下游）。清缓存后重新查询最新照片。
     *  ★ 单日缓存：源目录变化 → 照片缓存失效（同日期不同源目录照片不同），清空 photoCache。 */
    fun addSourceDir(dir: String) {
        val dirs = (state.value.sourceDirs + dir).distinct()
        store.sourceDirs = dirs
        repo.clearCache()
        clearThumbnailCache()
        store.clearStateCache()
        photoCache = null
        _state.update { it.copy(sourceDirs = dirs, parsed = null, classify = null, ledger = null, exportMessage = null) }
        autoPipeline()
    }

    fun removeSourceDir(dir: String) {
        val dirs = state.value.sourceDirs - dir
        store.sourceDirs = dirs
        repo.clearCache()
        clearThumbnailCache()
        store.clearStateCache()
        photoCache = null
        _state.update { it.copy(sourceDirs = dirs, parsed = null, classify = null, ledger = null, exportMessage = null) }
        autoPipeline()
    }

    /**
     * ★ 自动流水线：**粗筛必跑（强制实时）** → 校验单日缓存 ID 集合 → 一致复用 photos（跳过 EXIF）/
     *   不一致或无缓存重新精读。**不做自动分类**（2026-10-07 需求：分类统一由「解析日志」触发）。
     * 变更即触发；取消旧任务防止快速连续操作导致旧结果覆盖新结果。
     */
    private fun autoPipeline() {
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch(Dispatchers.IO) {
            val s = state.value
            if (s.sourceDirs.isEmpty()) {
                _state.update { it.copy(coarseCount = null, photos = emptyList(), busy = false, progressStage = null) }
                return@launch
            }
            _state.update {
                it.copy(busy = true, coarseCount = null, photos = emptyList(),
                    classify = null, ledger = null, exportMessage = null,
                    overrideMap = emptyMap(), progressStage = "扫描照片中…")
            }
            // ★ 2026-10-07 与 parseLogs 同构：日期/源目录变化 = 全新一批照片与事件，上一批人工移动作废
            store.overrideMap = emptyMap()
            // ★ 防线一：强制实时粗筛（绕过 30 分钟 TTL）——拿最新 ID 集合，删除/新增照片立即感知
            val cands = repo.coarseCandidatesFresh(s.date, s.sourceDirs)
            if (cands.isEmpty()) {
                photoCache = null
                _state.update {
                    it.copy(coarseCount = 0, busy = false, progressStage = null,
                        message = "所选日期（${s.date}）在源目录未找到照片，请检查日期或源目录")
                }
                saveStateCache(state.value.parsed, null, emptyList(), 0)
                return@launch
            }
            // ★ 校验：单日缓存命中且 ID 集合一致 → 复用（跳过 EXIF 精读）；否则重新精读
            val cached = photoCache
            val cachedIds = cached?.photos?.map { it.id }?.toSet() ?: emptySet()
            val latestIds = cands.map { it.id }.toSet()
            val photos = if (cached != null && cached.date == s.date && latestIds == cachedIds) {
                cached.photos
            } else {
                _state.update { it.copy(progressStage = "读取照片信息中…") }
                repo.readExif(cands)
            }
            _state.update { it.copy(coarseCount = cands.size, photos = photos) }
            // ★ 照片信息读取完成 → 预缓存本批缩略图（旧缓存已在 setDate/addSourceDir 阶段清空）
            preloadThumbnailCache(photos)
            // ★ 单日缓存：覆盖为当前日期
            photoCache = DatePhotoCache(s.date, photos, cands.size)
            // ★ 2026-10-07 需求：选取日期/源目录变更后**不做自动重新分类**。
            //   旧日志事件的时间戳基于旧日期，对新日期照片分类无意义（会大量误入未匹配）。
            //   分类统一由「解析日志」动作触发（parseLogs 内自动重新分类并写缓存）。
            //   这里仅缓存照片元数据；classify 保持 null（界面显示"照片尚未就绪"引导重新解析）。
            val parsed = state.value.parsed
            _state.update {
                it.copy(busy = false, progressStage = null,
                    // ★ P2：该日期尚无解析结果时给引导
                    message = if (parsed == null && photos.isNotEmpty())
                        "已加载 ${s.date} 的照片 ${photos.size} 张，粘贴日志后点「解析日志」即自动分类"
                    else null)
            }
            saveStateCache(parsed, null, photos, cands.size)
        }
    }

    /**
     * ★ 清空缩略图缓存（内存 + 磁盘）。
     *
     * 调用时机：巡查日期变更、源目录增删——即"上一批照片已经作废"的时刻。
     * 顺序：clearThumbnailCache() → 粗筛 → 精读 EXIF → preloadThumbnailCache()
     * 必须先清后读，否则新照片的预缓存会和上一批的残留混在同一份缓存里，旧图白占额度。
     */
    private fun clearThumbnailCache() {
        runCatching { thumbCache.clear() }
    }

    /**
     * ★ 预缓存本批照片的缩略图，写入内存缓存供事件卡片直接命中。
     * 时机：EXIF 精读之后（此时才知道本批照片的真实路径）。
     * 预加载在 Coil 内部异步进行，此处不阻塞流水线。
     */
    private fun preloadThumbnailCache(photos: List<Photo>) {
        runCatching {
            thumbCache.preload(
                refs = photos.map { it.sourceRef },
                maxCount = THUMB_PRELOAD_MAX,
            )
        }
    }

    fun setOutputDir(d: String?) { store.outputDir = d; _state.update { it.copy(outputDir = d) } }

    fun setNamingTemplate(t: Int) { store.namingTemplate = t; _state.update { it.copy(namingTemplate = t) } }

    // ---------- SAF 目录选择（§8.2 系统选择器选取，不再手打路径） ----------

    /**
     * 源目录选择：OpenDocumentTree 返回 content://…/tree/primary%3ADCIM%2FCamera，
     * 从 document id 提取相对路径（primary:DCIM/Camera → DCIM/Camera）供 MediaStore 查询。
     */
    fun pickSourceDir(uri: Uri) {
        val seg = uri.lastPathSegment ?: return
        val rel = Uri.decode(seg).substringAfter(':').trim('/')
        if (rel.isNotBlank()) addSourceDir(rel)
    }

    /** 输出目录选择：持久化 SAF 树授权（重启后可继续读写）。 */
    fun pickOutputDir(uri: Uri) {
        runCatching {
            getApp().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        setOutputDir(uri.toString())
    }

    // ---------- 照片流程（§4：粗筛 → 精读；被动触发，无手动按钮） ----------
    /**
     * ★ 被动触发兜底（切换到日志页时调用）：源目录 + 日期就绪且「尚未完成粗筛」时，
     * 补一次 粗筛→精读→分类 流水线，避免首次进入日志页时照片列表为空。
     * 已粗筛（coarseCount != null）则跳过——日期/源目录变更已由 setDate/addSourceDir/removeSourceDir 触发，
     * 防止每次切页重复扫描 MediaStore 与 EXIF。
     */
    fun ensurePipeline() {
        val s = state.value
        if (s.sourceDirs.isEmpty() || s.busy || s.coarseCount != null) return
        autoPipeline()
    }

    // ---------- 日志（§3：粘贴 → 解析，如实显示不脱敏） ----------
    fun setLogsText(text: String) { store.logsText = text; _state.update { it.copy(logsText = text) } }

    /** ★ 删除日志：清文本 + 清解析/匹配结果 + 清粗筛缓存（§4.4），避免旧匹配残留。 */
    fun clearLogs() {
        store.logsText = ""
        repo.clearCache()
        store.clearStateCache()
        _state.update { it.copy(logsText = "", parsed = null, classify = null, ledger = null, exportMessage = null) }
    }

    /**
     * ★ 2026-10-07 需求重定义「解析日志」（P0：照片流水线与日志解析解耦）：
     *   - 快路径：日志已解析、文本未改动、已有分类、**照片未增删**（防线一校验）→ **只清人工移动表
     *     （overrideMap）**，分类恢复为最初自动分类状态；**不重跑粗筛/精读/分类**（classify 从未被
     *     overrideMap 修改，它只是叠加层，清掉即恢复最初分类）。
     *   - 照片复用：**强制实时粗筛校验一致**（防线一：删除/新增照片立即感知）→ 只重新解析文本 +
     *     用现有照片重新分类，**不重扫 MediaStore / 不重读 EXIF / 不清缩略图缓存**（毫秒级）。
     *   - 完整流水线：照片未就绪或**校验不一致（照片已增删）**→ 清缓存重新 粗筛→精读→分类。
     * ★ 2026-10-08 修复：快路径前置照片校验——否则删除照片后文本未改仍命中快路径，
     *   旧 classify 引用已删照片 URI → 事件网格显示灰色方块缩略图。
     */
    fun parseLogs() {
        val s = state.value
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch(Dispatchers.IO) {
            // ── 快路径：已解析 + 已分类 + 文本未变 + 照片未增删 → 只清人工移动表 ──
            if (s.parsed != null && s.classify != null && s.logsText == store.parsedLogsText) {
                // ★ 防线一：照片校验——删除/新增照片后即使文本未改也要重扫重分类，
                //   避免旧 classify 引用失效 URI（灰块）。
                val photosUnchanged = s.sourceDirs.isEmpty() || s.photos.isEmpty() ||
                    repo.coarseCandidatesFresh(s.date, s.sourceDirs)
                        .map { it.id }.toSet() == s.photos.map { it.id }.toSet()
                if (photosUnchanged) {
                    val n = s.overrideMap.size
                    store.overrideMap = emptyMap()
                    _state.update {
                        it.copy(
                            overrideMap = emptyMap(),
                            message = if (n > 0) "已恢复最初分类（清除 $n 条人工移动）" else "已是最初分类状态",
                        )
                    }
                    return@launch
                }
                // 照片已增删 → 落入下方完整流水线（清缓存重扫 + 重新解析分类）
            }
            store.overrideMap = emptyMap()
            // ★ 记录本次解析的文本快照（供"只清人工移动"分支判定日志是否被修改）
            store.parsedLogsText = s.logsText
            _state.update {
                it.copy(busy = true, parsed = null, classify = null, ledger = null,
                    exportMessage = null, overrideMap = emptyMap(), progressStage = "解析日志中…")
            }
            val p = AlgorithmApi.parseLog(s.logsText, s.date)
            // ── 照片获取：防线一（强制实时粗筛）决定 复用 or 完整重扫 ──
            if (s.sourceDirs.isEmpty()) {
                _state.update { it.copy(parsed = p, busy = false, progressStage = null) }
                saveStateCache(p, null, emptyList(), 0)
                return@launch
            }
            val latestCands = repo.coarseCandidatesFresh(s.date, s.sourceDirs)
            if (latestCands.isEmpty()) {
                photoCache = null
                _state.update {
                    it.copy(parsed = p, coarseCount = 0, photos = emptyList(), busy = false,
                        progressStage = null,
                        message = "所选日期（${s.date}）在源目录未找到照片，请检查日期或源目录")
                }
                saveStateCache(p, null, emptyList(), 0)
                return@launch
            }
            val latestIds = latestCands.map { it.id }.toSet()
            val photos: List<Photo>
            val coarse: Int
            if (s.photos.isNotEmpty() && latestIds == s.photos.map { it.id }.toSet()) {
                // ★ 校验一致 → 复用现有照片（不重扫、不清缩略图缓存）
                photos = s.photos
                coarse = s.coarseCount ?: s.photos.size
            } else {
                // 照片未就绪或已增删 → 完整精读（粗筛已实时跑过）
                repo.clearCache()
                clearThumbnailCache()
                _state.update { it.copy(parsed = p, progressStage = "读取照片信息中…") }
                photos = repo.readExif(latestCands)
                coarse = latestCands.size
            }
            // ★ 同步进程内单日缓存
            photoCache = DatePhotoCache(s.date, photos, coarse)
            preloadThumbnailCache(photos)
            // ★ 日志已解析 + 照片已就绪 → 自动重新分类（重新解析日志后无需手动点「生成分类」）
            _state.update { it.copy(parsed = p, progressStage = "分类中…") }
            val g = AlgorithmApi.gpsGroup(photos)
            val c = AlgorithmApi.classify(p.events, g.clusters, g.singles, s.date, g.splitCount)
            _state.update {
                it.copy(coarseCount = coarse, photos = photos, classify = c, busy = false,
                    progressStage = null)
            }
            // ★ 流水线完成 → 写状态缓存（打开 App 恢复用）
            saveStateCache(p, c, photos, coarse)
        }
    }

    /**
     * ★ 2026-10-07 防线二：手动刷新照片缓存（设置页入口）。
     * 清进程内单日缓存 + 粗筛缓存 + 缩略图缓存 + 持久化状态缓存，强制完整重扫。
     * 事件列表（parsed）保留——照片变化不影响日志事件；重新解析即自动重新分类。
     */
    fun refreshPhotoCache() {
        photoCache = null
        repo.clearCache()
        clearThumbnailCache()
        store.clearStateCache()
        _state.update {
            it.copy(photos = emptyList(), coarseCount = null,
                classify = null, ledger = null, exportMessage = null)
        }
        autoPipeline()
    }

    // ---------- 分类 + 台账（§5/§6/§7） ----------
    fun runClassify() = viewModelScope.launch(Dispatchers.Default) {
        if (state.value.busy) return@launch
        val s = state.value
        val parsed = s.parsed ?: run { _state.update { it.copy(message = "请先解析日志") }; return@launch }
        if (s.photos.isEmpty()) { _state.update { it.copy(message = "无照片（请先粗筛并精读）") }; return@launch }
        // ★ 2026-10-07 修复：手动「生成分类」= 全新分配，上一批人工移动（overrideMap）作废
        store.overrideMap = emptyMap()
        _state.update { it.copy(busy = true, classify = null, overrideMap = emptyMap(), progressStage = "分类中…") }
        val g = AlgorithmApi.gpsGroup(s.photos)
        val c = AlgorithmApi.classify(parsed.events, g.clusters, g.singles, s.date, g.splitCount)
        _state.update { it.copy(classify = c, busy = false, progressStage = null) }
        // ★ 2026-10-07：手动生成分类也写状态缓存（打开 App 恢复最新分类）
        saveStateCache(parsed, c, s.photos, s.coarseCount ?: s.photos.size)
    }

    /**
     * ★ 2026-10-08 技术债统一：台账行生成**唯一入口**（photoById 建表 O(1) + null 保护）。
     * buildLedger() 与 prepareLedger() 都走这里，避免两处生成逻辑分叉。
     */
    private fun buildRows(s: UiState, eventPhotoMap: Map<Int, List<Long>>): List<LedgerRow>? {
        val c = s.classify ?: return null
        val parsed = s.parsed ?: return null
        val photoById: Map<Long, Photo> = s.photos.associateBy { it.id }
        // ★ 传入人工台账标记，否则「设为台账」在导出时被静默忽略
        return AlgorithmApi.buildLedger(parsed.events, eventPhotoMap, photoById::get, s.marked)
    }

    /**
     * 单独生成台账行并存入 state（**当前无 UI 入口**，保留供将来做台账预览页）。
     *
     * 注意：ZIP 导出路径走 [prepareLedger]，不经过本方法——本方法是独立入口，
     * 若将来删除"台账预览"计划，应连同 [UiState.ledger] 的语义一并清理。
     */
    fun buildLedger() = viewModelScope.launch(Dispatchers.Default) {
        val s = state.value
        val c = s.classify ?: return@launch
        val rows = buildRows(s, effectiveMap(c)) ?: return@launch
        _state.update { it.copy(ledger = rows) }
    }

    // ---------- 人工调整（§8.3） ----------
    /** 把照片移动到目标事件（从原位置移除，含 unmatched）。 */
    fun movePhoto(photoId: Long, targetEventId: Int) {
        val map = state.value.overrideMap.toMutableMap()
        map[photoId] = targetEventId
        store.overrideMap = map
        _state.update { it.copy(overrideMap = map) }
    }

    /** ★ 批量移动照片到目标事件（§8.3 多选移动）。 */
    fun movePhotos(photoIds: List<Long>, targetEventId: Int) {
        if (photoIds.isEmpty()) return
        val map = state.value.overrideMap.toMutableMap()
        photoIds.forEach { map[it] = targetEventId }
        store.overrideMap = map
        _state.update { it.copy(overrideMap = map) }
    }

    /**
     * ★ 从事件移除照片 → 回到未匹配照片池（§8.3 弹窗「移除」）。
     * 仅调整分组归属，不删除源文件；overrideMap 记 -1 表示「移出所有事件」。
     */
    fun removePhotosFromEvent(photoIds: List<Long>) {
        if (photoIds.isEmpty()) return
        val map = state.value.overrideMap.toMutableMap()
        photoIds.forEach { map[it] = -1 }
        store.overrideMap = map
        _state.update { it.copy(overrideMap = map) }
    }

    /** ★ 重置全部人工移动（2026-10-01 新增）：清空 overrideMap，恢复纯自动分类结果（撤销所有手动调整）。 */
    fun resetOverrides() {
        store.overrideMap = emptyMap()
        _state.update { it.copy(overrideMap = emptyMap(), message = "已重置全部人工移动，恢复自动分类结果") }
    }

    fun clearMessage() { _state.update { it.copy(message = null, exportMessage = null) } }

    /** ★ UI 轻提示（Snackbar）：供界面层校验类提示（如禁止跨事件移动）使用。 */
    fun notify(msg: String) { _state.update { it.copy(message = msg) } }

    // ---------- 状态缓存（§8.4.2：打开 App 恢复事件列表 + 分类） ----------

    /** ★ 写入状态缓存（事件列表 + 分类 + 照片 + 粗筛数）。JSON 序列化由 SettingsStore 完成。 */
    private fun saveStateCache(
        parsed: algorithm.ParsedLog?,
        classify: algorithm.ClassifyResult?,
        photos: List<algorithm.model.Photo>,
        coarseCount: Int,
    ) {
        runCatching {
            store.parsedCache = parsed
            store.classifyCache = classify
            store.photosCache = photos
            store.coarseCountCache = coarseCount
        }
    }

    // ---------- 导出（§7.3 Excel / §7.4 ZIP，经 AlgorithmApi 单向调用核心） ----------

    /**
     * 台账内容准备（§7.3，ZIP 导出使用）：
     * buildRows → 并行解码照片字节（4:3 裁剪 400px JPEG50）→ 返回 (rows, 图片字节缓存, 调试统计)。
     * 失败返回 null。
     * @param eventPhotoMap 打包前一刻的最终分类快照（台账与 ZIP 共用，保证照片一致）
     * @param onProgress 图片预收集进度回调（0.1..0.7 区间；★ 2026-10-08 并行 + 每 5 张节流）
     */
    private suspend fun prepareLedger(
        s: UiState,
        eventPhotoMap: Map<Int, List<Long>>,
        onProgress: (Float) -> Unit = {},
    ): Triple<List<LedgerRow>, Map<String, ByteArray?>, String>? {
        return try {
            val rows = buildRows(s, eventPhotoMap) ?: return null
            // ★ 分区存储：sourceRef 是 MediaStore DATA 物理路径，Android 10+ 无法直接 decodeFile，
            //   用 Photo.id 构造 content://media/... URI 读取（App 有 READ_MEDIA_IMAGES 权限）。
            val ctx = getApp()
            val photoByRef: Map<String, Photo> = s.photos.associateBy { it.sourceRef }
            val refs = rows.flatMap { listOfNotNull(it.photo1Ref, it.photo2Ref) }.distinct()
            if (refs.isEmpty()) return Triple(rows, emptyMap(), "图片:成功0/失败0")
            // ★ 2026-10-08 P1-4/P1-6：并行解码（固定并发，Dispatchers.Default 自然限流）
            //   async 任务按 refs 顺序 await 保序；计数/容器并发安全；进度每 5 张一跳。
            val done = AtomicInteger(0); val ok = AtomicInteger(0); val fail = AtomicInteger(0)
            val failures = ConcurrentLinkedQueue<String>()
            val bytesCache = ConcurrentHashMap<String, ByteArray?>()
            val total = refs.size
            coroutineScope {
                refs.map { ref ->
                    async(Dispatchers.Default) {
                        val photo = photoByRef[ref]
                        val readRef = photo?.let { p ->
                            if (ref.startsWith("/") || ref.startsWith("file:"))
                                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, p.id).toString()
                            else ref
                        } ?: ref
                        val bytes = ImageUtils.process(ctx, readRef)
                        if (bytes != null && bytes.isNotEmpty()) ok.incrementAndGet() else {
                            fail.incrementAndGet()
                            failures.add("${ref.substringAfterLast('/')}(found=${photo != null},id=${photo?.id})")
                        }
                        bytesCache[ref] = bytes
                        val n = done.incrementAndGet()
                        if (n % 5 == 0 || n == total) onProgress(0.1f + 0.6f * n / total.coerceAtLeast(1))
                    }
                }.forEach { it.await() }
            }
            val imgDebug = "图片:成功${ok.get()}/失败${fail.get()}" +
                if (failures.isNotEmpty()) " 失败[${failures.joinToString(",")}]" else ""
            Triple(rows, bytesCache, imgDebug)
        } catch (e: Exception) {
            null
        }
    }

    /** ★ 打开最近导出的台账文件（导出Excel 2.0 方案 §4.4：成功后提供「打开」）。
     *  用 Toast 直接提示（不依赖页面滚动位置），用 createChooser 不预检 resolveActivity。 */
    fun openExportedFile() {
        val shown = state.value.lastExport ?: return
        val app = getApp()
        val type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        val uri = if (shown.startsWith("content://")) {
            Uri.parse(shown)
        } else {
            runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    app, "${app.packageName}.fileprovider", File(shown)
                )
            }.getOrNull()
        }
        if (uri == null) {
            android.widget.Toast.makeText(app, "无法打开：$shown", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            app.startActivity(Intent.createChooser(intent, "打开台账"))
        }.onFailure {
            android.widget.Toast.makeText(app, "未找到可打开表格的应用", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** 导出 ZIP：按事件分类文件夹打包（命名模板由设置页选择），未匹配→未分类（§7.4）。
     *  ★ 2026-09-08 需求：压缩包同时包含 照片分类文件夹 + 台账 Excel（extraFiles 打入 ZIP）。
     *  核心 ZipExporter 仅收 File（PC 可移植）→ Android 侧先写临时文件，再复制到输出目标（SAF/路径）。 */
    fun exportZip() = viewModelScope.launch(Dispatchers.IO) {
        if (state.value.busy) return@launch
        val s = state.value
        // ★ 修复：未设置输出目录禁止导出
        if (s.outputDir.isNullOrBlank()) {
            _state.update { it.copy(message = "未设置输出目录，请先在「设置」页选择或输入输出目录后再导出") }
            return@launch
        }
        val c = s.classify ?: run { _state.update { it.copy(message = "请先在预览页生成分类") }; return@launch }
        val parsed = s.parsed ?: run { _state.update { it.copy(message = "请先解析日志") }; return@launch }
        _state.update { it.copy(exportProgress = 0f) }
        // ★ 2026-10-08 P0-3：临时文件声明移到 try 外——导出失败也必须清理（含原图，可达几十 MB）
        val fileName = "照片分类_${s.date.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))}_" +
                java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")) + ".zip"
        // ★ 断点续传状态文件与输出位置解耦（放 app 私有 files 目录），避免输出目录切换丢失
        val stateFile = File(getApp().filesDir, ".export_state_${s.date}.json")
        val tmpZip = File(getApp().cacheDir, fileName)
        try {
            // ★ 2026-10-08 P0-2：photoById 建表 O(1)（替代线性 find，与 photoByRef 对齐）
            val photoById: Map<Long, Photo> = s.photos.associateBy { it.id }
            // ★ 打包前一刻计算最终分类快照（合并人工移动/移除），台账与打包共用同一份，保证照片一致
            val finalMap = effectiveMap(c)
            val finalUnmatched = effectiveUnmatched(c)

            // ★ 台账 Excel 准备（用同一个 finalMap，保证台账照片 = 实际打包的照片）
            val prep = prepareLedger(s, finalMap) { frac -> _state.update { it.copy(exportProgress = 0.6f * frac) } }
            val xlsxBytes = prep?.let { (rows, cache, _) ->
                val bos = ByteArrayOutputStream()
                runCatching { AlgorithmApi.exportLedgerXlsx(rows, bos) { ref -> cache[ref] } }
                    .getOrNull()?.let { bos.toByteArray() }
            }
            // ★ 台账文件名含巡查日期 + 时分秒：区分"导出时刻"与"巡查日期"，
            //   并避免同日二次导出时同名被静默覆盖（SAF findFile 会复用同名文档）。
            val now = java.time.LocalDateTime.now()
            val stamp = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val xlsxName = "巡查台账_${s.date.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))}_$stamp.xlsx"
            val extraFiles = if (xlsxBytes != null && xlsxBytes.isNotEmpty()) mapOf(xlsxName to xlsxBytes) else emptyMap()

            val r = AlgorithmApi.exportZip(parsed.events, finalMap, finalUnmatched,
                photoById::get, s.namingTemplate, tmpZip, stateFile, extraFiles = extraFiles) { frac, _ ->
                _state.update { it.copy(exportProgress = 0.6f + 0.4f * frac) }
            }
            if (r.copied > 0 || r.failed > 0) {
                copyToOutput(tmpZip, fileName).also { tmpZip.delete() }
            }
            val skipNote = if (r.skippedFromState > 0) "（跳过已完成 ${r.skippedFromState}）" else ""
            // ★ 2026-10-08 P0-1：Excel 生成结果附图片统计/失败明细，失败可诊断（不再静默降级）
            val xlsxNote = if (extraFiles.isNotEmpty()) {
                val dbg = prep?.third.orEmpty()
                if (dbg.isNotBlank()) " + 台账Excel（$dbg）" else " + 台账Excel"
            } else {
                "（台账未生成：${prep?.third ?: "准备失败"}）"
            }
            _state.update {
                it.copy(
                    exportMessage = "ZIP 导出：成功 ${r.copied} / 失败 ${r.failed}$skipNote$xlsxNote",
                    exportProgress = null,
                    // ★ 回填台账行：底部操作条要显示「台账 N 行」，也需要它判断导出完成。
                    //   以前此处不回填，导致 state.ledger 永远为 null、底栏永远显示"台账待生成"。
                    ledger = prep?.first ?: it.ledger,
                )
            }
        } catch (e: Exception) {
            // ★ 2026-10-08 P0-3：失败也清理临时 ZIP（可能含全部原图，几十 MB）
            runCatching { tmpZip.delete() }
            _state.update { it.copy(exportMessage = "ZIP 导出失败：${e.message}", exportProgress = null) }
        }
    }

    private fun exportDir(): File {
        val custom = state.value.outputDir
        return if (!custom.isNullOrBlank() && !custom.startsWith("content://")) File(custom).also { it.mkdirs() }
        else File(getApp().getExternalFilesDir(null), "export").also { it.mkdirs() }
    }

    /** 打开输出写入流：SAF 树 URI → contentResolver；否则本地文件流。返回 (流, 用户可见位置)。 */
    private fun openOutputSink(fileName: String): Pair<java.io.OutputStream?, String> {
        val custom = state.value.outputDir
        if (!custom.isNullOrBlank() && custom.startsWith("content://")) {
            val parent = DocumentFile.fromTreeUri(getApp(), Uri.parse(custom)) ?: return null to custom
            val doc = parent.findFile(fileName) ?: parent.createFile("application/octet-stream", fileName)
                ?: return null to custom
            val os = getApp().contentResolver.openOutputStream(doc.uri)
            return os to doc.uri.toString()
        }
        val dir = exportDir().apply { mkdirs() }
        val f = File(dir, fileName)
        return f.outputStream() to f.absolutePath
    }

    /** 把已生成的临时文件复制到输出目标（SAF 树或本地路径）。返回用户可见位置。 */
    private fun copyToOutput(tmp: File, fileName: String): String {
        val (os, shown) = openOutputSink(fileName)
        if (os == null) return "（输出目录不可写）"
        os.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        return shown
    }

    private companion object {
        val BASIC_DATE: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.BASIC_ISO_DATE
    }

    /** 台账照片标记：slot=1 照片1 / slot=2 照片2；photoId=null 表示恢复默认（最早+最晚）。 */
    fun markLedgerPhoto(eventId: Int, slot: Int, photoId: Long?) {
        val cur = state.value.marked[eventId] ?: (null to null)
        val next = if (slot == 1) (photoId to cur.second) else (cur.first to photoId)
        val map = state.value.marked + (eventId to next)
        store.markedMap = map
        _state.update { it.copy(marked = map) }
    }

    // ---------- 台账照片字节供给（§7.3 Excel 嵌入照片） ----------

    /**
     * 台账照片读取：sourceRef（物理路径 / content uri）→ 原图字节 → 压缩小图（≤480px JPEG85），
     * 控制 xlsx 体积。读不到返回 null（该照片单元格只留文件名）。
     */
    private fun readPhotoBytes(ref: String): ByteArray? {
        val raw = runCatching {
            if (ref.startsWith("content://")) {
                getApp().contentResolver.openInputStream(Uri.parse(ref))?.use { it.readBytes() }
            } else File(ref).inputStream().use { it.readBytes() }
        }.getOrNull() ?: return null
        return compressForExcel(raw)
    }

    private fun compressForExcel(bytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return bytes
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDim / (sample * 2) >= 480) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes
        val scaled = if (bmp.width > 480 || bmp.height > 480) {
            val s = 480f / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1),
                (bmp.height * s).toInt().coerceAtLeast(1), true)
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== bmp) bmp.recycle()
        scaled.recycle()
        return out.toByteArray()
    }

    /** 合并 overrideMap 后的事件照片表（§8.3 移动兜底）。target=-1（移除）→ 从所有事件移除、不归入任何事件。 */
    fun effectiveMap(c: ClassifyResult): Map<Int, List<Long>> {
        val map = c.eventPhotoMap.mapValues { it.value.toMutableList() }.toMutableMap()
        val all = (c.eventPhotoMap.values.flatten() + c.unmatched).toSet()
        for ((pid, target) in state.value.overrideMap) {
            if (pid !in all) continue
            map.forEach { (_, v) -> v.remove(pid) }
            if (target >= 0) map.getOrPut(target) { mutableListOf() }.add(pid)
        }
        return map
    }

    /**
     * 有效未匹配照片（§8.3 移除兜底）：算法未匹配 + 被人工「从事件移除」（overrideMap=-1）的照片，
     * 再扣除已人工「移动到事件」（overrideMap>=0）的照片。
     * 导出 ZIP / 预览页未匹配池均以它为准，避免被移除的照片丢失、已移动的照片重复显示。
     */
    fun effectiveUnmatched(c: ClassifyResult): List<Long> {
        val all = (c.eventPhotoMap.values.flatten() + c.unmatched).toSet()
        val moved = state.value.overrideMap.filterValues { it >= 0 }.keys.toSet()
        val removed = state.value.overrideMap.filterValues { it < 0 }.keys.filter { it in all }
        return (c.unmatched.filter { it !in moved } + removed).distinct()
    }
}
