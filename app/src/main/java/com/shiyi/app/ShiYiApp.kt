package com.shiyi.app

import android.app.Application
import android.os.Build
import com.shiyi.app.diagnostics.CrashLog
import java.io.File
import com.shiyi.app.backup.AutoBackup
import com.shiyi.app.backup.BackupManager
import com.shiyi.app.data.AppDatabase
import com.shiyi.app.data.Repository
import com.shiyi.app.data.SettingsStore
import com.shiyi.app.importing.ImportStaging
import com.shiyi.app.importing.PendingImport
import com.shiyi.app.ocr.OcrEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 简单的手动依赖容器（单人 App 无需引入 DI 框架） */
class AppContainer(app: Application) {
    val appContext: android.content.Context = app
    val db = AppDatabase.build(app)
    val repo = Repository(app, db)
    val snacks = com.shiyi.app.data.SnackRepository(db, repo)
    val settings = SettingsStore(app)
    val staging = ImportStaging(app)
    val ocr = OcrEngine(app)
    val ocrClient = com.shiyi.app.ocr.OcrClient(app)
    val backup = BackupManager(app, repo)
    val cloud = com.shiyi.app.backup.CloudBackup(app, backup, settings)
    val autoBackup = com.shiyi.app.backup.AutoBackup(app, backup, cloud, settings)
    val barcodeLookup = com.shiyi.app.net.BarcodeLookup()

    /** 来自系统分享或相册选择、等待识别的导入任务 */
    val pendingImport = MutableStateFlow<PendingImport?>(null)

    /** 识别完成后交给确认页的预填数据（确认页取走后置空） */
    val prefill = MutableStateFlow<com.shiyi.app.importing.EditPrefill?>(null)
}

class ShiYiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val process = currentProcessName()
        CrashLog.install(this, process)
        // 识别进程只运行 OcrService，不初始化数据库等组件
        if (process.endsWith(":ocr")) return
        container = AppContainer(this)
        container.staging.cleanupStale()
        CrashLog.collectExitReasons(this)
        startAutoBackup()
    }

    /** 自动备份开着的话确保周期任务在排队；已经过期的（后台被系统限制）尽快补一次 */
    private fun startAutoBackup() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                val s = container.settings.settings.first()
                if (!s.autoEnabled) return@runCatching
                AutoBackup.schedule(this@ShiYiApp, true)
                if (container.autoBackup.isDue(s)) AutoBackup.catchUp(this@ShiYiApp)
            }
        }
    }

    private fun currentProcessName(): String =
        if (Build.VERSION.SDK_INT >= 28) getProcessName()
        else runCatching { File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }.getOrDefault(packageName)
}
