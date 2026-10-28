package com.shiyi.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shiyi.app.data.RecordEntity
import com.shiyi.app.domain.Money
import com.shiyi.app.domain.Platform
import com.shiyi.app.domain.RatingBand
import com.shiyi.app.domain.Scenario
import com.shiyi.app.domain.Times
import com.shiyi.app.ui.theme.Amount
import com.shiyi.app.ui.theme.LocalSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AvoidBadge(modifier: Modifier = Modifier, text: String = "不再推荐") {
    val c = LocalSemantic.current
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(c.avoidBg).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Block, null, Modifier.size(13.dp), tint = c.avoidFg)
        Spacer(Modifier.width(3.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = c.avoidFg, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GoodBadge(modifier: Modifier = Modifier, text: String = "值得回购") {
    val c = LocalSemantic.current
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(c.goodBg).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ThumbUp, null, Modifier.size(12.dp), tint = c.goodFg)
        Spacer(Modifier.width(3.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = c.goodFg, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun RatingPill(rating: Int?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (rating) {
        null -> cs.surfaceContainerHigh to cs.onSurfaceVariant
        in 1..3 -> LocalSemantic.current.avoidBg to LocalSemantic.current.avoidFg
        in 4..6 -> cs.secondaryContainer to cs.onSecondaryContainer
        else -> cs.tertiaryContainer to cs.onTertiaryContainer
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(rating?.let { "$it 分" } ?: "未评分", style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun RecordRow(r: RecordEntity, @Suppress("UNUSED_PARAMETER") hasAvoid: Boolean = false, onClick: () -> Unit) {
    val verdict = com.shiyi.app.domain.Classifier.of(r).verdict
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.shopName, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    when (verdict) {
                        com.shiyi.app.domain.Verdict.AVOID -> { Spacer(Modifier.width(6.dp)); AvoidBadge() }
                        com.shiyi.app.domain.Verdict.GOOD -> { Spacer(Modifier.width(6.dp)); GoodBadge() }
                        else -> {}
                    }
                }
                if (r.title.isNotBlank()) {
                    Text(r.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(
                        Times.friendly(r.orderTime),
                        Platform.of(r.platform)?.label,
                        Scenario.of(r.scenario)?.label,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(Money.format(r.paidCents), style = Amount)
                Spacer(Modifier.height(4.dp))
                RatingPill(r.rating)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    }
}

/** 1～10 分整体评分选择；再次点击已选分数可清除（跳过评分） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RatingPicker(value: Int?, onChange: (Int?) -> Unit) {
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..10).forEach { n ->
                val selected = value == n
                val cs = MaterialTheme.colorScheme
                Box(
                    Modifier.size(42.dp).clip(CircleShape)
                        .background(if (selected) cs.primary else cs.surfaceContainerHigh)
                        .clickable { onChange(if (selected) null else n) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$n", color = if (selected) cs.onPrimary else cs.onSurface, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (value == null) "未评分（可跳过）" else "$value 分 · ${RatingBand.label(value)}　再点一次可清除",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipChoices(
    options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

/**
 * 按尺寸上限解码图片：宽度不超过 [maxPx]，总像素不超过 [maxPx]² × 4，
 * 高度不超过 8192（GPU 纹理上限），避免长截图占用过多内存。
 */
@Composable
fun rememberThumb(file: File?, maxPx: Int): ImageBitmap? {
    val state by produceState<ImageBitmap?>(null, file?.absolutePath, maxPx) {
        value = if (file == null || !file.exists()) null else withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, o)
                if (o.outWidth <= 0 || o.outHeight <= 0) return@runCatching null
                val maxPixels = maxPx.toLong() * maxPx * 4
                var s = 1
                while (o.outWidth / s > maxPx * 2 ||
                    (o.outWidth / s).toLong() * (o.outHeight / s) > maxPixels ||
                    o.outHeight / s > 8192
                ) s *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return state
}

@Composable
fun ImageThumb(file: File, size: Dp, onClick: () -> Unit) {
    val bmp = rememberThumb(file, 360)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick),
    ) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun FullImageDialog(file: File, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val bmp = rememberThumb(file, 1440)
        Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
    }
}

/**
 * 日期选择：今天/昨天/前天一键选；选日期后直接生效，不会强制再选时间。
 * 时间是可选的，点右侧的时间按钮才修改。[millis] 为空时显示“未设置”。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DateTimeField(
    label: String, millis: Long?, onChange: (Long?) -> Unit,
    modifier: Modifier = Modifier, clearable: Boolean = false, hint: String? = null,
) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val day = 24 * 3600 * 1000L
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("今天" to 0, "昨天" to 1, "前天" to 2).forEach { (name, back) ->
                val target = Times.toUtcDate(now - back * day)
                val selected = millis != null && Times.toUtcDate(millis) == target
                FilterChip(selected, { onChange(Times.withDate(millis ?: now, target)) }, { Text(name) })
            }
            OutlinedButton(onClick = { showDate = true }) {
                Icon(Icons.Outlined.Event, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(millis?.let(Times::date) ?: "选日期")
            }
            if (millis != null) TextButton(onClick = { showTime = true }) { Text(Times.time(millis)) }
            if (clearable && millis != null) TextButton(onClick = { onChange(null) }) { Text("清除") }
        }
        hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
    }
    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = Times.toUtcDate(millis ?: now),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= Times.toUtcDate(now)
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    showDate = false
                    state.selectedDateMillis?.let { onChange(Times.withDate(millis ?: now, it)) }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("取消") } },
        ) { DatePicker(state = state, showModeToggle = false) }
    }
    if (showTime && millis != null) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val tState = rememberTimePickerState(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = { TextButton(onClick = { showTime = false; onChange(Times.withTime(millis, tState.hour, tState.minute)) }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("取消") } },
            text = { TimePicker(state = tState) },
        )
    }
}

/**
 * 按好评/差评分组的标签选择。评分高时好评组在前，评分低时差评组在前。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> GroupedTagPicker(
    rating: Int?, positive: List<T>, negative: List<T>, extra: Pair<String, List<T>>? = null,
    selected: Collection<T>, label: (T) -> String, onToggle: (T) -> Unit,
) {
    val goodFirst = (rating ?: 6) >= 6
    val groups = buildList {
        val good = "👍 好的方面" to positive
        val bad = "👎 不足之处" to negative
        if (goodFirst) { add(good); add(bad) } else { add(bad); add(good) }
        extra?.let { if (it.second.isNotEmpty()) add(it) }
    }
    val sem = LocalSemantic.current
    Column {
        groups.forEach { (title, items) ->
            val isGood = items === positive
            Text(title, style = MaterialTheme.typography.labelLarge,
                color = if (isGood) sem.goodFg else if (items === negative) sem.avoidFg else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { t -> FilterChip(t in selected, { onToggle(t) }, { Text(label(t)) }) }
            }
        }
    }
}
