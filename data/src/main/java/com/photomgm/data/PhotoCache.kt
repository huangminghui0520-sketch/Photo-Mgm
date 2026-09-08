// data/PhotoCache.kt —— 粗筛结果内存缓存（§4.4：key = logDates+sourceDirsHash，30 分钟）
// 纯 Kotlin，JVM 可单测；时间由调用方注入（now）便于测试与 Android 主线程一致性
package com.photomgm.data

import algorithm.CandidatePhoto

class PhotoCache(private val ttlMs: Long = 30 * 60 * 1000L) {
    private data class Entry(val photos: List<CandidatePhoto>, val at: Long)

    private val map = HashMap<String, Entry>()

    /** 命中且未过期 → 返回缓存照片；未命中/过期 → null（并清除过期项）。 */
    fun get(key: String, now: Long): List<CandidatePhoto>? {
        val e = map[key] ?: return null
        if (now - e.at > ttlMs) { map.remove(key); return null }
        return e.photos
    }

    fun put(key: String, photos: List<CandidatePhoto>, now: Long) {
        map[key] = Entry(photos, now)
    }

    fun clear() = map.clear()
}
