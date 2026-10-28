package com.shiyi.app.ocr

import com.shiyi.app.domain.Platform
import com.shiyi.app.domain.Scenario
import java.util.Calendar

/** 金额候选。confidence: 3=实付类标签 2=合计类 1=无标签 */
data class AmountCandidate(val cents: Long, val label: String, val confidence: Int, val line: String)

data class TimeCandidate(val millis: Long, val kind: Kind, val line: String) {
    enum class Kind { ORDER, CONSUME, UNLABELED }
}

data class ParsedOrder(
    val platform: Platform? = null,
    val scenario: Scenario? = null,
    val shopCandidates: List<String> = emptyList(),
    val itemNames: List<String> = emptyList(),
    val title: String? = null,
    val amountCandidates: List<AmountCandidate> = emptyList(),
    /** 只有出现明确的“实付/合计”类标签时才给出，否则留空让用户选择 */
    val bestAmountCents: Long? = null,
    val orderTime: Long? = null,
    val consumeTime: Long? = null,
    val timeCandidates: List<TimeCandidate> = emptyList(),
    val orderNo: String? = null,
) {
    val shopName: String? get() = shopCandidates.firstOrNull()
    val keywords: String get() = itemNames.joinToString(" ")
}

/**
 * 基于本地规则的订单字段解析。OCR 只提供文字行；这里不假设识别一定准确：
 * 不确定的字段留空，同时给出候选供用户点选。
 */
object OrderParser {

    private const val AMT = """(\d{1,5}(?:\.\d{1,2})?)"""
    private val currency = Regex("""(?<![\d.])[-－]?\s*[¥￥]\s*$AMT""")
    private val yuanSuffix = Regex("""(?<![\d.])$AMT\s*元""")

    private val paidLabels = listOf("实付款", "实付", "实际支付", "实际付款", "已支付", "已付款", "支付金额", "付款金额", "实收", "到手价")
    private val totalLabels = listOf("合计", "总计", "订单金额", "应付", "共计", "总价")

    private val orderTimeLabels = listOf("下单时间", "购买时间", "支付时间", "付款时间", "创建时间", "订单时间", "下单", "成交时间")
    private val consumeTimeLabels = listOf("使用时间", "核销时间", "消费时间", "验券时间", "到店时间", "验证时间", "使用日期")
    private val ignoreTimeLabels = listOf("有效期", "截止", "过期", "至", "营业", "预计", "送达", "退款", "可用")

    private val fullDateTime = Regex("""(20\d{2})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?\s*(\d{1,2})\s*[:：]\s*(\d{2})(?:\s*[:：]\s*(\d{2}))?""")
    private val shortDateTime = Regex("""(?<!\d)(\d{1,2})\s*[-/月]\s*(\d{1,2})\s*日?\s+(\d{1,2})\s*[:：]\s*(\d{2})""")
    private val dateOnly = Regex("""(20\d{2})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?""")

    private val orderNoRegex = Regex("""订单(?:编号|号码|号)\s*[:：]?\s*([0-9A-Za-z]{8,40})""")

    private val itemRegex = Regex("""^[•·\-\s]*(.{2,40}?)\s*[x×X*＊]\s*(\d{1,2})(?=\s|$|[¥￥])""")

    private val shopSuffix = Regex("""(店|馆|坊|铺|餐厅|饭店|酒楼|酒家|食堂|厨房|小厨|烧烤|烤肉|火锅|面馆|米粉|粉面|茶饮|奶茶|咖啡|甜品|烘焙|蛋糕|麻辣烫|炸鸡|汉堡|披萨|寿司|料理|小吃|食府|大排档|饺子|包子|粥|串串|冒菜|烤鱼|小馆|餐饮|快餐|牛肉面|拉面|米线|酸菜鱼|卤味)""")
    private val branch = Regex("""[（(][^）)]{1,12}店[）)]""")
    private val shopTagPrefix = Regex("""^(?:外卖|团购|到店|自取|堂食|拼好饭|美团|饿了么|抖音|抖省省)\s*""")
    private val shopLabel = Regex("""^(?:商家|店铺|门店|商户|适用门店)\s*[:：]\s*(.{2,30})$""")
    private val nonShopWords = listOf(
        "订单", "时间", "实付", "配送", "地址", "电话", "联系", "退款", "客服", "评价", "再来一单", "发票", "备注",
        "优惠", "¥", "￥", "http", "合计", "支付", "骑手", "送达", "收货", "券码", "有效期", "查看", "全部", "申请", "售后",
        "分享", "投诉", "进店", "关注", "距离", "km", "公里", "营业"
    )

