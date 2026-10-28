package com.shiyi.app.ui.edit

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shiyi.app.AppContainer
import com.shiyi.app.data.FoodItemEntity
import com.shiyi.app.data.LinkDraft
import com.shiyi.app.domain.ItemStatus
import com.shiyi.app.domain.Money
import com.shiyi.app.domain.Platform
import com.shiyi.app.domain.ReasonTag
import com.shiyi.app.domain.RepurchaseIntent
import com.shiyi.app.domain.Scenario
import com.shiyi.app.domain.Times
import com.shiyi.app.ui.Routes
import com.shiyi.app.ui.appViewModel
import com.shiyi.app.ui.components.AvoidBadge
import com.shiyi.app.ui.components.ChipChoices
import com.shiyi.app.ui.components.GroupedTagPicker
import com.shiyi.app.domain.Classifier
import com.shiyi.app.domain.Verdict
import com.shiyi.app.ui.components.DateTimeField
import com.shiyi.app.ui.components.FullImageDialog
import com.shiyi.app.ui.components.GoodBadge
import com.shiyi.app.ui.components.ImageThumb
import com.shiyi.app.ui.components.RatingPicker
import com.shiyi.app.ui.components.SectionTitle
import com.shiyi.app.ui.theme.LocalSemantic
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordEditScreen(
    c: AppContainer, nav: NavHostController,
    recordId: String?, scenario: String?, fromImport: Boolean,
) {
    val vm = appViewModel { RecordEditViewModel(c, recordId, scenario, fromImport) }
    val sameDish by vm.sameDish.collectAsState()
    val shopHistory by vm.shopHistory.collectAsState()
    val shopSuggest by vm.shopSuggestions.collectAsState()
    var confirmLeave by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(vm.savedId) {
        val id = vm.savedId ?: return@LaunchedEffect
        if (vm.isNew) {
            nav.navigate(Routes.detail(id)) { popUpTo(nav.currentDestination!!.id) { inclusive = true } }
        } else nav.popBackStack()
    }

    val dirty = vm.shop.isNotBlank() || vm.paidText.isNotBlank() || vm.stagedImages.isNotEmpty()
    BackHandler(enabled = vm.isNew && dirty) { confirmLeave = true }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(if (!vm.isNew) "编辑记录" else if (fromImport) "确认识别结果" else "添加记录") },
                navigationIcon = {
                    IconButton(onClick = { if (vm.isNew && dirty) confirmLeave = true else nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = { TextButton(onClick = { vm.save() }, enabled = !vm.saving) { Text("保存") } },
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
            vm.importInfo?.let { InfoCard(it) }
            vm.sharedText?.let { st ->
                val urls = com.shiyi.app.importing.ImportStaging.extractUrls(st)
                if (urls.isNotEmpty()) {
                    Text("分享链接：" + urls.first(), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }

            SectionTitle("消费场景")
            ChipChoices(Scenario.entries, vm.scenario, { it.label }, vm::changeScenario)

            SectionTitle("平台")
            ChipChoices(Platform.entries, vm.platform, { it.label }, vm::changePlatform)

            SectionTitle("店铺 *")
            OutlinedTextField(
                vm.shop, { vm.shop = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("店铺名称") },
            )
            val ocrShops = vm.parsed?.shopCandidates.orEmpty().filter { it != vm.shop }
            val chips = (ocrShops.map { "识别：$it" to it } + shopSuggest.map { "历史：$it" to it }).distinctBy { it.second }.take(6)
            if (chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { (label, value) -> SuggestionChip(onClick = { vm.shop = value }, label = { Text(label, maxLines = 1) }) }
                }
            }
            val shopBad = shopHistory.filter { Classifier.of(it).verdict == Verdict.AVOID }
            if (shopBad.isNotEmpty()) {
                val sc = LocalSemantic.current
                Surface(color = sc.avoidBg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("这家店有你不再推荐的：", color = sc.avoidFg, style = MaterialTheme.typography.labelLarge)
                        shopBad.take(5).forEach { r ->
                            Text("· ${r.title.ifBlank { "（无标题）" }} — ${r.rating?.let { "$it 分" } ?: ""} ${Times.date(r.orderTime)}",
                                color = sc.avoidFg, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (shopHistory.isNotEmpty()) {
                val last = shopHistory.first()
                Text(
                    "这家店已有 ${shopHistory.size} 条记录，上次 ${Times.date(last.orderTime)} · ${Money.format(last.paidCents)} · ${last.rating?.let { "$it 分" } ?: "未评分"}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            SectionTitle("吃的什么（标题，可选）")
            OutlinedTextField(
                vm.title, { vm.title = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(if (vm.scenario == Scenario.GROUP_BUY) "如：双人套餐" else "如：黑椒牛肉盖浇饭") },
                supportingText = { Text("同一家店、同样标题的消费会自动归在一起，方便比价") },
            )
            val itemNames = vm.parsed?.itemNames.orEmpty().filter { it != vm.title }
            if (itemNames.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemNames.forEach { n -> SuggestionChip(onClick = { vm.title = n }, label = { Text("识别：$n", maxLines = 1) }) }
                }
            }
            if (sameDish.isNotEmpty()) {
                val last = sameDish.first()
                val v = Classifier.of(last).verdict
                val sc = LocalSemantic.current
                Surface(
                    color = if (v == Verdict.AVOID) sc.avoidBg else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(
                        "你吃过 ${sameDish.size} 次。上次 ${Times.date(last.orderTime)}，${Platform.of(last.platform)?.label ?: ""} " +
                            "实付 ${Money.format(last.paidCents)}，${last.rating?.let { "$it 分" } ?: "未评分"}" +
                            if (v == Verdict.AVOID) "，当时归为“不再推荐”" else if (v == Verdict.GOOD) "，值得回购" else "",
                        color = if (v == Verdict.AVOID) sc.avoidFg else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp),
                    )
                }
            }

            SectionTitle("本人实付金额 *")
            OutlinedTextField(
                vm.paidText, { t -> vm.paidText = t.filter { it.isDigit() || it == '.' }.take(10) },
                Modifier.fillMaxWidth(), singleLine = true, prefix = { Text("¥") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = { Text("只记录你自己实际支付的金额") },
            )
            val amounts = vm.parsed?.amountCandidates.orEmpty()
            if (amounts.isNotEmpty()) {
                Text("识别到的金额（点选）：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    amounts.forEach { a ->
                        val selected = Money.parseYuan(vm.paidText) == a.cents
                        FilterChip(selected, onClick = { vm.paidText = Money.editable(a.cents) },
                            label = { Text((if (a.label.isNotBlank()) a.label + " " else "") + Money.format(a.cents)) })
                    }
                }
            }

            SectionTitle("日期")
            DateTimeField(
                when (vm.scenario) {
                    Scenario.DELIVERY -> "下单日期"
                    Scenario.GROUP_BUY -> "购买日期"
                    Scenario.DINE_IN -> "消费日期"
                },
                vm.orderTime, { it?.let { t -> vm.orderTime = t } },
                hint = if (vm.timeGuessed) "截图里没有时间，先按今天记录；补录旧订单时点一下日期改掉即可" else null,
            )
            if (vm.scenario == Scenario.GROUP_BUY) {
                Spacer(Modifier.height(10.dp))
                DateTimeField("实际到店日期（可空，未使用可不填）", vm.consumeTime, { vm.consumeTime = it }, clearable = true)
            }

            SectionTitle("整体评分（1～10，可跳过）")
            RatingPicker(vm.rating) { vm.rating = it }

            SectionTitle("评价原因（可选）")
            val tags = ReasonTag.entries.filter { vm.scenario == Scenario.DELIVERY || !it.delivery }
            GroupedTagPicker(
                rating = vm.rating,
                positive = tags.filter { it.positive && !it.delivery },
                negative = tags.filter { !it.positive && !it.delivery },
                extra = "🛵 配送问题（不算菜品的错）" to tags.filter { it.delivery },
                selected = vm.reasons, label = { it.label }, onToggle = vm::toggleReason,
            )

            SectionTitle("归类")
            val auto = Classifier.auto(vm.rating, vm.reasons)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(vm.intent == null, { vm.intent = null }, { Text("按评分自动（${auto.verdict.label}）") })
                RepurchaseIntent.entries.forEach { i ->
                    FilterChip(vm.intent == i, onClick = { vm.intent = if (vm.intent == i) null else i }, label = { Text(i.label) })
                }
            }
            Text(
                "8～10 分自动算“值得回购”，1～4 分自动算“不再推荐”，不用额外确认。" +
                    if (auto.verdict == Verdict.NEUTRAL && (vm.rating ?: 9) <= 4) "\n" + auto.reason else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
            )

            SectionTitle("备注（可选）")
            OutlinedTextField(vm.note, { vm.note = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 6)

            SectionTitle("搜索关键词（可选）")
            OutlinedTextField(
                vm.keywords, { vm.keywords = it }, Modifier.fillMaxWidth(), maxLines = 3,
                supportingText = { Text("例如订单里的菜名，仅用于搜索，不参与评分和价格比较") },
            )

            if (vm.stagedImages.isNotEmpty() || vm.existingImages.isNotEmpty()) {
                SectionTitle("图片")
                if (vm.stagedImages.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("保留截图到 App", style = MaterialTheme.typography.bodyLarge)
                            Text("默认不保留；保留的图片存于 App 私有目录，不会改动相册原图",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(vm.keepImages, { vm.keepImages = it })
                    }
                    Spacer(Modifier.height(8.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.existingImages.forEach { img ->
                        val f = c.repo.imageFile(img.fileName)
                        RemovableThumb(f, onOpen = { preview = f }) { vm.removeExistingImage(img) }
                    }
                    vm.stagedImages.forEach { f -> RemovableThumb(f, onOpen = { preview = f }) { vm.removeStaged(f) } }
                }
            }

            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }
            Button(
                onClick = { vm.save() }, enabled = !vm.saving,
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp).height(50.dp),
            ) { Text(if (vm.saving) "保存中…" else "保存") }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("放弃这条记录？") },
            text = { Text("尚未保存的内容和临时截图将被清除。") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; nav.popBackStack() }) { Text("放弃") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("继续编辑") } },
        )
    }

    vm.duplicate?.let { d ->
        AlertDialog(
            onDismissRequest = { vm.duplicate = null },
            icon = { Icon(Icons.Outlined.WarningAmber, null) },
            title = { Text("可能是重复记录") },
            text = { Text("已有一条相似记录：\n${d.shopName} · ${Money.format(d.paidCents)} · ${Times.dateTime(d.orderTime)}") },
            confirmButton = { TextButton(onClick = { vm.duplicate = null; vm.save(ignoreDuplicate = true) }) { Text("仍然保存") } },
            dismissButton = { TextButton(onClick = { vm.duplicate = null }) { Text("取消") } },
        )
    }

    preview?.let { FullImageDialog(it) { preview = null } }
}

@Composable
private fun InfoCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(Modifier.padding(12.dp)) {
            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(text, color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RemovableThumb(f: File, onOpen: () -> Unit, onRemove: () -> Unit) {
    Box {
        ImageThumb(f, 92.dp, onOpen)
        Surface(
            shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp), onClick = onRemove,
        ) { Icon(Icons.Outlined.Close, "移除", Modifier.padding(4.dp)) }
    }
}

