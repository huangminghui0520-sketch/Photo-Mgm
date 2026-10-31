// app/ThumbnailCacheWiringTest —— 缩略图缓存接线验证
//
// 存在的意义：这里最容易犯、且最不容易被发现的错误是「预加载写进缓存 A，界面读缓存 B」——
// 代码能编译、能跑、界面也不报错，只是预加载完全没效果。
// 因此用测试真正钉死：Application 注册的实例必须就是 Coil 全局单例。
package com.photomgm.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.memory.MemoryCache
import coil.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThumbnailCacheWiringTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    /**
     * 核心断言：全局单例必须就是 PhotoMgmApp 构建的那个实例。
     * 若有人以后改成在别处 new 一个 ImageLoader，此用例会失败。
     */
    @Test
    fun `Application 注册的 ImageLoader 就是 Coil 全局单例`() {
        val application = app()
        assertTrue(
            "manifest 的 android:name 应指向 PhotoMgmApp，实际拿到 " + application::class.java.name,
            application is PhotoMgmApp,
        )
        val owned = (application as PhotoMgmApp).imageLoader
        val global = Coil.imageLoader(application)
        assertSame(
            "预加载与 AsyncImage 必须共用同一实例，否则缓存互不相通、预加载等于白做",
            owned,
            global,
        )
    }

    @Test
    fun `全局单例同时具备内存缓存与磁盘缓存`() {
        val loader = Coil.imageLoader(app())
        assertTrue("内存缓存应可用（供缩略图命中）", loader.memoryCache != null)
        assertTrue("磁盘缓存应可用（跨进程重启复用）", loader.diskCache != null)
    }

    @Test
    fun `preload 超过上限时静默放弃`() {
        val cache = CoilThumbnailCache(app())
        val many = (1..50).map { "/nonexistent/photo_$it.jpg" }
        cache.preload(many, maxCount = 10)   // 超限 → 不应抛异常
        cache.clear()
    }

    @Test
    fun `clear 与 preload 在空输入下不崩溃`() {
        val cache = CoilThumbnailCache(app())
        cache.clear()
        cache.preload(emptyList(), maxCount = 200)
        ThumbnailCache.NoOp.clear()
        ThumbnailCache.NoOp.preload(listOf("x"), 200)
    }

    /**
     * 固化现状（写它就是为了别被顺手删掉）：显式内存/磁盘缓存键必须等于照片引用。
     * 这是"预加载写进去、界面 AsyncImage 取出来"的前提——界面端是按 ref 取图的。
     */
    @Test
    fun `预加载请求的缓存键等于照片引用_与界面AsyncImage一致`() {
        val ref = "/sdcard/DCIM/Camera/a.jpg"
        val req = CoilThumbnailCache(app()).buildRequest(ref)
        assertEquals(
            "内存缓存键必须等于 ref，否则界面 AsyncImage 命中不到预加载结果",
            MemoryCache.Key(ref),
            req.memoryCacheKey,
        )
        assertEquals("磁盘缓存键同理", ref, req.diskCacheKey)
    }

    /**
     * ★ W5（本轮只固化，下一轮修）：预加载请求**必须显式**限定解码尺寸。
     *
     *   实测（Coil 2.5，Robolectric 下打印确认）：[CoilThumbnailCache.buildRequest] 没有
     *   `.size(...)` 时，sizeResolver 是 Coil 默认的 **DisplaySizeResolver** —— 即"整屏尺寸"
     *   （实测解析出 Size(470×470)），不是原图尺寸，也不是卡片尺寸。两个后果：
     *     1) 事件网格里 100dp 见方的卡片，解码出的却是整屏级别的位图，几十张就能吃掉几十 MB；
     *     2) 请求又显式指定了 `memoryCacheKey(ref)`，**尺寸维度被挤出缓存键**，
     *        于是预加载（整屏尺寸）与界面 AsyncImage（卡片尺寸）共用同一个键，
     *        互相覆盖——要么白预加载，要么界面拿到尺寸不匹配的位图。
     *
     *   ★ 之所以用 @Ignore 而不是删掉：这条判据的形状就是"memoryCacheKey 坑"——
     *   修 W5 时若只加了 size 却仍保留裸 ref 键（或反之），本用例依然红，能挡住半吊子修法。
     *   ★ 本仓约定：@Ignore 必须带「原因 + issue 编号」，由 scripts/check-ignore-policy.py
     *   在 CI 强制；修复后删除 @Ignore 即可变绿。
     */
    @Ignore("W5(#3): preload 未显式 .size(w,h)，且 memoryCacheKey(ref) 把尺寸维度挤出缓存键；" +
        "修复须同时加尺寸与尺寸化缓存键（去掉裸 ref 键或把尺寸拼进 key），修复后删除本注解")
    @Test
    fun `预加载请求必须显式限定解码尺寸_不能依赖默认解析器`() {
        val req = CoilThumbnailCache(app()).buildRequest("/sdcard/DCIM/Camera/a.jpg")
        // ★ 判据用 req.defined.sizeResolver（Coil 的公开 API：只有**显式**调用过 .size(...)
        //   才会非空），而不是去猜内部解析器类型（RealSizeResolver 是 internal，测试侧碰不到）。
        val defined = req.defined.sizeResolver
        assertNotNull(
            "预加载请求必须显式 `.size(w, h)`。当前 defined.sizeResolver 为空，即依赖 Coil 默认解析器" +
                "（DisplaySizeResolver = 整屏尺寸）；又因显式指定了 memoryCacheKey(ref)，" +
                "尺寸维度不进缓存键，预加载与界面 AsyncImage 会共用同一份位图互相覆盖",
            defined,
        )
        val size = runBlocking { defined!!.size() }   // 上一行断言已保证非空
        assertNotEquals("显式限定的尺寸必须是确定像素值，不能是 Original", Size.ORIGINAL, size)
    }
}
