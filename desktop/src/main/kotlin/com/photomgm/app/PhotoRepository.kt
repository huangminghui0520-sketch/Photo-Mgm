// desktop/.../PhotoRepository.kt —— 照片数据接入编排（§4.1 PC 版）
// 文件系统扫候选 → 粗筛 → EXIF 精读；复用 :data 平台实现（FilePhotoSourceProvider / ExifPhotoReader / PhotoCache）
// 与 :algorithm 纯逻辑（PhotoIngest.coarseFilter / readExif / dedupById）
package com.photomgm.app

import algorithm.CandidatePhoto
import algorithm.PhotoIngest
import algorithm.model.Photo
import com.photomgm.data.ExifPhotoReader
import com.photomgm.data.FilePhotoSourceProvider
import com.photomgm.data.PhotoCache
import java.time.LocalDate

class PhotoRepository {
    private val provider = FilePhotoSourceProvider()
    private val exif: ExifPhotoReader = ExifPhotoReader()
    private val cache = PhotoCache()

    /** 多源目录候选合并 → 目录白名单 + 文件名日期粗筛 → 按 sourceRef 去重。30 分钟缓存（§4.4）。 */
    fun coarseCandidates(date: LocalDate, sourceDirs: List<String>): List<CandidatePhoto> {
        val key = "$date|${sourceDirs.sorted()}"
        cache.get(key, System.currentTimeMillis())?.let { return it }
        val all = mutableListOf<CandidatePhoto>()
        for (dir in sourceDirs) all += provider.listCandidates(dir)
        val result = PhotoIngest.coarseFilter(all, sourceDirs, date..date)
        cache.put(key, result, System.currentTimeMillis())
        return result
    }

    /** EXIF 精读（失败置 null 不中断）+ 按 _ID 去重（PC id=0 全保留）。 */
    fun readExif(candidates: List<CandidatePhoto>): List<Photo> =
        PhotoIngest.dedupById(PhotoIngest.readExif(candidates, exif))

    /** 清除粗筛结果缓存（§4.4）。 */
    fun clearCache() = cache.clear()
}
