package com.shiyi.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shiyi.app.AppContainer
import com.shiyi.app.data.RecordEntity
import com.shiyi.app.domain.ItemStatus
import com.shiyi.app.domain.Money
import com.shiyi.app.domain.Platform
import com.shiyi.app.domain.RatingBand
import com.shiyi.app.domain.ReasonTag
import com.shiyi.app.domain.RepurchaseIntent
import com.shiyi.app.domain.Scenario
import com.shiyi.app.domain.Times
import com.shiyi.app.ui.Routes
import com.shiyi.app.ui.components.AvoidBadge
import com.shiyi.app.ui.components.FullImageDialog
import com.shiyi.app.ui.components.GoodBadge
import com.shiyi.app.ui.components.ImageThumb
import com.shiyi.app.ui.components.RatingPill
import com.shiyi.app.ui.components.RecordRow
import com.shiyi.app.ui.components.SectionTitle
import com.shiyi.app.ui.theme.LocalSemantic
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordDetailScreen(c: AppContainer, nav: NavHostController, id: String) {
    val repo = c.repo
    val record by remember(id) { repo.observeRecord(id) }.collectAsState(initial = null)
    val images by remember(id) { repo.imagesForRecord(id) }.collectAsState(initial = emptyList())
    val shop = record?.shopName.orEmpty()
    val sameShop by remember(shop) { repo.sameShop(shop, id) }.collectAsState(initial = emptyList())
    val dishTitle = record?.title.orEmpty()
    val sameDish by remember(shop, dishTitle) { repo.sameDish(shop, dishTitle, id) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<File?>(null) }
    var deleted by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("消费详情") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.editRecord(id)) }, enabled = record != null) { Icon(Icons.Outlined.Edit, "编辑") }
                    IconButton(onClick = { confirmDelete = true }, enabled = record != null) { Icon(Icons.Outlined.Delete, "删除") }
                },
            )
        },
    ) { pad ->
        val r = record
        if (r == null) {
            if (!deleted) Text("记录不存在或已删除", Modifier.padding(pad).padding(24.dp))
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(r.shopName, style = MaterialTheme.typography.headlineSmall)
            if (r.title.isNotBlank()) Text(r.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Money.format(r.paidCents), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("  本人实付", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.weight(1f))
                RatingPill(r.rating)
            }
            if (r.rating != null) Text(RatingBand.label(r.rating), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.align(Alignment.End))

            val cls = com.shiyi.app.domain.Classifier.of(r)
            val sem = LocalSemantic.current
            val (vbg, vfg) = when (cls.verdict) {
                com.shiyi.app.domain.Verdict.GOOD -> sem.goodBg to sem.goodFg
                com.shiyi.app.domain.Verdict.AVOID -> sem.avoidBg to sem.avoidFg
                else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
            }
            Surface(color = vbg, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(cls.verdict.label, color = vfg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (cls.manual) "你手动归的类" else "按评分自动归类：" + cls.reason, color = vfg, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(r.repurchaseIntent == null, { scope.launch { repo.setRecordIntent(r.id, null) } }, { Text("自动") })
                        RepurchaseIntent.entries.forEach { i ->
                            FilterChip(r.repurchaseIntent == i.name, {
                                scope.launch { repo.setRecordIntent(r.id, if (r.repurchaseIntent == i.name) null else i.name) }
                            }, { Text(i.label) })
                        }
                    }
                }
            }

            SectionTitle("基本信息")
            InfoLine("平台", Platform.of(r.platform)?.label ?: r.platform)
            InfoLine("场景", Scenario.of(r.scenario)?.label ?: r.scenario)
            val sc = Scenario.of(r.scenario)
            InfoLine(when (sc) { Scenario.GROUP_BUY -> "购买时间"; Scenario.DINE_IN -> "消费时间"; else -> "下单时间" }, Times.dateTime(r.orderTime))
            if (sc == Scenario.GROUP_BUY) InfoLine("实际消费", r.consumeTime?.let(Times::dateTime) ?: "未记录")

            val tags = ReasonTag.parse(r.reasonTags)
            if (tags.isNotEmpty() || r.note.isNotBlank()) {
                SectionTitle("评价")
                if (tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { t ->
                            Surface(shape = RoundedCornerShape(8.dp), color = if (t.positive) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Text(t.label + if (t.delivery) "·配送" else "", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }

            if (r.keywords.isNotBlank()) {
                SectionTitle("搜索关键词")
                Text(r.keywords, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (images.isNotEmpty()) {
                SectionTitle("保留的图片")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.forEach { img -> val f = repo.imageFile(img.fileName); ImageThumb(f, 100.dp) { preview = f } }
                }
            }

            if (sameDish.isNotEmpty()) {
                SectionTitle("同一道菜的历史（同店同标题，含其他平台）")
                val prev = sameDish.firstOrNull { it.orderTime < r.orderTime }
                if (prev != null) {
                    val d = r.paidCents - prev.paidCents
                    Text(
                        "比上一次（${Times.date(prev.orderTime)} · ${Platform.of(prev.platform)?.label ?: ""}）" + when {
                            d > 0 -> "贵 ${Money.format(d)}"
                            d < 0 -> "便宜 ${Money.format(-d)}"
                            else -> "价格一样"
                        },
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                val all = sameDish.map { it.paidCents } + r.paidCents
                Text("共 ${all.size} 次 · 最低 ${Money.format(all.min())} · 最高 ${Money.format(all.max())}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 6.dp))
                RelatedList(sameDish) { nav.navigate(Routes.detail(it)) }
            }
            val others = sameShop.filter { s -> sameDish.none { it.id == s.id } }
            if (others.isNotEmpty()) {
                SectionTitle("这家店的其他记录")
                RelatedList(others.take(10)) { nav.navigate(Routes.detail(it)) }
            }
            Text(
                "创建于 ${Times.dateTime(r.createdAt)} · 更新于 ${Times.dateTime(r.updatedAt)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记录？") },
            text = { Text("将同时删除这条记录保留的图片。美食档案本身不会被删除。此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleted = true
                    scope.launch { repo.deleteRecord(id); nav.popBackStack() }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
    preview?.let { FullImageDialog(it) { preview = null } }
}

@Composable
private fun InfoLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, Modifier.width(84.dp), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
        Text(v, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RelatedList(list: List<RecordEntity>, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { r -> RecordRow(r) { onOpen(r.id) } }
    }
}
