// data/ExifPhotoReader.kt —— §4.3 EXIF 精读（Android 实现）
// 读取失败字段置 null、不中断（§4.3）；时间解析走纯函数 ExifTimeParser（可单测）
package com.photomgm.data

import algorithm.ExifReader
import androidx.exifinterface.media.ExifInterface

/**
 * ExifInterface 数据接入：按文件路径/uri 精读拍摄时间 + GPS。
 * - captureTimeMs：TAG_DATETIME_ORIGINAL 优先，回退 TAG_DATETIME；解析失败 null
 * - GPS：latLong（ExifInterface 已封装北/南纬、东/西经换算）；缺失 null
 */
class ExifPhotoReader : ExifReader {

    override fun readCaptureTimeMs(sourceRef: String): Long? = runCatching {
        val ei = ExifInterface(sourceRef)
        val raw = ei.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: ei.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        ExifTimeParser.parse(raw)
    }.getOrNull()

    override fun readGps(sourceRef: String): Pair<Double, Double>? = runCatching {
        val ei = ExifInterface(sourceRef)
        val latLng = ei.latLong ?: return null
        Pair(latLng[0], latLng[1])
    }.getOrNull()
}
