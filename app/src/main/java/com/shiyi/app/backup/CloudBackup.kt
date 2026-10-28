package com.shiyi.app.backup

import android.content.Context
import android.net.Uri
import com.shiyi.app.data.AppSettings
import com.shiyi.app.data.SecretStore
import com.shiyi.app.data.SettingsStore
import com.shiyi.app.net.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WebDAV（坚果云）云备份：上传数据（记录、零食、评分、价格等），按设置附带压缩后的零食照片/订单截图；
 * 上传前用备份密码加密（格式同本地备份），云端只能看到密文。云端保留最近 [KEEP] 份。
 */
class CloudBackup(private val context: Context, private val backup: BackupManager, private val settings: SettingsStore) {

    private fun client(s: AppSettings) = WebDavClient(s.davUrl, s.davUser, SecretStore.decrypt(s.davPasswordEnc))

    private val work get() = File(context.cacheDir, "cloud").apply { mkdirs() }

    suspend fun testAndSave(url: String, user: String, password: String, folder: String) = withContext(Dispatchers.IO) {
        val c = WebDavClient(url, user, password)
        c.test()
        val dir = folder.trim().trim('/').ifBlank { "食忆备份" }
        c.ensureDir(dir)
        settings.setDav(url, user, password, dir)
    }

    /** 立即备份，返回云端文件名 */
    suspend fun backupNow(password: CharArray, hint: String): String = withContext(Dispatchers.IO) {
        val s = settings.settings.first()
        require(s.davConfigured) { "请先设置坚果云账号" }
        val c = client(s)
        val name = "shiyi-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date()) + ".shiyibak"
        val tmp = File(work, name)
        try {
            backup.export(
                Uri.fromFile(tmp), password, hint,
                ImageOptions(recordImages = s.cloudRecordImages, snackImages = s.cloudSnackImages, recompressMaxDim = CLOUD_IMAGE_DIM),
            )
            c.ensureDir(s.davFolder)
            c.upload(s.davFolder, name, tmp)
            // 只保留最近 KEEP 份
            c.list(s.davFolder).filter { it.name.startsWith("shiyi-") && it.name.endsWith(".shiyibak") }
                .sortedByDescending { it.name }.drop(KEEP)
                .forEach { runCatching { c.delete(s.davFolder, it.name) } }
            settings.setLastCloudBackup(System.currentTimeMillis())
            name
        } finally {
            tmp.delete()
        }
    }

    suspend fun list(): List<WebDavClient.RemoteFile> = withContext(Dispatchers.IO) {
        val s = settings.settings.first()
        client(s).list(s.davFolder).filter { it.name.endsWith(".shiyibak") }.sortedByDescending { it.name }
    }

    /** 下载到本机临时文件，交给本地的“验证/恢复”流程 */
    suspend fun download(name: String): File = withContext(Dispatchers.IO) {
        val s = settings.settings.first()
        val f = File(work, name)
        client(s).download(s.davFolder, name, f)
        f
    }

    fun clearTemp() { work.listFiles()?.forEach { it.delete() } }

    companion object {
        const val KEEP = 10
        /** 云备份里的图片再压缩到长边（截图为宽度）约 1080 像素 */
        const val CLOUD_IMAGE_DIM = 1080

        /** "shiyi-20261025-213000.shiyibak" → "2026-10-25 21:30" */
        fun displayName(name: String): String {
            val m = Regex("""shiyi-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})""").find(name) ?: return name
            val g = m.groupValues
            return "${g[1]}-${g[2]}-${g[3]} ${g[4]}:${g[5]}"
        }
    }
}
