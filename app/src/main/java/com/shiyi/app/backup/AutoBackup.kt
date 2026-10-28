package com.shiyi.app.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shiyi.app.ShiYiApp
import com.shiyi.app.data.AppSettings
import com.shiyi.app.data.SecretStore
import com.shiyi.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 定期自动备份：按设置的间隔，把加密备份写到本机文件夹和/或坚果云。
 *
 * - 密码在开启时设置一次，用手机安全芯片里的密钥加密保存（同坚果云应用密码），后台备份时解密使用。
 * - 数据自上次自动备份以来没有变化时跳过，不产生重复文件。
 * - 本机文件夹和云端各自只保留最近 [KEEP_LOCAL] / [CloudBackup.KEEP] 份。
 * - 不申请“读取网络状态”权限（ML Kit 统计依赖它），所以不用 WorkManager 的联网条件；
 *   没网时上传失败，按退避策略稍后重试。
 */
class AutoBackup(
    private val context: Context,
    private val backup: BackupManager,
    private val cloud: CloudBackup,
    private val settings: SettingsStore,
) {
    sealed interface Outcome {
        /** 没开启、还没到时间，或数据没变化 */
        data class Skipped(val reason: String) : Outcome
        data class Done(val targets: List<String>) : Outcome
        /** [retryable]：网络类错误，值得稍后再试 */
        data class Failed(val message: String, val retryable: Boolean) : Outcome
    }

    /** 到时间了吗？留 1 小时余量，避免系统调度稍早几分钟就整整推迟一个周期 */
    fun isDue(s: AppSettings, now: Long = System.currentTimeMillis()): Boolean =
        s.autoEnabled && now - s.lastAutoBackup >= TimeUnit.DAYS.toMillis(s.autoDays.toLong()) - TimeUnit.HOURS.toMillis(1)

    /**
     * 执行一次自动备份。[force] = 用户点了“立即执行一次”：不管是否到时间、数据有没有变化。
     * 任一目的地成功即记为成功；另一个失败时把原因记下来，在设置页提示。
     */
    suspend fun run(force: Boolean = false): Outcome = try {
        runOnce(force)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // 意外错误（数据库、存储等）：记下来在设置页提示，不让后台任务或界面崩溃
        val msg = e.message ?: e.javaClass.simpleName
        runCatching { settings.setAutoError(msg, System.currentTimeMillis()) }
        Outcome.Failed(msg, retryable = false)
    }

    private suspend fun runOnce(force: Boolean): Outcome = withContext(Dispatchers.IO) {
        val s = settings.settings.first()
        if (!s.autoEnabled) return@withContext Outcome.Skipped("未开启")
        if (!force && !isDue(s)) return@withContext Outcome.Skipped("还没到时间")
        val sig = backup.dataSignature()
        if (!force && sig == s.lastAutoSignature) return@withContext Outcome.Skipped("数据没有变化")

        val password = SecretStore.decrypt(s.autoPasswordEnc)
        if (password.isEmpty()) {
            // 安全芯片里的密钥失效（例如系统清除了凭据），只能让用户重新设置
            val msg = "无法读取保存的备份密码，请在设置里重新开启自动备份"
            settings.setAutoError(msg, System.currentTimeMillis())
            return@withContext Outcome.Failed(msg, retryable = false)
        }

        val done = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var retryable = false
        if (s.autoDirUri.isNotBlank()) {
            runCatching { writeLocal(Uri.parse(s.autoDirUri), password, s.autoHint) }
                .onSuccess { done += "本机文件夹" }
                .onFailure { errors += "本机文件夹：" + (it.message ?: it.javaClass.simpleName) }
        }
        if (s.autoToCloud) {
            if (!s.davConfigured) errors += "坚果云：账号未设置"
            else runCatching { cloud.backupNow(password.toCharArray(), s.autoHint) }
                .onSuccess { done += "坚果云" }
                .onFailure {
                    if (it is java.io.IOException && it !is com.shiyi.app.net.WebDavClient.AuthException) retryable = true
                    errors += "坚果云：" + (it.message ?: it.javaClass.simpleName)
                }
        }

        val now = System.currentTimeMillis()
        if (done.isNotEmpty()) settings.setAutoResult(now, sig)
        if (errors.isNotEmpty()) settings.setAutoError(errors.joinToString("\n"), now)
        // 一处成功、一处失败也算完成：本轮不重试（否则成功的那处会重复写一份），只在设置页提示失败原因
        if (done.isNotEmpty()) Outcome.Done(done) else Outcome.Failed(errors.joinToString("\n"), retryable)
    }

    /** 在用户授权的文件夹里新建备份文件，并删掉多余的旧自动备份 */
    private suspend fun writeLocal(tree: Uri, password: String, hint: String) {
        val cr = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = FILE_PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date()) + FILE_EXT
        val created: Uri? = try {
            DocumentsContract.createDocument(cr, parent, BackupManager.MIME, name)
        } catch (e: SecurityException) {
            throw IllegalStateException("没有该文件夹的访问权限，请重新选择文件夹")
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("所选文件夹已不存在，请重新选择文件夹")
        }
        val doc = created ?: error("无法在所选文件夹中创建文件")
        try {
            backup.export(doc, password.toCharArray(), hint)
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(cr, doc) }
            throw e
        }
        pruneLocal(tree)
    }

    private fun pruneLocal(tree: Uri) {
        val cr = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val files = mutableListOf<Pair<String, String>>() // 文件名 → documentId
        cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val n = c.getString(1) ?: continue
                if (n.startsWith(FILE_PREFIX) && n.endsWith(FILE_EXT)) files += n to id
            }
        }
        // 文件名里带时间，按名字倒序 = 从新到旧
        files.sortedByDescending { it.first }.drop(KEEP_LOCAL).forEach { (_, id) ->
            runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    companion object {
        const val KEEP_LOCAL = 10
        /** 与云备份文件同名格式，“从备份恢复”里一眼能认出 */
        const val FILE_PREFIX = "shiyi-auto-"
        const val FILE_EXT = ".shiyibak"
        private const val PERIODIC = "auto_backup"
        private const val CATCH_UP = "auto_backup_catch_up"

        /** 可选的间隔（天） */
        val INTERVALS = listOf(1, 3, 7)

        fun intervalLabel(days: Int) = when (days) { 0 -> "关闭"; 1 -> "每天"; 7 -> "每周"; else -> "每 $days 天" }

        private val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()

        /**
         * 开启时排一个每 12 小时检查一次的周期任务（是否真的备份由 [isDue] 决定），关闭时取消。
         * 检查频率与间隔天数无关，所以改间隔不需要重排任务。
         */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(PERIODIC); wm.cancelUniqueWork(CATCH_UP); return }
            val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /**
         * 打开 App 时补一次：部分手机（如真我/ColorOS）会长时间不给后台任务运行机会，
         * 已经过期的话在用户打开 App 时尽快补上。
         */
        fun catchUp(context: Context) {
            val req = OneTimeWorkRequestBuilder<AutoBackupWorker>()
                .setConstraints(constraints)
                .setInitialDelay(20, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CATCH_UP, ExistingWorkPolicy.KEEP, req)
        }

        /** 选择文件夹后长期保留访问权限（重启手机后仍有效） */
        fun persistDirPermission(context: Context, tree: Uri) {
            context.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        fun releaseDirPermission(context: Context, uri: String) {
            if (uri.isBlank()) return
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }

        /** 文件夹 URI 的可读名称，如“Download/食忆” */
        fun dirLabel(uri: String): String {
            if (uri.isBlank()) return ""
            return runCatching {
                val id = DocumentsContract.getTreeDocumentId(Uri.parse(uri)) // 例如 "primary:Download/食忆"
                id.substringAfter(':').ifBlank { "手机存储根目录" }
            }.getOrDefault("已选择的文件夹")
        }
    }
}

class AutoBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? ShiYiApp ?: return Result.failure()
        return when (val r = app.container.autoBackup.run()) {
            is AutoBackup.Outcome.Failed -> if (r.retryable && runAttemptCount < 3) Result.retry() else Result.success()
            else -> Result.success()
        }
    }
}
