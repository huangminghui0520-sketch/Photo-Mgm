// desktop/.../ui/PhotoPanel.kt —— 照片右栏
// 事件下拉选择 → 该事件照片网格（自适应列）；多选 + 右键菜单 + 双击大图 + 台账标记
package com.photomgm.app.ui

import algorithm.model.LogEvent
import algorithm.model.Photo
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.onClick
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.photomgm.app.AppViewModel
import com.photomgm.app.UiState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoPanel(vm: AppViewModel, state: UiState) {
    val c = state.classify
    val events = state.parsed?.events ?: emptyList()

    var selectedEvent by rememberSaveable { mutableStateOf<Int?>(null) }
    val finalMap = remember(c, state.overrideMap) { c?.let { vm.effectiveMap(it) } }
    val finalUnmatched = remember(c, state.overrideMap) { c?.let { vm.effectiveUnmatched(it) } }

    var selectedPhotos by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    var viewerRef by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("照片工作区", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.weight(1f))
            Text("共 ${state.photos.size} 张", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (c == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.photos.isEmpty())
                        "请先在「设置 ⚙」添加照片源目录并确认巡查日期\n再导入日志、点击「生成分类」"
                    else "已粗筛 ${state.photos.size} 张照片\n点击工具栏「生成分类」以查看事件照片",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return
        }

        // 事件下拉选择
        var menuOpen by remember { mutableStateOf(false) }
        val unmatchedSize = finalUnmatched?.size ?: 0
        val currentLabel = when {
            selectedEvent == null && unmatchedSize > 0 -> "未匹配照片（$unmatchedSize）"
            selectedEvent == null -> events.firstOrNull()?.eventType ?: "选择事件"
            else -> events.find { it.id == selectedEvent }?.eventType ?: "选择事件"
        }
        Surface(
            onClick = { menuOpen = true },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DriveFileMove, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text("  $currentLabel",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f))
                Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                events.forEach { e ->
                    DropdownMenuItem(
                        text = { Text("${e.id.toString().padStart(2, '0')} · ${e.eventType}（${finalMap?.get(e.id)?.size ?: 0}张）") },
                        onClick = { selectedEvent = e.id; selectedPhotos = emptySet(); menuOpen = false },
                    )
                }
                if (unmatchedSize > 0) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("未匹配照片（$unmatchedSize）") },
                        onClick = { selectedEvent = null; selectedPhotos = emptySet(); menuOpen = false },
                    )
                }
            }
        }

        // 台账标记提示
        if (selectedEvent != null) {
            val mark = state.marked[selectedEvent]
            if (mark != null && (mark.first != null || mark.second != null)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Stars, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Text("已标记：", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    mark.first?.let {
                        Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                            Text("1", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    mark.second?.let {
                        Surface(color = MaterialTheme.colorScheme.tertiary, shape = CircleShape) {
                            Text("2", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiary)
                        }
                    }
                }
            }
        }

        // 已选操作条
        if (selectedPhotos.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("已选 ${selectedPhotos.size} 张", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                var moveMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { moveMenu = true }) { Text("移动到…") }
                    DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                        events.forEach { e ->
                            DropdownMenuItem(text = { Text(e.eventType) }, onClick = {
                                vm.movePhotos(selectedPhotos.toList(), e.id)
                                vm.notify("已移动 ${selectedPhotos.size} 张到「${e.eventType}」")
                                selectedPhotos = emptySet(); moveMenu = false
                            })
                        }
                    }
                }
                OutlinedButton(onClick = {
                    vm.removePhotosFromEvent(selectedPhotos.toList())
                    vm.notify("已移除 ${selectedPhotos.size} 张（移入未匹配）")
                    selectedPhotos = emptySet()
                }) { Text("移除") }
                OutlinedButton(onClick = { selectedPhotos = emptySet() }) { Text("清空") }
            }
        }

        // 照片网格
        val currentPhotos: List<Photo> = when {
            selectedEvent == null -> {
                val um = finalUnmatched.orEmpty().toSet()
                state.photos.filter { it.id in um }
            }
            else -> {
                val ids = finalMap?.get(selectedEvent).orEmpty().toSet()
                state.photos.filter { it.id in ids }
            }
        }
        if (currentPhotos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("该事件暂无照片", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(currentPhotos, key = { it.id }) { p ->
                    PhotoTileDesktop(
                        photo = p,
                        selected = p.id in selectedPhotos,
                        markedSlot = if (selectedEvent != null) {
                            val evt = selectedEvent!!
                            val m = state.marked[evt]
                            when (p.id) { m?.first -> 1; m?.second -> 2; else -> null }
                        } else null,
                        events = events,
                        onClick = {
                            selectedPhotos = if (p.id in selectedPhotos) selectedPhotos - p.id
                            else selectedPhotos + p.id
                        },
                        onOpenBig = { viewerRef = p.sourceRef },
                        onMove = { target ->
                            vm.movePhoto(p.id, target)
                            vm.notify("已移动照片到「${events.find { it.id == target }?.eventType ?: target}」")
                            selectedPhotos = emptySet()
                        },
                        onRemove = {
                            vm.removePhotosFromEvent(listOf(p.id))
                            vm.notify("已移除照片（移入未匹配）")
                        },
                        onMark = { slot ->
                            val evt = selectedEvent
                            if (evt != null) vm.markLedgerPhoto(evt, slot, p.id)
                        },
                    )
                }
            }
        }
    }

    viewerRef?.let { ref ->
        BigImageViewer(sourceRef = ref, onDismiss = { viewerRef = null })
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun PhotoTileDesktop(
    photo: Photo,
    selected: Boolean,
    markedSlot: Int?,
    events: List<LogEvent>,
    onClick: () -> Unit,
    onOpenBig: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onMark: (Int) -> Unit,
) {
    // 右键菜单状态
    var ctxOpen by remember { mutableStateOf(false) }

    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary,
                RoundedCornerShape(10.dp)) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onClick, onDoubleClick = onOpenBig)
            .onClick(
                matcher = PointerMatcher.mouse(PointerButton.Secondary),
            ) {
                ctxOpen = true
            },
    ) {
        PhotoThumb(photo.sourceRef, photo.displayName, Modifier.fillMaxSize())
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp))
        }
        if (markedSlot != null) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 6.dp).size(20.dp)
                .background(if (markedSlot == 1) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.tertiary, CircleShape),
                contentAlignment = Alignment.Center) {
                Text(markedSlot.toString(),
                    color = if (markedSlot == 1) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onTertiary,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
            }
        }
    }

    if (ctxOpen) {
        Popup(
            alignment = Alignment.TopStart,
            onDismissRequest = { ctxOpen = false },
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 6.dp,
                modifier = Modifier.width(240.dp),
            ) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    events.forEach { e ->
                        DropdownMenuItem(
                            text = { Text("移动到「${e.id.toString().padStart(2, '0')} · ${e.eventType}」") },
                            onClick = { ctxOpen = false; onMove(e.id) },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("移入未匹配（移除）") }, onClick = { ctxOpen = false; onRemove() })
                    DropdownMenuItem(text = { Text("标记为台账照片1") }, onClick = { ctxOpen = false; onMark(1) })
                    DropdownMenuItem(text = { Text("标记为台账照片2") }, onClick = { ctxOpen = false; onMark(2) })
                    DropdownMenuItem(text = { Text("查看大图") }, onClick = { ctxOpen = false; onOpenBig() })
                }
            }
        }
    }
}
