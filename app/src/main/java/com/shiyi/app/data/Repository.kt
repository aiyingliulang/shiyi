package com.shiyi.app.data

import android.content.Context
import androidx.room.withTransaction
import com.shiyi.app.domain.Classifier
import com.shiyi.app.domain.ItemStatus
import com.shiyi.app.domain.Verdict
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 编辑页里待保存的一条档案关联 */
data class LinkDraft(
    /** 关联已有档案时非空 */
    val existingItemId: String?,
    val name: String,
    val shopName: String,
    val spec: String,
    val isSole: Boolean,
    val itemPriceCents: Long?,
    /** 已有档案的状态，仅用于显示 */
    val status: ItemStatus = ItemStatus.NONE,
)

data class SearchFilter(
    val query: String = "",
    val platform: String? = null,
    val scenario: String? = null,
    /** null = 不限；Pair(null,null) 不会出现。ratingRange 为 null 且 unratedOnly=false 表示不限 */
    val ratingRange: IntRange? = null,
    val unratedOnly: Boolean = false,
    val minCents: Long? = null,
    val maxCents: Long? = null,
    /** 0 时间新→旧, 1 时间旧→新, 2 评分高→低, 3 金额高→低, 4 金额低→高 */
    val sort: Int = 0,
    /** 按自动/手动归类筛选 */
    val verdict: com.shiyi.app.domain.Verdict? = null,
)

class Repository(private val context: Context, val db: AppDatabase) {
    private val records = db.records()
    private val foods = db.foods()
    private val links = db.links()
    private val images = db.images()

    val imagesDir: File get() = File(context.filesDir, "images").apply { mkdirs() }

    fun imageFile(fileName: String) = File(imagesDir, fileName)

    // ---------- 查询 ----------
    fun recent(limit: Int = 8) = records.recent(limit)
    fun observeRecord(id: String) = records.observe(id)
    fun sameShop(shop: String, excludeId: String) = records.sameShop(shop, excludeId)
    fun relatedByItem(recordId: String) = links.relatedByItem(recordId)
    fun linksForRecord(recordId: String) = links.itemsForRecord(recordId)
    fun imagesForRecord(recordId: String) = images.forRecord(recordId)
    fun recordIdsWithAvoid() = links.recordIdsWithAvoid()
    fun willingRecords() = records.willing()
    fun observeItem(id: String) = foods.observe(id)
    fun recordsForItem(id: String) = links.recordsForItem(id)
    fun itemsWithStats(status: ItemStatus?) = foods.withStats(status?.name)
    fun searchItems(q: String) = foods.search(like(q))
    fun avoidAtShop(shop: String) = foods.avoidAtShop(shop.trim())

    fun search(f: SearchFilter): Flow<List<RecordEntity>> = records.search(
        q = f.query.trim().takeIf { it.isNotEmpty() }?.let(::like),
        platform = f.platform,
        scenario = f.scenario,
        unratedOnly = f.unratedOnly,
        minRating = f.ratingRange?.first,
        maxRating = f.ratingRange?.last,
        minCents = f.minCents,
        maxCents = f.maxCents,
        sort = f.sort,
    ).map { list -> if (f.verdict == null) list else list.filter { Classifier.of(it).verdict == f.verdict } }

    /** 同一家店、同一个标题的历史消费（跨平台），用于“上次吃过”和价格对比 */
    fun sameDish(shop: String, title: String, excludeId: String): Flow<List<RecordEntity>> =
        if (shop.isBlank() || title.isBlank()) flowOf(emptyList())
        else records.sameDish(shop.trim(), title.trim(), excludeId)

    val stats: Flow<HomeStats> = combine(
        records.observeAll(),
        records.sumSince(com.shiyi.app.domain.Times.startOfMonth()),
    ) { all, month ->
        val verdicts = all.map { Classifier.of(it).verdict }
        val thisMonth = com.shiyi.app.domain.Times.startOfMonth()
        val prevMonth = com.shiyi.app.domain.Times.startOfPrevMonth()
        HomeStats(
            recordCount = all.size,
            repurchaseCount = verdicts.count { it == Verdict.GOOD },
            avoidCount = verdicts.count { it == Verdict.AVOID },
            monthCents = month,
            lastMonthCents = all.filter { it.orderTime in prevMonth until thisMonth }.sumOf { it.paidCents },
        )
    }

    suspend fun getRecord(id: String) = records.get(id)
    suspend fun linksForRecordOnce(id: String) = links.itemsForRecordOnce(id)
    suspend fun imagesForRecordOnce(id: String) = images.forRecordOnce(id)
    suspend fun shopSuggestions(prefix: String) =
        if (prefix.isBlank()) emptyList() else records.shopSuggestions("%" + escape(prefix.trim()) + "%")

