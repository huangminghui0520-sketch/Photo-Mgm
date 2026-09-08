// algorithm/ZipExporter.kt —— §7.4 ZIP 导出（纯 Kotlin，零 Android 依赖，可移植 PC）
// 按事件分类文件夹打包（复制不移动源文件）；流式复制 32KB；命名模板二选一；未匹配→「未分类」；
// 断点续传 .export_state.json（整包级别：上次已全部完成 → 跳过）；进度回调（总体百分比 + 当前文件名）；
// extraFiles：额外写入 ZIP 的文件（如台账 Excel），非空时跳过断点续传保证内容最新
package algorithm

import algorithm.model.LogEvent
import algorithm.model.Photo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipExporter(private val zone: ZoneId = ZoneId.systemDefault()) {

    data class Result(val copied: Int, val failed: Int, val skippedFromState: Int)

    /**
     * 导出 ZIP。
     * @param eventPhotoMap 已合并人工移动后的 eventId -> photoIds（app 侧 effectiveMap）
     * @param unmatched     未匹配照片 id（§7.4 → 「未分类」）
     * @param photoById     照片解析（Android=物理路径 data 层；PC=文件列表）
     * @param namingTemplate 0=事件类型（默认）｜1=日期+事件类型+巡查日志内容（§7.4）
     * @param outFile       ZIP 输出文件
     * @param stateFile     断点续传状态（上次全部完成后跳过）
     * @param extraFiles    ZIP 内路径 -> 字节（如台账 Excel）；非空时整包跳过失效（保证内容最新）
     */
    fun export(
        events: List<LogEvent>,
        eventPhotoMap: Map<Int, List<Long>>,
        unmatched: List<Long>,
        photoById: (Long) -> Photo?,
        namingTemplate: Int,
        outFile: File,
        stateFile: File?,
        extraFiles: Map<String, ByteArray> = emptyMap(),
        onProgress: (Float, String) -> Unit,
    ): Result {
        // 1) 建 文件夹 -> 照片 映射（含未匹配 → 未分类）
        val folders = linkedMapOf<String, MutableList<Photo>>()
        for (e in events) {
            val folder = folderName(e, namingTemplate)
            val list = folders.getOrPut(folder) { mutableListOf() }
            (eventPhotoMap[e.id] ?: emptyList()).forEach { pid ->
                photoById(pid)?.let { list.add(it) }
            }
        }
        (unmatched).forEach { pid ->
            photoById(pid)?.let { folders.getOrPut("未分类") { mutableListOf() }.add(it) }
        }

        // 2) 去重（同一照片可能被重复列出）+ 全目标路径集合
        val targets = linkedMapOf<String, File>()   // zip 内路径 -> 源文件
        val usedNames = mutableSetOf<String>()
        for ((folder, photos) in folders) {
            for (p in photos) {
                if (p.sourceRef.isEmpty()) continue
                val base = p.displayName.ifBlank { p.sourceRef.substringAfterLast('/').substringAfterLast('\\') }
                var name = base
                var k = 1
                while ("$folder/$name" in usedNames) { name = base.substringBeforeLast('.') + "_$k." + base.substringAfterLast('.', ""); k++ }
                usedNames += "$folder/$name"
                targets["$folder/$name"] = File(p.sourceRef)
            }
        }

        // 3) 断点续传：仅当本次期望的目标集合与已完成集合【完全一致】才整包跳过。
        //    ★ 修复：旧逻辑 targets ⊆ completed 即跳过，用户移动/删除照片后仍返回含旧照片的 ZIP；
        //      改为集合完全相等才跳过，任何增删改都重新打包。
        //    ★ extraFiles 非空时不跳过：台账内容随标记/移动变化，必须每次都重新打包保证最新。
        val completed = readCompleted(stateFile)
        if (extraFiles.isEmpty() && completed != null && completed == targets.keys) {
            return Result(0, 0, targets.size)
        }

        // 4) 流式打包（32KB 缓冲）
        var copied = 0
        var failed = 0
        val total = targets.size + extraFiles.size
        FileOutputStream(outFile).use { fos ->
            ZipOutputStream(fos.buffered()).use { zip ->
                for ((path, src) in targets) {
                    try {
                        if (!src.isFile) throw java.io.IOException("文件不存在: ${src.path}")
                        zip.putNextEntry(ZipEntry(path))
                        FileInputStream(src).use { ins ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                zip.write(buf, 0, n)
                            }
                        }
                        zip.closeEntry()
                        copied++
                    } catch (e: Exception) {
                        failed++
                    }
                    onProgress(if (total == 0) 1f else copied.toFloat() / total, path)
                }
                // 4.5) 额外文件（如台账 Excel）
                for ((path, bytes) in extraFiles) {
                    try {
                        zip.putNextEntry(ZipEntry(path))
                        zip.write(bytes)
                        zip.closeEntry()
                        copied++
                    } catch (e: Exception) {
                        failed++
                    }
                    onProgress(if (total == 0) 1f else copied.toFloat() / total, path)
                }
            }
        }
        // 5) 写完成状态
        if (failed == 0) writeCompleted(stateFile, targets.keys)
        return Result(copied, failed, 0)
    }

    /**
     * 事件分类文件夹名（§7.4）。
     * 模板 1 = 日期 + 事件类型 + 巡查日志内容（描述截断 24 字符，换行合并为空格）。
     */
    fun folderName(e: LogEvent, namingTemplate: Int): String {
        return if (namingTemplate == 1) {
            val parts = mutableListOf<String>()
            e.startTimeMs?.let { ms ->
                parts += Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().format(BASIC_DATE)
            }
            if (e.eventType.isNotBlank()) parts += e.eventType
            if (e.description.isNotBlank()) parts += truncate(e.description, 24)
            sanitize(parts.joinToString("_"))
        } else {
            sanitize(e.eventType)
        }
    }

    /** 描述单行化 + 截断（保留语义，避免文件夹名过长）。 */
    private fun truncate(s: String, max: Int): String {
        val oneLine = s.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= max) oneLine else oneLine.take(max).trimEnd()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_")

    private fun readCompleted(f: File?): Set<String>? {
        if (f == null || !f.isFile) return null
        val raw = f.readText()
        val m = Regex(""""completed":\s*\[(.*?)\]""").find(raw) ?: return emptySet()
        return m.groupValues[1].split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }.toSet()
    }

    private fun writeCompleted(f: File?, paths: Set<String>) {
        if (f == null) return
        val json = paths.joinToString(",", "{\"completed\":[", "]}") { "\"${it.replace("\"", "\\\"")}\"" }
        f.parentFile?.mkdirs()
        f.writeText(json)
    }

    private companion object {
        val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    }
}
