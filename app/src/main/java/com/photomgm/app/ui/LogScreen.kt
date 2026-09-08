// app/ui/LogScreen.kt —— 日志页（人机交互重构版）
package com.photomgm.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photomgm.app.AppViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LogScreen(vm: AppViewModel) {
    val state by vm.state.collectAsState()

    // ★ HCI：rememberSaveable —— 旋转 / 分屏保持
    var pasteDialog by rememberSaveable { mutableStateOf(false) }
    var showEvents  by rememberSaveable { mutableStateOf(false) }

    val logsFocus = remember { FocusRequester() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ── 标题区 ──
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("日志解析", style = MaterialTheme.typography.headlineSmall)
            Text(
                "巡查日志，如实解析不脱敏（粘贴导入）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 入口按钮组 ──
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { pasteDialog = true },
                Modifier.fillMaxWidth(),
                enabled = !state.busy,
            ) {
                Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp))
                Text(" 粘贴导入")
            }
            Button(
                onClick = { vm.parseLogs() },
                Modifier.fillMaxWidth(),
                enabled = !state.busy && state.logsText.isNotBlank(),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                Text(" 解析日志")
            }
        }

        // ── 日志输入框：内部滚动 240dp + trailingIcon 清除按钮 ──
        OutlinedTextField(
            value = state.logsText,
            onValueChange = { vm.setLogsText(it) },
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .focusRequester(logsFocus),
            label = { Text("日志内容（每行一条；可滚动编辑）") },
            shape = MaterialTheme.shapes.medium,
            trailingIcon = if (state.logsText.isNotEmpty()) {
                {
                    IconButton(
                        onClick = {
                            // ★ 删除日志：同时清空解析/匹配结果与粗筛缓存，避免旧匹配残留
                            vm.clearLogs()
                            logsFocus.requestFocus()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .padding(end = 4.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.outline,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Clear, "清除",
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            } else null,
        )

        // ── 解析结果区 ──
        state.parsed?.let { p ->
            val hasSkip = p.skippedLines.isNotEmpty()
            // 头部汇总条
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (hasSkip) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                ),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ReceiptLong, null, Modifier.size(20.dp),
                            tint = if (hasSkip) MaterialTheme.colorScheme.tertiary
                                   else MaterialTheme.colorScheme.primary)
                        Text(
                            " 解析出 ${p.events.size} 条事件" +
                                    if (hasSkip) " · 未识别 ${p.skippedLines.size} 行" else "",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = if (hasSkip) MaterialTheme.colorScheme.onTertiaryContainer
                                   else MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    p.inspectors?.let {
                        Text("巡查人员：$it",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasSkip) MaterialTheme.colorScheme.onTertiaryContainer
                                   else MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    p.recorder?.let {
                        Text("记录人：$it",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasSkip) MaterialTheme.colorScheme.onTertiaryContainer
                                   else MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    p.vehicle?.let {
                        Text("车牌：$it",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasSkip) MaterialTheme.colorScheme.onTertiaryContainer
                                   else MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            // ★ 折叠/展开事件明细
            OutlinedButton(onClick = { showEvents = !showEvents }, Modifier.fillMaxWidth()) {
                Text(
                    if (showEvents) "收起事件明细（${p.events.size}）"
                    else "查看事件明细（${p.events.size} 条，逐条核对解析）"
                )
            }

            AnimatedVisibility(
                visible = showEvents,
                enter = expandVertically(clip = true) + fadeIn(),
                exit  = shrinkVertically(clip = true)   + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    p.events.forEachIndexed { i, e ->
                        EventListItem(
                            idx = i + 1,
                            type = e.eventType,
                            time = "${hm(e.startTimeMs)}～${hm(e.endTimeMs)}",
                            loc  = e.location,
                            desc = e.description,
                        )
                    }
                }
            }

            if (hasSkip) {
                AssistChip(
                    onClick = {},
                    leadingIcon = {
                        Icon(Icons.Filled.Error, null, Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error)
                    },
                    label = {
                        Text(
                            "未识别行 ${p.skippedLines.size}（请在上方文本中修正后重新解析）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
        }
    }

    // ── 粘贴导入弹窗 ──
    if (pasteDialog) {
        PasteLogDialog(
            onConfirm = { text ->
                if (text.isNotBlank()) {
                    vm.setLogsText((state.logsText + "\n" + text).trim())
                }
                pasteDialog = false
            },
            onDismiss = { pasteDialog = false },
        )
    }

}

// ──────────────────────────────────────────────────────────────────
// 事件列表项：序号徽章 + 类型 + 时间地点 Chips + 描述
// ──────────────────────────────────────────────────────────────────
@Composable
private fun EventListItem(
    idx: Int, type: String, time: String, loc: String, desc: String,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 序号徽章
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        idx.toString().padStart(2, '0'),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    type,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            // 时间 · 地点行
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        time,
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        "🗺 $loc",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            // 描述
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 粘贴日志对话框：多行 + 清除按钮（保留焦点）+ 取消/确定
// ──────────────────────────────────────────────────────────────────
@Composable
private fun PasteLogDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴日志文本") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .focusRequester(focus),
                minLines = 8,
                label = { Text("粘贴内容（多行，支持修改）") },
                shape = MaterialTheme.shapes.medium,
                trailingIcon = if (text.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { text = ""; focus.requestFocus() },
                            modifier = Modifier
                                .size(48.dp)
                                .padding(end = 4.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.outline,
                            ),
                        ) {
                            Icon(
                                Icons.Filled.Clear, "清除",
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                } else null,
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text("追加到日志") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

private val tf = DateTimeFormatter.ofPattern("HH:mm")
private fun hm(ms: Long?): String =
    ms?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(tf)
    } ?: "无时间"
