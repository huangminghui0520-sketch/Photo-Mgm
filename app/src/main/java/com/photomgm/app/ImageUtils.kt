// app/ImageUtils.kt —— 导出Excel 2.0 方案 §3.4 图片处理
// content:// Uri 或本地路径 → 解码（采样降内存）→ 4:3 中心裁剪 → 缩放 400×300 → JPEG 质量 50%。
// 裁剪几何计算复用 algorithm.ImageCrop（可移植、可单测）。
package com.photomgm.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import algorithm.ImageCrop
import java.io.ByteArrayOutputStream

object ImageUtils {
    /** 目标缩略图宽度（px，导出Excel 2.0 方案） */
    const val TARGET_WIDTH = 400
    /** JPEG 压缩质量（导出Excel 2.0 方案） */
    const val JPEG_QUALITY = 50

    /** 目标高度 = 400 × 3/4 = 300（4:3 比例，导出 Excel 宽 3.8cm） */
    val TARGET_HEIGHT: Int = (TARGET_WIDTH * ImageCrop.RATIO_H / ImageCrop.RATIO_W).toInt()

    /**
     * 从引用（content:// 或本地物理路径）解码 Bitmap。
     * 先读边界、按目标尺寸 2 倍超采样（inSampleSize），控制峰值内存（大图不全尺寸解码）。
     * ★ 分区存储兜底：Android 10+ 上 MediaStore 返回的 DATA 物理路径无法直接 decodeFile，
     *   需通过 MediaStore content URI 读取；decodeFile 失败时自动回退到 MediaStore 查询。
     */
    fun loadBitmap(context: Context, ref: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(context, ref, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w("PM_Image", "bounds invalid, return null")
            return null
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH * 2 &&
            bounds.outHeight / (sample * 2) >= TARGET_HEIGHT * 2
        ) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        decode(context, ref, opts)
    }.getOrElse { e ->
        Log.e("PM_Image", "loadBitmap exception: ${e.message}", e)
        null
    }

    private fun decode(context: Context, ref: String, opts: BitmapFactory.Options): Bitmap? {
        if (ref.startsWith("content://")) {
            return context.contentResolver.openInputStream(Uri.parse(ref))
                ?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
        // 物理路径：先尝试直接 decodeFile（旧版 / App 自有文件）
        BitmapFactory.decodeFile(ref, opts)?.let { return it }
        // ★ 分区存储兜底：通过 MediaStore 查 DATA 匹配的记录，拿 content URI 读取
        return decodeFromMediaStore(context, ref, opts)
    }

    /** 通过 MediaStore content URI 读取物理路径对应的照片（Android 10+ 分区存储必需）。 */
    private fun decodeFromMediaStore(context: Context, path: String, opts: BitmapFactory.Options): Bitmap? =
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                MediaStore.Images.Media.DATA + "=?",
                arrayOf(path), null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    context.contentResolver.openInputStream(contentUri)
                        ?.use { BitmapFactory.decodeStream(it, null, opts) }
                } else null
            }
        }.getOrNull()

    /** 处理单张照片：解码 → 4:3 中心裁剪 → 400×300 → JPEG50 → 字节；任一步失败返回 null（该照片单元格留空）。 */
    fun process(context: Context, ref: String): ByteArray? {
        val bmp = loadBitmap(context, ref)
        if (bmp == null) { Log.w("PM_Image", "process: bmp null, ref=$ref"); return null }
        val rect = ImageCrop.centerCrop4to3(bmp.width, bmp.height)
        if (rect == null) { Log.w("PM_Image", "process: crop rect null"); return null }
        val cropped = runCatching {
            Bitmap.createBitmap(bmp, rect.left, rect.top, rect.width, rect.height)
        }.getOrNull()
        if (cropped == null) { Log.w("PM_Image", "process: crop failed"); return null }
        if (cropped !== bmp) bmp.recycle()
        val scaled = Bitmap.createScaledBitmap(cropped, TARGET_WIDTH, TARGET_HEIGHT, true)
        if (scaled !== cropped) cropped.recycle()
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        scaled.recycle()
        return out.toByteArray()
    }
}
