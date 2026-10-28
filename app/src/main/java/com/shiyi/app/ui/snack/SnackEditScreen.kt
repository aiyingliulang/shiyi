package com.shiyi.app.ui.snack

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.shiyi.app.AppContainer
import com.shiyi.app.data.SnackEntity
import com.shiyi.app.data.SnackImageEntity
import com.shiyi.app.data.SnackWithCover
import com.shiyi.app.domain.Money
import com.shiyi.app.domain.SnackTag
import com.shiyi.app.domain.SnackVerdict
import com.shiyi.app.ui.Routes
import com.shiyi.app.ui.appViewModel
import com.shiyi.app.ui.components.DateTimeField
import com.shiyi.app.ui.components.FullImageDialog
import com.shiyi.app.ui.components.ImageThumb
import com.shiyi.app.ui.components.RatingPicker
import com.shiyi.app.ui.components.SectionTitle
import com.shiyi.app.ui.components.rememberTakePhoto
import com.shiyi.app.ui.theme.LocalSemantic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class SnackEditViewModel(private val c: AppContainer, private val snackId: String?, initialBarcode: String?) : ViewModel() {
    val isNew = snackId == null
    private val id = snackId ?: UUID.randomUUID().toString()

    var loaded by mutableStateOf(isNew)
        private set
    var name by mutableStateOf("")
    var brand by mutableStateOf("")
    var flavor by mutableStateOf("")
    var barcode by mutableStateOf(initialBarcode.orEmpty())
    var store by mutableStateOf("")
    var priceText by mutableStateOf("")
    var purchaseDate by mutableStateOf(System.currentTimeMillis())
    var rating by mutableStateOf<Int?>(null)
        private set
    var verdict by mutableStateOf(SnackVerdict.NONE)
        private set
    private var verdictTouched = false
    val tags = mutableStateListOf<SnackTag>()
    var note by mutableStateOf("")
    private var createdAt = System.currentTimeMillis()

    val existingImages = mutableStateListOf<SnackImageEntity>()
    private val removedImageIds = mutableListOf<String>()
    val newImages = mutableStateListOf<File>()

    var stores by mutableStateOf<List<String>>(emptyList())
        private set
    var brandSuggest by mutableStateOf<List<String>>(emptyList())
        private set
    /** 同一条码已有的其他记录 */
    var sameBarcode by mutableStateOf<List<SnackWithCover>>(emptyList())
        private set

    var scanning by mutableStateOf(false)
        private set
    /** 联网查条码的结果提示 */
    var lookupInfo by mutableStateOf<String?>(null)
        private set
    var lookingUp by mutableStateOf(false)
        private set
    /** 包装照片上识别到的文字，点一下填入名称 */
    var packageTexts by mutableStateOf<List<String>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
        private set
    var savedId by mutableStateOf<String?>(null)
        private set
    private var saved = false

    init {
        viewModelScope.launch {
            stores = runCatching { c.snacks.recentStores() }.getOrDefault(emptyList())
            if (snackId != null) {
                val s = c.snacks.get(snackId)
                if (s == null) error = "记录不存在" else {
                    name = s.name; brand = s.brand; flavor = s.flavor; barcode = s.barcode; store = s.store
                    priceText = Money.editable(s.priceCents); purchaseDate = s.purchaseDate
                    rating = s.rating; verdict = SnackVerdict.of(s.verdict); verdictTouched = true
                    tags.clear(); tags.addAll(SnackTag.parse(s.tags)); note = s.note; createdAt = s.createdAt
                    existingImages.clear(); existingImages.addAll(c.snacks.imagesOnce(snackId))
                }
                loaded = true
            }
            refreshBarcode()
            if (snackId == null && barcode.isNotBlank()) lookupOnline(barcode)
        }
    }

    /** 联网按条码查名称/品牌/规格，只填空着的字段 */
    fun lookupOnline(code: String) {
        viewModelScope.launch {
            if (!c.settings.settings.first().onlineBarcode) return@launch
            lookingUp = true
            lookupInfo = "正在联网查询商品信息…"
            lookupInfo = when (val r = c.barcodeLookup.lookup(code)) {
                is com.shiyi.app.net.BarcodeLookup.Result.Found -> {
                    val i = r.info
                    if (name.isBlank()) name = i.name
                    if (brand.isBlank()) brand = i.brand
                    if (flavor.isBlank()) flavor = i.spec
                    "已从商品库自动填入（${i.source}），请核对"
                }
                com.shiyi.app.net.BarcodeLookup.Result.NotFound -> "商品库里没有这个条码，手动填一下；下次扫码就能认出来"
                is com.shiyi.app.net.BarcodeLookup.Result.Failed -> "联网查询失败：${r.message}"
            }
            lookingUp = false
        }
    }

    fun changeRating(r: Int?) {
        rating = r
        if (!verdictTouched) verdict = SnackVerdict.suggest(r)
    }

    fun changeVerdict(v: SnackVerdict) { verdict = v; verdictTouched = true }

    fun toggleTag(t: SnackTag) { if (t in tags) tags.remove(t) else tags.add(t) }

    fun refreshBarcode() {
        viewModelScope.launch {
            sameBarcode = runCatching { c.snacks.byBarcode(barcode) }.getOrDefault(emptyList()).filter { it.snack.id != id }
        }
    }

    fun refreshBrand() {
        viewModelScope.launch { brandSuggest = runCatching { c.snacks.brandSuggestions(brand) }.getOrDefault(emptyList()).filter { it != brand.trim() } }
    }

    fun addPhoto(f: File) {
        newImages.add(f)
        readPackageText(f)
    }

    private val specRegex = Regex("(\\d+(?:\\.\\d+)?\\s*(?:g|克|kg|千克|ml|毫升|L|升))", RegexOption.IGNORE_CASE)

    /** 离线识别包装上的文字（在独立进程中），给出可点选的候选 */
    private fun readPackageText(f: File) {
        viewModelScope.launch {
            val lines = c.ocrClient.recognizeAll(listOf(f)) {}?.firstOrNull()?.getOrNull() ?: return@launch
            val skip = listOf("配料", "净含量", "生产", "保质", "贮存", "条码", "营养", "许可证", "地址", "电话", "执行标准", "日期")
            packageTexts = (packageTexts + lines.map { it.trim() }
                .filter { t -> t.length in 2..20 && t.count { ch -> ch.code in 0x4e00..0x9fff } >= 2 && skip.none { t.contains(it) } })
                .distinct().take(10)
            // “净含量 70g”之类直接给出规格
            lines.firstNotNullOfOrNull { specRegex.find(it)?.value }?.let { if (flavor.isBlank()) flavor = it.replace(" ", "") }
        }
    }

    fun addPicked(uris: List<android.net.Uri>) {
        viewModelScope.launch {
            val (files, _) = c.staging.stageUris(uris)
            files.forEach { addPhoto(it) }
        }
    }

    fun removeNew(f: File) { newImages.remove(f); f.delete() }
    fun removeExisting(img: SnackImageEntity) { existingImages.remove(img); removedImageIds += img.id }

    /** 用拍下的照片识别条码 */
    fun scanBarcode(f: File) {
        scanning = true
        viewModelScope.launch {
            val r = c.ocrClient.scanBarcode(f)
            f.delete()
            scanning = false
            val code = r?.getOrNull()?.firstOrNull()
            if (code == null) error = "没识别到条码，可以重拍或直接输入数字"
            else { barcode = code; error = null; refreshBarcode(); lookupOnline(code) }
        }
    }

    fun save() {
        if (saving) return
        val n = name.trim()
        val price = priceText.trim().takeIf { it.isNotEmpty() }?.let { Money.parseYuan(it) ?: run { error = "价格格式不正确"; return } }
        if (n.isEmpty()) { error = "请填写零食名称"; return }
        error = null
        saving = true
        viewModelScope.launch {
            try {
                c.snacks.save(
                    SnackEntity(
                        id = id, name = n, brand = brand.trim(), flavor = flavor.trim(), barcode = barcode.trim(),
                        store = store.trim(), priceCents = price, purchaseDate = purchaseDate, rating = rating,
                        verdict = verdict.name, tags = SnackTag.encode(tags), note = note.trim(),
                        createdAt = createdAt, updatedAt = System.currentTimeMillis(),
                    ),
                    newImages.toList(), removedImageIds.toList(),
                )
                saved = true
                newImages.forEach { it.delete() }
                savedId = id
            } catch (t: Throwable) {
                error = "保存失败：" + (t.message ?: t.javaClass.simpleName)
            } finally {
                saving = false
            }
        }
    }

    override fun onCleared() {
        if (!saved) newImages.forEach { it.delete() }
        super.onCleared()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SnackEditScreen(c: AppContainer, nav: NavHostController, snackId: String?, barcode: String?) {
    val vm = appViewModel { SnackEditViewModel(c, snackId, barcode) }
    var preview by remember { mutableStateOf<File?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val takePackage = rememberTakePhoto { f -> f?.let(vm::addPhoto) }
    val takeBarcode = rememberTakePhoto { f -> f?.let(vm::scanBarcode) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { vm.addPicked(it) }

    LaunchedEffect(vm.savedId) {
        val id = vm.savedId ?: return@LaunchedEffect
        if (vm.isNew) nav.navigate(Routes.snack(id)) { popUpTo(nav.currentDestination!!.id) { inclusive = true } }
        else nav.popBackStack()
    }
    LaunchedEffect(vm.brand) { delay(250); vm.refreshBrand() }
    LaunchedEffect(vm.barcode) { delay(400); vm.refreshBarcode() }

    val dirty = vm.isNew && (vm.name.isNotBlank() || vm.newImages.isNotEmpty())
    BackHandler(enabled = dirty) { confirmLeave = true }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "记一款零食" else "编辑零食") },
                navigationIcon = {
                    IconButton(onClick = { if (dirty) confirmLeave = true else nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                },
                actions = { TextButton(onClick = vm::save, enabled = !vm.saving) { Text("保存") } },
            )
        },
    ) { pad ->
        if (!vm.loaded) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).navigationBarsPadding(),
        ) {
            SectionTitle("包装照片（建议拍正面，下次一眼认出来）")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = takePackage) { Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("拍照") }
                OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("相册")
                }
            }
            if (vm.existingImages.isNotEmpty() || vm.newImages.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.existingImages.forEach { img ->
                        val f = c.repo.imageFile(img.fileName)
                        Removable(f, { preview = f }) { vm.removeExisting(img) }
                    }
                    vm.newImages.forEach { f -> Removable(f, { preview = f }) { vm.removeNew(f) } }
                }
            }

            SectionTitle("名称 *")
            OutlinedTextField(vm.name, { vm.name = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("如：番茄味薯片") })
            vm.lookupInfo?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
            }
            if (vm.packageTexts.isNotEmpty()) {
                Text("包装上的文字（点一下填入名称）：", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.packageTexts.forEach { t -> SuggestionChip(onClick = { vm.name = t }, label = { Text(t, maxLines = 1) }) }
                }
            }

            SectionTitle("品牌 / 口味规格（可选）")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vm.brand, { vm.brand = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("品牌") })
                OutlinedTextField(vm.flavor, { vm.flavor = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("口味/规格") })
            }
            if (vm.brandSuggest.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.brandSuggest.forEach { b -> SuggestionChip(onClick = { vm.brand = b }, label = { Text(b) }) }
            }

            SectionTitle("条码（可选，用于以后扫码查询）")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    vm.barcode, { t -> vm.barcode = t.filter { it.isLetterOrDigit() }.take(32) }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("条码下方的数字") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.width(8.dp))
                if (vm.scanning) CircularProgressIndicator(Modifier.size(28.dp))
                else IconButton(onClick = takeBarcode) { Icon(Icons.Outlined.QrCodeScanner, "拍条码") }
            }
            if (vm.sameBarcode.isNotEmpty()) {
                val sc = LocalSemantic.current
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("这个条码已经记录过：", style = MaterialTheme.typography.labelLarge)
                        vm.sameBarcode.forEach { m ->
                            val v = SnackVerdict.of(m.snack.verdict)
                            TextButton(onClick = { nav.navigate(Routes.snack(m.snack.id)) }) {
                                Text("${m.snack.name} · ${v.label}" + (m.snack.rating?.let { " · $it 分" } ?: ""),
                                    color = if (v == SnackVerdict.AVOID) sc.avoidFg else MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            SectionTitle("在哪买的 / 价格（可选）")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vm.store, { vm.store = it }, Modifier.weight(1.4f), singleLine = true, placeholder = { Text("超市/便利店") })
                OutlinedTextField(
                    vm.priceText, { t -> vm.priceText = t.filter { it.isDigit() || it == '.' }.take(9) }, Modifier.weight(1f), singleLine = true,
                    prefix = { Text("¥") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            val storeChips = vm.stores.filter { it != vm.store.trim() }.take(6)
            if (storeChips.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                storeChips.forEach { s -> SuggestionChip(onClick = { vm.store = s }, label = { Text(s) }) }
            }
            Spacer(Modifier.height(10.dp))
            DateTimeField("购买日期", vm.purchaseDate, { it?.let { t -> vm.purchaseDate = t } })

            SectionTitle("评分（1～10，可跳过）")
            RatingPicker(vm.rating, vm::changeRating)

            SectionTitle("结论")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(SnackVerdict.AVOID, SnackVerdict.OK, SnackVerdict.BUY_AGAIN).forEach { v ->
                    FilterChip(vm.verdict == v, { vm.changeVerdict(if (vm.verdict == v) SnackVerdict.NONE else v) }, { Text(v.label) })
                }
            }
            Text("结论会根据评分自动建议，也可以自己改。扫码时会醒目提示“别买”。",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)

            SectionTitle("原因（可选）")
            com.shiyi.app.ui.components.GroupedTagPicker(
                rating = vm.rating,
                positive = SnackTag.entries.filter { it.positive },
                negative = SnackTag.entries.filter { !it.positive },
                selected = vm.tags, label = { it.label }, onToggle = vm::toggleTag,
            )

            SectionTitle("备注（可选）")
            OutlinedTextField(vm.note, { vm.note = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                placeholder = { Text("如：甜到齁，包装上的图片和实物差很多") })

            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            Button(onClick = vm::save, enabled = !vm.saving, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp).height(50.dp)) {
                Text(if (vm.saving) "保存中…" else "保存")
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("放弃这条记录？") },
            text = { Text("尚未保存的内容和照片将被清除。") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; nav.popBackStack() }) { Text("放弃") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("继续编辑") } },
        )
    }
    preview?.let { FullImageDialog(it) { preview = null } }
}

@Composable
private fun Removable(f: File, onOpen: () -> Unit, onRemove: () -> Unit) {
    Box {
        ImageThumb(f, 96.dp, onOpen)
        Surface(
            shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp), onClick = onRemove,
        ) { Icon(Icons.Outlined.Close, "移除", Modifier.padding(4.dp)) }
    }
}
