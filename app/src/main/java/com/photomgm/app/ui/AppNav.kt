// app/ui/AppNav.kt —— 三段式导航 + 4 步进度条（人机交互重构版）
package com.photomgm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.photomgm.app.AppViewModel
import com.photomgm.app.UiState

@Composable
fun AppNav(vm: AppViewModel, tab: String, modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val state by vm.state.collectAsState()

    LaunchedEffect(tab) {
        if (nav.currentDestination?.route != tab) {
            nav.navigate(tab) { launchSingleTop = true }
        }
        // ★ 被动触发：切到日志页时，若日期+源目录就绪且尚未粗筛，补一次 粗筛→精读→分类（不重复，见 ensurePipeline）
        if (tab == "logs") vm.ensurePipeline()
    }

    Column(modifier) {
        NavHost(
            navController = nav,
            startDestination = "settings",
            modifier = Modifier.weight(1f),
        ) {
            composable("settings") { SettingsScreen(vm) }
            composable("logs")     { LogScreen(vm) }
            composable("preview")  { PreviewScreen(vm) }
        }

        ProgressStrip(state)
    }
}

// ──────────────────────────────────────────────────────────────────
// 4 步可视化进度条 · 图标 + 对勾 + 连接线状态
// ──────────────────────────────────────────────────────────────────
@Composable
private fun ProgressStrip(state: UiState) {
    val step1Done = state.sourceDirs.isNotEmpty()
    val step2Done = state.parsed?.events?.isNotEmpty() == true
    val logN = state.parsed?.events?.size ?: 0
    val photoM = state.photos.size
    val pendingK = state.classify?.unmatched?.size ?: 0
    val step3Done = state.classify != null
    val step4Done = state.ledger != null

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StepChip(0, "设置",  Icons.Filled.Settings, step1Done, step1Done, active = true)
                Connector(step1Done)
                StepChip(1, "日志$logN", Icons.Filled.Description, step2Done, step1Done, active = true)
                Connector(step2Done)
                StepChip(2, "照片${photoM}待$pendingK", Icons.Filled.PhotoLibrary, step3Done, step2Done, active = true)
                Connector(step3Done)
                StepChip(3, "导出", Icons.Filled.Upload, step4Done, step3Done, active = true)
            }
            if (state.busy) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
            } else {
                HorizontalDivider(color = Color.Transparent, thickness = 2.dp)
            }
        }
    }
}

@Composable
private fun StepChip(
    idx: Int, label: String, icon: ImageVector, done: Boolean, enabled: Boolean, active: Boolean,
) {
    val bg = when {
        done  -> MaterialTheme.colorScheme.primary
        enabled && active -> MaterialTheme.colorScheme.secondaryContainer
        else  -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val fg = when {
        done  -> MaterialTheme.colorScheme.onPrimary
        enabled && active -> MaterialTheme.colorScheme.onSecondaryContainer
        else  -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(30.dp)
                .background(bg, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (done) Icons.Filled.Check else icon,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = fg,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (done) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 11.sp,
            ),
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Connector(highlight: Boolean) {
    Box(
        Modifier
            .height(2.dp)
            .width(22.dp)
            .background(
                if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(1.dp),
            )
    )
}

// ──────────────────────────────────────────────────────────────────
// 底部三段式导航 · 48dp 最小触控 + 选中药丸 + 手势栏避让
// ──────────────────────────────────────────────────────────────────
@Composable
fun AppBottomBar(tab: String, onSelect: (String) -> Unit) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // ★ HCI：navigationBarsPadding 防止 Android 10+ 手势导航栏遮挡按钮
        Column(Modifier.navigationBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TabItem(
                    route = "settings", label = "设置",
                    iconSel = Icons.Filled.Settings,      icon = Icons.Outlined.Settings,
                    current = tab, onClick = { onSelect("settings") },
                )
                TabItem(
                    route = "logs", label = "日志",
                    iconSel = Icons.Filled.Description,   icon = Icons.Outlined.Description,
                    current = tab, onClick = { onSelect("logs") },
                )
                TabItem(
                    route = "preview", label = "预览",
                    iconSel = Icons.Filled.PhotoLibrary,  icon = Icons.Outlined.PhotoLibrary,
                    current = tab, onClick = { onSelect("preview") },
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    route: String, label: String,
    iconSel: ImageVector, icon: ImageVector,
    current: String, onClick: () -> Unit,
) {
    val selected = current == route
    Box(
        Modifier
            // ★ HCI：WCAG 最小 48×48dp 触控目标
            .height(64.dp)
            .width(96.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box {
                Icon(
                    imageVector = if (selected) iconSel else icon,
                    contentDescription = label,
                    modifier = Modifier.size(24.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary
                           else LocalContentColor.current,
                )
                // ★ 选中药丸指示条
                if (selected) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = 6.dp)
                            .width(18.dp)
                            .height(3.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(2.dp),
                            )
                    )
                }
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (selected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
