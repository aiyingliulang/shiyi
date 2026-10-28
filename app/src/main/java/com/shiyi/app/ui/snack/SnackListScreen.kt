package com.shiyi.app.ui.snack

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.shiyi.app.AppContainer
import com.shiyi.app.data.SnackWithCover
import com.shiyi.app.domain.SnackVerdict
import com.shiyi.app.ui.Routes
import com.shiyi.app.ui.appViewModel
import com.shiyi.app.ui.components.EmptyHint
import com.shiyi.app.ui.components.RatingPill
import com.shiyi.app.ui.components.rememberTakePhoto
import com.shiyi.app.ui.components.rememberThumb
import com.shiyi.app.ui.theme.LocalSemantic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface ScanState {
    data object Scanning : ScanState
    data class Found(val code: String, val matches: List<SnackWithCover>, val online: String? = null) : ScanState
    data object NoCode : ScanState
    data class Failed(val message: String) : ScanState
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SnackListViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val verdict = MutableStateFlow<SnackVerdict?>(null)
    val results = combine(query.debounce(200), verdict) { q, v -> q to v }
        .flatMapLatest { (q, v) -> c.snacks.search(q, v) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    var scan by mutableStateOf<ScanState?>(null)

    /** 拍条码照片 → 在识别进程中解码 → 查本地记录 */
    fun scanPhoto(file: File) {
        scan = ScanState.Scanning
        viewModelScope.launch {
            val r = c.ocrClient.scanBarcode(file)
            file.delete()
            scan = when {
                r == null -> ScanState.Failed("无法启动识别服务，请手动输入条码")
                r.isFailure -> ScanState.Failed("识别失败，请对准条码重拍或手动输入")
                r.getOrThrow().isEmpty() -> ScanState.NoCode
                else -> lookup(r.getOrThrow().first())
            }
        }
    }

    fun lookupManual(code: String) {
        viewModelScope.launch { scan = lookup(code.trim()) }
    }

    private suspend fun lookup(code: String): ScanState.Found {
        val local = c.snacks.byBarcode(code)
        if (local.isNotEmpty()) return ScanState.Found(code, local)
        // 本地没记录过：联网查一下是什么商品（可在设置关闭）
        if (!c.settings.settings.first().onlineBarcode) return ScanState.Found(code, local)
        scan = ScanState.Scanning
        val online = when (val r = c.barcodeLookup.lookup(code)) {
            is com.shiyi.app.net.BarcodeLookup.Result.Found -> listOf(r.info.brand, r.info.name, r.info.spec).filter { it.isNotBlank() }.joinToString(" · ")
            else -> null
        }
        return ScanState.Found(code, local, online)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SnackListScreen(c: AppContainer, nav: NavHostController) {
    val vm = appViewModel { SnackListViewModel(c) }
    val q by vm.query.collectAsState()
    val verdict by vm.verdict.collectAsState()
    val list by vm.results.collectAsState()
    var manualCode by remember { mutableStateOf<String?>(null) }
    val takePhoto = rememberTakePhoto { f -> if (f != null) vm.scanPhoto(f) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column {
                Text("零食", style = MaterialTheme.typography.headlineSmall)
                Text("超市里看到想买的零食，先扫一下条码，看看以前吃过没有。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = takePhoto, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("拍条码查询")
                    }
                    OutlinedButton(onClick = { nav.navigate(Routes.newSnack()) }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("记一款零食")
                    }
                }
                TextButton(onClick = { manualCode = "" }) { Text("手动输入条码查询") }
                OutlinedTextField(
                    q, { vm.query.value = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("搜名称、品牌、口味、在哪买的") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Outlined.Close, "清空") } },
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(verdict == null, { vm.verdict.value = null }, { Text("全部") })
                    listOf(SnackVerdict.AVOID, SnackVerdict.BUY_AGAIN, SnackVerdict.OK, SnackVerdict.NONE).forEach { v ->
                        FilterChip(verdict == v, { vm.verdict.value = if (verdict == v) null else v }, { Text(v.label) })
                    }
                }
            }
        }
        val l = list
        if (l != null && l.isEmpty()) {
            item(span = { GridItemSpan(2) }) {
                EmptyHint(if (q.isBlank() && verdict == null) "还没有记录零食。吃到难吃的，记得拍下包装记一笔。" else "没有找到")
            }
        }
        items(l.orEmpty(), key = { it.snack.id }) { s -> SnackCard(c, s) { nav.navigate(Routes.snack(s.snack.id)) } }
    }

    // 扫码结果
    when (val st = vm.scan) {
        null -> {}
        ScanState.Scanning -> AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 16.dp)); Text("正在识别条码…")
            }
        })
        ScanState.NoCode -> AlertDialog(
            onDismissRequest = { vm.scan = null },
            title = { Text("没识别到条码") },
            text = { Text("请让条码充满画面、保持清晰后重拍，或手动输入条码下方的数字。") },
            confirmButton = { TextButton(onClick = { vm.scan = null; takePhoto() }) { Text("重拍") } },
            dismissButton = { TextButton(onClick = { vm.scan = null; manualCode = "" }) { Text("手动输入") } },
        )
        is ScanState.Failed -> AlertDialog(
            onDismissRequest = { vm.scan = null },
            title = { Text("识别失败") }, text = { Text(st.message) },
            confirmButton = { TextButton(onClick = { vm.scan = null; manualCode = "" }) { Text("手动输入") } },
            dismissButton = { TextButton(onClick = { vm.scan = null }) { Text("关闭") } },
        )
        is ScanState.Found -> ScanResultDialog(
            c, st,
            onOpen = { id -> vm.scan = null; nav.navigate(Routes.snack(id)) },
            onAdd = { vm.scan = null; nav.navigate(Routes.newSnack(st.code)) },
            onDismiss = { vm.scan = null },
        )
    }

    manualCode?.let { code ->
        var text by remember { mutableStateOf(code) }
        AlertDialog(
            onDismissRequest = { manualCode = null },
            title = { Text("输入条码") },
            text = {
                OutlinedTextField(text, { t -> text = t.filter { it.isLetterOrDigit() }.take(32) }, singleLine = true,
                    label = { Text("包装上条码下方的数字") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            },
            confirmButton = { TextButton(enabled = text.length >= 6, onClick = { manualCode = null; vm.lookupManual(text) }) { Text("查询") } },
            dismissButton = { TextButton(onClick = { manualCode = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ScanResultDialog(c: AppContainer, st: ScanState.Found, onOpen: (String) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val sc = LocalSemantic.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    st.matches.isEmpty() -> "没吃过这款"
                    st.matches.any { it.snack.verdict == SnackVerdict.AVOID.name } -> "别买！你吃过，不好吃"
                    st.matches.any { it.snack.verdict == SnackVerdict.BUY_AGAIN.name } -> "吃过，好吃 👍"
                    else -> "你吃过这款"
                },
                color = if (st.matches.any { it.snack.verdict == SnackVerdict.AVOID.name }) sc.avoidFg else MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                Text("条码 ${st.code}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                if (st.matches.isEmpty()) {
                    st.online?.let { Text("商品库查到：$it", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                    Text("你还没记录过这款。买来吃过之后记一下，下次扫码就能提醒自己。", modifier = Modifier.padding(top = 8.dp))
                }
                st.matches.forEach { m ->
                    Surface(onClick = { onOpen(m.snack.id) }, shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(c, m.cover, Modifier.size(56.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.snack.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(m.snack.brand, m.snack.flavor).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                                VerdictBadge(SnackVerdict.of(m.snack.verdict))
                                if (m.snack.note.isNotBlank()) Text(m.snack.note, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            RatingPill(m.snack.rating)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text(if (st.matches.isEmpty()) "记一下这款" else "另记一款（不同口味）") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun VerdictBadge(v: SnackVerdict, modifier: Modifier = Modifier) {
    if (v == SnackVerdict.NONE) return
    val sc = LocalSemantic.current
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (v) {
        SnackVerdict.AVOID -> sc.avoidBg to sc.avoidFg
        SnackVerdict.BUY_AGAIN -> sc.goodBg to sc.goodFg
        else -> cs.secondaryContainer to cs.onSecondaryContainer
    }
    Box(modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(v.label, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Cover(c: AppContainer, fileName: String?, modifier: Modifier) {
    val bmp = rememberThumb(fileName?.let { c.repo.imageFile(it) }, 400)
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Outlined.Cookie, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun SnackCard(c: AppContainer, s: SnackWithCover, onClick: () -> Unit) {
    val v = SnackVerdict.of(s.snack.verdict)
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column {
            Box {
                Cover(c, s.cover, Modifier.fillMaxWidth().aspectRatio(1f))
                if (v == SnackVerdict.AVOID) {
                    val sc = LocalSemantic.current
                    Text("别买", color = sc.avoidFg, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                            .background(sc.avoidBg).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            Column(Modifier.padding(10.dp)) {
                Text(s.snack.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOf(s.snack.brand, s.snack.flavor).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VerdictBadge(v, Modifier.weight(1f, fill = false))
                    Spacer(Modifier.weight(1f))
                    RatingPill(s.snack.rating)
                }
            }
        }
    }
}
