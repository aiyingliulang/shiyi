package com.shiyi.app.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 金额统一用 Long 型“分”存储，避免浮点误差。 */
object Money {
    private val pattern = Regex("""^\d{1,7}(\.\d{0,2})?$""")

    /** "23.5" -> 2350；非法输入返回 null。 */
    fun parseYuan(text: String?): Long? {
        val t = text?.trim()?.removePrefix("¥")?.removePrefix("￥")?.trim().orEmpty()
        if (t.isEmpty() || !pattern.matches(t)) return null
        return BigDecimal(t).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    /** 2350 -> "23.50" */
    fun plain(cents: Long): String = BigDecimal(cents).movePointLeft(2).setScale(2).toPlainString()

    /** 2350 -> "¥23.5"；整数去掉小数 */
    fun format(cents: Long?): String {
        if (cents == null) return "—"
        val bd = BigDecimal(cents).movePointLeft(2).stripTrailingZeros()
        return "¥" + (if (bd.scale() < 0) bd.setScale(0) else bd).toPlainString()
    }

    /** 编辑框展示用：2350 -> "23.5" */
    fun editable(cents: Long?): String = cents?.let { format(it).removePrefix("¥") } ?: ""
}

object Times {
    private fun fmt(p: String) = SimpleDateFormat(p, Locale.CHINA)

    fun dateTime(ms: Long): String = fmt("yyyy-MM-dd HH:mm").format(Date(ms))
    fun date(ms: Long): String = fmt("yyyy-MM-dd").format(Date(ms))
    fun time(ms: Long): String = fmt("HH:mm").format(Date(ms))

    fun friendly(ms: Long, now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        return if (c.get(Calendar.YEAR) == n.get(Calendar.YEAR)) fmt("M月d日 HH:mm").format(Date(ms))
        else fmt("yyyy年M月d日").format(Date(ms))
    }

    fun startOfMonth(now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    fun startOfPrevMonth(now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply { timeInMillis = startOfMonth(now); add(Calendar.MONTH, -1) }.timeInMillis

    /** 用新日期（UTC 零点毫秒，来自 DatePicker）替换 [base] 的年月日，保留时分 */
    fun withDate(base: Long, utcDateMs: Long): Long {
        val u = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcDateMs }
        return Calendar.getInstance().apply {
            timeInMillis = base
            set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    fun withTime(base: Long, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = base
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** 本地时间 -> 对应日期的 UTC 零点（喂给 DatePicker） */
    fun toUtcDate(ms: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear(); set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
}
