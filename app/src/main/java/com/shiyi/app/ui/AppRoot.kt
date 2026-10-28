package com.shiyi.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shiyi.app.AppContainer
import com.shiyi.app.ui.detail.RecordDetailScreen
import com.shiyi.app.ui.edit.RecordEditScreen
import com.shiyi.app.ui.home.HomeScreen
import com.shiyi.app.ui.importer.ImportScreen
import com.shiyi.app.ui.records.RecordListScreen
import com.shiyi.app.ui.settings.SettingsScreen
import java.net.URLEncoder

object Routes {
    const val HOME = "home"
    const val RECORDS = "records?q={q}&v={v}"
    const val SETTINGS = "settings"
    const val EDIT = "edit?id={id}&scenario={scenario}&fromImport={fromImport}"
    const val DETAIL = "detail/{id}"
    const val IMPORT = "import"
    const val SNACKS = "snacks"
    const val SNACK_EDIT = "snack_edit?id={id}&barcode={barcode}"
    const val SNACK = "snack/{id}"

    fun records(q: String = "", verdict: com.shiyi.app.domain.Verdict? = null) =
        "records?q=" + URLEncoder.encode(q, "UTF-8") + (verdict?.let { "&v=" + it.name } ?: "")
    fun newRecord(scenario: String) = "edit?scenario=$scenario"
    fun editRecord(id: String) = "edit?id=$id"
    fun fromImport() = "edit?fromImport=true"
    fun detail(id: String) = "detail/$id"
    fun newSnack(barcode: String = "") = "snack_edit?barcode=" + URLEncoder.encode(barcode, "UTF-8")
    fun editSnack(id: String) = "snack_edit?id=$id"
    fun snack(id: String) = "snack/$id"
}

/** 在当前导航条目范围内创建 ViewModel */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: () -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create() } })

private data class Tab(val route: String, val base: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "home", "首页", Icons.Outlined.Home),
    Tab(Routes.RECORDS, "records", "记录", Icons.Outlined.ReceiptLong),
    Tab(Routes.SNACKS, "snacks", "零食", Icons.Outlined.Cookie),
    Tab(Routes.SETTINGS, "settings", "设置", Icons.Outlined.Settings),
)

@Composable
fun AppRoot(container: AppContainer) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val showBar = tabs.any { it.route == route }

    // 收到系统分享 → 跳转到导入页（每个导入任务只跳一次）
    var handledImport by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        container.pendingImport.collect { p ->
            if (p != null && p.id != handledImport) {
                handledImport = p.id
                if (nav.currentDestination?.route != Routes.IMPORT) nav.navigate(Routes.IMPORT) { launchSingleTop = true }
            }
        }
    }

    // 上次异常退出 → 提示一次，可查看并复制诊断日志
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var crashNotice by remember { mutableStateOf(com.shiyi.app.diagnostics.CrashLog.hasUnseen(ctx)) }
    var showDiag by remember { mutableStateOf(false) }
    if (crashNotice) com.shiyi.app.ui.diagnostics.CrashNoticeDialog(
        onView = { crashNotice = false; showDiag = true },
        onDismiss = { crashNotice = false },
    )
    if (showDiag) com.shiyi.app.ui.diagnostics.DiagnosticsDialog { showDiag = false }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = { nav.switchTab(t.base) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(
            nav, startDestination = Routes.HOME, modifier = Modifier.padding(pad),
            enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
        ) {
            composable(Routes.HOME) { HomeScreen(container, nav) }
            composable(
                Routes.RECORDS,
                arguments = listOf(
                    navArgument("q") { type = NavType.StringType; defaultValue = "" },
                    navArgument("v") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { e -> RecordListScreen(container, nav, e.arguments?.getString("q").orEmpty(), e.arguments?.getString("v")) }
            composable(Routes.SETTINGS) { SettingsScreen(container, nav) }
            composable(
                Routes.EDIT,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("scenario") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("fromImport") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { e ->
                RecordEditScreen(
                    container, nav,
                    recordId = e.arguments?.getString("id"),
                    scenario = e.arguments?.getString("scenario"),
                    fromImport = e.arguments?.getBoolean("fromImport") ?: false,
                )
            }
            composable(Routes.DETAIL) { e -> RecordDetailScreen(container, nav, e.arguments?.getString("id").orEmpty()) }
            composable(Routes.IMPORT) { ImportScreen(container, nav) }
            composable(Routes.SNACKS) { com.shiyi.app.ui.snack.SnackListScreen(container, nav) }
            composable(
                Routes.SNACK_EDIT,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("barcode") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { e -> com.shiyi.app.ui.snack.SnackEditScreen(container, nav, e.arguments?.getString("id"), e.arguments?.getString("barcode")?.ifBlank { null }) }
            composable(Routes.SNACK) { e -> com.shiyi.app.ui.snack.SnackDetailScreen(container, nav, e.arguments?.getString("id").orEmpty()) }
        }
    }
}

fun NavHostController.switchTab(base: String) {
    // 回首页：直接弹回起始页。不能用 saveState/restoreState，
    // 否则会把刚离开的页面当作“首页的已保存状态”重新恢复出来，表现为点不到首页。
    if (base == "home") {
        if (!popBackStack(Routes.HOME, inclusive = false)) {
            navigate(Routes.HOME) { popUpTo(graph.id) { inclusive = true }; launchSingleTop = true }
        }
        return
    }
    val target = when (base) {
        "records" -> Routes.records()
        else -> base
    }
    navigate(target) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