    fun parse(lines: List<String>, now: Long = System.currentTimeMillis()): ParsedOrder {
        val rows = lines.map { normalize(it) }.filter { it.isNotBlank() }
        val text = rows.joinToString("\n")

        val platform = detectPlatform(text)
        val scenario = detectScenario(text)
        val amounts = amountCandidates(rows)
        // 同等级时取页面靠下的（“合计/实付”通常在费用明细之后）
        val top = amounts.maxOfOrNull { it.confidence } ?: 0
        val best = if (top >= 2) amounts.lastOrNull { it.confidence == top }?.cents else null
        val times = timeCandidates(rows, now)
        val orderTime = times.firstOrNull { it.kind == TimeCandidate.Kind.ORDER }?.millis
            ?: times.firstOrNull { it.kind == TimeCandidate.Kind.UNLABELED }?.millis
        val consumeTime = times.firstOrNull { it.kind == TimeCandidate.Kind.CONSUME }?.millis
        val items = itemNames(rows)
        val shops = shopCandidates(rows, items.toSet())
        val title = when {
            items.size == 1 -> items[0]
            items.size > 1 -> "${items[0]} 等${items.size}件"
            else -> null
        }
        return ParsedOrder(
            platform = platform,
            scenario = scenario,
            shopCandidates = shops,
            itemNames = items,
            title = title,
            amountCandidates = amounts.distinctBy { it.cents }.sortedByDescending { it.confidence }.take(8),
            bestAmountCents = best,
            orderTime = orderTime,
            consumeTime = consumeTime,
            timeCandidates = times.distinctBy { it.millis },
            orderNo = orderNoRegex.find(text.replace(" ", ""))?.groupValues?.get(1),
        )
    }

    fun normalize(s: String): String = s
        .replace('　', ' ')
        .replace("：", ":")
        .replace(Regex("""\s+"""), " ")
        .trim()

    fun detectPlatform(text: String): Platform? {
        val hits = listOf(
            Platform.MEITUAN to listOf("美团", "拼好饭", "大众点评外卖"),
            Platform.DOUSHENGSHENG to listOf("抖省省", "抖省省外卖"),
            Platform.DOUYIN to listOf("抖音", "抖店", "来客"),
            Platform.ELEME to listOf("饿了么", "淘宝闪购", "饿了吗"),
            Platform.DIANPING to listOf("大众点评", "点评"),
            Platform.JD to listOf("京东", "秒送"),
        ).map { (p, kws) -> p to kws.sumOf { kw -> Regex(Regex.escape(kw)).findAll(text).count() } }
        val best = hits.filter { it.second > 0 }.maxByOrNull { it.second } ?: return null
        return best.first
    }

    fun detectScenario(text: String): Scenario? {
        fun count(kws: List<String>) = kws.sumOf { kw -> Regex(Regex.escape(kw)).findAll(text).count() }
        val group = count(listOf("团购", "核销", "到店", "代金券", "套餐券", "验券", "券码", "有效期", "随时退", "过期退"))
        val delivery = count(listOf("配送", "骑手", "送达", "外卖", "打包费", "收货", "餐盒费", "准时宝", "配送费", "送餐", "运费"))
        return when {
            group > delivery -> Scenario.GROUP_BUY
            delivery > group -> Scenario.DELIVERY
            else -> null
        }
    }

    /** 金额前紧挨着这些字（如“已减¥4”“省0.63元”）的，是优惠金额 */
    private val discountMarks = listOf("减", "省", "抵", "-", "－", "优惠")
    /** 金额所在段落含这些词的，是费用或优惠，不是实付 */
    private val feeWords = listOf(
        "打包费", "配送费", "运费", "餐盒", "包装费", "优惠", "券", "红包", "月卡", "会员", "原价", "门市价",
        "满减", "立减", "减免", "抵扣", "节省", "已省", "补贴", "膨胀", "退款", "起送"
    )
    /** 有参考价值但不是实付的金额（仍作为候选，可点选） */
    private val softWords = listOf("商品总价", "商品金额", "小计", "到手", "单价")

