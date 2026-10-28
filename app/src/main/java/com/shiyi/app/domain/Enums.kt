package com.shiyi.app.domain

/** 消费场景 */
enum class Scenario(val label: String) {
    DELIVERY("外卖"),
    GROUP_BUY("团购到店"),
    DINE_IN("线下堂食");

    companion object {
        fun of(code: String?): Scenario? = entries.firstOrNull { it.name == code }
    }
}

/** 购买平台 */
enum class Platform(val label: String) {
    MEITUAN("美团"),
    DOUYIN("抖音"),
    DOUSHENGSHENG("抖省省"),
    ELEME("饿了么/淘宝闪购"),
    DIANPING("大众点评"),
    JD("京东"),
    OFFLINE("线下/无平台"),
    OTHER("其他平台");

    companion object {
        fun of(code: String?): Platform? = entries.firstOrNull { it.name == code }

        /** 场景下合理的默认平台 */
        fun defaultFor(s: Scenario): Platform = when (s) {
            Scenario.DINE_IN -> OFFLINE
            else -> MEITUAN
        }
    }
}

/**
 * 手动归类（可选，留空 = 按评分自动）。数据库里沿用原“回购意愿”字段的取值。
 */
enum class RepurchaseIntent(val label: String) {
    WILLING("值得回购"),
    MAYBE("一般"),
    UNWILLING("不再推荐");

    companion object {
        fun of(code: String?): RepurchaseIntent? = entries.firstOrNull { it.name == code }
    }
}

/** 美食档案状态：值得回购 / 不再推荐 二者互斥，都需用户主动标记 */
enum class ItemStatus(val label: String) {
    NONE("未标记"),
    REPURCHASE("值得回购"),
    AVOID("不再推荐");

    companion object {
        fun of(code: String?): ItemStatus = entries.firstOrNull { it.name == code } ?: NONE
    }
}

/**
 * 评价原因标签。[delivery] 为 true 的属于配送/平台问题，
 * 用于避免把配送问题错误归因到商品本身。
 */
enum class ReasonTag(val label: String, val positive: Boolean, val delivery: Boolean = false) {
    TASTY("好吃", true),
    GENEROUS("分量足", true),
    GOOD_VALUE("性价比高", true),
    FRESH("食材新鲜", true),
    GOOD_SERVICE("服务好", true),
    NICE_PLACE("环境好", true),
    FAST_DELIVERY("送得快", true, delivery = true),

    BAD_TASTE("不好吃", false),
    SMALL_PORTION("分量少", false),
    PRICEY("偏贵", false),
    NOT_FRESH("不新鲜", false),
    TOO_SALTY_OILY("太咸/太油", false),
    HYGIENE("卫生问题", false),
    BAD_SERVICE("服务差", false),
    NOT_AS_DESCRIBED("与描述不符", false),
    SLOW_DELIVERY("配送慢", false, delivery = true),
    SPILLED("洒漏/包装破损", false, delivery = true),
    WRONG_MISSING("送错/漏送", false, delivery = true),
    COLD("送到时已凉", false, delivery = true);

    companion object {
        fun parse(csv: String?): Set<ReasonTag> =
            csv.orEmpty().split(',').mapNotNull { c -> entries.firstOrNull { it.name == c.trim() } }.toSet()

        fun encode(tags: Collection<ReasonTag>): String =
            tags.sortedBy { it.ordinal }.joinToString(",") { it.name }

        /** 负面原因是否全部来自配送问题 */
        fun mostlyDelivery(tags: Collection<ReasonTag>): Boolean {
            val neg = tags.filter { !it.positive }
            return neg.isNotEmpty() && neg.all { it.delivery }
        }
    }
}

/** 三档评分：1～4 较差 / 5～7 一般 / 8～10 满意（外卖与零食一致） */
object RatingBand {
    fun label(rating: Int?): String = when (rating) {
        null -> "未评分"
        in 1..4 -> "体验较差"
        in 5..7 -> "一般"
        in 8..10 -> "很满意"
        else -> "未评分"
    }

