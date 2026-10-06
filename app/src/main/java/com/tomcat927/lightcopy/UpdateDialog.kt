package com.tomcat927.lightcopy

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun UpdateDialog(vm: UpdateViewModel) {
    val ctx = LocalContext.current
    when (val s = vm.state.collectAsState().value) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { vm.dismiss() },
            title = { Text("发现新版本") },
            text = {
                Column {
                    Text(s.info.versionDisplay, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "当前版本：v${vm.currentVersionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    val notes = s.info.notes
                    if (!notes.isNullOrBlank()) {
                        Text(
                            notes,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    Text(
                        "下载完成后将校验 SHA-256 并启动系统安装器。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.download(s.info) }) { Text("立即更新") }
            },
            dismissButton = { TextButton(onClick = { vm.dismiss() }) { Text("稍后") } }
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("正在下载更新") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { s.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${s.percent}%  ${fmtMB(s.received)} / ${fmtMB(s.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {}
        )

        is UpdateState.Ready -> AlertDialog(
            onDismissRequest = {},
            title = { Text("需要安装权限") },
            text = {
                Text(
                    "${s.info.versionDisplay} 已下载并通过校验，但尚未允许本应用「安装未知应用」。\n\n点击「去授权」开启允许后返回，再点「安装」即可（仅需一次，之后更新会直接调起安装器）。",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (vm.install(ctx, s.apk)) vm.dismiss()
                    else Toast.makeText(ctx, "请开启「允许安装未知应用」后返回重试", Toast.LENGTH_LONG).show()
                }) { Text("去授权") }
            },
            dismissButton = { TextButton(onClick = { vm.dismiss() }) { Text("稍后") } }
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { vm.dismiss() },
            title = { Text("更新失败") },
            text = { Text(s.message, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { vm.checkNow() }) { Text("重试") }
            },
            dismissButton = { TextButton(onClick = { vm.dismiss() }) { Text("关闭") } }
        )

        // 主页手动检查时无新版本：提示一下即复位
        is UpdateState.UpToDate -> LaunchedEffect(Unit) {
            Toast.makeText(ctx, "已是最新版本", Toast.LENGTH_SHORT).show()
            vm.dismiss()
        }

        UpdateState.Idle, UpdateState.Checking -> Unit
    }
}

private fun fmtMB(bytes: Long): String = when {
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
