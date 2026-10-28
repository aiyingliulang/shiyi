package com.shiyi.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ManageAccounts
import com.shiyi.app.backup.CloudBackup
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shiyi.app.AppContainer
import com.shiyi.app.BuildConfig
import com.shiyi.app.backup.AutoBackup
import com.shiyi.app.backup.BackupCrypto
import com.shiyi.app.backup.BackupSummary
import com.shiyi.app.data.AppSettings
import com.shiyi.app.domain.Times
import com.shiyi.app.ui.components.SectionTitle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Dlg {
    data object NewBackup : Dlg
    data class RestorePassword(val uri: Uri, val hint: String, val error: String? = null) : Dlg
    data class ConfirmRestore(val uri: Uri, val password: CharArray, val summary: BackupSummary) : Dlg
    data class Message(val title: String, val text: String) : Dlg
    data object DavSetup : Dlg
    data object CloudBackupPw : Dlg
    data class CloudList(val files: List<com.shiyi.app.net.WebDavClient.RemoteFile>) : Dlg
    data object AutoSetup : Dlg
}

@Composable
fun SettingsScreen(c: AppContainer, nav: NavHostController) {
    val settings by c.settings.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var pendingBackup by remember { mutableStateOf<Pair<CharArray, String>?>(null) }
    var showDiag by remember { mutableStateOf(false) }
    if (showDiag) com.shiyi.app.ui.diagnostics.DiagnosticsDialog { showDiag = false }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    /** 自动备份对话框里刚选的文件夹（还没保存）；"" 表示取消了文件夹 */
    var autoDirDraft by remember { mutableStateOf<String?>(null) }
    val pickAutoDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { AutoBackup.persistDirPermission(ctx, uri) }.fold(
            { autoDirDraft = uri.toString() },
            { dlg = Dlg.Message("无法使用该文件夹", "系统没有授予长期访问权限，请换一个文件夹（例如在“下载”里新建一个“食忆备份”）。") },
        )
    }

    fun runAutoNow() {
        scope.launch {
            busy = "正在自动备份…"
            dlg = when (val r = c.autoBackup.run(force = true)) {
                is AutoBackup.Outcome.Done -> {
                    val err = c.settings.settings.first().autoError
                    Dlg.Message("自动备份完成", "已备份到：${r.targets.joinToString("、")}。" + (if (err.isNotBlank()) "\n\n部分失败：\n$err" else ""))
                }
                is AutoBackup.Outcome.Failed -> Dlg.Message("自动备份失败", r.message)
                is AutoBackup.Outcome.Skipped -> Dlg.Message("没有执行", r.reason)
            }
            busy = null
        }
    }

    fun stamp() = SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date())

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = pendingBackup; pendingBackup = null
        if (uri == null || p == null) { p?.first?.fill('\u0000'); return@rememberLauncherForActivityResult }
        scope.launch {
            busy = "正在加密备份…"
            dlg = runCatching { c.backup.export(uri, p.first, p.second).also { c.settings.setLastLocalBackup(System.currentTimeMillis()) } }.fold(
                { s -> Dlg.Message("备份完成", "已备份 ${s.records} 条消费记录、${s.foodItems} 个美食档案、${s.snacks} 款零食、${s.images} 张图片。\n\n建议立即在“从备份恢复”中选择该文件做一次“验证”。请牢记密码，忘记密码将无法恢复。") },
                { e -> Dlg.Message("备份失败", e.message ?: e.javaClass.simpleName) },
            )
            busy = null
        }
    }

    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            dlg = runCatching { c.backup.readHint(uri) }.fold(
                { hint -> Dlg.RestorePassword(uri, hint) },
                { e -> Dlg.Message("无法打开", if (e is BackupCrypto.NotABackup) "所选文件不是食忆备份。" else (e.message ?: "读取失败")) },
            )
        }
    }

    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "正在导出…"
            dlg = runCatching { c.backup.exportCsv(uri) }.fold(
                { n -> Dlg.Message("导出完成", "已导出 $n 条记录。CSV 便于用表格软件查看，但不包含图片，不能代替完整备份。") },
                { e -> Dlg.Message("导出失败", e.message ?: "") },
            )
            busy = null
        }
    }

    val createSnackCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "正在导出…"
            dlg = runCatching { c.backup.exportSnackCsv(uri) }.fold(
                { n -> Dlg.Message("导出完成", "已导出 $n 款零食。") },
                { e -> Dlg.Message("导出失败", e.message ?: "") },
            )
            busy = null
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("设置与数据", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))

        SectionTitle("备份与导出")
        ActionRow(Icons.Outlined.Backup, "加密备份", "包含全部记录、档案、零食和保留的图片；AES-256-GCM 加密，由你选择保存位置") { dlg = Dlg.NewBackup }
        ActionRow(Icons.Outlined.Restore, "从备份恢复 / 验证备份", "先校验密码与完整性，再确认是否覆盖当前数据") { openBackup.launch(arrayOf("*/*")) }
        ActionRow(Icons.Outlined.TableChart, "导出消费记录 CSV", "用于表格查看与分析，不代替完整备份") { createCsv.launch("食忆记录_${stamp()}.csv") }
        ActionRow(Icons.Outlined.TableChart, "导出零食 CSV", "零食名称、品牌、评分、结论等") { createSnackCsv.launch("食忆零食_${stamp()}.csv") }

        SectionTitle("定期自动备份")
        ActionRow(
            Icons.Outlined.Schedule, "自动备份",
            if (!settings.autoEnabled) "已关闭 · 开启后在后台按时加密备份，数据没有变化时不重复备份"
            else AutoBackup.intervalLabel(settings.autoDays) + " · 备份到" + autoTargets(settings).joinToString("、") +
                " · " + (if (settings.lastAutoBackup > 0) "上次 ${Times.dateTime(settings.lastAutoBackup)}" else "还没有执行过"),
        ) { autoDirDraft = null; dlg = Dlg.AutoSetup }
        if (settings.autoEnabled && settings.autoError.isNotBlank()) {
            ActionRow(
                Icons.Outlined.ErrorOutline, "上次自动备份有问题（${Times.dateTime(settings.autoErrorAt)}）",
                settings.autoError + "\n点此重试",
                tint = MaterialTheme.colorScheme.error,
            ) { runAutoNow() }
        } else if (settings.autoEnabled) {
            ActionRow(Icons.Outlined.Backup, "立即自动备份一次", "不等到时间，现在就按自动备份的设置备份一份") { runAutoNow() }
        }

        SectionTitle("云备份（坚果云 / WebDAV）")
        if (!settings.davConfigured) {
            ActionRow(Icons.Outlined.Cloud, "设置坚果云账号", "加密后上传到你自己的网盘，换手机也能恢复") { dlg = Dlg.DavSetup }
        } else {
            ActionRow(
                Icons.Outlined.CloudUpload, "立即备份到云端",
                (if (settings.lastCloudBackup > 0) "上次：${Times.dateTime(settings.lastCloudBackup)}" else "还没有备份过") +
                    " · 加密上传，云端保留最近 ${CloudBackup.KEEP} 份",
            ) { dlg = Dlg.CloudBackupPw }
            ActionRow(Icons.Outlined.CloudDownload, "从云端恢复", "选择一份云端备份，校验后再决定是否覆盖") {
                scope.launch {
                    busy = "正在读取云端备份…"
                    dlg = runCatching { c.cloud.list() }.fold(
                        { if (it.isEmpty()) Dlg.Message("云端没有备份", "“${settings.davFolder}”文件夹里还没有食忆备份。") else Dlg.CloudList(it) },
                        { e -> Dlg.Message("读取失败", e.message ?: "网络不可用") },
                    )
                    busy = null
                }
            }
            ToggleRow("云备份包含零食照片", "照片会再压缩到约 1080 像素，每张约 100KB", settings.cloudSnackImages) {
                scope.launch { c.settings.setCloudSnackImages(it) }
            }
            ToggleRow("云备份包含订单截图", "默认不包含；开启后同样压缩上传", settings.cloudRecordImages) {
                scope.launch { c.settings.setCloudRecordImages(it) }
            }
            ActionRow(Icons.Outlined.ManageAccounts, "修改账号 / 退出", "${settings.davUser} · ${settings.davFolder}") { dlg = Dlg.DavSetup }
        }

        SectionTitle("联网")
        ToggleRow("扫条码后联网查商品信息", "自动填零食名称、品牌、规格；只发送条码数字（Open Food Facts）", settings.onlineBarcode) {
            scope.launch { c.settings.setOnlineBarcode(it) }
        }

        SectionTitle("隐私")
        ToggleRow("截图识别后默认保留原图", "关闭时识别完即删除临时图片（推荐）", settings.keepImagesByDefault) {
            scope.launch { c.settings.setKeepImages(it) }
        }
        ToggleRow("保存订单号指纹用于去重", "只保存不可逆的 SHA-256 指纹，不保存订单号明文", settings.storeOrderFingerprint) {
            scope.launch { c.settings.setStoreFingerprint(it) }
        }
        ToggleRow("隐藏最近任务预览并禁止截屏", "开启后在多任务界面不显示内容", settings.secureWindow) {
            scope.launch { c.settings.setSecureWindow(it) }
        }
        ActionRow(Icons.Outlined.CleaningServices, "清理临时文件", "删除识别过程中残留的临时截图") {
            c.staging.clearAll(); dlg = Dlg.Message("已清理", "临时文件已删除。相册原图不受影响。")
        }

        ActionRow(Icons.Outlined.BugReport, "诊断日志", "查看闪退/异常记录，可复制发给开发者；只存本机") { showDiag = true }

        SectionTitle("关于")
        ActionRow(Icons.Outlined.VerifiedUser, "数据说明", "数据存在本机；联网只用于查条码和你自己的云备份；无广告与统计") {
            dlg = Dlg.Message(
                "数据说明",
                "• 所有数据保存在本机 App 私有目录，截图识别和条码识别都在本机离线完成。\n" +
                    "• 联网只有两处，都可关闭：① 扫条码后查商品信息，只发送条码数字；② 云备份，先用你的备份密码加密再上传到你自己的坚果云。\n" +
                    "• 坚果云应用密码用手机安全芯片里的密钥加密保存。\n" +
                    "• 没有广告和统计 SDK；识别库自带的使用统计已被关闭。\n" +
                    "• 已关闭系统自动备份，请使用本地加密备份或云备份。\n• 卸载 App 会删除全部数据，卸载前请先备份。"
            )
        }
        ActionRow(Icons.Outlined.Info, "版本", "食忆 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）") {}
        Spacer(Modifier.height(24.dp))
    }

    busy?.let {
        AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 16.dp)); Text(it)
            }
        })
    }

    when (val d = dlg) {
        null -> {}
        Dlg.AutoSetup -> AutoBackupDialog(
            settings = settings,
            dirUri = autoDirDraft ?: settings.autoDirUri,
            onPickDir = { pickAutoDir.launch(null) },
            onClearDir = { autoDirDraft = "" },
            onDismiss = {
                // 选了但没保存的文件夹：归还访问权限
                autoDirDraft?.let { if (it != settings.autoDirUri) AutoBackup.releaseDirPermission(ctx, it) }
                autoDirDraft = null; dlg = null
            },
            onTurnOff = {
                scope.launch {
                    AutoBackup.schedule(ctx, false)
                    AutoBackup.releaseDirPermission(ctx, settings.autoDirUri)
                    autoDirDraft?.let { AutoBackup.releaseDirPermission(ctx, it) }
                    autoDirDraft = null
                    c.settings.turnOffAutoBackup()
                    dlg = Dlg.Message("已关闭自动备份", "保存在手机上的自动备份密码已删除。已经备份好的文件不受影响。")
                }
            },
            onSave = { days, toCloud, dir, pw, hint ->
                scope.launch {
                    if (dir != settings.autoDirUri) AutoBackup.releaseDirPermission(ctx, settings.autoDirUri)
                    autoDirDraft = null
                    c.settings.setAutoBackup(days, toCloud, dir, pw?.let { String(it) }, hint)
                    pw?.fill('\u0000')
                    AutoBackup.schedule(ctx, true)
                    dlg = null
                    // 立即做第一次，确认密码和备份位置都可用
                    runAutoNow()
                }
            },
        )
        Dlg.DavSetup -> DavSetupDialog(
            settings = settings,
            onDismiss = { dlg = null },
            onLogout = { scope.launch { c.settings.clearDav(); dlg = Dlg.Message("已退出", "已清除本机保存的坚果云账号。云端的备份文件不受影响。") } },
            onSave = { url, user, pass, folder ->
                scope.launch {
                    busy = "正在连接…"
                    dlg = runCatching { c.cloud.testAndSave(url, user, pass, folder) }.fold(
                        { Dlg.Message("连接成功", "备份会保存在坚果云的“${folder.ifBlank { "食忆备份" }}”文件夹。") },
                        { e -> Dlg.Message("连接失败", (e.message ?: "网络不可用") + "\n\n请确认填写的是坚果云“第三方应用管理”里生成的应用密码，而不是登录密码。") },
                    )
                    busy = null
                }
            },
        )
        Dlg.CloudBackupPw -> NewBackupDialog(
            onDismiss = { dlg = null },
            onConfirm = { pw, hint ->
                dlg = null
                scope.launch {
                    busy = "正在压缩、加密并上传…"
                    dlg = runCatching { c.cloud.backupNow(pw, hint) }.fold(
                        { name -> Dlg.Message("云备份完成", "已上传 ${CloudBackup.displayName(name)} 的备份。请记住这次的备份密码，恢复时需要。") },
                        { e -> Dlg.Message("云备份失败", e.message ?: "网络不可用") },
                    )
                    busy = null
                }
            },
        )
        is Dlg.CloudList -> AlertDialog(
            onDismissRequest = { dlg = null },
            title = { Text("选择要恢复的云端备份") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    d.files.forEach { f ->
                        TextButton(onClick = {
                            scope.launch {
                                busy = "正在下载…"
                                dlg = runCatching {
                                    val file = c.cloud.download(f.name)
                                    val uri = Uri.fromFile(file)
                                    Dlg.RestorePassword(uri, c.backup.readHint(uri))
                                }.getOrElse { e -> Dlg.Message("下载失败", e.message ?: "网络不可用") }
                                busy = null
                            }
                        }) { Text("${CloudBackup.displayName(f.name)} · ${f.size / 1024} KB") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = null }) { Text("取消") } },
        )
        is Dlg.Message -> AlertDialog(
            onDismissRequest = { dlg = null }, title = { Text(d.title) }, text = { Text(d.text) },
            confirmButton = { TextButton(onClick = { dlg = null }) { Text("好") } },
        )
        Dlg.NewBackup -> NewBackupDialog(
            onDismiss = { dlg = null },
            onConfirm = { pw, hint ->
                dlg = null
                pendingBackup = pw to hint
                createBackup.launch("食忆备份_${stamp()}.shiyibak")
            },
        )
        is Dlg.RestorePassword -> PasswordDialog(
            hint = d.hint, error = d.error,
            onDismiss = { dlg = null },
            onConfirm = { pw ->
                scope.launch {
                    busy = "正在解密并校验…"
                    val r = runCatching { c.backup.verify(d.uri, pw.copyOf()) }
                    busy = null
                    dlg = r.fold(
                        { s -> Dlg.ConfirmRestore(d.uri, pw, s) },
                        { e ->
                            pw.fill('\u0000')
                            when (e) {
                                is BackupCrypto.WrongPasswordOrCorrupted -> d.copy(error = "密码错误或文件已损坏")
                                else -> Dlg.Message("校验失败", e.message ?: e.javaClass.simpleName)
                            }
                        },
                    )
                }
            },
        )
        is Dlg.ConfirmRestore -> AlertDialog(
            onDismissRequest = { d.password.fill('\u0000'); dlg = null },
            title = { Text("备份校验通过") },
            text = {
                Text(
                    "备份时间：${Times.dateTime(d.summary.exportedAt)}（版本 ${d.summary.appVersion}）\n" +
                        "消费记录 ${d.summary.records} 条 · 美食档案 ${d.summary.foodItems} 个 · 零食 ${d.summary.snacks} 款 · 图片 ${d.summary.images} 张" +
                        (if (d.summary.missingImages > 0) "（缺失 ${d.summary.missingImages} 张）" else "") +
                        "\n\n恢复将用备份内容替换当前全部数据，当前数据会被覆盖。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dlg = null
                    scope.launch {
                        busy = "正在恢复…"
                        dlg = runCatching { c.backup.restore(d.uri, d.password).also { c.cloud.clearTemp() } }.fold(
                            { s -> Dlg.Message("恢复完成", "已恢复 ${s.records} 条记录、${s.foodItems} 个档案、${s.snacks} 款零食、${s.images} 张图片。") },
                            { e -> Dlg.Message("恢复失败", "当前数据未改动。\n" + (e.message ?: "")) },
                        )
                        busy = null
                    }
                }) { Text("覆盖并恢复", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { d.password.fill('\u0000'); dlg = null }) { Text("仅验证，不恢复") } },
        )
    }
}

