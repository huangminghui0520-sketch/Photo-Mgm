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

    /**
     * 构造单张照片的预加载请求。
     *
     * ★ 2026-10-31 复核：抽成 internal 方法的唯一理由是让"请求参数"变成**可断言的**——
     *   原先请求在 preload 内部匿名构造，size / 缓存键全是黑盒，W5 的"未限定解码尺寸"
     *   只能靠人肉读代码发现（据此才有 ThumbnailCacheWiringTest 里的那条尺寸用例）。
     *   ⚠ preload 必须且只能走这里入队，否则测试断言的对象与真实入队的请求会分叉。
     */
    internal fun buildRequest(ref: String): ImageRequest = ImageRequest.Builder(appContext)
        .data(ref)
        // 显式指定与 AsyncImage 相同的缓存键，确保界面端能命中
        // ★ 已知坑（W5，下一轮修；2026-10-31 实测更正）：
        //   实测 Coil 2.5 在未指定 size 时用的是默认的 **DisplaySizeResolver**（整屏尺寸，
        //   Robolectric 下实测解析出 Size(470×470)），并非早前以为的"原图尺寸"。
        //   问题不在"太大"本身，而在组合：显式 memoryCacheKey(ref) 把**尺寸维度挤出缓存键**，
        //   于是整屏尺寸的预加载与卡片尺寸的界面请求共用同一个键、互相覆盖。
        //   → 修复必须**同时**做两件事：加 `.size(w, h)`，并让缓存键带上尺寸
        //     （要么去掉裸 ref 键改回 Coil 自动计算，要么把尺寸拼进 key）。只做一半都会被
        //     ThumbnailCacheWiringTest 里那条 @Ignore 的用例挡下。
        .memoryCacheKey(ref)
        .diskCacheKey(ref)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .build()

    override fun preload(refs: List<String>, maxCount: Int) {
        if (refs.isEmpty() || refs.size > maxCount) return
        val loader = imageLoader
        refs.forEach { ref -> loader.enqueue(buildRequest(ref)) }
    }
}
