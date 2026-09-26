// data/FilePhotoSourceProvider.kt —— §4.4 候选照片查询（PC 桌面实现）
// 递归扫描源目录下的常见图片文件；目录白名单（前缀+分隔符）复用 :algorithm 的
// PhotoIngest.inSourceDir（纯逻辑），目录尾部斜杠容忍由该逻辑保证。
package com.photomgm.data

import algorithm.CandidatePhoto
import algorithm.PhotoSourceProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * 文件系统数据接入：递归列出源目录（如 D:/巡查照片/2026-09-26）下的候选照片。
 * - sourceRef = 文件绝对路径（供 ExifPhotoReader 直接读）
 * - id = 0（PC 端无 MediaStore _ID，PhotoIngest.dedupById 对 id<=0 全部保留）
 * - 支持递归子目录；扫描异常（权限等）返回已收集结果，不抛异常
 */
class FilePhotoSourceProvider : PhotoSourceProvider {

    /** 常见图片扩展名（小写，去点） */
    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "bmp")

    override fun listCandidates(sourceDir: String): List<CandidatePhoto> {
        val out = mutableListOf<CandidatePhoto>()
        val root = File(sourceDir)
        if (!root.isDirectory) return out
        val stack = ArrayDeque<Path>()
        stack.add(root.toPath())
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            runCatching { Files.list(dir).use { paths ->
                paths.forEach { p ->
                    when {
                        Files.isRegularFile(p) && isImage(p.fileName.toString()) ->
                            out.add(CandidatePhoto(
                                id = 0,
                                sourceRef = p.toAbsolutePath().toString(),
                                displayName = p.fileName.toString(),
                            ))
                        Files.isDirectory(p) && !Files.isSymbolicLink(p) -> stack.add(p)
                    }
                }
            } }
        }
        return out
    }

    private fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in imageExts
}
