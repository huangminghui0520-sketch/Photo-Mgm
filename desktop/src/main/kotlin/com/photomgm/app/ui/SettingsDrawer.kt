// desktop/.../ui/SettingsDrawer.kt —— 设置抽屉（⚙ 收敛）
// 照片源目录（添加/删除）/ 输出目录 / 事件分类文件夹命名模板（ZIP 保留）
package com.photomgm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.photomgm.app.UiState
import java.awt.Frame
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

private fun chooseDir(title: String): java.io.File? {
    var result: java.io.File? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        if (chooser.showOpenDialog(Frame.getFrames().firstOrNull()) == JFileChooser.APPROVE_OPTION) {
            result = chooser.selectedFile
        }
    }
    return result
}

@Composable
fun SettingsDrawer(
    state: UiState,
    onDismiss: () -> Unit,
    onAddDir: (String) -> Unit,
    onRemoveDir: (String) -> Unit,
    onSetOutput: (String?) -> Unit,
    onSetTemplate: (Int) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxHeight().widthIn(min = 380.dp, max = 460.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 标题
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设置", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "关闭") }
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        // 照片源目录
                        Text("照片源目录", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = {
                                chooseDir("选择照片源目录")?.let { onAddDir(it.absolutePath) }
                            }) { Icon(Icons.Filled.Add, null, Modifier.width(16.dp).height(16.dp)); Text(" 添加目录") }
                            Text("（可拖入文件夹到此窗口）", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.sourceDirs.forEach { dir ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Folder, null, Modifier.width(18.dp).height(18.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Text(" $dir", style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = { onRemoveDir(dir) }) {
                                    Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    item {
                        // 输出目录
                        Text("输出目录", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = {
                                chooseDir("选择输出目录")?.let { onSetOutput(it.absolutePath) }
                            }) { Text("选择") }
                            Text(state.outputDir ?: "未设置（导出前必须设置）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                        }
                    }

                    item {
                        // 事件分类文件夹命名模板（服务 ZIP）
                        Text("ZIP 事件分类文件夹命名", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = state.namingTemplate == 1,
                                onClick = { onSetTemplate(1) })
                            Text("月日+巡查日志内容（如 0926交通事故处置）")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = state.namingTemplate == 0,
                                onClick = { onSetTemplate(0) })
                            Text("仅事件类型（如 交通事故处置）")
                        }
                    }

                    item {
                        Text("提示", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("· 巡查日期在工具栏常驻，可直接 ◀ ▶ 切换\n· 右键照片可移动到事件 / 标记台账照片\n· 支持拖入照片文件夹 / 日志文本文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
