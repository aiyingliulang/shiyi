package com.shiyi.app.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiyi.app.diagnostics.CrashLog

/** 查看/复制/清空本地诊断日志 */
@Composable
fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(CrashLog.read(ctx)) }
    AlertDialog(
        onDismissRequest = { CrashLog.markSeen(ctx); onDismiss() },
        title = { Text("诊断日志") },
        text = {
            Column {
                Text(
                    "日志只保存在本机，不会自动上传。遇到闪退时可复制后发给开发者。",
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer {
                    Text(
                        text.ifBlank { "暂无记录" },
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                        modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                copy(ctx, text)
                CrashLog.markSeen(ctx)
            }) { Text("复制") }
        },
        dismissButton = {
            Column {
                TextButton(enabled = text.isNotBlank(), onClick = { CrashLog.clear(ctx); text = "" }) { Text("清空") }
                TextButton(onClick = { CrashLog.markSeen(ctx); onDismiss() }) { Text("关闭") }
            }
        },
    )
}

/** 启动时：上次异常退出的提示 */
@Composable
fun CrashNoticeDialog(onView: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { CrashLog.markSeen(ctx); onDismiss() },
        title = { Text("检测到 App 曾异常退出") },
        text = { Text("已在本机记录诊断信息（不含订单内容，不会上传）。可以查看并复制，发给开发者帮助定位问题。") },
        confirmButton = { TextButton(onClick = onView) { Text("查看日志") } },
        dismissButton = { TextButton(onClick = { CrashLog.markSeen(ctx); onDismiss() }) { Text("知道了") } },
    )
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("食忆诊断日志", text))
    Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
}
