// data/ExifPhotoReader.kt —— §4.3 EXIF 精读（PC 桌面实现）
// 使用 metadata-extractor 纯 Java 库读取拍摄时间 + GPS；读取失败置 null、不中断（§4.3）
package com.photomgm.data

import algorithm.ExifReader
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import java.io.File

/**
 * metadata-extractor 数据接入：按文件绝对路径精读拍摄时间 + GPS。
 * - captureTimeMs：EXIF DateTimeOriginal 优先，回退 DateTime；解析失败 null
 * - GPS：GPS 经纬度（十进制度）；缺失 null
 */
class ExifPhotoReader : ExifReader {

    override fun readCaptureTimeMs(sourceRef: String): Long? = runCatching {
        val dir = ImageMetadataReader.readMetadata(File(sourceRef))
            .getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            ?: return null
        val raw = dir.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
            ?: dir.getString(ExifSubIFDDirectory.TAG_DATETIME)
            ?: return null
        ExifTimeParser.parse(raw)
    }.getOrNull()

    override fun readGps(sourceRef: String): Pair<Double, Double>? = runCatching {
        val dir = ImageMetadataReader.readMetadata(File(sourceRef))
            .getFirstDirectoryOfType(GpsDirectory::class.java)
            ?: return null
        val loc = dir.geoLocation ?: return null
        Pair(loc.latitude, loc.longitude)
    }.getOrNull()
}