    suspend fun itemCandidates(name: String, shop: String): List<FoodItemEntity> =
        if (name.isBlank()) emptyList() else foods.candidates(like(name), shop.trim())

    /** 去重检查：订单号指纹一致，或同店同金额 10 分钟内 */
    suspend fun findDuplicate(r: RecordEntity): RecordEntity? {
        r.orderNoHash?.let { h -> records.findByOrderHash(h, r.id)?.let { return it } }
        val win = 10 * 60 * 1000L
        return records.findSimilar(r.shopName, r.paidCents, r.orderTime - win, r.orderTime + win, r.id)
    }

    // ---------- 写入 ----------

    /**
     * 保存一条记录（新建或编辑）。
     * [stagedImages] 为用户选择保留的临时图片，会复制进私有目录；
     * [removedImageIds] 为编辑时删除的已保存图片。
     */
    suspend fun saveRecord(
        record: RecordEntity,
        linkDrafts: List<LinkDraft>,
        stagedImages: List<File>,
        removedImageIds: List<String>,
    ): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // 先在事务外复制文件；事务失败时清理
        // 保留的订单截图压缩成 JPEG（按宽度缩放，文字仍清晰），节省空间；无法解码时按原样保存
        val copied = stagedImages.filter { it.exists() }.map { src ->
            val jpg = UUID.randomUUID().toString() + ".jpg"
            if (com.shiyi.app.importing.ImageUtil.compressScreenshotTo(src, imageFile(jpg))) jpg
            else {
                val name = UUID.randomUUID().toString() + "." + (src.extension.ifBlank { "jpg" })
                src.copyTo(imageFile(name), overwrite = true)
                name
            }
        }
        val removedFiles = mutableListOf<String>()
        try {
            db.withTransaction {
                val existing = records.get(record.id)
                records.upsert(record.copy(createdAt = existing?.createdAt ?: now, updatedAt = now))
                links.deleteForRecord(record.id)
                linkDrafts.forEach { d ->
                    val itemId = d.existingItemId ?: UUID.randomUUID().toString().also { id ->
                        foods.upsert(
                            FoodItemEntity(
                                id = id, name = d.name.trim(), shopName = d.shopName.trim(),
                                spec = d.spec.trim(), createdAt = now, updatedAt = now,
                            )
                        )
                    }
                    links.upsert(
                        RecordFoodLinkEntity(
                            recordId = record.id, foodItemId = itemId, isSole = d.isSole,
                            itemPriceCents = d.itemPriceCents, createdAt = now,
                        )
                    )
                }
                copied.forEach { name ->
                    images.insert(RecordImageEntity(UUID.randomUUID().toString(), record.id, name, now))
                }
                val current = images.forRecordOnce(record.id)
                removedImageIds.forEach { id ->
                    current.firstOrNull { it.id == id }?.let { removedFiles += it.fileName }
                    images.delete(id)
                }
            }
        } catch (t: Throwable) {
            copied.forEach { imageFile(it).delete() }
            throw t
        }
        removedFiles.forEach { imageFile(it).delete() }
        record.id
    }

    suspend fun deleteRecord(id: String) = withContext(Dispatchers.IO) {
        val imgs = images.forRecordOnce(id)
        records.delete(id) // 关联与图片记录级联删除；档案本身保留
        imgs.forEach { imageFile(it.fileName).delete() }
    }

    suspend fun setRecordIntent(id: String, intent: String?) {
        val r = records.get(id) ?: return
        records.upsert(r.copy(repurchaseIntent = intent, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setItemStatus(id: String, status: ItemStatus, reason: String) {
        val f = foods.get(id) ?: return
        val now = System.currentTimeMillis()
        foods.upsert(
            f.copy(
                status = status.name,
                statusReason = if (status == ItemStatus.NONE) "" else reason.trim(),
                statusAt = if (status == ItemStatus.NONE) null else now,
                updatedAt = now,
            )
        )
    }

    suspend fun updateItem(id: String, name: String, spec: String, note: String) {
        val f = foods.get(id) ?: return
        foods.upsert(f.copy(name = name.trim(), spec = spec.trim(), note = note.trim(), updatedAt = System.currentTimeMillis()))
    }

    /** 删除档案只解除关联，不影响任何消费记录 */
    suspend fun deleteItem(id: String) = foods.delete(id)

    companion object {
        fun escape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        fun like(s: String) = "%" + escape(s.trim()) + "%"

        fun orderNoFingerprint(orderNo: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(("shiyi-order:" + orderNo.trim()).toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