    /**
     * 金额候选。逐行处理：同一行的多个金额按“与前一个金额之间的文字”分段判断，
     * 例如“合计 共减¥17.63(…省0.63元) ¥12.37”：17.63 紧跟“减”→优惠；0.63 紧跟“省”→优惠；
     * 12.37 前面没有标签 → 继承行首的“合计”。
     */
    fun amountCandidates(rows: List<String>): List<AmountCandidate> {
        val out = mutableListOf<AmountCandidate>()
        rows.forEachIndexed { i, raw ->
            // 标签单独一行、金额在下一行的情况
            val row = if (isBareLabel(raw) && i + 1 < rows.size) raw + " " + rows[i + 1] else raw
            val matches = (currency.findAll(row).map { it.range.first to it } + yuanSuffix.findAll(row).map { it.range.first to it })
                .sortedBy { it.first }.map { it.second }.distinctBy { it.range.first }.toList()
            if (matches.isEmpty()) return@forEachIndexed
            val head = row.substring(0, matches.first().range.first)
            val headKind = classify(head)
            var prevEnd = 0
            val rowOut = mutableListOf<Pair<AmountCandidate, Boolean>>() // 候选 + 是否继承行首标签
            for (m in matches) {
                val start = m.range.first
                val seg = row.substring(minOf(prevEnd, start), start)
                prevEnd = m.range.last + 1
                val num = m.groupValues[1]
                val signed = m.value.trimStart().let { it.startsWith("-") || it.startsWith("－") }
                val pre = seg.trimEnd().takeLast(3)
                if (signed || discountMarks.any { pre.endsWith(it) || (it.length > 1 && pre.contains(it)) }) continue
                // “13元超级餐补券”“5元红包”：金额后面紧跟券/红包的是优惠
                val post = row.substring(m.range.last + 1).take(8)
                if (post.contains("券") || post.contains("红包") || post.contains("优惠")) continue
                val segText = seg.filter { it.isLetter() }
                val (kind, label, inherited) = when {
                    segText.isNotEmpty() -> classify(seg).let { Triple(it.first, it.second, false) }
                    else -> Triple(headKind.first, headKind.second, true)
                }
                if (kind < 0) continue
                val cents = com.shiyi.app.domain.Money.parseYuan(num) ?: continue
                if (cents <= 0) continue
                rowOut += AmountCandidate(cents, label, kind, raw) to inherited
            }
            // 行首标签只赋给本行最后一个金额，避免同一行出现多个“实付”
            val lastInheritedLabeled = rowOut.indexOfLast { it.second && it.first.confidence >= 2 }
            rowOut.forEachIndexed { idx, (c, inherited) ->
                out += if (inherited && c.confidence >= 2 && idx != lastInheritedLabeled) c.copy(confidence = 1, label = "") else c
            }
        }
        return out
    }

    /** 判断一段标签文字：3 实付类，2 合计类，1 普通/参考，-1 费用或优惠 */
    private fun classify(text: String): Pair<Int, String> {
        paidLabels.firstOrNull { text.contains(it) }?.let { return 3 to it }
        // “商品总价/商品金额/小计”是优惠前的金额，必须在“总价/合计”之前判断
        softWords.firstOrNull { text.contains(it) }?.let { return 1 to it }
        totalLabels.firstOrNull { text.contains(it) }?.let { return 2 to it }
        if (feeWords.any { text.contains(it) }) return -1 to ""
        softWords.firstOrNull { text.contains(it) }?.let { return 1 to it }
        return 1 to ""
    }

    private fun isBareLabel(row: String): Boolean {
        val r = row.replace(":", "").trim()
        return (paidLabels + totalLabels).any { r == it }
    }

    fun timeCandidates(rows: List<String>, now: Long): List<TimeCandidate> {
        val out = mutableListOf<TimeCandidate>()
        rows.forEachIndexed { i, row ->
            val ms = parseTime(row, now) ?: return@forEachIndexed
            val prefix = row.substringBefore(Regex("""\d""").find(row)?.value ?: "")
            val context = prefix + " " + (if (i > 0) rows[i - 1].takeLast(8) else "")
            if (ignoreTimeLabels.any { prefix.contains(it) } || row.contains("至") || row.contains("~")) return@forEachIndexed
            val kind = when {
                consumeTimeLabels.any { context.contains(it) } -> TimeCandidate.Kind.CONSUME
                orderTimeLabels.any { context.contains(it) } -> TimeCandidate.Kind.ORDER
                else -> TimeCandidate.Kind.UNLABELED
            }
            out += TimeCandidate(ms, kind, row)
        }
        return out
    }

