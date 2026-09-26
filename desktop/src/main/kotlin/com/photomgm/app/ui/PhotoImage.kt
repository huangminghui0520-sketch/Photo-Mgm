// desktop/.../ui/PhotoImage.kt —— 缩略图异步加载（ImageIO + 简单内存缓存）
package com.photomgm.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** 简单 LRU 缩略图内存缓存（上限约 600 张，防内存暴涨）。 */
private object ThumbCache {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    private val order = java.util.ArrayDeque<String>()
    private const val MAX = 600

    fun get(ref: String): ImageBitmap? = cache[ref]

    fun put(ref: String, bmp: ImageBitmap) {
        synchronized(order) {
            if (cache.containsKey(ref)) return
            cache[ref] = bmp
            order.addLast(ref)
            while (order.size > MAX) {
                val old = order.removeFirst()
                cache.remove(old)
            }
        }
    }
}

private fun decodeThumb(ref: String, maxDim: Int): ImageBitmap? = runCatching {
    val src = ImageIO.read(File(ref)) ?: return null
    val w = src.width; val h = src.height
    val scale = maxOf(w, h) / maxDim.toFloat()
    val tw = if (scale > 1f) (w / scale).toInt() else w
    val th = if (scale > 1f) (h / scale).toInt() else h
    val scaled = BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB)
    val g = scaled.createGraphics()
    try {
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, tw, th, null)
    } finally { g.dispose() }
    scaled.toComposeImageBitmap()
}.getOrNull()

/** 缩略图：加载中显示灰块，加载后显示图片。 */
@Composable
fun PhotoThumb(sourceRef: String, contentDescription: String?, modifier: Modifier = Modifier) {
    var bmp by remember(sourceRef) { mutableStateOf<ImageBitmap?>(ThumbCache.get(sourceRef)) }
    LaunchedEffect(sourceRef) {
        if (bmp == null) {
            decodeThumb(sourceRef, 512)?.let {
                ThumbCache.put(sourceRef, it)
                bmp = it
            }
        }
    }
    val cur = bmp
    if (cur != null) {
        Image(cur, contentDescription, modifier = modifier)
    } else {
        Box(modifier.background(Color(0xFFE0E0E0))) { /* 加载占位 */ }
    }
}
