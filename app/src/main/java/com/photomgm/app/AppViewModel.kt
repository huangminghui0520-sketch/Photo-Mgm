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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

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

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val repo = PhotoRepository(app)

    /** 供平台组件获取 applicationContext（§2.4 核心不碰平台，本类负责提供）。 */
    fun getApp(): Application = getApplication()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** ★ 自动流水线任务句柄：日期/源目录变更后触发，新变更取消旧任务，避免并发扫描。 */
    private var pipelineJob: Job? = null

    init {
        _state.update {
            it.copy(
                date = store.date, sourceDirs = store.sourceDirs,
                outputDir = store.outputDir, namingTemplate = store.namingTemplate,
                logsText = store.logsText, overrideMap = store.overrideMap,
                marked = store.markedMap,
            )
        }
    }

    // ---------- 设置（§8.4 设置即存） ----------
    /**
     * ★ 巡查日期变更：自动触发 粗筛→精读（无需手动点击）；同时清空下游避免旧分类配新日期导出错乱。
     * 清粗筛缓存（§4.4）→ 确保从 MediaStore 重新查询最新照片，再自动重新匹配。
     */
    fun setDate(d: LocalDate) {
        store.date = d
        repo.clearCache()
        _state.update { it.copy(date = d, classify = null, ledger = null, exportMessage = null) }
        autoPipeline()
    }

    /** ★ 源目录变更：自动触发 粗筛→精读（照片集合已变，清空下游）。清缓存后重新查询最新照片。 */
    fun addSourceDir(dir: String) {
        val dirs = (state.value.sourceDirs + dir).distinct()
        store.sourceDirs = dirs
        repo.clearCache()
        _state.update { it.copy(sourceDirs = dirs, classify = null, ledger = null, exportMessage = null) }
        autoPipeline()
    }

    fun removeSourceDir(dir: String) {
        val dirs = state.value.sourceDirs - dir
        store.sourceDirs = dirs
        repo.clearCache()
        _state.update { it.copy(sourceDirs = dirs, classify = null, ledger = null, exportMessage = null) }
        autoPipeline()
    }

    /**
     * ★ 自动流水线：粗筛 → 精读（§4）→ 若日志已解析则自动重新匹配（§5）。
     * 变更即触发；取消旧任务防止快速连续操作导致旧结果覆盖新结果。
     */
    private fun autoPipeline() {
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch(Dispatchers.IO) {
            val s = state.value
            if (s.sourceDirs.isEmpty()) {
                _state.update { it.copy(coarseCount = null, photos = emptyList(), busy = false) }
                return@launch
            }
            _state.update {
                it.copy(busy = true, coarseCount = null, photos = emptyList(),
                    classify = null, ledger = null, exportMessage = null)
            }
            val cands = repo.coarseCandidates(s.date, s.sourceDirs)
            if (cands.isEmpty()) {
                _state.update {
                    it.copy(coarseCount = 0, busy = false,
                        message = "所选日期（${s.date}）在源目录未找到照片，请检查日期或源目录")
                }
                return@launch
            }
            val photos = repo.readExif(cands)
            _state.update { it.copy(coarseCount = cands.size, photos = photos) }
            // ★ 日志已解析 → 自动重新匹配（日期/源目录变更后无需手动点「生成分类」）
            val parsed = state.value.parsed
            if (parsed != null && photos.isNotEmpty()) {
                val g = AlgorithmApi.gpsGroup(photos)
                val c = AlgorithmApi.classify(parsed.events, g.clusters, g.singles, s.date, g.splitCount)
                _state.update { it.copy(classify = c, busy = false) }
            } else {
                _state.update { it.copy(busy = false) }
            }
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
        _state.update { it.copy(logsText = "", parsed = null, classify = null, ledger = null, exportMessage = null) }
    }

    /** ★ 重新解析日志：清匹配缓存 → 重新解析 → 若照片已就绪自动重新匹配（§5）。 */
    fun parseLogs() = viewModelScope.launch(Dispatchers.Default) {
        if (state.value.busy) return@launch
        repo.clearCache()
        // ★ 修复：置空 parsed 同时清空 classify，避免「旧分类 + 无 parsed」中间态导致导出 NPE
        _state.update { it.copy(busy = true, parsed = null, classify = null, ledger = null) }
        val p = AlgorithmApi.parseLog(state.value.logsText, state.value.date)
        _state.update { it.copy(parsed = p) }
        // ★ 照片已就绪 → 自动重新匹配（重新解析日志后无需手动点「生成分类」）
        val s = state.value
        if (s.photos.isNotEmpty()) {
            val g = AlgorithmApi.gpsGroup(s.photos)
            val c = AlgorithmApi.classify(p.events, g.clusters, g.singles, s.date, g.splitCount)
            _state.update { it.copy(classify = c, busy = false) }
        } else {
            _state.update { it.copy(busy = false) }
        }
    }

    // ---------- 分类 + 台账（§5/§6/§7） ----------
    fun runClassify() = viewModelScope.launch(Dispatchers.Default) {
        if (state.value.busy) return@launch
        val s = state.value
        val parsed = s.parsed ?: run { _state.update { it.copy(message = "请先解析日志") }; return@launch }
        if (s.photos.isEmpty()) { _state.update { it.copy(message = "无照片（请先粗筛并精读）") }; return@launch }
        _state.update { it.copy(busy = true, classify = null) }
        val g = AlgorithmApi.gpsGroup(s.photos)
        val c = AlgorithmApi.classify(parsed.events, g.clusters, g.singles, s.date, g.splitCount)
        _state.update { it.copy(classify = c, busy = false) }
    }

    fun buildLedger() = viewModelScope.launch(Dispatchers.Default) {
        val s = state.value
        val c = s.classify ?: return@launch
        val parsed = s.parsed ?: return@launch   // ★ 修复：禁止对 null parsed 强解包
        val photoById: (Long) -> Photo? = { id -> s.photos.find { it.id == id } }
        val rows = AlgorithmApi.buildLedger(parsed.events, effectiveMap(c), photoById)
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

    fun clearMessage() { _state.update { it.copy(message = null, exportMessage = null) } }

    /** ★ UI 轻提示（Snackbar）：供界面层校验类提示（如禁止跨事件移动）使用。 */
    fun notify(msg: String) { _state.update { it.copy(message = msg) } }

    // ---------- 导出（§7.3 Excel / §7.4 ZIP，经 AlgorithmApi 单向调用核心） ----------

    /** 导出台账 Excel：yyyyMMdd.xlsx（§7.3）。输出目录支持 SAF 树 URI 或本地路径。 */
    fun exportLedger() = viewModelScope.launch(Dispatchers.IO) {
        if (state.value.busy) return@launch
        val s = state.value
        // ★ 修复：未设置输出目录禁止导出（不再静默写默认目录）
        if (s.outputDir.isNullOrBlank()) {
            _state.update { it.copy(message = "未设置输出目录，请先在「设置」页选择或输入输出目录后再导出") }
            return@launch
        }
        val c = s.classify ?: run { _state.update { it.copy(message = "请先在预览页生成分类") }; return@launch }
        val parsed = s.parsed ?: run { _state.update { it.copy(message = "请先解析日志") }; return@launch }
        _state.update { it.copy(exportProgress = 0.05f, exportMessage = null, lastExport = null) }
        try {
            // ★ 台账内容准备（buildLedger + 图片预收集），与 ZIP 导出共用同一逻辑
            val prep = prepareLedger(s, effectiveMap(c)) { frac -> _state.update { it.copy(exportProgress = frac) } }
                ?: throw IllegalStateException("台账数据准备失败")
            val (rows, bytesCache, imgDebug) = prep
            // ★ 文件名：yyyyMMdd.xlsx
            val fileName = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx"
            val (os, shown) = openOutputSink(fileName)
            if (os == null) { _state.update { it.copy(exportMessage = "导出失败：无法写入输出目录", exportProgress = null) }; return@launch }
            _state.update { it.copy(exportProgress = 0.85f) }
            os.use { AlgorithmApi.exportLedgerXlsx(rows, it) { ref -> bytesCache[ref] } }
            _state.update {
                it.copy(
                    exportMessage = "台账已导出（${rows.size} 行，$imgDebug）",
                    exportProgress = null, ledger = rows, lastExport = shown,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(exportMessage = "台账导出失败：${e.message}", exportProgress = null) }
        }
    }

    /**
     * 台账内容准备（§7.3，导出 Excel / ZIP 共用）：
     * buildLedger → 收集照片字节（4:3 裁剪 400px JPEG50）→ 返回 (rows, 图片字节缓存, 调试统计)。
     * 失败返回 null。
     * @param eventPhotoMap 打包前一刻的最终分类快照（台账与 ZIP 共用，保证照片一致）
     * @param onProgress 图片预收集进度回调（0.1..0.7 区间）
     */
    private fun prepareLedger(
        s: UiState,
        eventPhotoMap: Map<Int, List<Long>>,
        onProgress: (Float) -> Unit = {},
    ): Triple<List<LedgerRow>, Map<String, ByteArray?>, String>? {
        return try {
            val photoById: (Long) -> Photo? = { id -> s.photos.find { it.id == id } }
            val rows = AlgorithmApi.buildLedger(s.parsed!!.events, eventPhotoMap, photoById)
            // ★ 分区存储：sourceRef 是 MediaStore DATA 物理路径，Android 10+ 无法直接 decodeFile，
            //   用 Photo.id 构造 content://media/... URI 读取（App 有 READ_MEDIA_IMAGES 权限）。
            val ctx = getApp()
            val photoByRef: Map<String, Photo> = s.photos.associateBy { it.sourceRef }
            val bytesCache = mutableMapOf<String, ByteArray?>()
            val refs = rows.flatMap { listOfNotNull(it.photo1Ref, it.photo2Ref) }.distinct()
            var imgOk = 0; var imgFail = 0
            val failDetails = mutableListOf<String>()
            refs.forEachIndexed { i, ref ->
                val photo = photoByRef[ref]
                val readRef = photo?.let { p ->
                    if (ref.startsWith("/") || ref.startsWith("file:"))
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, p.id).toString()
                    else ref
                } ?: ref
                val bytes = ImageUtils.process(ctx, readRef)
                if (bytes != null && bytes.isNotEmpty()) imgOk++ else {
                    imgFail++
                    failDetails.add("${ref.substringAfterLast('/')}(found=${photo != null},id=${photo?.id})")
                }
                bytesCache[ref] = bytes
                onProgress(0.1f + 0.6f * (i + 1) / refs.size.coerceAtLeast(1))
            }
            val imgDebug = "图片:成功$imgOk/失败$imgFail" + if (failDetails.isNotEmpty()) " 失败[${failDetails.joinToString(",")}]" else ""
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
        try {
            val photoById: (Long) -> Photo? = { id -> s.photos.find { it.id == id } }
            // ★ 打包前一刻计算最终分类快照（合并人工移动/移除），台账与打包共用同一份，保证照片一致
            val finalMap = effectiveMap(c)
            val finalUnmatched = effectiveUnmatched(c)
            // ★ ZIP 文件名：导出时刻 yyyyMMdd_HHmmss.zip
            val fileName = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".zip"
            // ★ 断点续传状态文件与输出位置解耦（放 app 私有 files 目录），避免输出目录切换丢失
            val stateFile = File(getApp().filesDir, ".export_state_${s.date}.json")
            val tmpZip = File(getApp().cacheDir, fileName)

            // ★ 台账 Excel 准备（用同一个 finalMap，保证台账照片 = 实际打包的照片）
            val prep = prepareLedger(s, finalMap) { frac -> _state.update { it.copy(exportProgress = 0.6f * frac) } }
            val xlsxBytes = prep?.let { (rows, cache, _) ->
                val bos = ByteArrayOutputStream()
                runCatching { AlgorithmApi.exportLedgerXlsx(rows, bos) { ref -> cache[ref] } }
                    .getOrNull()?.let { bos.toByteArray() }
            }
            val xlsxName = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx"
            val extraFiles = if (xlsxBytes != null && xlsxBytes.isNotEmpty()) mapOf(xlsxName to xlsxBytes) else emptyMap()

            val r = AlgorithmApi.exportZip(parsed.events, finalMap, finalUnmatched,
                photoById, s.namingTemplate, tmpZip, stateFile, extraFiles = extraFiles) { frac, _ ->
                _state.update { it.copy(exportProgress = 0.6f + 0.4f * frac) }
            }
            if (r.copied > 0 || r.failed > 0) {
                copyToOutput(tmpZip, fileName).also { tmpZip.delete() }
            }
            val skipNote = if (r.skippedFromState > 0) "（跳过已完成 ${r.skippedFromState}）" else ""
            val xlsxNote = if (extraFiles.isNotEmpty()) " + 台账Excel" else "（台账未生成）"
            _state.update {
                it.copy(exportMessage = "ZIP 导出：成功 ${r.copied} / 失败 ${r.failed}$skipNote$xlsxNote",
                    exportProgress = null)
            }
        } catch (e: Exception) {
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