    fun parseTime(row: String, now: Long): Long? {
        fun build(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long? {
            if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59 || s !in 0..59) return null
            return Calendar.getInstance().apply {
                clear(); set(y, mo - 1, d, h, mi, s)
            }.timeInMillis
        }
        fullDateTime.find(row)?.let { m ->
            val g = m.groupValues
            return build(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        }
        shortDateTime.find(row)?.let { m ->
            val g = m.groupValues
            val year = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
            var t = build(year, g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), 0) ?: return null
            // 没有年份且晚于现在：视为去年
            if (t > now + 24 * 3600 * 1000L) t = build(year - 1, g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), 0) ?: return null
            return t
        }
        dateOnly.find(row)?.let { m ->
            val g = m.groupValues
            return build(g[1].toInt(), g[2].toInt(), g[3].toInt(), 12, 0, 0)
        }
        return null
    }

    private val quantityRow = Regex("""^(?:数量|份数|共)\s*[x×X*＊]?\s*(\d{1,2})\s*(?:份|件)?$""")
    private val nameCut = listOf("到手", "¥", "￥", "ⓘ", "①", "(i)", "（i）")

    private fun cleanItemName(raw: String): String {
        var n = raw
        nameCut.forEach { c -> val i = n.indexOf(c); if (i > 0) n = n.substring(0, i) }
        return n.trim().trimEnd('-', ' ', '·', '|')
    }

    private fun looksLikeName(n: String): Boolean =
        n.length in 2..40 && n.count { it.code in 0x4e00..0x9fff } >= 2 &&
            !n.startsWith("默认") && !n.startsWith("规格") &&
            !(nonShopWords.any { n.contains(it) } && !n.contains("套餐")) &&
            n.count { it.isDigit() } <= n.length / 2

    fun itemNames(rows: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        rows.forEachIndexed { i, row ->
            // 美团等把“数量 ×1”单独放一行：商品名在上方 1～3 行内
            if (quantityRow.matches(row.trim())) {
                for (k in 1..3) {
                    val prev = rows.getOrNull(i - k) ?: break
                    val n = cleanItemName(prev)
                    if (looksLikeName(n) && !shopSuffixOnly(n)) { out += n; break }
                }
                return@forEachIndexed
            }
            val m = itemRegex.find(row) ?: return@forEachIndexed
            val name = cleanItemName(m.groupValues[1])
            if (looksLikeName(name) && !name.startsWith("数量")) out += name
        }
        return out.toList()
    }

    /** “外卖 新一小厨”这类店铺行不能被当作商品 */
    private fun shopSuffixOnly(n: String) = n.startsWith("外卖") || n.startsWith("团购")

    fun shopCandidates(rows: List<String>, itemNames: Set<String>): List<String> {
        val scored = mutableListOf<Pair<String, Int>>()
        rows.forEachIndexed { idx, raw ->
            val row = raw.trimEnd('>', '〉', '›', '»', ' ', '＞').trim()
                .replace(shopTagPrefix, "").removeSuffix("进店").trim()
            shopLabel.find(row)?.let { m ->
                scored += m.groupValues[1].trim() to (1000 - idx)
                return@forEachIndexed
            }
            if (row.length !in 2..30) return@forEachIndexed
            if (nonShopWords.any { row.contains(it) }) return@forEachIndexed
            if (row.count { it.isDigit() } > 4) return@forEachIndexed
            if (itemNames.any { row.startsWith(it) }) return@forEachIndexed
            if (itemRegex.containsMatchIn(row)) return@forEachIndexed
            val score = when {
                branch.containsMatchIn(row) -> 500
                shopSuffix.containsMatchIn(row) -> 200
                else -> return@forEachIndexed
            } - idx
            scored += row to score
        }
        return scored.sortedByDescending { it.second }.map { it.first }.distinct().take(5)
    }
}
