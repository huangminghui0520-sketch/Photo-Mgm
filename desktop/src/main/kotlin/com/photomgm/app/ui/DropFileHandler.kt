// desktop/.../ui/DropFileHandler.kt —— P0-1 拖放导入（系统文件拖入窗口）
// 文件夹拖入 → 源目录；.txt/.log 拖入 → 日志文本。用 AWT DropTarget 监听（Compose Desktop 窗口）。
package com.photomgm.app.ui

import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.Component
import java.io.File
import java.util.TooManyListenersException
import javax.swing.SwingUtilities

/**
 * 给窗口组件挂载文件拖放：拖入文件夹当源目录、拖入文本文件当日志。
 * 必须在 EDT 创建（Compose Desktop 主线程即 EDT）。
 */
class DropFileHandler(
    private val target: Component,
    private val onFolder: (File) -> Unit,
    private val onLogFile: (File) -> Unit,
) {
    private val dropTarget: DropTarget

    init {
        dropTarget = DropTarget(
            target,
            DnDConstants.ACTION_COPY,
            object : DropTargetAdapter() {
                override fun drop(e: DropTargetDropEvent) {
                    e.acceptDrop(DnDConstants.ACTION_COPY)
                    val files = try {
                        @Suppress("UNCHECKED_CAST")
                        e.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
                            as List<File>
                    } catch (_: Exception) {
                        e.dropComplete(false); emptyList()
                    }
                    if (files.isEmpty()) { e.dropComplete(false); return }
                    files.forEach { f ->
                        SwingUtilities.invokeLater {
                            when {
                                f.isDirectory -> onFolder(f)
                                isLogTextFile(f.name) -> onLogFile(f)
                            }
                        }
                    }
                    e.dropComplete(true)
                }
            },
        )
        dropTarget.isActive = true
        try {
            target.dropTarget = dropTarget
        } catch (_: TooManyListenersException) { /* 已有监听则忽略 */ }
    }

    private fun isLogTextFile(name: String): Boolean =
        name.lowercase().endsWith(".txt") || name.lowercase().endsWith(".log")
}
