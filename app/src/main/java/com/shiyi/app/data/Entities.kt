package com.shiyi.app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 一笔消费 = 一条记录。一个整体评分（可空）。
 * 枚举字段以字符串存储（见 domain/Enums.kt），便于迁移和导出。
 */
@Serializable
@Entity(
    tableName = "records",
    indices = [Index("orderTime"), Index("shopName"), Index("orderNoHash")]
)
data class RecordEntity(
    @PrimaryKey val id: String,
    val shopName: String,
    val title: String = "",
    val scenario: String,
    val platform: String,
    /** 本人实付金额，单位：分 */
    val paidCents: Long,
    /** 下单/购买时间（堂食为消费时间） */
    val orderTime: Long,
    /** 团购的实际到店消费时间，可空 */
    val consumeTime: Long? = null,
    /** 1～10，可空 */
    val rating: Int? = null,
    /** ReasonTag 名称，逗号分隔 */
    val reasonTags: String = "",
    val note: String = "",
    val repurchaseIntent: String? = null,
    /** OCR 识别到的菜名等辅助搜索词，不参与评分或价格 */
    val keywords: String = "",
    /** 订单号的 SHA-256 指纹，只用于去重，不保存明文 */
    val orderNoHash: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 可复用的美食档案：一个明确的商品或套餐（同店同规格）。 */
@Serializable
@Entity(tableName = "food_items", indices = [Index("name"), Index("shopName"), Index("status")])
data class FoodItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val shopName: String,
    /** 规格/份量，如“大份”“单人餐”，用于判断能否比较价格 */
    val spec: String = "",
    val status: String = "NONE",
    val statusReason: String = "",
    val statusAt: Long? = null,
    val note: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 消费记录 ↔ 美食档案 关联。每次消费独立保留平台/时间/金额/评分，
 * 关联本身不覆盖任何记录字段。
 */
@Serializable
@Entity(
    tableName = "record_food_links",
    primaryKeys = ["recordId", "foodItemId"],
    foreignKeys = [
        ForeignKey(RecordEntity::class, ["id"], ["recordId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(FoodItemEntity::class, ["id"], ["foodItemId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("foodItemId")]
)
data class RecordFoodLinkEntity(
    val recordId: String,
    val foodItemId: String,
    /** 这笔订单是否只包含这一个商品/套餐 */
    val isSole: Boolean,
    /**
     * 该商品在此次消费中的价格（分）。仅当口径明确时才有值：
     * 单品订单默认等于实付；多菜订单只有用户手动填写才有值，绝不用整单金额代替。
     */
    val itemPriceCents: Long? = null,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "record_images",
    foreignKeys = [ForeignKey(RecordEntity::class, ["id"], ["recordId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordId")]
)
data class RecordImageEntity(
    @PrimaryKey val id: String,
    val recordId: String,
    /** App 私有目录 files/images/ 下的文件名 */
    val fileName: String,
    val createdAt: Long,
)

/** 档案详情里的一行：关联 + 对应的消费记录 */
data class LinkWithRecord(
    @Embedded val link: RecordFoodLinkEntity,
    @Embedded(prefix = "r_") val record: RecordEntityPrefixed,
)

/** Room 的 @Embedded 前缀映射用（与 RecordEntity 字段一致的轻量视图） */
data class RecordEntityPrefixed(
    val id: String,
    val shopName: String,
    val title: String,
    val platform: String,
    val scenario: String,
    val paidCents: Long,
    val orderTime: Long,
    val rating: Int?,
)

/** 记录详情里的一行：关联 + 对应档案 */
data class LinkWithItem(
    @Embedded val link: RecordFoodLinkEntity,
    @Embedded(prefix = "f_") val item: FoodItemEntity,
)

data class FoodItemWithStats(
    @Embedded val item: FoodItemEntity,
    @ColumnInfo(name = "recordCount") val recordCount: Int,
    @ColumnInfo(name = "lastTime") val lastTime: Long?,
)

data class HomeStats(
    val recordCount: Int,
    val repurchaseCount: Int,
    val avoidCount: Int,
    val monthCents: Long,
    /** 上个月全月实付 */
    val lastMonthCents: Long = 0L,
)
