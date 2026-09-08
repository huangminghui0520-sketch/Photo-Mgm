// data/PhotoCacheTest.kt —— 粗筛缓存（纯 JVM 单测，now 注入可控时间）
package com.photomgm.data

import algorithm.CandidatePhoto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PhotoCacheTest {
    private fun cand(id: Long) = CandidatePhoto(id, "s$id", "img$id.jpg")

    @Test fun `命中返回缓存`() {
        val cache = PhotoCache(ttlMs = 1000)
        val list = listOf(cand(1), cand(2))
        cache.put("k1", list, 100)
        assertEquals(list, cache.get("k1", 500))
    }

    @Test fun `未命中返回null`() {
        val cache = PhotoCache(ttlMs = 1000)
        assertNull(cache.get("k1", 100))
    }

    @Test fun `过期后返回null并清除`() {
        val cache = PhotoCache(ttlMs = 1000)
        cache.put("k1", listOf(cand(1)), 100)
        assertNull(cache.get("k1", 100 + 1001), "超 TTL 应失效")
    }

    @Test fun `恰好TTL内仍有效`() {
        val cache = PhotoCache(ttlMs = 1000)
        cache.put("k1", listOf(cand(1)), 100)
        assertNotNull(cache.get("k1", 100 + 1000), "t == ttl 边界仍有效")
    }

    @Test fun `clear清空全部`() {
        val cache = PhotoCache(ttlMs = 1000)
        cache.put("k1", listOf(cand(1)), 100)
        cache.clear()
        assertNull(cache.get("k1", 200))
    }
}
