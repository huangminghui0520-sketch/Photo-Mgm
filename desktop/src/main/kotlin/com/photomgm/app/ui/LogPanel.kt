// desktop/.../ui/LogPanel.kt —— 日志左栏
// 事件列表（可搜/折叠）+ 待定/未识别提示 + 干预提示 + 拖放导入日志
package com.photomgm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photomgm.app.AppViewModel
import com.photomgm.app.UiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LogPanel(vm: AppViewModel, state: UiState) {
    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 标题
        Text("日志解析", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))

        // 日志输入框（简洁版，可展开编辑）
        var editing by rememberSaveable { mutableStateOf(false) }
        if (editing) {
            OutlinedTextField(
                value = state.logsText,
                onValueChange = { vm.setLogsText(it) },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                label = { Text("日志内容（每行一条）") },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { vm.parseLogs() }, enabled = !state.busy && state.logsText.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("解析") }
                androidx.compose.material3.TextButton(onClick = { vm.clearLogs() }) { Text("清空") }
                androidx.compose.material3.TextButton(onClick = { editing = false }) { Text("完成") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { editing = true },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("编辑日志") }
                androidx.compose.material3.OutlinedButton(
                    onClick = { vm.parseLogs() },
                    enabled = !state.busy && state.logsText.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("解析") }
            }
            Text(
                if (state.logsText.isBlank()) "未导入日志（可用工具栏「日志导入」或拖入 .txt 文件）"
                else "已录入 ${state.logsText.lineSequence().count { it.isNotBlank() }} 行",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 解析结果统计
        val p = state.parsed
        if (p != null) {
            val hasSkip = p.skippedLines.isNotEmpty()
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (hasSkip) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "解析出 ${p.events.size} 条事件" + (if (hasSkip) " · 未识别 ${p.skippedLines.size} 行" else ""),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    p.inspectors?.let { Text("巡查人员：$it", style = MaterialTheme.typography.labelSmall) }
                    p.recorder?.let { Text("记录人：$it", style = MaterialTheme.typography.labelSmall) }
                    p.vehicle?.let { Text("车牌：$it", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }

        // 干预提示
        state.classify?.interventions?.takeIf { it.isNotEmpty() }?.let { ivs ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, Modifier.width(18.dp).height(18.dp),
                            tint = MaterialTheme.colorScheme.error)
                        Text(" 需人工核对 ${ivs.size} 条", style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.error)
                    }
                    ivs.forEach { iv ->
                        Text(iv.message, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // 事件列表
        if (state.parsed != null && state.parsed.events.isNotEmpty()) {
            Text("事件列表（点击右侧查看照片）", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.parsed.events, key = { it.id }) { e ->
                    val photoCount = state.classify?.let { c -> vm.effectiveMap(c)[e.id]?.size ?: 0 } ?: 0
                    EventRow(
                        id = e.id,
                        type = e.eventType,
                        time = hmRange(e.startTimeMs, e.endTimeMs),
                        loc = e.location,
                        desc = e.description,
                        photoCount = photoCount,
                    )
                }
            }
        }
    }
}

@Composable
private fun EventRow(id: Int, type: String, time: String, loc: String, desc: String, photoCount: Int) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(6.dp)) {
                    Text(id.toString().padStart(2, '0'),
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(6.dp))
                Text(type, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$photoCount 张", style = MaterialTheme.typography.labelSmall,
                    color = if (photoCount == 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(time, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("🗺 $loc", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(desc, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val tf = DateTimeFormatter.ofPattern("HH:mm")
private fun hmRange(start: Long?, end: Long?): String {
    val zone = ZoneId.systemDefault()
    val s = start?.let { Instant.ofEpochMilli(it).atZone(zone).format(tf) } ?: return "无时间"
    val e = end ?: return s
    if (e == start) return s
    return "$s～${Instant.ofEpochMilli(e).atZone(zone).format(tf)}"
}
