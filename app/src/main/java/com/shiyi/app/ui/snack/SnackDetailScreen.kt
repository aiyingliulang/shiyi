package com.shiyi.app.ui.snack

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shiyi.app.AppContainer
import com.shiyi.app.domain.Money
import com.shiyi.app.domain.RatingBand
import com.shiyi.app.domain.SnackTag
import com.shiyi.app.domain.SnackVerdict
import com.shiyi.app.domain.Times
import com.shiyi.app.ui.Routes
import com.shiyi.app.ui.components.FullImageDialog
import com.shiyi.app.ui.components.ImageThumb
import com.shiyi.app.ui.components.SectionTitle
import com.shiyi.app.ui.theme.LocalSemantic
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SnackDetailScreen(c: AppContainer, nav: NavHostController, id: String) {
    val snack by remember(id) { c.snacks.observe(id) }.collectAsState(initial = null)
    val images by remember(id) { c.snacks.images(id) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<File?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("零食") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.editSnack(id)) }, enabled = snack != null) { Icon(Icons.Outlined.Edit, "编辑") }
                    IconButton(onClick = { confirmDelete = true }, enabled = snack != null) { Icon(Icons.Outlined.Delete, "删除") }
                },
            )
        },
    ) { pad ->
        val s = snack
        if (s == null) {
            if (!deleted) Text("记录不存在或已删除", Modifier.padding(pad).padding(24.dp))
            return@Scaffold
        }
        val v = SnackVerdict.of(s.verdict)
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            if (images.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.forEach { img -> val f = c.repo.imageFile(img.fileName); ImageThumb(f, 160.dp) { preview = f } }
                }
            }
            Text(s.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
            val sub = listOf(s.brand, s.flavor).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant)

            val sc = LocalSemantic.current
            val (bg, fg) = when (v) {
                SnackVerdict.AVOID -> sc.avoidBg to sc.avoidFg
                SnackVerdict.BUY_AGAIN -> sc.goodBg to sc.goodFg
                else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
            }
            Surface(color = bg, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(v.label, color = fg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(s.rating?.let { "$it 分 · ${RatingBand.label(it)}" } ?: "未评分", color = fg)
                }
            }

            val tags = SnackTag.parse(s.tags)
            if (tags.isNotEmpty()) {
                SectionTitle("原因")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { t ->
                        Surface(shape = RoundedCornerShape(8.dp), color = if (t.positive) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(t.label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if (s.note.isNotBlank()) {
                SectionTitle("备注")
                Text(s.note)
            }
            SectionTitle("信息")
            Line("购买日期", Times.date(s.purchaseDate))
            if (s.store.isNotBlank()) Line("在哪买的", s.store)
            if (s.priceCents != null) Line("价格", Money.format(s.priceCents))
            if (s.barcode.isNotBlank()) Line("条码", s.barcode)
            Text("记录于 ${Times.dateTime(s.createdAt)} · 更新于 ${Times.dateTime(s.updatedAt)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这款零食？") },
            text = { Text("会同时删除保存的包装照片，无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false; deleted = true
                    scope.launch { c.snacks.delete(id); nav.popBackStack() }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
    preview?.let { FullImageDialog(it) { preview = null } }
}

@Composable
private fun Line(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, Modifier.width(84.dp), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
        Text(v, style = MaterialTheme.typography.bodyMedium)
    }
}
