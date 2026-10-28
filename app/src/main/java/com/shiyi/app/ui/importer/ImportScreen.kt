package com.shiyi.app.ui.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.shiyi.app.AppContainer
import com.shiyi.app.importing.EditPrefill
import com.shiyi.app.importing.ImportStaging
import com.shiyi.app.importing.PendingImport
import com.shiyi.app.ocr.OrderParser
import com.shiyi.app.domain.Scenario
import com.shiyi.app.ui.Routes
import com.shiyi.app.ui.appViewModel
import kotlinx.coroutines.launch

class ImportViewModel(private val c: AppContainer) : ViewModel() {
    var progress by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var ready by mutableStateOf(false)
        private set
    private var processingId: String? = null
    private var handedOff = false
    private var current: PendingImport? = null

    fun stage(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            progress = "正在读取图片…"
            val (files, failed) = c.staging.stageUris(uris)
            if (files.isEmpty()) {
                progress = null
                message = "无法读取所选图片，请重试或手动录入。"
                return@launch
            }
            c.pendingImport.value = PendingImport(images = files, failedCount = failed)
        }
    }

    fun process(p: PendingImport) {
        if (processingId == p.id) return
        processingId = p.id
        current = p
        viewModelScope.launch {
            val lines = mutableListOf<String>()
            var failedImages = 0
            var processDied = false
            val total = p.images.size
            if (total > 0) {
                progress = "正在启动离线识别…"
                // 优先在独立进程识别；无法连接时才回退到本进程
                val results = runCatching {
                    c.ocrClient.recognizeAll(p.images) { i -> progress = "正在离线识别 ${i + 1}/$total…" }
                }.getOrNull() ?: p.images.mapIndexed { i, f ->
                    progress = "正在离线识别 ${i + 1}/$total…"
                    runCatching { c.ocr.recognize(f) }
                }
                results.forEach { r ->
                    r.onSuccess { lines += it }.onFailure {
                        failedImages++
                        if (it is com.shiyi.app.ocr.OcrProcessDiedException) processDied = true
                    }
                }
            }
            p.sharedText?.let { t -> lines += t.lines() }
            val parsed = runCatching { OrderParser.parse(lines) }.getOrElse { e ->
                com.shiyi.app.diagnostics.CrashLog.append(c.appContext, "【解析失败】" + e.stackTraceToString().take(3000))
                com.shiyi.app.ocr.ParsedOrder()
            }
            val note = when {
                processDied -> "识别过程中出现异常（可能是截图过长或格式特殊），已记录诊断信息。截图已保留，请手动填写。"
                failedImages > 0 && failedImages < total -> "有 $failedImages 张截图识别失败，已忽略。"
                p.failedCount > 0 -> "有 ${p.failedCount} 项内容无法读取，已忽略。"
                else -> null
            }
            c.prefill.value = EditPrefill(
                parsed = parsed,
                images = p.images,
                sharedText = p.sharedText,
                ocrFailed = total > 0 && (failedImages == total || lines.isEmpty()),
                imageCount = total,
                note = note,
            )
            handedOff = true
            c.pendingImport.value = null
            progress = null
            ready = true
        }
    }

    override fun onCleared() {
        if (!handedOff) current?.let { c.staging.discard(it.images) }
        super.onCleared()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(c: AppContainer, nav: NavHostController) {
    val vm = appViewModel { ImportViewModel(c) }
    val pending by c.pendingImport.collectAsState()
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ImportStaging.MAX_IMAGES),
    ) { uris -> vm.stage(uris) }

    LaunchedEffect(pending) { pending?.let(vm::process) }
    LaunchedEffect(vm.ready) {
        if (vm.ready) nav.navigate(Routes.fromImport()) { popUpTo(Routes.IMPORT) { inclusive = true } }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("截图识别") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val p = vm.progress
            if (p != null) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(p)
                Text("识别在本机完成，不会上传图片", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            } else {
                Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(
                    "选择外卖或团购的订单详情截图（最多 ${ImportStaging.MAX_IMAGES} 张）。\n多张截图会合并为同一笔消费识别。",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("从相册选择截图") }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { nav.navigate(Routes.newRecord(Scenario.DELIVERY.name)) { popUpTo(Routes.IMPORT) { inclusive = true } } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("不识别，手动录入") }
                Spacer(Modifier.height(16.dp))
                Text(
                    "也可以在美团/抖音等 App 里分享截图或链接到“记到食忆”。\n使用系统照片选择器，App 无需相册权限，也不会删除相册原图。",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center,
                )
            }
            vm.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}
