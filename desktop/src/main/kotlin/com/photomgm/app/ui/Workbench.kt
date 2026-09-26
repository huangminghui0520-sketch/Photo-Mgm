// desktop/.../ui/Workbench.kt —— PC 双栏工作台主界面
// 布局：全局工具栏（日期/日志导入/生成分类/导出/ZIP/设置）+ 日志左栏 + 照片右栏 + 状态栏 + 设置抽屉
package com.photomgm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGestures
import com.photomgm.app.AppViewModel
import com.photomgm.app.UiState
import java.io.File

@Composable
fun Workbench(vm: AppViewModel) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // 左栏宽度（可拖，默认 340，范围 280..480）
    var leftWidth by rememberSaveable { mutableFloatStateOf(340f) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // ★ HCI：副作用统一反馈 → Snackbar（导出完成 / 移动成功 / 错误）
    state.message?.let { msg ->
        LaunchedEffect(msg) {
            snackbar.showSnackbar(msg)
            vm.clearMessage()
        }
    }
    state.exportMessage?.let { msg ->
        LaunchedEffect(msg) {
            snackbar.showSnackbar(msg)
            vm.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { StatusBar(state, onOpenFolder = vm::openExportedFolder) },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner)
                .onPreviewKeyEvent { ke ->
                    // P0-3 键盘流：←/→ 切换巡查日期（需无修饰键、且非文本输入场景）
                    if (ke.type == KeyEventType.KeyUp) return@onPreviewKeyEvent false
                    if (ke.isAltPressed || ke.isCtrlPressed || ke.isMetaPressed) return@onPreviewKeyEvent false
                    when (ke.key) {
                        Key.DirectionLeft -> { vm.setDate(state.date.minusDays(1)); true }
                        Key.DirectionRight -> { vm.setDate(state.date.plusDays(1)); true }
                        else -> false
                    }
                },
        ) {
            // 全局工具栏
            WorkbenchToolbar(
                state = state,
                vm = vm,
                onPrevDay = { vm.setDate(state.date.minusDays(1)) },
                onNextDay = { vm.setDate(state.date.plusDays(1)) },
                onToday = { vm.setDate(java.time.LocalDate.now()) },
                onOpenSettings = { settingsOpen = true },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 双栏：日志左栏 + 照片右栏（分栏可拖）
            Row(Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier.width(leftWidth.dp).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    LogPanel(vm, state)
                }
                // 分栏拖拽手柄：拖动调整左栏宽度
                Box(
                    Modifier
                        .width(5.dp)
                        .fillMaxHeight()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                leftWidth = (leftWidth + dragAmount.x).coerceIn(280f, 480f)
                            }
                        },
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    PhotoPanel(vm, state)
                }
            }
        }
    }

    // 设置抽屉
    if (settingsOpen) {
        SettingsDrawer(
            state = state,
            onDismiss = { settingsOpen = false },
            onAddDir = { vm.addSourceDir(it) },
            onRemoveDir = { vm.removeSourceDir(it) },
            onSetOutput = { vm.setOutputDir(it) },
            onSetTemplate = { vm.setNamingTemplate(it) },
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// 状态栏：实时统计
// ──────────────────────────────────────────────────────────────────
@Composable
private fun StatusBar(state: UiState, onOpenFolder: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val logN = state.parsed?.events?.size ?: 0
            val pendingK = state.classify?.unmatched?.size ?: 0
            StatusText("日志 ${logN} 条")
            StatusText("照片 ${state.photos.size} 张")
            StatusText("已分类 ${state.classify?.eventPhotoMap?.values?.sumOf { it.size } ?: 0} 张")
            StatusText("未匹配 ${pendingK} 张")
            state.exportProgress?.let {
                Text("导出中 ${(it * 100).toInt()}%", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall)
            }
            if (state.lastExport != null) {
                androidx.compose.material3.TextButton(
                    onClick = onOpenFolder,
                    contentPadding = androidx.compose.material3.ButtonDefaults.TextButtonContentPadding,
                ) { Text("打开导出文件夹", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun StatusText(t: String) {
    Text(t, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
