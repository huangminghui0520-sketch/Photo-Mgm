// desktop/.../AppViewModel.kt —— UI 状态 + 编排（§8，PC 版）
// ★ 核心调用一律经 AlgorithmApi 单向调用（§2.4）；本类只做状态聚合与平台侧编排
// PC 版：无 Android 依赖；文件系统直读；ZIP 打包保留（复用 :algorithm ZipExporter）
package com.photomgm.app

import algorithm.AlgorithmApi
import algorithm.ClassifyResult
import algorithm.CandidatePhoto
import algorithm.ParsedLog
import algorithm.model.Photo
import algorithm.model.LedgerRow
import algorithm.model.LogEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

data class UiState(
    val date: LocalDate = LocalDate.now(),
    val sourceDirs: List<String> = emptyList(),
    val outputDir: String? = null,
    val namingTemplate: Int = 1,          // 0=事件类型，1=月日+巡查日志内容（默认1）
    val coarseCount: Int? = null,
    val logsText: String = "",
    val parsed: ParsedLog? = null,
    val photos: List<Photo> = emptyList(),
    val classify: ClassifyResult? = null,
    val ledger: List<LedgerRow>? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val overrideMap: Map<Long, Int> = emptyMap(),
    val marked: Map<Int, Pair<Long?, Long?>> = emptyMap(),
    val exportProgress: Float? = null,
    val exportMessage: String? = null,
    val lastExport: String? = null,
)

class AppViewModel {
    private val store = SettingsStore()
    private val repo = PhotoRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

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
    fun setDate(d: LocalDate) {
        store.date = d
        repo.clearCache()
        _state.update { it.copy(date = d, classify = null, ledger = null, exportMessage = null) }
        autoPipeline()
    }

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

    /** 拖放导入：文件夹 → 源目录（P0-1）。 */
    fun importSourceDir(file: File) {
        if (file.isDirectory) addSourceDir(file.absolutePath)
    }

    /** 拖放导入：日志文本文件 → 填充日志（P0-1）。 */
    fun importLogFile(file: File) {
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val merged = (state.value.logsText + "\n" + text).trim()
        store.logsText = merged
        _state.update { it.copy(logsText = merged) }
    }

