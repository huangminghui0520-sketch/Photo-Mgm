// app/ui/PreviewScreen.kt —— 预览页（人机交互重构版）
package com.photomgm.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.aspectRatio
import coil.compose.AsyncImage
import algorithm.model.Photo
import algorithm.model.LogEvent
import com.photomgm.app.AppViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

// ──────────────────────────────────────────────────────────────────
// 自定义 Saver：Set<Long> / Pair<*,*> → 可保存 Bundle 的基础类型
// ──────────────────────────────────────────────────────────────────
private val LongSetSaver: Saver<Set<Long>, List<Long>> = Saver(
    save = { it.toList() },
    restore = { it.toSet() },
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(vm: AppViewModel) {
    val state by vm.state.collectAsState()
    val classify = state.classify
    val parsed   = state.parsed

    // ★ HCI：rememberSaveable —— 屏幕旋转时保持 UI 交互状态
    var selected by rememberSaveable(stateSaver = LongSetSaver) { mutableStateOf(emptySet()) }
    var moveTarget by rememberSaveable(stateSaver = LongSetSaver) { mutableStateOf(emptySet()) }
    var moveDialogOpen by rememberSaveable { mutableStateOf(false) }
    // ★ 单事件照片弹窗：当前打开的事件 id（null=未打开）
    var openSheetEventId by rememberSaveable { mutableStateOf<Int?>(null) }
    // ★ 未匹配照片弹窗：true=打开（交互与事件弹窗一致）
    var openUnmatchedSheet by rememberSaveable { mutableStateOf(false) }
    // ★ 事件搜索：预览页解析日志后按 编号/事件/地点/描述 过滤事件卡片
    var eventQuery by rememberSaveable { mutableStateOf("") }

    fun toggleSelect(pid: Long) {
        selected = if (pid in selected) selected - pid else selected + pid
    }

    // ── 前置引导：日志 / 照片 / 分类 尚未就绪 ──
    if (classify == null || parsed == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).imePadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("尚未完成分类",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    val steps = buildList {
                        if (parsed == null) add(1 to "请先在「日志」页解析日志（未识别行会标红提示）")
                        if (state.photos.isEmpty()) add(2 to "请先在「设置」页选择日期 / 源目录（自动粗筛并精读照片）")
                    }
                    steps.forEach { (n, s) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            ) {
                                Text("$n",
                                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ))
                            }
                            Text("  $s",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    if (parsed != null && state.photos.isNotEmpty()) {
                        Text("资料已就绪，点击下方按钮生成分类：",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Button(
                            onClick = { vm.runClassify() },
                            Modifier.fillMaxWidth(),
                            enabled = !state.busy,
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) {
                            Text("生成分类（GPS 分组 + 事件归类）")
                        }
                    }
                }
            }
        }
        return
    }

    val effective    = remember(classify, state.overrideMap) { vm.effectiveMap(classify) }
    val effectiveUnmatched = remember(classify, state.overrideMap) { vm.effectiveUnmatched(classify) }
    val photosById   = remember(state.photos) { state.photos.associateBy { it.id } }
    val pendingEvents = parsed.events.filter { it.id in classify.pendingEventIds }
    val normalEvents  = parsed.events.filter { it.id !in classify.pendingEventIds }
    // ★ 事件搜索过滤（编号 / 事件类型 / 地点（桩号·收费站）/ 描述，忽略大小写）
    val filteredEvents = remember(eventQuery, normalEvents) {
        val q = eventQuery.trim()
        if (q.isEmpty()) normalEvents
        else normalEvents.filter { e ->
            e.eventType.contains(q, ignoreCase = true) ||
            e.id.toString() == q ||
            e.location?.contains(q, ignoreCase = true) == true ||
            e.description?.contains(q, ignoreCase = true) == true
        }
    }

    /**
     * ★ 打开移动对话框前的校验（§8.3 人机交互）：
     * 多选照片必须来自同一事件（或都来自未匹配池），禁止跨事件照片混在一起移动。
     * 通过校验后打开「移动 N 张到事件」对话框。
     */
    fun requestMove(photoIds: Set<Long>) {
        if (photoIds.isEmpty()) return
        val ownerOf: (Long) -> Int? = { pid ->
            effective.entries.firstOrNull { pid in it.value }?.key
        }
        val owners = photoIds.map { ownerOf(it) }.toSet()
        if (owners.size > 1) {
            vm.notify("禁止多个事件的照片一起移动：所选照片分属 ${owners.size} 个来源，请只选择同一事件（或未匹配）的照片")
            return
        }
        moveTarget = photoIds
        moveDialogOpen = true
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // ── 固定搜索栏（不随列表滚动，解析日志后显示） ──
        if (state.parsed != null) {
            OutlinedTextField(
                value = eventQuery,
                onValueChange = { eventQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                placeholder = { Text("搜索事件", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                trailingIcon = {
                    if (eventQuery.isNotEmpty()) {
                        IconButton(onClick = { eventQuery = "" }) {
                            Icon(Icons.Filled.Close, "清空搜索", Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
            )
        }
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        // ── 标题 ──
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "预览 · 人工调整与兜底", style = MaterialTheme.typography.titleMedium)
                Text(
                    "点击事件卡片 / 未匹配入口 = 打开照片管理弹窗（3 列多选 / 批量移动 / 移除 / 台账标记）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── 已选照片聚合操作条 ──
        if (selected.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f),
                            shape = CircleShape,
                        ) {
                            Text("${selected.size}",
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ))
                        }
                        Text(
                            "张已选中",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { requestMove(selected) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onPrimary,
                                contentColor   = MaterialTheme.colorScheme.primary,
                            ),
                        ) { Text("批量移动") }
                        TextButton(
                            onClick = { selected = emptySet() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) { Text("清空") }
                    }
                }
            }
        }

        // ── GPS 干预警示 ──
        if (classify.interventions.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp))
                            Text(
                                " 需人工核对 · ${classify.interventions.size} 条干预",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        classify.interventions.forEach { iv ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable {
                                        // ★ 点击干预 → 打开首个相关事件的照片弹窗供人工核对
                                        iv.relatedEventIds.firstOrNull()?.let { openSheetEventId = it }
                                    }
                                    .padding(vertical = 4.dp, horizontal = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(iv.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                                if (iv.relatedEventIds.isNotEmpty()) {
                                    Text("点击核对相关事件 ${iv.relatedEventIds.joinToString("、") { "#$it" }}"
                                        + (if (iv.photoIds.isNotEmpty()) " · 涉及 ${iv.photoIds.size} 张照片" else ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 待定事件区 ──
        if (pendingEvents.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("待定事件区（无时间，照片不自动归入，可手动移入）",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                        pendingEvents.forEach { e ->
                            val pids = effective[e.id] ?: emptyList()
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("• ${e.eventType}｜${e.description}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                                if (pids.isNotEmpty()) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        pids.mapNotNull { photosById[it] }.forEach { p ->
                                            ThumbCell(
                                                p = p,
                                                selected = p.id in selected,
                                                onToggle = { toggleSelect(p.id) },
                                                onClick = { requestMove(setOf(p.id)) },
                                                onLongClick = { requestMove(setOf(p.id)) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 未匹配池（算法未匹配 + 被人工「从事件移除」的照片） ──
        // ★ 与事件一致：入口卡片 → 点击打开独立照片管理弹窗（3 列多选 / 批量移动）
        if (effectiveUnmatched.isNotEmpty()) {
            item {
                Card(
                    onClick = { openUnmatchedSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("未匹配照片（${effectiveUnmatched.size}）",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                modifier = Modifier.weight(1f))
                            Text(
                                "点击进入管理 ›",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            "3 列浏览 / 点击多选 / 批量移动到对应事件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 搜索栏已移至页面顶部固定（见 Column 开头）
        // 搜索结果计数（有筛选时提示）
        if (eventQuery.isNotBlank()) {
            item {
                Text("匹配 ${filteredEvents.size} / ${normalEvents.size} 个事件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (filteredEvents.isEmpty()) {
            item {
                Text("无匹配事件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp))
            }
        }

        // ── 事件卡片列表（点击卡片打开该事件照片管理弹窗） ──
        items(filteredEvents, key = { it.id }) { e ->
            val photoIds = effective[e.id] ?: emptyList()
            val marked = state.marked[e.id]
            EventCard(
                eventId = e.id,
                title = e.eventType,
                time = fmtRange(e.startTimeMs, e.endTimeMs),
                location = e.location,
                description = e.description,
                photoCount = photoIds.size,
                marked = marked,
                onOpenSheet = { openSheetEventId = e.id },
            )
        }

        // ── 导出按钮组 ──
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 导出压缩包 = 主操作（含照片分类 + 台账 Excel）
                Button(
                    onClick = { vm.exportZip() },
                    Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor   = MaterialTheme.colorScheme.onTertiary,
                    ),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) { Text("导出压缩包（照片+台账）") }
                // 导出进度
                state.exportProgress?.let { p ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        LinearProgressIndicator(
                            progress = { p }, Modifier.fillMaxWidth().height(6.dp),
                        )
                        Text(
                            "导出中 ${(p * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.exportMessage?.let { msg ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("导出完成 ✓",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.weight(1f))
                                // ★ 打开最近导出的台账文件（导出Excel 2.0 方案 §4.4）
                                if (state.lastExport != null) {
                                    FilledTonalButton(
                                        onClick = { vm.openExportedFile() },
                                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                                    ) { Text("打开") }
                                }
                            }
                            Text(msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
                state.ledger?.let { rows ->
                    Text("台账已生成 ${rows.size} 行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    }

    // ── 移动对话框（单张 / 多选统一：moveTarget = 待移动照片集合，已过跨事件校验） ──
    if (moveDialogOpen) {
        MoveTargetDialog(
            title = "移动 ${moveTarget.size} 张照片到事件",
            events = parsed.events,
            onConfirm = { target ->
                vm.movePhotos(moveTarget.toList(), target)
                moveDialogOpen = false
            },
            onDismiss = { moveDialogOpen = false },
        )
    }

    // ── 单事件照片弹窗（Dialog：置顶操作栏 + 3 列网格 + 多选 + 批量操作） ──
    openSheetEventId?.let { eid ->
        val ev = parsed.events.find { it.id == eid }
        if (ev != null) {
            val sheetPhotos = (effective[eid] ?: emptyList())
                .mapNotNull { photosById[it] }
                .sortedBy { it.captureTimeMs ?: Long.MAX_VALUE }
            EventPhotosSheet(
                eventTitle = ev.eventType,
                photos = sheetPhotos,
                marked = state.marked[eid],
                events = parsed.events,
                onMove = { ids, target -> vm.movePhotos(ids, target) },
                onRemoveFromEvent = { ids -> vm.removePhotosFromEvent(ids) },
                onMark = { pid, slot -> vm.markLedgerPhoto(eid, slot, pid) },
                onClearMark = { slot -> vm.markLedgerPhoto(eid, slot, null) },
                onNotify = { msg -> vm.notify(msg) },
                onDismiss = { openSheetEventId = null },
            )
        }
    }

    // ── 未匹配照片弹窗（与事件照片弹窗一致：3 列多选 / 置顶操作栏 / 批量移动） ──
    if (openUnmatchedSheet) {
        val unmatchedPhotos = effectiveUnmatched
            .mapNotNull { photosById[it] }
            .sortedBy { it.captureTimeMs ?: Long.MAX_VALUE }
        UnmatchedPhotosSheet(
            photos = unmatchedPhotos,
            events = parsed.events,
            onMove = { ids, target -> vm.movePhotos(ids, target) },
            onDismiss = { openUnmatchedSheet = false },
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// 移动目标对话框：Dialog + Surface + Column（避免 AlertDialog 嵌套滚动冲突）
// 每项目 = RadioButton + 类型/时间/地点/完整日志（自动换行）
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveTargetDialog(
    title: String,
    events: List<LogEvent>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var target by rememberSaveable { mutableStateOf(events.firstOrNull()?.id ?: -1) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(top = 18.dp)) {
                // 标题
                Text(title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                // 列表（独立滚动，不与 AlertDialog 默认容器冲突）
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    events.forEach { e ->
                        val sel = target == e.id
                        Surface(
                            onClick = { target = e.id },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = when {
                                sel -> MaterialTheme.colorScheme.primaryContainer
                                else -> Color.Transparent
                            },
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = sel,
                                    onClick = { target = e.id },
                                )
                                Column(
                                    Modifier.weight(1f).padding(end = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            e.eventType,
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = if (sel) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(6.dp),
                                        ) {
                                            Text(
                                                fmtRange(e.startTimeMs, e.endTimeMs),
                                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                        }
                                    }
                                    Text(
                                        "🗺 ${e.location}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        e.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                // 按钮行
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = { onConfirm(target) },
                        enabled = target != -1,
                    ) { Text("确认移动") }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 标记台账照片对话框：图标按钮 1 / 2
// ──────────────────────────────────────────────────────────────────
@Composable
private fun MarkLedgerDialog(
    onSlot1: () -> Unit,
    onSlot2: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标记台账照片",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold
            )) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("为该事件选择一张照片作为台账记录：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSlot1,
                        Modifier.weight(1f),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                        ) {
                            Text("1",
                                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ))
                        }
                        Text(" 设为照片 1（最早）")
                    }
                }
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSlot2,
                        Modifier.weight(1f),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiary,
                            shape = CircleShape,
                        ) {
                            Text("2",
                                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onTertiary,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ))
                        }
                        Text(" 设为照片 2（最晚）")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}


// ──────────────────────────────────────────────────────────────────
// 缩略图单元格：8dp 安全边距 + 2.5dp 主色边框 + 圆形对勾叠层 + 底部标准 Checkbox（不缩放）
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThumbCell(
    p: Photo,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        // ★ HCI：8dp 安全边距（作为整个单元格的最外层 padding，含触控区域）
        modifier = Modifier.padding(4.dp),
    ) {
        // ★ 2.5dp 主色边框 + 圆形对勾叠层徽章
        Thumb(
            p.sourceRef, p.displayName,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
        )
        // ★ Checkbox 原尺寸（不缩放！）保证 48dp 最小触控
        Row(
            Modifier.heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventCard(
    eventId: Int,
    title: String,
    time: String,
    location: String,
    description: String,
    photoCount: Int,
    marked: Pair<Long?, Long?>?,
    onOpenSheet: () -> Unit,
) {
    Card(
        onClick = onOpenSheet,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 头部：照片数量徽章 + 标题 + 时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (photoCount == 0) MaterialTheme.colorScheme.errorContainer
                           else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        "${photoCount}张",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (photoCount == 0) MaterialTheme.colorScheme.onErrorContainer
                               else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.weight(1f))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        time,
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            // 地点 · 描述
            Column(verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(start = 2.dp)) {
                Text("🗺 $location",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(description,
                    style = MaterialTheme.typography.bodySmall)
            }
            // 人工标记状态徽章
            if (marked != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        "已人工标记 · 照片1 ${marked.first?.let { "✓" } ?: "-"} · 照片2 ${marked.second?.let { "✓" } ?: "-"}",
                        Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// Thumb：缩略图主体（方形圆角 · 2.5dp 选中边框 · 圆形对勾叠层 · 槽号标记）
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumb(
    model: String,
    name: String,
    selected: Boolean = false,
    markedSlot: Int? = null,
    thumbSize: Dp = 80.dp,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            // ★ 8dp 总安全边距（padding） + 2.5dp 边框视觉效果
            .padding(4.dp)
            .size(thumbSize)
            .aspectRatio(1f)
            .then(
                if (selected) Modifier.border(
                    width = 2.5.dp,
                    color = primary,
                    shape = RoundedCornerShape(14.dp),
                )
                else Modifier
            )
            .clip(RoundedCornerShape(if (selected) 11.dp else 14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = model,
            contentDescription = name,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        // ★ 选中 → 右上角圆形对勾徽章
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(20.dp)
                    .background(primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check, null,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        // 台账槽 1 / 2 角标（左下）
        if (markedSlot != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 6.dp)
                    .size(20.dp)
                    .background(
                        color = if (markedSlot == 1) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    markedSlot.toString(),
                    color = if (markedSlot == 1) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onTertiary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 时间格式化
// ──────────────────────────────────────────────────────────────────
private val fmt = DateTimeFormatter.ofPattern("HH:mm")
private fun fmtRange(start: Long?, end: Long?): String {
    val zone = ZoneId.systemDefault()
    val s = start?.let { Instant.ofEpochMilli(it).atZone(zone).format(fmt) } ?: return "无时间"
    val e = end ?: return s
    if (e == start) return s
    return "$s～${Instant.ofEpochMilli(e).atZone(zone).format(fmt)}"
}

// ──────────────────────────────────────────────────────────────────
// 单事件照片管理弹窗：Dialog（居中、高度自适应：少时包裹、多时 ≤80% 屏高）
// + 置顶固定操作栏（已选 N / 批量移动 / 移除 / 台账标记 / 清空 / 关闭）
// + 3 列网格（点击多选）+ 移动目标弹窗（带搜索）+ 移除二次确认 + 台账槽位
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventPhotosSheet(
    eventTitle: String,
    photos: List<Photo>,          // 已按拍摄时间升序
    marked: Pair<Long?, Long?>?,
    events: List<LogEvent>,
    onMove: (List<Long>, Int) -> Unit,
    onRemoveFromEvent: (List<Long>) -> Unit,
    onMark: (Long, Int) -> Unit,
    onClearMark: (Int) -> Unit,
    onNotify: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable(stateSaver = LongSetSaver) { mutableStateOf(emptySet()) }
    var moveDialogOpen by rememberSaveable { mutableStateOf(false) }
    var markTarget by remember { mutableStateOf<Long?>(null) }
    var confirmRemoveOpen by rememberSaveable { mutableStateOf(false) }
    val screenMaxH = (LocalConfiguration.current.screenHeightDp * 0.8f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            // ★ 高度自适应：内容少时包裹居中；多时封顶 80% 屏高，网格自行滚动
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .padding(horizontal = 16.dp)
                .heightIn(max = screenMaxH)
                .imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(vertical = 14.dp)) {
                // 标题行：事件名 · N 张 | 已选 N | 清空 | 关闭
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$eventTitle · ${photos.size} 张",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.weight(1f))
                    Text("已选 ${selected.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { selected = emptySet() },
                        enabled = selected.isNotEmpty(),
                    ) { Text("清空") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                // 置顶操作栏（固定，不随网格滚动）
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { if (selected.isNotEmpty()) moveDialogOpen = true },
                        enabled = selected.isNotEmpty(),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) { Text("批量移动") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { if (selected.isNotEmpty()) confirmRemoveOpen = true },
                        enabled = selected.isNotEmpty(),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) { Text("移除", color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = {
                            if (selected.size == 1) markTarget = selected.first()
                            else onNotify("台账标记请仅选择一张照片")
                        },
                        enabled = selected.size == 1,
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) { Text("台账标记") }
                }
                // 3 列网格：weight(1f) 占满操作栏之外剩余高度，超出由网格自行滚动
                if (photos.isEmpty()) {
                    Text("该事件暂无照片",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        gridItems(photos, key = { it.id }) { p ->
                            val isSel = p.id in selected
                            val slot = when {
                                marked?.first == p.id -> 1
                                marked?.second == p.id -> 2
                                else -> null
                            }
                            PhotoTile(
                                p = p,
                                selected = isSel,
                                markedSlot = slot,
                                onToggle = {
                                    selected = if (isSel) selected - p.id else selected + p.id
                                },
                            )
                        }
                    }
                }
                // 底部说明 + 台账恢复默认
                Text(
                    "点击照片 = 选中/取消（多选）· 移除仅回到未匹配池，不删源文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
                if (marked != null) {
                    Row(Modifier.padding(horizontal = 20.dp)) {
                        TextButton(onClick = { onClearMark(1) }) { Text("照片1恢复默认") }
                        TextButton(onClick = { onClearMark(2) }) { Text("照片2恢复默认") }
                    }
                }
            }
        }
    }

    // 移动目标弹窗（带搜索）：点击事件项即完成移动
    if (moveDialogOpen) {
        MoveTargetDialogWithSearch(
            title = "移动 ${selected.size} 张照片到事件",
            events = events,
            onConfirm = { target ->
                onMove(selected.toList(), target)
                selected = emptySet()
                moveDialogOpen = false
            },
            onDismiss = { moveDialogOpen = false },
        )
    }
    // 台账照片标记（单选）
    markTarget?.let { pid ->
        MarkLedgerDialog(
            onSlot1 = { onMark(pid, 1); markTarget = null },
            onSlot2 = { onMark(pid, 2); markTarget = null },
            onDismiss = { markTarget = null },
        )
    }
    // 移除确认（仅从当前事件移除 → 未匹配池）
    if (confirmRemoveOpen) {
        AlertDialog(
            onDismissRequest = { confirmRemoveOpen = false },
            title = { Text("从当前事件移除") },
            text = { Text("将把选中的 ${selected.size} 张照片移出「$eventTitle」，回到未匹配照片池。\n不会删除源文件。") },
            confirmButton = {
                TextButton({
                    onRemoveFromEvent(selected.toList())
                    selected = emptySet()
                    confirmRemoveOpen = false
                }) { Text("移除") }
            },
            dismissButton = { TextButton({ confirmRemoveOpen = false }) { Text("取消") } },
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// 目标事件选择弹窗（批量移动）：Dialog + 置顶搜索框 + 事件列表（点击即移动）
// 搜索字段：事件类型 / 编号 / 地点（桩号·收费站）/ 描述，300ms 防抖
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveTargetDialogWithSearch(
    title: String,
    events: List<LogEvent>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query != debouncedQuery) {
            delay(300)
            debouncedQuery = query
        }
    }
    val filtered = remember(debouncedQuery, events) {
        val q = debouncedQuery.trim()
        if (q.isEmpty()) events
        else events.filter { e ->
            e.eventType.contains(q, ignoreCase = true) ||
            e.id.toString() == q ||
            e.location?.contains(q, ignoreCase = true) == true ||
            e.description?.contains(q, ignoreCase = true) == true
        }
    }
    val screenMaxH = (LocalConfiguration.current.screenHeightDp * 0.8f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .padding(horizontal = 16.dp)
                .heightIn(max = screenMaxH)
                .imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(vertical = 14.dp)) {
                Text(title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(horizontal = 20.dp))
                // 搜索框（固定，不随列表滚动）
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("搜索：事件名称 / 编号 / 桩号·收费站 / 描述") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, "清空搜索")
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
                // 事件列表（weight(1f)，超出滚动；点击项即移动）
                if (filtered.isEmpty()) {
                    Text("无匹配事件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filtered, key = { it.id }) { e ->
                            Surface(
                                onClick = { onConfirm(e.id) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Column(Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(e.eventType,
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            modifier = Modifier.weight(1f))
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            shape = RoundedCornerShape(6.dp),
                                        ) {
                                            Text(
                                                fmtRange(e.startTimeMs, e.endTimeMs),
                                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            )
                                        }
                                    }
                                    Text("编号 ${e.id} · 🗺 ${e.location}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(e.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                // 底部取消
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 弹窗网格单元格：方形圆角 · 选中=主色边框+半透明遮罩+对勾 · 台账槽号角标
// ──────────────────────────────────────────────────────────────────
@Composable
private fun PhotoTile(
    p: Photo,
    selected: Boolean,
    markedSlot: Int?,
    onToggle: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (selected) Modifier.border(
                    width = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(10.dp),
                )
                else Modifier
            )
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onToggle),
    ) {
        AsyncImage(
            model = p.sourceRef,
            contentDescription = p.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Icon(
                Icons.Filled.Check, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp),
            )
        }
        if (markedSlot != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 6.dp)
                    .size(20.dp)
                    .background(
                        color = if (markedSlot == 1) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    markedSlot.toString(),
                    color = if (markedSlot == 1) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onTertiary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 未匹配照片管理弹窗：与事件弹窗交互一致（Dialog 居中 / 高度自适应 / 置顶操作栏
// + 3 列网格点击多选 + 批量移动）。无事件上下文，故不含台账标记与移除。
// ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnmatchedPhotosSheet(
    photos: List<Photo>,          // 已按拍摄时间升序
    events: List<LogEvent>,
    onMove: (List<Long>, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable(stateSaver = LongSetSaver) { mutableStateOf(emptySet()) }
    var moveDialogOpen by rememberSaveable { mutableStateOf(false) }
    val screenMaxH = (LocalConfiguration.current.screenHeightDp * 0.8f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .padding(horizontal = 16.dp)
                .heightIn(max = screenMaxH)
                .imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(vertical = 14.dp)) {
                // 标题行：未匹配照片 · N 张 | 已选 N | 清空 | 关闭
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("未匹配照片 · ${photos.size} 张",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.weight(1f))
                    Text("已选 ${selected.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { selected = emptySet() },
                        enabled = selected.isNotEmpty(),
                    ) { Text("清空") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                // 置顶操作栏（固定，不随网格滚动）
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { if (selected.isNotEmpty()) moveDialogOpen = true },
                        enabled = selected.isNotEmpty(),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) { Text("批量移动") }
                    Spacer(Modifier.width(10.dp))
                    Text("点击照片 = 选中/取消（多选）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 3 列网格：weight(1f) 占满剩余高度，超出自行滚动
                if (photos.isEmpty()) {
                    Text("暂无未匹配照片",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        gridItems(photos, key = { it.id }) { p ->
                            val isSel = p.id in selected
                            PhotoTile(
                                p = p,
                                selected = isSel,
                                markedSlot = null,
                                onToggle = {
                                    selected = if (isSel) selected - p.id else selected + p.id
                                },
                            )
                        }
                    }
                }
                // 底部说明
                Text(
                    "批量移动后照片归入对应事件；不删除源文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }
        }
    }

    // 移动目标弹窗（带搜索）：点击事件项即完成移动
    if (moveDialogOpen) {
        MoveTargetDialogWithSearch(
            title = "移动 ${selected.size} 张照片到事件",
            events = events,
            onConfirm = { target ->
                onMove(selected.toList(), target)
                selected = emptySet()
                moveDialogOpen = false
            },
            onDismiss = { moveDialogOpen = false },
        )
    }
}