private fun autoTargets(s: AppSettings) = buildList {
    if (s.autoDirUri.isNotBlank()) add("本机“${AutoBackup.dirLabel(s.autoDirUri)}”")
    if (s.autoToCloud) add("坚果云")
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, sub: String, tint: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            }
            Switch(value, onChange)
        }
    }
}

@Composable
private fun NewBackupDialog(onDismiss: () -> Unit, onConfirm: (CharArray, String) -> Unit) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    val err = when {
        p1.isNotEmpty() && p1.length < 8 -> "密码至少 8 位"
        p2.isNotEmpty() && p1 != p2 -> "两次输入不一致"
        hint.isNotBlank() && p1.isNotEmpty() && hint.contains(p1) -> "提示中不能包含密码"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置备份密码") },
        text = {
            Column {
                Text("忘记密码将无法恢复备份，App 无法找回。", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                PwField(p1, { p1 = it }, "密码（至少 8 位）")
                PwField(p2, { p2 = it }, "再次输入密码")
                OutlinedTextField(hint, { hint = it.take(100) }, label = { Text("密码提示（可选，明文保存在备份中）") }, singleLine = true)
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = err == null && p1.length >= 8 && p1 == p2, onClick = { onConfirm(p1.toCharArray(), hint.trim()) }) { Text("选择保存位置") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AutoBackupDialog(
    settings: AppSettings, dirUri: String,
    onPickDir: () -> Unit, onClearDir: () -> Unit, onDismiss: () -> Unit, onTurnOff: () -> Unit,
    onSave: (days: Int, toCloud: Boolean, dirUri: String, password: CharArray?, hint: String) -> Unit,
) {
    var days by remember { mutableStateOf(settings.autoDays.takeIf { it > 0 } ?: 1) }
    var toCloud by remember { mutableStateOf(if (settings.autoEnabled) settings.autoToCloud else settings.davConfigured) }
    val hasPassword = settings.autoPasswordEnc.isNotBlank()
    var changePw by remember { mutableStateOf(!hasPassword) }
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf(settings.autoHint) }
    val pwErr = when {
        !changePw -> null
        p1.isNotEmpty() && p1.length < 8 -> "密码至少 8 位"
        p2.isNotEmpty() && p1 != p2 -> "两次输入不一致"
        hint.isNotBlank() && p1.isNotEmpty() && hint.contains(p1) -> "提示中不能包含密码"
        else -> null
    }
    val pwOk = !changePw || (pwErr == null && p1.length >= 8 && p1 == p2)
    val cloudOn = toCloud && settings.davConfigured
    val hasTarget = cloudOn || dirUri.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定期自动备份") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("多久备份一次", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    AutoBackup.INTERVALS.forEach { d -> FilterChip(days == d, { days = d }, { Text(AutoBackup.intervalLabel(d)) }) }
                }
                Spacer(Modifier.height(8.dp))
                Text("备份到哪里（可以都选）", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(cloudOn, { toCloud = it }, enabled = settings.davConfigured)
                    Text(
                        if (settings.davConfigured) "坚果云（与手动云备份一起保留最近 ${CloudBackup.KEEP} 份）" else "坚果云（请先在下方设置账号）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(dirUri.isNotBlank(), { on -> if (on) onPickDir() else onClearDir() })
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (dirUri.isBlank()) "手机上的文件夹" else "文件夹：${AutoBackup.dirLabel(dirUri)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "保留最近 ${AutoBackup.KEEP_LOCAL} 份；卸载 App 也不会被删除",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (dirUri.isNotBlank()) TextButton(onClick = onPickDir) { Text("更换") }
                }
                Spacer(Modifier.height(8.dp))
                Text("备份密码", style = MaterialTheme.typography.labelLarge)
                if (!changePw) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("沿用已保存的密码", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { changePw = true }) { Text("修改") }
                    }
                } else {
                    Text(
                        "后台备份时没法问你密码，所以会把它保存在手机上（用安全芯片里的密钥加密）。恢复备份时要输入这个密码，忘记将无法恢复。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PwField(p1, { p1 = it }, "密码（至少 8 位）")
                    PwField(p2, { p2 = it }, "再次输入密码")
                }
                OutlinedTextField(
                    hint, { hint = it.take(100) }, label = { Text("密码提示（可选，明文保存在备份中）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                pwErr?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!hasTarget) Text("请至少选择一个备份位置", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (settings.autoEnabled) TextButton(onClick = onTurnOff) { Text("关闭自动备份", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = hasTarget && pwOk, onClick = {
                onSave(days, cloudOn, dirUri, if (changePw) p1.toCharArray() else null, hint.trim())
            }) { Text("保存并备份一次") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PasswordDialog(hint: String, error: String?, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var p by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入备份密码") },
        text = {
            Column {
                if (hint.isNotBlank()) Text("密码提示：$hint", style = MaterialTheme.typography.bodyMedium)
                PwField(p, { p = it }, "密码")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = p.isNotEmpty(), onClick = { onConfirm(p.toCharArray()) }) { Text("校验") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PwField(v: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        v, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DavSetupDialog(
    settings: AppSettings, onDismiss: () -> Unit, onLogout: () -> Unit,
    onSave: (url: String, user: String, pass: String, folder: String) -> Unit,
) {
    var url by remember { mutableStateOf(settings.davUrl) }
    var user by remember { mutableStateOf(settings.davUser) }
    var pass by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(settings.davFolder) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("坚果云 / WebDAV") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "应用密码获取：坚果云网页版 → 右上角账户信息 → 安全选项 → 第三方应用管理 → 添加应用，生成的密码填在下面。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(user, { user = it.trim() }, label = { Text("坚果云账号（邮箱/手机号）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                PwField(pass, { pass = it }, if (settings.davConfigured) "应用密码（不修改可留空）" else "应用密码")
                OutlinedTextField(folder, { folder = it }, label = { Text("备份文件夹") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it.trim() }, label = { Text("服务器地址（其他 WebDAV 网盘可修改）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (settings.davConfigured) TextButton(onClick = onLogout) { Text("退出账号", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val canSave = url.startsWith("http") && user.isNotBlank() && (pass.isNotBlank() || settings.davConfigured)
            TextButton(enabled = canSave, onClick = {
                val p = pass.ifBlank { com.shiyi.app.data.SecretStore.decrypt(settings.davPasswordEnc) }
                onSave(url, user, p, folder)
            }) { Text("测试并保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
