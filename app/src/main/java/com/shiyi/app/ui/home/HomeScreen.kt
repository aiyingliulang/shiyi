package com.shiyi.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.DeliveryDining
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shiyi.app.AppContainer
import com.shiyi.app.data.HomeStats
import com.shiyi.app.domain.Money
import com.shiyi.app.domain.Scenario
import com.shiyi.app.ui.Routes
import com.shiyi.app.ui.components.EmptyHint
import com.shiyi.app.ui.components.RecordRow
import com.shiyi.app.ui.components.SectionTitle
import com.shiyi.app.ui.switchTab
import com.shiyi.app.ui.theme.LocalSemantic
import com.shiyi.app.data.AppSettings
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(c: AppContainer, nav: NavHostController) {
    val stats by c.repo.stats.collectAsState(initial = null)
    val recent by remember { c.repo.recent(8) }.collectAsState(initial = null)
    val avoidIds by remember { kotlinx.coroutines.flow.flowOf(emptyList<String>()) }.collectAsState(initial = emptyList())
    var q by rememberSaveable { mutableStateOf("") }
    val settings by c.settings.settings.collectAsState(initial = null)
    val snackTotal by remember { c.snacks.count() }.collectAsState(initial = 0)
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("食忆", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            OutlinedTextField(
                q, { q = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("搜索店铺、商品或套餐") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { nav.navigate(Routes.records(q.trim())) }),
            )
        }
        backupReminder(settings, (stats?.recordCount ?: 0) + snackTotal)?.let { text ->
            item {
                BackupReminder(
                    text,
                    onOpen = { nav.switchTab("settings") },
                    onLater = { scope.launch { c.settings.snoozeBackupReminder(System.currentTimeMillis() + REMIND_SNOOZE_MS) } },
                )
            }
        }
        item {
            SectionTitle("快速添加")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAdd("截图识别", Icons.Outlined.DocumentScanner, Modifier.weight(1f), primary = true) { nav.navigate(Routes.IMPORT) }
                QuickAdd("堂食", Icons.Outlined.Restaurant, Modifier.weight(1f)) { nav.navigate(Routes.newRecord(Scenario.DINE_IN.name)) }
                QuickAdd("外卖", Icons.Outlined.DeliveryDining, Modifier.weight(1f)) { nav.navigate(Routes.newRecord(Scenario.DELIVERY.name)) }
                QuickAdd("团购", Icons.Outlined.ConfirmationNumber, Modifier.weight(1f)) { nav.navigate(Routes.newRecord(Scenario.GROUP_BUY.name)) }
            }
        }
        item { StatsRow(stats, onGood = { nav.navigate(Routes.records(verdict = com.shiyi.app.domain.Verdict.GOOD)) },
            onAvoid = { nav.navigate(Routes.records(verdict = com.shiyi.app.domain.Verdict.AVOID)) }) }
        item {
            val snackCount by remember { c.snacks.count() }.collectAsState(initial = 0)
            val snackAvoid by remember { c.snacks.avoidCount() }.collectAsState(initial = 0)
            Surface(
                onClick = { nav.switchTab("snacks") }, shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Cookie, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("零食", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (snackCount == 0) "超市看到想买的，先扫码查一下吃没吃过"
                            else "已记录 $snackCount 款，其中 $snackAvoid 款“别再买”",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
        item {
            SectionTitle("最近消费", trailing = {
                TextButton(onClick = { nav.switchTab("records") }) { Text("全部") }
            })
        }
        val list = recent
        if (list != null && list.isEmpty()) {
            item { EmptyHint("还没有记录。截图识别或手动记一笔吧。") }
        }
        items(list.orEmpty(), key = { it.id }) { r ->
            RecordRow(r, r.id in avoidIds) { nav.navigate(Routes.detail(r.id)) }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun QuickAdd(label: String, icon: ImageVector, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, shape = RoundedCornerShape(16.dp),
        color = if (primary) cs.primary else cs.surfaceContainerLowest, modifier = modifier,
    ) {
        Column(Modifier.padding(vertical = 14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = if (primary) cs.onPrimary else cs.primary)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (primary) cs.onPrimary else cs.onSurface)
        }
    }
}

@Composable
private fun StatsRow(s: HomeStats?, onGood: () -> Unit, onAvoid: () -> Unit) {
    val sc = LocalSemantic.current
    Column(Modifier.padding(top = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("消费记录", s?.recordCount?.toString() ?: "…", Modifier.weight(1f))
            Stat(
                "本月实付", s?.let { Money.format(it.monthCents) } ?: "…", Modifier.weight(1f),
                sub = s?.takeIf { it.lastMonthCents > 0 }?.let { "上月 " + Money.format(it.lastMonthCents) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("值得回购", s?.repurchaseCount?.toString() ?: "…", Modifier.weight(1f), Icons.Outlined.ThumbUp, sc.goodBg, sc.goodFg, onGood)
            Stat("不再推荐", s?.avoidCount?.toString() ?: "…", Modifier.weight(1f), Icons.Outlined.Block, sc.avoidBg, sc.avoidFg, onAvoid)
        }
    }
}

@Composable
private fun Stat(
    label: String, value: String, modifier: Modifier,
    icon: ImageVector? = null, bg: Color? = null, fg: Color? = null, onClick: (() -> Unit)? = null,
    sub: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Icon(it, null, Modifier.size(16.dp), tint = fg ?: cs.onSurfaceVariant); Spacer(Modifier.size(4.dp)) }
                Text(label, style = MaterialTheme.typography.labelMedium, color = fg ?: cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = fg ?: cs.onSurface)
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.outline) }
        }
    }
    val shape = RoundedCornerShape(16.dp)
    if (onClick != null) Surface(onClick = onClick, shape = shape, color = bg ?: cs.surfaceContainerLowest, modifier = modifier) { content() }
    else Surface(shape = shape, color = bg ?: cs.surfaceContainerLowest, modifier = modifier) { content() }
}

/** 多久没备份就在首页提醒 */
private const val REMIND_AFTER_MS = 14L * 24 * 3600 * 1000
/** 点“稍后”后多久不再提醒 */
private const val REMIND_SNOOZE_MS = 7L * 24 * 3600 * 1000

/**
 * 首页的备份提醒：自动备份出错，或者有数据却很久没备份过。卸载、换机都会清空数据，这是唯一的兜底。
 * 返回 null 表示不需要提醒。
 */
private fun backupReminder(s: AppSettings?, itemCount: Int, now: Long = System.currentTimeMillis()): String? {
    if (s == null || itemCount < 3 || now < s.backupReminderSnoozeUntil) return null
    if (s.autoEnabled && s.autoError.isNotBlank()) return "自动备份失败：" + s.autoError.lineSequence().first()
    val last = s.lastAnyBackup
    return when {
        last == 0L -> "还没有备份过。卸载 App 或换手机会丢失全部记录，建议开启定期自动备份。"
        now - last >= REMIND_AFTER_MS -> "已经 ${(now - last) / (24 * 3600 * 1000)} 天没有备份了，建议开启定期自动备份。"
        else -> null
    }
}

@Composable
private fun BackupReminder(text: String, onOpen: () -> Unit, onLater: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onOpen, shape = RoundedCornerShape(16.dp), color = cs.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Backup, null, tint = cs.onErrorContainer)
            Spacer(Modifier.size(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onLater) { Text("稍后", color = cs.onErrorContainer) }
        }
    }
}
