// desktop/.../ImageUtils.kt —— 导出Excel 图片处理（PC 版）
// 本地文件路径 → ImageIO 解码 → 4:3 中心裁剪（复用 algorithm.ImageCrop）→ 缩放 400×300 → JPEG 50%。
package com.photomgm.app

import algorithm.ImageCrop
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

object ImageUtils {
    /** 目标缩略图宽度（px） */
    const val TARGET_WIDTH = 400
    /** JPEG 压缩质量 */
    const val JPEG_QUALITY = 50

    /** 目标高度 = 400 × 3/4 = 300（4:3 比例） */
    val TARGET_HEIGHT: Int = (TARGET_WIDTH * ImageCrop.RATIO_H / ImageCrop.RATIO_W).toInt()

    /** 从本地文件路径解码 BufferedImage；失败返回 null。 */
    fun loadImage(ref: String): BufferedImage? = runCatching {
        ImageIO.read(File(ref))
    }.getOrNull()

    /** 处理单张照片：解码 → 4:3 中心裁剪 → 400×300 → JPEG50 → 字节；任一步失败返回 null。 */
    fun process(ref: String): ByteArray? {
        val img = loadImage(ref) ?: return null
        val rect = ImageCrop.centerCrop4to3(img.width, img.height) ?: return null
        val cropped = runCatching { img.getSubimage(rect.left, rect.top, rect.width, rect.height) }.getOrNull()
        if (cropped == null) return null
        val scaled = BufferedImage(TARGET_WIDTH, TARGET_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(cropped, 0, 0, TARGET_WIDTH, TARGET_HEIGHT, null)
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        runCatching { ImageIO.write(scaled, "jpg", out) }.getOrNull() ?: return null
        return out.toByteArray()
    }
}
