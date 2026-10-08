// app/ThumbnailCache.kt —— 缩略图缓存：清旧缓存 → 预加载当日照片
//
// 为什么需要它：
//   Coil 按 model 字符串（这里是照片路径）做内存/磁盘缓存。用户切换巡查日期或更换源目录后，
//   若不主动清缓存，上一批照片的缩略图会滞留在内存里占额度；而新一批照片要等到事件卡片
//   真正滚动到可见位置才开始解码，滚动时会出现明显白块。
//
// 做法（对应用户要求的"清除之前的缓存缩略图然后再缓存相关照片缩略图"）：
//   1. clear()   清空内存缓存 + 磁盘缓存
//   2. preload() 对刚精读完 EXIF 的照片批量解码为缩略图并写入内存缓存
//
// ★ 关键：必须复用 PhotoMgmApp 注册的全局单例 ImageLoader。
//   若此处自建实例，预加载写入缓存 A 而 AsyncImage 读缓存 B，预加载完全无效。
package com.photomgm.app

import android.content.Context
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest

interface ThumbnailCache {
    /** 清空全部已缓存缩略图（内存 + 磁盘）。 */
    fun clear()

    /**
     * 批量预加载缩略图并写入内存缓存，供事件卡片直接命中。
     * @param refs 照片引用：绝对路径（MediaStore DATA）或相对路径（RELATIVE_PATH）
     * @param maxCount 上限，超过则放弃预加载（交回惰性加载，避免一次解码上百张）
     */
    fun preload(refs: List<String>, maxCount: Int)

    /** 空实现：不需要缩略图缓存的场景（如单元测试）。 */
    object NoOp : ThumbnailCache {
        override fun clear() = Unit
        override fun preload(refs: List<String>, maxCount: Int) = Unit
    }
}

/**
 * Coil 实现。
 *
 * 不持有自己的 ImageLoader，而是取 **Coil 的全局单例**（由 PhotoMgmApp.onCreate 注册）——
 * 这样预加载与界面 AsyncImage 用的是同一份内存/磁盘缓存，预加载才真正生效。
 */
class CoilThumbnailCache(context: Context) : ThumbnailCache {

    private val appContext = context.applicationContext
    private val imageLoader: ImageLoader get() = coil.Coil.imageLoader(appContext)

    override fun clear() {
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }

    override fun preload(refs: List<String>, maxCount: Int) {
        if (refs.isEmpty() || refs.size > maxCount) return
        val loader = imageLoader
        refs.forEach { ref ->
            val request = ImageRequest.Builder(appContext)
                .data(ref)
                // 显式指定与 AsyncImage 相同的缓存键，确保界面端能命中
                .memoryCacheKey(ref)
                .diskCacheKey(ref)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
            loader.enqueue(request)
        }
    }
}
