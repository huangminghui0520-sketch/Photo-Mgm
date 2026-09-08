// algorithm/AlgorithmApi.kt —— §2.2 统一算法入口
// UI / PC 只调用此入口；核心内部零 Android 依赖，输入输出均为纯数据。
package algorithm

import algorithm.model.Cluster
import algorithm.model.GpsGroupResult
import algorithm.model.LedgerRow
import algorithm.model.LogEvent
import algorithm.model.Photo
import java.time.LocalDate
import java.time.ZoneId

object AlgorithmApi {
    /** 日志解析：输入日志文本 + 巡查日期，输出 ParsedLog（§3.4）。 */
    fun parseLog(text: String, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): ParsedLog =
        LogParserEngine.parse(text, date, zone)

    /** GPS 分组（§5）：输入已精读 EXIF 的照片，输出 簇 + 无GPS/无时间 singles + 拆簇次数。 */
    fun gpsGroup(
        photos: List<Photo>,
        maxDistanceM: Double = 50.0,
        timeWindowMs: Long = 5 * 60 * 1000,
    ): GpsGroupResult = GpsGrouperEngine(maxDistanceM, timeWindowMs).group(photos)

    /** 事件分类（§6）：簇 + 单张 按窗口归事件，输出 归属表/未匹配/干预提示/待定事件。 */
    fun classify(
        events: List<LogEvent>,
        clusters: List<Cluster>,
        singles: List<Photo>,
        patrolDate: LocalDate,
        splitCount: Int = 0,
        maxDistanceM: Double = 50.0,
    ): ClassifyResult = EventClassifyEngine(maxDistanceM).classify(events, clusters, singles, patrolDate, splitCount)

    /** 台账生成（§7）：事件 + 分类结果 → 每事件一行（日期/地点/日志内容/照片第一张+最后一张）。 */
    fun buildLedger(
        events: List<LogEvent>,
        eventPhotoMap: Map<Int, List<Long>>,
        photoById: (Long) -> Photo?,
    ): List<LedgerRow> = LedgerEngine().buildLedger(events, eventPhotoMap, photoById)

    /** Excel 写入（§7.3）：LedgerRow → .xlsx 字节流（纯 Kotlin OOXML，可移植 PC）。
     *  @param imageLoader 照片引用 → 图片字节（JPEG/PNG），嵌入台账照片列；返回 null/缺省 则照片列仅写文件名。 */
    fun exportLedgerXlsx(
        rows: List<LedgerRow>,
        out: java.io.OutputStream,
        imageLoader: (String?) -> ByteArray? = { null },
    ): Unit = XlsxWriter.write(rows, out, imageLoader)

    /** ZIP 导出（§7.4）：按事件分类文件夹打包，命名模板二选一，未匹配→未分类；
     *  extraFiles 额外写入 ZIP（如台账 Excel），非空时跳过断点续传。 */
    fun exportZip(
        events: List<LogEvent>,
        eventPhotoMap: Map<Int, List<Long>>,
        unmatched: List<Long>,
        photoById: (Long) -> Photo?,
        namingTemplate: Int,
        outFile: java.io.File,
        stateFile: java.io.File?,
        extraFiles: Map<String, ByteArray> = emptyMap(),
        onProgress: (Float, String) -> Unit,
    ): ZipExporter.Result = ZipExporter().export(events, eventPhotoMap, unmatched, photoById,
        namingTemplate, outFile, stateFile, extraFiles, onProgress)
}
