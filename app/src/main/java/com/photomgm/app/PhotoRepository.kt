// app/PhotoRepository.kt —— 照片数据接入编排（§4.1：MediaStore 查候选 → 粗筛 → EXIF 精读）
// 复用 :data 平台实现（MediaStorePhotoSourceProvider / ExifPhotoReader / PhotoCache）
// 与 :algorithm 纯逻辑（PhotoIngest.coarseFilter / readExif / dedupById）
package com.photomgm.app

import algorithm.CandidatePhoto
import algorithm.PhotoIngest
import algorithm.model.Photo
import android.content.Context
import com.photomgm.data.ExifPhotoReader
import com.photomgm.data.MediaStorePhotoSourceProvider
import com.photomgm.data.PhotoCache
import java.time.LocalDate

class PhotoRepository(context: Context) {
    private val appContext = context.applicationContext
    private val provider = MediaStorePhotoSourceProvider(appContext.contentResolver)
    private val exif: ExifPhotoReader = ExifPhotoReader()
    private val cache = PhotoCache()

    /**
     * 多源目录候选合并 → 目录白名单 + 文件名日期粗筛 → 按 sourceRef 去重。
     * key = 日期 + 目录集合 hash；30 分钟缓存（§4.4）。
     */
    fun coarseCandidates(date: LocalDate, sourceDirs: List<String>): List<CandidatePhoto> {
        val key = "$date|${sourceDirs.sorted()}"
        cache.get(key, System.currentTimeMillis())?.let { return it }
        val all = mutableListOf<CandidatePhoto>()
        for (dir in sourceDirs) all += provider.listCandidates(dir)   // 每源独立查询（§4.4）
        val result = PhotoIngest.coarseFilter(all, sourceDirs, date..date)
        cache.put(key, result, System.currentTimeMillis())
        return result
    }

    /** EXIF 精读（仅对粗筛出的 50-200 张；失败置 null 不中断）+ 按 _ID 去重（§4.3/§4.4）。 */
    fun readExif(candidates: List<CandidatePhoto>): List<Photo> =
        PhotoIngest.dedupById(PhotoIngest.readExif(candidates, exif))

    /**
     * ★ 清除粗筛结果缓存（§4.4）。
     * 修改日期 / 源目录 / 删除或重新解析日志后调用，强制下一次重新从 MediaStore 查询并匹配，
     * 避免 30 分钟 TTL 内同 key 命中旧照片（照片文件在源目录被增删时的陈旧结果）。
     */
    fun clearCache() = cache.clear()
}