    /** 列表筛选用的分档：label -> 范围；null 范围表示“未评分” */
    val filters: List<Pair<String, IntRange?>> = listOf(
        "8～10 很满意" to 8..10,
        "5～7 一般" to 5..7,
        "1～4 较差" to 1..4,
        "未评分" to null,
    )
}

/** 一笔消费的归类结果 */
enum class Verdict(val label: String) {
    GOOD("值得回购"),
    NEUTRAL("一般"),
    AVOID("不再推荐"),
    UNRATED("未评分");
}

/**
 * 自动归类规则（不需要确认）：
 * - 手动选了归类 → 以手动为准
 * - 8～10 分 → 值得回购；1～4 分 → 不再推荐；5～7 分 → 一般；未评分 → 未评分
 * - 例外：低分但差评原因全是配送问题（送慢、洒漏等）→ 归为“一般”，避免冤枉菜品本身
 */
object Classifier {
    data class Result(val verdict: Verdict, val reason: String, val manual: Boolean)

    fun auto(rating: Int?, tags: Collection<ReasonTag>): Result = when {
        rating == null -> Result(Verdict.UNRATED, "还没有评分", false)
        rating >= 8 -> Result(Verdict.GOOD, "评分 $rating 分", false)
        rating <= 4 && ReasonTag.mostlyDelivery(tags) -> Result(Verdict.NEUTRAL, "低分主要是配送问题，未算作菜品不好", false)
        rating <= 4 -> Result(Verdict.AVOID, "评分 $rating 分", false)
        else -> Result(Verdict.NEUTRAL, "评分 $rating 分", false)
    }

    fun classify(rating: Int?, tagsCsv: String?, manual: String?): Result =
        when (RepurchaseIntent.of(manual)) {
            RepurchaseIntent.WILLING -> Result(Verdict.GOOD, "手动归类", true)
            RepurchaseIntent.MAYBE -> Result(Verdict.NEUTRAL, "手动归类", true)
            RepurchaseIntent.UNWILLING -> Result(Verdict.AVOID, "手动归类", true)
            null -> auto(rating, ReasonTag.parse(tagsCsv))
        }

    fun of(r: com.shiyi.app.data.RecordEntity) = classify(r.rating, r.reasonTags, r.repurchaseIntent)
}

/** 零食结论：需要用户明确选择（可由评分给出建议） */
enum class SnackVerdict(val label: String) {
    NONE("未下结论"),
    BUY_AGAIN("好吃，会再买"),
    OK("一般"),
    AVOID("难吃，别再买");

    companion object {
        fun of(code: String?): SnackVerdict = entries.firstOrNull { it.name == code } ?: NONE

        /** 根据评分给出建议结论（只作为默认值，可修改） */
        fun suggest(rating: Int?): SnackVerdict = when (rating) {
            null -> NONE
            in 1..4 -> AVOID
            in 5..7 -> OK
            else -> BUY_AGAIN
        }
    }
}

enum class SnackTag(val label: String, val positive: Boolean) {
    TASTY("好吃", true),
    TEXTURE_GOOD("口感好", true),
    GOOD_VALUE("性价比高", true),
    ADDICTIVE("停不下来", true),

    TOO_SWEET("太甜", false),
    TOO_SALTY("太咸", false),
    GREASY("太油腻", false),
    BAD_TEXTURE("口感差", false),
    WEIRD_TASTE("有怪味/香精味", false),
    BLAND("没味道", false),
    TOO_HARD("太硬/太黏", false),
    AIR("分量少/空气多", false),
    PRICEY("不值这个价", false),
    PACKAGING_TRAP("包装好看但难吃", false);

    companion object {
        fun parse(csv: String?): Set<SnackTag> =
            csv.orEmpty().split(',').mapNotNull { c -> entries.firstOrNull { it.name == c.trim() } }.toSet()

        fun encode(tags: Collection<SnackTag>): String = tags.sortedBy { it.ordinal }.joinToString(",") { it.name }
    }
}
