// app/ui/SettingsScreen.kt —— 设置页（被动触发 · 人机交互重构版）
// 核心：日期/源目录变更即自动粗筛+精读（ViewModel.autoPipeline），本页无任何手动触发按钮；
//       目录一律通过系统 SAF 选择器选取，去掉全部手动路径输入框。
package com.photomgm.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photomgm.app.AppViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val state by vm.state.collectAsState()

    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    // ★ SAF：源目录 / 输出目录统一走系统文件夹选择器，无手动输入
    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.pickSourceDir(it) }
    }
    val pickOutput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.pickOutputDir(it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 标题区
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("设置", style = MaterialTheme.typography.headlineSmall)
            Text(
                "选择日期 / 源目录后自动粗筛并精读照片，全部为预操作，导出时才从源文件复制",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ★ 被动触发加载状态：日期/源目录变更后自动执行中，不阻塞操作
        if (state.busy) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
            Text(
                "正在自动粗筛并精读照片…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // ─────────────────────────────────────────────────
        // ① 巡查日期 · 日期变更自动触发粗筛+精读
        // ─────────────────────────────────────────────────
        SettingCard(icon = Icons.Filled.CalendarMonth, title = "巡查日期") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { showDatePicker = true },
                    label = {
                        Text(
                            state.date.toString(),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.CalendarMonth, null, Modifier.size(18.dp))
                    },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { vm.setDate(LocalDate.now()) },
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    content = {
                        Icon(Icons.Outlined.Today, null, Modifier.size(18.dp))
                        Text(" 今天")
                    },
                )
            }
            Text(
                "变更日期后自动粗筛并精读，已有分类会清空重算",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ─────────────────────────────────────────────────
        // ② 照片源目录 · 系统选择器添加，变更自动触发粗筛+精读
        // ─────────────────────────────────────────────────
        SettingCard(icon = Icons.Filled.Folder, title = "照片源目录（可增删）") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.sourceDirs.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            "尚未添加任何目录，请点击下方按钮选择照片所在文件夹",
                            Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    state.sourceDirs.forEach { dir ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Text(
                                    dir,
                                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Box(Modifier.weight(1f))
                            IconButton(
                                onClick = { vm.removeSourceDir(dir) },
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Icon(Icons.Filled.Close, "删除 $dir")
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { pickSource.launch(null) },
                    Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Text(" 添加照片目录（系统文件夹选择）")
                }
                Text(
                    "常用：DCIM/Camera · DCIM · Pictures（可多次添加）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ─────────────────────────────────────────────────
        // ③ 输出目录 · 系统选择器（SAF 持久授权），导出必填
        // ─────────────────────────────────────────────────
        SettingCard(icon = Icons.Filled.FolderZip, title = "输出目录（导出必填）") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pickOutput.launch(null) },
                    Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.FolderZip, null, Modifier.size(18.dp))
                    Text(" 选择输出目录（系统文件夹选择）")
                }

                if (state.outputDir.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.FolderZip, null, Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            " 未设置输出目录时无法导出，请选择输出文件夹",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    val d = state.outputDir!!
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            if (d.startsWith("content://")) "已选：${safeDirLabel(d)}（SAF 目录）" else "已选：$d",
                            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────
        // ④ 事件分类文件夹命名
        // ─────────────────────────────────────────────────
        SettingCard(icon = Icons.Filled.Folder, title = "事件分类文件夹命名") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                NamingRadio(
                    selected = state.namingTemplate == 0,
                    onClick = { vm.setNamingTemplate(0) },
                    title = "事件类型",
                    example = "例：交通事故处置",
                )
                NamingRadio(
                    selected = state.namingTemplate == 1,
                    onClick = { vm.setNamingTemplate(1) },
                    title = "日期 + 巡查日志内容（最多50字）",
                    example = "例：20260908_北行K123+000M路面发现抛洒物现场处置",
                )
            }
        }

        // ── 底部说明 ──
        Text(
            "有bug联系QQ：289883382",
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }

    // ── 日期选择弹窗 ──
    if (showDatePicker) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = state.date
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        Dialog(
            onDismissRequest = { showDatePicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.widthIn(max = 340.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    DatePicker(
                        picker,
                        modifier = Modifier.weight(1f, fill = false),
                        showModeToggle = true,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showDatePicker = false }) { Text("取消") }
                        TextButton(onClick = {
                            picker.selectedDateMillis?.let { ms ->
                                vm.setDate(
                                    Instant.ofEpochMilli(ms)
                                        .atZone(ZoneId.systemDefault()).toLocalDate()
                                )
                            }
                            showDatePicker = false
                        }) { Text("确定") }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 小组件：带图标的设置分组卡片
// ──────────────────────────────────────────────────────────────────
@Composable
private fun SettingCard(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(icon, null, Modifier.padding(8.dp).size(18.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Text(
                    "  $title",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                )
            }
            content()
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 命名 Radio：整行点击 48dp 高度 + 示例预览
// ──────────────────────────────────────────────────────────────────
@Composable
private fun NamingRadio(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    example: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                example,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** SAF tree URI → 可读目录名 */
private fun safeDirLabel(uri: String): String = runCatching {
    val u = Uri.parse(uri)
    val seg = u.lastPathSegment ?: return@runCatching uri
    val dec = java.net.URLDecoder.decode(seg, "UTF-8")
    dec.substringAfter(':').trim('/').ifBlank { uri }
}.getOrElse { uri }
