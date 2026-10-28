package com.shiyi.app.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.shiyi.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地诊断日志：记录未捕获异常，以及系统提供的历史异常退出原因
 * （包括原生库崩溃、内存不足被杀、ANR）。只保存在本机，不会上传；
 * 用户可在“设置 → 诊断日志”中查看、复制后自行发送。
 * 日志只含调用栈与系统信息，不主动写入订单内容。
 */
object CrashLog {
    private const val MAX_BYTES = 96 * 1024
    private const val PREFS = "diagnostics"
    private const val KEY_LAST_EXIT = "last_exit_ts"
    private const val KEY_UNSEEN = "unseen"

    private fun file(ctx: Context) = File(ctx.filesDir, "diagnostics/crash.log").apply { parentFile?.mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

    fun install(ctx: Context, process: String) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                append(app, "【未捕获异常】进程=$process 线程=${t.name}\n" + e.stackTraceToString().take(12_000))
                prefs(app).edit().putBoolean(KEY_UNSEEN, true).commit()
            }
            prev?.uncaughtException(t, e)
        }
    }

    @Synchronized
    fun append(ctx: Context, text: String) {
        runCatching {
            val f = file(ctx)
            val header = "===== ${now()} · 食忆 ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT}) =====\n"
            f.appendText(header + text.trimEnd() + "\n\n")
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText("…（更早的日志已截断）\n" + keep)
            }
        }
    }

    /** 读取系统记录的历史异常退出（Android 11+），只追加上次检查之后的新记录。 */
    fun collectExitReasons(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return
            val last = prefs(ctx).getLong(KEY_LAST_EXIT, 0L)
            val infos = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 20)
                .filter { it.timestamp > last }
                .sortedBy { it.timestamp }
            var newest = last
            var found = false
            for (info in infos) {
                newest = maxOf(newest, info.timestamp)
                val foreground = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                val relevant = when (info.reason) {
                    ApplicationExitInfo.REASON_CRASH,
                    ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR,
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> true
                    ApplicationExitInfo.REASON_LOW_MEMORY,
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                    ApplicationExitInfo.REASON_SIGNALED -> foreground
                    else -> false
                }
                if (!relevant) continue
                found = true
                val sb = StringBuilder()
                sb.append("【异常退出】发生于 ")
                    .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(info.timestamp)))
                    .append(" 进程=").append(info.processName)
                    .append(" 原因=").append(reasonName(info.reason))
                    .append(" 状态=").append(info.status)
                    .append(" 前台=").append(foreground)
                    .append(" PSS=").append(info.pss / 1024).append("MB")
                    .append(" RSS=").append(info.rss / 1024).append("MB\n")
                info.description?.let { sb.append("说明：").append(it.take(500)).append('\n') }
                if (info.reason == ApplicationExitInfo.REASON_ANR || info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    runCatching {
                        info.traceInputStream?.use { input ->
                            val bytes = input.readBytes()
                            val text = if (info.reason == ApplicationExitInfo.REASON_ANR) String(bytes).take(6000)
                            else printableStrings(bytes).take(6000) // 原生崩溃为 protobuf，提取其中可读的调用栈文本
                            sb.append("轨迹：\n").append(text).append('\n')
                        }
                    }
                }
                append(ctx, sb.toString())
            }
            val editor = prefs(ctx).edit().putLong(KEY_LAST_EXIT, newest)
            if (found) editor.putBoolean(KEY_UNSEEN, true)
            editor.apply()
        }
    }

    private fun printableStrings(b: ByteArray): String {
        val out = StringBuilder()
        val cur = StringBuilder()
        val s = String(b, Charsets.ISO_8859_1)
        for (ch in s) {
            if (ch.code in 0x20..0x7e) cur.append(ch) else {
                if (cur.length >= 6) out.append(cur).append('\n')
                cur.setLength(0)
            }
        }
        if (cur.length >= 6) out.append(cur)
        return out.toString()
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生库崩溃"
        ApplicationExitInfo.REASON_ANR -> "无响应(ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被系统结束"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高"
        ApplicationExitInfo.REASON_SIGNALED -> "被信号结束"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        else -> "代码 $r"
    }

    fun read(ctx: Context): String = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    fun hasUnseen(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_UNSEEN, false)

    fun markSeen(ctx: Context) = prefs(ctx).edit().putBoolean(KEY_UNSEEN, false).apply()

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
        markSeen(ctx)
    }
}
