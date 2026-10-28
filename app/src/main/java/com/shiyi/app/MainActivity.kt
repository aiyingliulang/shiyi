package com.shiyi.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.shiyi.app.ui.AppRoot
import com.shiyi.app.ui.theme.ShiYiTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as ShiYiApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.settings.settings.collect { s ->
                    if (s.secureWindow) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        setContent {
            ShiYiTheme {
                AppRoot(container = container)
            }
        }
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        // 必须在当前 Activity 持有临时 URI 授权期间立刻复制
        lifecycleScope.launch {
            val pending = runCatching { container.staging.fromShareIntent(intent) }.getOrNull()
            when {
                pending == null || (pending.unsupported && pending.images.isEmpty() && pending.sharedText == null) -> {
                    Toast.makeText(this@MainActivity, "无法读取分享的内容，请改用截图导入", Toast.LENGTH_LONG).show()
                }
                else -> {
                    container.pendingImport.value?.let { old -> container.staging.discard(old.images) }
                    container.pendingImport.value = pending
                }
            }
            // 避免旋转/重建时重复处理同一个分享
            this@MainActivity.intent = Intent(Intent.ACTION_MAIN)
        }
    }
}
