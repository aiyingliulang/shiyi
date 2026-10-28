package com.shiyi.app.ui.records

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.shiyi.app.AppContainer
import com.shiyi.app.data.FoodItemEntity
import com.shiyi.app.data.RecordEntity
import com.shiyi.app.data.SearchFilter
import com.shiyi.app.domain.ItemStatus
import com.shiyi.app.domain.Money
import com.shiyi.app.domain.Platform
import com.shiyi.app.domain.RatingBand
import com.shiyi.app.domain.Scenario
import com.shiyi.app.ui.Routes
import com.shiyi.app.ui.appViewModel
import com.shiyi.app.ui.components.AvoidBadge
import com.shiyi.app.ui.components.EmptyHint
import com.shiyi.app.ui.components.GoodBadge
import com.shiyi.app.ui.components.RecordRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class RecordListViewModel(c: AppContainer, initialQuery: String, initialVerdict: com.shiyi.app.domain.Verdict?) : ViewModel() {
    val filter = MutableStateFlow(SearchFilter(query = initialQuery, verdict = initialVerdict))

    val results: StateFlow<List<RecordEntity>?> = filter.debounce(200)
        .flatMapLatest { c.repo.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val snacks = filter.map { it.query.trim() }.debounce(200)
        .flatMapLatest { if (it.isEmpty()) flowOf(emptyList()) else c.snacks.search(it, null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun update(f: (SearchFilter) -> SearchFilter) { filter.value = f(filter.value) }
}

private val sorts = listOf("时间 新→旧", "时间 旧→新", "评分 高→低", "金额 高→低", "金额 低→高")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordListScreen(c: AppContainer, nav: NavHostController, initialQuery: String, initialVerdict: String? = null) {
    val vm = appViewModel { RecordListViewModel(c, initialQuery, com.shiyi.app.domain.Verdict.entries.firstOrNull { it.name == initialVerdict }) }
    val f by vm.filter.collectAsState()
    val results by vm.results.collectAsState()
    val snackHits by vm.snacks.collectAsState()
    var showFilter by rememberSaveable { mutableStateOf(false) }
    var minText by rememberSaveable { mutableStateOf(f.minCents?.let(Money::editable) ?: "") }
    var maxText by rememberSaveable { mutableStateOf(f.maxCents?.let(Money::editable) ?: "") }

    val activeCount = listOf(f.platform != null, f.scenario != null, f.ratingRange != null || f.unratedOnly,
        f.minCents != null || f.maxCents != null, f.sort != 0).count { it }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        f.query, { q -> vm.update { it.copy(query = q) } }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("店铺、商品、套餐、备注") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = {
                            if (f.query.isNotEmpty()) IconButton(onClick = { vm.update { it.copy(query = "") } }) { Icon(Icons.Outlined.Close, "清空") }
                        },
                        shape = RoundedCornerShape(16.dp),
                    )
                    IconButton(onClick = { showFilter = !showFilter }) {
                        BadgedBox(badge = { if (activeCount > 0) Badge { Text("$activeCount") } }) {
                            Icon(Icons.Outlined.FilterList, "筛选")
                        }
                    }
                }
            }
            item {
                // 原“清单”：按评分自动归类，不需要手动确认
                val sem = com.shiyi.app.ui.theme.LocalSemantic.current
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(f.verdict == null, { vm.update { it.copy(verdict = null) } }, { Text("全部") })
                    listOf(
                        com.shiyi.app.domain.Verdict.GOOD to "👍 值得回购",
                        com.shiyi.app.domain.Verdict.AVOID to "🚫 不再推荐",
                        com.shiyi.app.domain.Verdict.NEUTRAL to "一般",
                        com.shiyi.app.domain.Verdict.UNRATED to "未评分",
                    ).forEach { (v, label) ->
                        FilterChip(
                            f.verdict == v, { vm.update { it.copy(verdict = if (it.verdict == v) null else v) } }, { Text(label) },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = when (v) {
                                    com.shiyi.app.domain.Verdict.GOOD -> sem.goodBg
                                    com.shiyi.app.domain.Verdict.AVOID -> sem.avoidBg
                                    else -> MaterialTheme.colorScheme.secondaryContainer
                                },
                            ),
                        )
                    }
                }
                if (f.verdict == com.shiyi.app.domain.Verdict.GOOD || f.verdict == com.shiyi.app.domain.Verdict.AVOID) {
                    Text(
                        if (f.verdict == com.shiyi.app.domain.Verdict.GOOD) "8～10 分自动归入，也包括你手动标为“值得回购”的"
                        else "1～4 分自动归入（低分只因配送问题的除外），也包括你手动标为“不再推荐”的",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            item {
                AnimatedVisibility(showFilter) {
                    Column(Modifier.padding(top = 4.dp)) {
                        Label("平台")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Platform.entries.forEach { p ->
                                FilterChip(f.platform == p.name, { vm.update { it.copy(platform = if (it.platform == p.name) null else p.name) } }, { Text(p.label) })
                            }
                        }
                        Label("场景")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Scenario.entries.forEach { s ->
                                FilterChip(f.scenario == s.name, { vm.update { it.copy(scenario = if (it.scenario == s.name) null else s.name) } }, { Text(s.label) })
                            }
                        }
                        Label("评分")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RatingBand.filters.forEach { (label, range) ->
                                val sel = if (range == null) f.unratedOnly else f.ratingRange == range
                                FilterChip(sel, {
                                    vm.update {
                                        if (range == null) it.copy(unratedOnly = !it.unratedOnly, ratingRange = null)
                                        else it.copy(ratingRange = if (it.ratingRange == range) null else range, unratedOnly = false)
                                    }
                                }, { Text(label) })
                            }
                        }
                        Label("实付金额区间（元）")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(minText, { t -> minText = t.filter { it.isDigit() || it == '.' }; vm.update { it.copy(minCents = Money.parseYuan(minText)) } },
                                Modifier.weight(1f), singleLine = true, placeholder = { Text("最低") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            Text("  —  ")
                            OutlinedTextField(maxText, { t -> maxText = t.filter { it.isDigit() || it == '.' }; vm.update { it.copy(maxCents = Money.parseYuan(maxText)) } },
                                Modifier.weight(1f), singleLine = true, placeholder = { Text("最高") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                        Label("排序")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            sorts.forEachIndexed { i, s -> FilterChip(f.sort == i, { vm.update { it.copy(sort = i) } }, { Text(s) }) }
                        }
                        if (activeCount > 0) {
                            TextButton(onClick = {
                                minText = ""; maxText = ""
                                vm.update { SearchFilter(query = it.query) }
                            }) { Text("清除筛选") }
                        }
                    }
                }
            }
            if (snackHits.isNotEmpty()) {
                item { Label("匹配的零食") }
                items(snackHits.take(6), key = { "s" + it.snack.id }) { sn ->
                    val v = com.shiyi.app.domain.SnackVerdict.of(sn.snack.verdict)
                    Surface(onClick = { nav.navigate(Routes.snack(sn.snack.id)) }, shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(sn.snack.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(sn.snack.brand, sn.snack.flavor).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            }
                            com.shiyi.app.ui.snack.VerdictBadge(v)
                        }
                    }
                }
            }
            val list = results
            if (list != null) {
                item {
                    Text("共 ${list.size} 条 · 合计实付 ${Money.format(list.sumOf { it.paidCents })}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                }
                if (list.isEmpty()) item { EmptyHint(if (f.query.isBlank() && activeCount == 0) "还没有记录" else "没有符合条件的记录") }
            }
            items(list.orEmpty(), key = { it.id }) { r -> RecordRow(r) { nav.navigate(Routes.detail(r.id)) } }
        }
        FloatingActionButton(
            onClick = { nav.navigate(Routes.newRecord(Scenario.DINE_IN.name)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Outlined.Add, "添加记录") }
    }
}

@Composable
private fun Label(t: String) {
    Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}
