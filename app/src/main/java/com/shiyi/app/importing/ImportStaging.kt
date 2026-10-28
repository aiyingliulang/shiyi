package com.shiyi.app.importing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 一次导入会话：若干张已复制到缓存的临时图片 + 可选的分享文本 */
data class PendingImport(
    val id: String = UUID.randomUUID().toString(),
    val images: List<File> = emptyList(),
    val sharedText: String? = null,
    /** 无法读取/不支持的条目数量，用于提示 */
    val failedCount: Int = 0,
    val unsupported: Boolean = false,
)

/**
 * 系统分享/照片选择器给出的 URI 授权是临时的（随 Activity 生命周期失效），
 * 因此收到后立即复制到 App 缓存目录的临时区，识别/保存后删除。
 * 不会删除或修改用户相册中的原图。
 */
class ImportStaging(private val context: Context) {

    val dir: File get() = File(context.cacheDir, "import_staging").apply { mkdirs() }

    suspend fun stageUris(uris: List<Uri>): Pair<List<File>, Int> = withContext(Dispatchers.IO) {
        var failed = 0
        val files = uris.distinct().take(MAX_IMAGES).mapNotNull { uri ->
            runCatching {
                val mime = context.contentResolver.getType(uri).orEmpty()
                if (mime.isNotEmpty() && !mime.startsWith("image/")) error("not image")
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
                val out = File(dir, UUID.randomUUID().toString() + "." + ext)
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    out.outputStream().use { o ->
                        val copied = input.copyTo(o)
                        if (copied > MAX_BYTES) error("too large")
                    }
                }
                if (out.length() == 0L) { out.delete(); error("empty") }
                out
            }.onFailure { failed++ }.getOrNull()
        }
        failed += (uris.distinct().size - MAX_IMAGES).coerceAtLeast(0)
        files to failed
    }

    /** 解析系统分享 Intent。必须在 Activity 仍持有 URI 授权时调用。 */
    suspend fun fromShareIntent(intent: Intent): PendingImport? {
        val action = intent.action ?: return null
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
        val type = intent.type.orEmpty()
        val uris = buildList {
            if (action == Intent.ACTION_SEND) {
                intent.streamExtra()?.let { add(it) }
            } else {
                addAll(intent.streamListExtra())
            }
            // 部分 App 通过 ClipData 传递
            intent.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { add(it) } }
        }.distinct()
        val text = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        ).joinToString("\n").trim().take(4000).ifEmpty { null }

        if (uris.isEmpty() && text == null) return PendingImport(unsupported = true)
        val (files, failed) = if (uris.isNotEmpty() && (type.startsWith("image/") || type == "*/*" || type.isEmpty() || action == Intent.ACTION_SEND_MULTIPLE))
            stageUris(uris) else (emptyList<File>() to uris.size)
        if (files.isEmpty() && text == null) return PendingImport(failedCount = failed, unsupported = true)
        return PendingImport(images = files, sharedText = text, failedCount = failed)
    }

    fun discard(files: List<File>) = files.forEach { if (it.parentFile == dir) it.delete() }

    /** 启动时清理超过 1 小时的残留临时文件 */
    fun cleanupStale(maxAgeMs: Long = 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        cameraDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
        cameraDir.listFiles()?.forEach { it.delete() }
    }

    /** 系统相机拍照的临时目录 */
    private val cameraDir: File get() = File(context.cacheDir, "camera")

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    @Suppress("DEPRECATION")
    private fun Intent.streamListExtra(): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

    companion object {
        const val MAX_IMAGES = 9
        const val MAX_BYTES = 40L * 1024 * 1024

        private val urlRegex = Regex("""https?://\S+""")
        fun extractUrls(text: String?): List<String> = text?.let { urlRegex.findAll(it).map { m -> m.value }.toList() }.orEmpty()
    }
}
