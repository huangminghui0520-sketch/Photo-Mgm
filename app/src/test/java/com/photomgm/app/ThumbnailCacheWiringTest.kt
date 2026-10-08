// app/ThumbnailCacheWiringTest —— 缩略图缓存接线验证
//
// 存在的意义：这里最容易犯、且最不容易被发现的错误是「预加载写进缓存 A，界面读缓存 B」——
// 代码能编译、能跑、界面也不报错，只是预加载完全没效果。
// 因此用测试真正钉死：Application 注册的实例必须就是 Coil 全局单例。
package com.photomgm.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
}
