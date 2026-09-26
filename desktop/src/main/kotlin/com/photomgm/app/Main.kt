// desktop/.../Main.kt —— PC 桌面版入口：单窗口双栏工作台
package com.photomgm.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.photomgm.app.theme.PhotoMgmTheme
import com.photomgm.app.ui.DropFileHandler
import com.photomgm.app.ui.Workbench

/** 默认窗口尺寸（1200×800 适中窗口，方案已确认）。 */
private val DEFAULT_W = 1200
private val DEFAULT_H = 800

@Composable
fun rememberAppViewModel(): AppViewModel = remember { AppViewModel() }

/** 挂载系统文件拖放：文件夹→源目录，.txt/.log→日志（P0-1）。 */
@Suppress("FunctionName")
@Composable
private fun WindowScope.installDrop(vm: AppViewModel) {
    LaunchedEffect(Unit) {
        DropFileHandler(
            target = window,
            onFolder = { vm.importSourceDir(it) },
            onLogFile = { vm.importLogFile(it) },
        )
    }
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "高速公路巡查照片分类",
        state = rememberWindowState(width = DEFAULT_W.dp, height = DEFAULT_H.dp),
    ) {
        PhotoMgmTheme {
            val vm = rememberAppViewModel()
            installDrop(vm)
            Workbench(vm)
        }
    }
}
