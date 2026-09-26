// desktop/.../ui/Toolbar.kt —— 全局工具栏
package com.photomgm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photomgm.app.AppViewModel
import com.photomgm.app.UiState
import java.awt.Frame
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/** JFileChooser 文件选择（原生对话框，parent 可空）。 */
private fun chooseFile(parent: Frame?, title: String, filterDesc: String, exts: List<String>): java.io.File? {
    var result: java.io.File? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        if (exts.isNotEmpty()) {
            chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter(filterDesc, *exts.toTypedArray())
        }
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            result = chooser.selectedFile
        }
    }
    return result
}

private fun chooseDirectory(parent: Frame?, title: String): java.io.File? {
    var result: java.io.File? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            result = chooser.selectedFile
        }
    }
    return result
}

@Composable
fun WorkbenchToolbar(
    state: UiState,
    vm: AppViewModel,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 巡查日期（高频常驻）：◀ 日期 ▶ + 今天
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevDay) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "前一天")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 2.dp),
                ) {
                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        " ${state.date}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                IconButton(onClick = onNextDay) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "后一天")
                }
            }
        }
        Button(onClick = onToday, contentPadding = ButtonDefaults.ContentPadding) { Text("今天") }

        Spacer(Modifier.width(4.dp))

        // 日志导入：选择日志文本文件（.txt/.log/任意）
        Button(
            onClick = {
                chooseFile(Frame.getFrames().firstOrNull(), "导入日志文件", "文本", listOf("txt", "log"))?.let {
                    vm.importLogFile(it)
                }
            },
            enabled = !state.busy,
        ) {
            Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp))
            Text(" 日志导入")
        }
        // 生成分类
        Button(
            onClick = { vm.runClassify() },
            enabled = !state.busy && state.parsed != null && state.photos.isNotEmpty(),
        ) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
            Text(" 生成分类")
        }

        Spacer(Modifier.weight(1f))

        // 导出台账（Excel）
        Button(
            onClick = { vm.exportLedger() },
            enabled = !state.busy,
        ) {
            Icon(Icons.Filled.Upload, null, Modifier.size(18.dp))
            Text(" 导出台账")
        }
        // 导出压缩包（ZIP，保留）
        Button(
            onClick = { vm.exportZip() },
            enabled = !state.busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            ),
        ) {
            Icon(Icons.Filled.FolderZip, null, Modifier.size(18.dp))
            Text(" 导出压缩包")
        }
        // 设置
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, "设置")
        }
    }
}
