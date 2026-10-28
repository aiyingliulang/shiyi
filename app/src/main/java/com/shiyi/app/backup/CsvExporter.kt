package com.shiyi.app.backup

import com.shiyi.app.data.FoodItemEntity
import com.shiyi.app.data.RecordEntity
import com.shiyi.app.data.RecordFoodLinkEntity
import com.shiyi.app.domain.Money
import com.shiyi.app.domain.Platform
import com.shiyi.app.domain.ReasonTag
import com.shiyi.app.domain.RepurchaseIntent
import com.shiyi.app.domain.Scenario
import com.shiyi.app.domain.Times

/**
 * CSV 导出（UTF-8 BOM，Excel 可直接打开中文）。
 * 仅用于查看/分析，不能代替完整备份（不含图片与档案状态细节）。
 */
object CsvExporter {
    private val header = listOf(
        "下单/消费时间", "实际到店时间", "场景", "平台", "店铺", "标题", "本人实付(元)",
        "整体评分", "评价原因", "回购意愿", "关联美食档案", "备注", "搜索关键词", "创建时间"
    )

    fun build(records: List<RecordEntity>, links: List<RecordFoodLinkEntity>, items: List<FoodItemEntity>): String {
        val itemById = items.associateBy { it.id }
        val linksByRecord = links.groupBy { it.recordId }
        val sb = StringBuilder("﻿")
        sb.append(header.joinToString(",") { cell(it) }).append("\r\n")
        records.sortedByDescending { it.orderTime }.forEach { r ->
            val foods = linksByRecord[r.id].orEmpty().mapNotNull { l ->
                itemById[l.foodItemId]?.let { f -> f.name + (if (f.spec.isNotBlank()) "(${f.spec})" else "") + if (f.status == "AVOID") "[不再推荐]" else "" }
            }.joinToString("；")
            val row = listOf(
                Times.dateTime(r.orderTime),
                r.consumeTime?.let(Times::dateTime).orEmpty(),
                Scenario.of(r.scenario)?.label ?: r.scenario,
                Platform.of(r.platform)?.label ?: r.platform,
                r.shopName,
                r.title,
                Money.plain(r.paidCents),
                r.rating?.toString().orEmpty(),
                ReasonTag.parse(r.reasonTags).joinToString("、") { it.label },
                RepurchaseIntent.of(r.repurchaseIntent)?.label.orEmpty(),
                foods,
                r.note,
                r.keywords,
                Times.dateTime(r.createdAt),
            )
            sb.append(row.joinToString(",") { cell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** 转义并防止 CSV 公式注入（以 = + - @ 开头的文本加前导单引号） */
    fun cell(raw: String): String {
        var v = raw.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        if (v.isNotEmpty() && v[0] in charArrayOf('=', '+', '-', '@', '\t') && v.toDoubleOrNull() == null) v = "'$v"
        return "\"" + v.replace("\"", "\"\"") + "\""
    }

    fun buildSnacks(list: List<com.shiyi.app.data.SnackEntity>): String {
        val sb = StringBuilder("\uFEFF")
        sb.append(listOf("名称", "品牌", "口味/规格", "条码", "购买地点", "价格(元)", "购买日期", "评分", "结论", "原因", "备注")
            .joinToString(",") { cell(it) }).append("\r\n")
        list.sortedByDescending { it.updatedAt }.forEach { s ->
            sb.append(listOf(
                s.name, s.brand, s.flavor, s.barcode, s.store,
                s.priceCents?.let(Money::plain).orEmpty(), Times.date(s.purchaseDate),
                s.rating?.toString().orEmpty(),
                com.shiyi.app.domain.SnackVerdict.of(s.verdict).label,
                com.shiyi.app.domain.SnackTag.parse(s.tags).joinToString("、") { it.label },
                s.note,
            ).joinToString(",") { cell(it) }).append("\r\n")
        }
        return sb.toString()
    }
}