    private fun autoPipeline() {
        pipelineJob?.cancel()
        pipelineJob = scope.launch(Dispatchers.IO) {
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

    fun ensurePipeline() {
        val s = state.value
        if (s.sourceDirs.isEmpty() || s.busy || s.coarseCount != null) return
        autoPipeline()
    }

    // ---------- 日志（§3：粘贴/导入 → 解析） ----------
    fun setLogsText(text: String) { store.logsText = text; _state.update { it.copy(logsText = text) } }

    fun clearLogs() {
        store.logsText = ""
        repo.clearCache()
        _state.update { it.copy(logsText = "", parsed = null, classify = null, ledger = null, exportMessage = null) }
    }

    fun parseLogs() = scope.launch(Dispatchers.Default) {
        if (state.value.busy) return@launch
        repo.clearCache()
        _state.update { it.copy(busy = true, parsed = null, classify = null, ledger = null) }
        val p = AlgorithmApi.parseLog(state.value.logsText, state.value.date)
        _state.update { it.copy(parsed = p) }
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
    fun runClassify() = scope.launch(Dispatchers.Default) {
        if (state.value.busy) return@launch
        val s = state.value
        val parsed = s.parsed ?: run { _state.update { it.copy(message = "请先解析日志") }; return@launch }
        if (s.photos.isEmpty()) { _state.update { it.copy(message = "无照片（请先粗筛并精读）") }; return@launch }
        _state.update { it.copy(busy = true, classify = null) }
        val g = AlgorithmApi.gpsGroup(s.photos)
        val c = AlgorithmApi.classify(parsed.events, g.clusters, g.singles, s.date, g.splitCount)
        _state.update { it.copy(classify = c, busy = false) }
    }

    fun buildLedger() = scope.launch(Dispatchers.Default) {
        val s = state.value
        val c = s.classify ?: return@launch
        val parsed = s.parsed ?: return@launch
        val photoById: (Long) -> Photo? = { id -> s.photos.find { it.id == id } }
        val rows = AlgorithmApi.buildLedger(parsed.events, effectiveMap(c), photoById)
        _state.update { it.copy(ledger = rows) }
    }

    // ---------- 人工调整（§8.3） ----------
    fun movePhoto(photoId: Long, targetEventId: Int) {
        val map = state.value.overrideMap.toMutableMap()
        map[photoId] = targetEventId
        store.overrideMap = map
        _state.update { it.copy(overrideMap = map) }
    }

    fun movePhotos(photoIds: List<Long>, targetEventId: Int) {
        if (photoIds.isEmpty()) return
        val map = state.value.overrideMap.toMutableMap()
        photoIds.forEach { map[it] = targetEventId }
        store.overrideMap = map
        _state.update { it.copy(overrideMap = map) }
    }

    fun removePhotosFromEvent(photoIds: List<Long>) {
        if (photoIds.isEmpty()) return
        val map = state.value.overrideMap.toMutableMap()
        photoIds.forEach { map[it] = -1 }
        store.overrideMap = map
        _state.update { it.copy(overrideMap = map) }
    }

    fun clearMessage() { _state.update { it.copy(message = null, exportMessage = null) } }

    fun notify(msg: String) { _state.update { it.copy(message = msg) } }

    // ---------- 导出（§7.3 Excel / §7.4 ZIP，经 AlgorithmApi 单向调用核心） ----------

    /** 导出台账 Excel：yyyyMMdd.xlsx（§7.3）。输出目录为本地路径。 */
    fun exportLedger() = scope.launch(Dispatchers.IO) {
        if (state.value.busy) return@launch
        val s = state.value
        if (s.outputDir.isNullOrBlank()) {
            _state.update { it.copy(message = "未设置输出目录，请先在「设置」中选择输出目录后再导出") }
            return@launch
        }
        val c = s.classify ?: run { _state.update { it.copy(message = "请先在预览区生成分类") }; return@launch }
        val parsed = s.parsed ?: run { _state.update { it.copy(message = "请先解析日志") }; return@launch }
        _state.update { it.copy(exportProgress = 0.05f, exportMessage = null, lastExport = null) }
        try {
            val prep = prepareLedger(s, effectiveMap(c)) { frac -> _state.update { it.copy(exportProgress = frac) } }
                ?: throw IllegalStateException("台账数据准备失败")
            val (rows, bytesCache, imgDebug) = prep
            val fileName = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx"
            val dir = File(s.outputDir!!).apply { mkdirs() }
            val outFile = File(dir, fileName)
            _state.update { it.copy(exportProgress = 0.85f) }
            outFile.outputStream().use { AlgorithmApi.exportLedgerXlsx(rows, it) { ref -> bytesCache[ref] } }
            _state.update {
                it.copy(
                    exportMessage = "台账已导出（${rows.size} 行，$imgDebug）",
                    exportProgress = null, ledger = rows, lastExport = outFile.absolutePath,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(exportMessage = "台账导出失败：${e.message}", exportProgress = null) }
        }
    }

    /**
     * 台账内容准备（§7.3，导出 Excel / ZIP 共用）。
     * PC 版：sourceRef 即本地绝对路径，ImageUtils 直接读文件。
     */
    private fun prepareLedger(
        s: UiState,
        eventPhotoMap: Map<Int, List<Long>>,
        onProgress: (Float) -> Unit = {},
    ): Triple<List<LedgerRow>, Map<String, ByteArray?>, String>? {
        return try {
            val photoById: (Long) -> Photo? = { id -> s.photos.find { it.id == id } }
            val rows = AlgorithmApi.buildLedger(s.parsed!!.events, eventPhotoMap, photoById)
            val bytesCache = mutableMapOf<String, ByteArray?>()
            val refs = rows.flatMap { listOfNotNull(it.photo1Ref, it.photo2Ref) }.distinct()
            var imgOk = 0; var imgFail = 0
            val failDetails = mutableListOf<String>()
            refs.forEachIndexed { i, ref ->
                val bytes = ImageUtils.process(ref)
                if (bytes != null && bytes.isNotEmpty()) imgOk++ else {
                    imgFail++
                    failDetails.add(ref.substringAfterLast('/'))
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

    /** 导出 ZIP：按事件分类文件夹打包（命名模板由设置选择），未匹配→未分类（§7.4）。 */
    fun exportZip() = scope.launch(Dispatchers.IO) {
        if (state.value.busy) return@launch
        val s = state.value
        if (s.outputDir.isNullOrBlank()) {
            _state.update { it.copy(message = "未设置输出目录，请先在「设置」中选择输出目录后再导出") }
            return@launch
        }
        val c = s.classify ?: run { _state.update { it.copy(message = "请先在预览区生成分类") }; return@launch }
        val parsed = s.parsed ?: run { _state.update { it.copy(message = "请先解析日志") }; return@launch }
        _state.update { it.copy(exportProgress = 0f) }
        try {
            val photoById: (Long) -> Photo? = { id -> s.photos.find { it.id == id } }
            val finalMap = effectiveMap(c)
            val finalUnmatched = effectiveUnmatched(c)
            val fileName = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".zip"
            val outDir = File(s.outputDir!!).apply { mkdirs() }
            val tmpZip = File.createTempFile("photomgm_export", ".zip")

            val prep = prepareLedger(s, finalMap) { frac -> _state.update { it.copy(exportProgress = 0.6f * frac) } }
            val xlsxBytes = prep?.let { (rows, cache, _) ->
                val bos = java.io.ByteArrayOutputStream()
                runCatching { AlgorithmApi.exportLedgerXlsx(rows, bos) { ref -> cache[ref] } }
                    .getOrNull()?.let { bos.toByteArray() }
            }
            val xlsxName = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx"
            val extraFiles = if (xlsxBytes != null && xlsxBytes.isNotEmpty()) mapOf(xlsxName to xlsxBytes) else emptyMap()

            val r = AlgorithmApi.exportZip(parsed.events, finalMap, finalUnmatched,
                photoById, s.namingTemplate, tmpZip, null, extraFiles = extraFiles) { frac, _ ->
                _state.update { it.copy(exportProgress = 0.6f + 0.4f * frac) }
            }
            val outFile = File(outDir, fileName)
            tmpZip.copyTo(outFile, overwrite = true)
            tmpZip.delete()
            val skipNote = if (r.skippedFromState > 0) "（跳过已完成 ${r.skippedFromState}）" else ""
            val xlsxNote = if (extraFiles.isNotEmpty()) " + 台账Excel" else "（台账未生成）"
            _state.update {
                it.copy(exportMessage = "ZIP 导出：成功 ${r.copied} / 失败 ${r.failed}$skipNote$xlsxNote",
                    exportProgress = null, lastExport = outFile.absolutePath)
            }
        } catch (e: Exception) {
            _state.update { it.copy(exportMessage = "ZIP 导出失败：${e.message}", exportProgress = null) }
        }
    }

    /** 打开最近导出的文件所在文件夹（P0-4：桌面打开资源管理器）。 */
    fun openExportedFolder() {
        val shown = state.value.lastExport ?: return
        val f = File(shown)
        val target = if (f.isDirectory) f else f.parentFile
        if (target == null) { _state.update { it.copy(message = "无法定位输出文件夹：$shown") }; return }
        runCatching { java.awt.Desktop.getDesktop().open(target) }
            .onFailure { _state.update { it.copy(message = "无法打开文件夹：${it.message}") } }
    }

    /** 标记台账照片：slot=1 照片1 / slot=2 照片2；photoId=null 恢复默认。 */
    fun markLedgerPhoto(eventId: Int, slot: Int, photoId: Long?) {
        val cur = state.value.marked[eventId] ?: (null to null)
        val next = if (slot == 1) (photoId to cur.second) else (cur.first to photoId)
        val map = state.value.marked + (eventId to next)
        store.markedMap = map
        _state.update { it.copy(marked = map) }
    }

    /** 合并 overrideMap 后的事件照片表（§8.3 移动兜底）。 */
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

    /** 有效未匹配照片（§8.3 移除兜底）。 */
    fun effectiveUnmatched(c: ClassifyResult): List<Long> {
        val all = (c.eventPhotoMap.values.flatten() + c.unmatched).toSet()
        val moved = state.value.overrideMap.filterValues { it >= 0 }.keys.toSet()
        val removed = state.value.overrideMap.filterValues { it < 0 }.keys.filter { it in all }
        return (c.unmatched.filter { it !in moved } + removed).distinct()
    }
}
