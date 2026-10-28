package com.shiyi.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** 拍照临时目录（FileProvider 的 cache-path “camera/”） */
fun cameraDir(ctx: Context) = File(ctx.cacheDir, "camera").apply { mkdirs() }

/**
 * 调用系统相机拍一张照片。App 不申请相机权限：由系统相机拍摄后写入我们提供的临时文件。
 * 返回一个启动函数；结果为 null 表示取消或失败。
 */
@Composable
fun rememberTakePhoto(onResult: (File?) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPath?.let(::File)
        pendingPath = null
        if (ok && f != null && f.exists() && f.length() > 0) onResult(f) else { f?.delete(); onResult(null) }
    }
    return {
        val f = File(cameraDir(ctx), UUID.randomUUID().toString() + ".jpg")
        pendingPath = f.absolutePath
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            launcher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pendingPath = null
            Toast.makeText(ctx, "没有找到可用的相机应用", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            pendingPath = null
            Toast.makeText(ctx, "无法打开相机：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }
}
