package com.shiyi.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val F_COLS = """
    f.id AS f_id, f.name AS f_name, f.shopName AS f_shopName, f.spec AS f_spec,
    f.status AS f_status, f.statusReason AS f_statusReason, f.statusAt AS f_statusAt,
    f.note AS f_note, f.createdAt AS f_createdAt, f.updatedAt AS f_updatedAt
"""

private const val R_COLS = """
    r.id AS r_id, r.shopName AS r_shopName, r.title AS r_title, r.platform AS r_platform,
    r.scenario AS r_scenario, r.paidCents AS r_paidCents, r.orderTime AS r_orderTime, r.rating AS r_rating
"""

@Dao
interface RecordDao {
    @Upsert
    suspend fun upsert(record: RecordEntity)

    @Query("SELECT * FROM records WHERE id = :id")
    fun observe(id: String): Flow<RecordEntity?>

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun get(id: String): RecordEntity?

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM records ORDER BY orderTime DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records ORDER BY orderTime DESC")
    suspend fun all(): List<RecordEntity>

    @Query("SELECT * FROM records ORDER BY orderTime DESC")
    fun observeAll(): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE shopName = :shop AND title = :title AND id != :excludeId ORDER BY orderTime DESC LIMIT 30")
    fun sameDish(shop: String, title: String, excludeId: String): Flow<List<RecordEntity>>

    /**
     * 综合搜索与筛选。LIKE 参数需调用方已转义并包好 %。
     * 中文没有可靠的分词，个人数据量下 LIKE 足够快也更准确。
     */
    @Query(
        """
        SELECT r.* FROM records r
        WHERE (:q IS NULL
               OR r.shopName LIKE :q ESCAPE '\' OR r.title LIKE :q ESCAPE '\'
               OR r.keywords LIKE :q ESCAPE '\' OR r.note LIKE :q ESCAPE '\'
               OR EXISTS (SELECT 1 FROM record_food_links l JOIN food_items f ON f.id = l.foodItemId
                          WHERE l.recordId = r.id AND f.name LIKE :q ESCAPE '\'))
          AND (:platform IS NULL OR r.platform = :platform)
          AND (:scenario IS NULL OR r.scenario = :scenario)
          AND (:unratedOnly = 0 OR r.rating IS NULL)
          AND (:minRating IS NULL OR r.rating >= :minRating)
          AND (:maxRating IS NULL OR r.rating <= :maxRating)
          AND (:minCents IS NULL OR r.paidCents >= :minCents)
          AND (:maxCents IS NULL OR r.paidCents <= :maxCents)
        ORDER BY
          CASE WHEN :sort = 0 THEN r.orderTime END DESC,
          CASE WHEN :sort = 1 THEN r.orderTime END ASC,
          CASE WHEN :sort = 2 THEN r.rating END DESC,
          CASE WHEN :sort = 3 THEN r.paidCents END DESC,
          CASE WHEN :sort = 4 THEN r.paidCents END ASC,
          r.orderTime DESC
        """
    )
    fun search(
        q: String?, platform: String?, scenario: String?, unratedOnly: Boolean,
        minRating: Int?, maxRating: Int?, minCents: Long?, maxCents: Long?, sort: Int,
    ): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE shopName = :shop AND id != :excludeId ORDER BY orderTime DESC LIMIT 20")
    fun sameShop(shop: String, excludeId: String): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE orderNoHash = :hash AND id != :excludeId LIMIT 1")
    suspend fun findByOrderHash(hash: String, excludeId: String): RecordEntity?

    @Query(
        """SELECT * FROM records WHERE id != :excludeId AND shopName = :shop AND paidCents = :cents
           AND orderTime BETWEEN :from AND :to LIMIT 1"""
    )
    suspend fun findSimilar(shop: String, cents: Long, from: Long, to: Long, excludeId: String): RecordEntity?

    @Query("SELECT * FROM records WHERE repurchaseIntent = 'WILLING' ORDER BY orderTime DESC")
    fun willing(): Flow<List<RecordEntity>>

    // 注意：含反斜杠的 SQL 一律用 """ 原始字符串，普通字符串里 '\' 会被 Kotlin 当成转义，生成 ESCAPE ''
    @Query("""SELECT DISTINCT shopName FROM records WHERE shopName LIKE :prefix ESCAPE '\' ORDER BY orderTime DESC LIMIT 6""")
    suspend fun shopSuggestions(prefix: String): List<String>

    @Query("SELECT COUNT(*) FROM records")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM records WHERE repurchaseIntent = 'WILLING'")
    fun willingCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(paidCents), 0) FROM records WHERE orderTime >= :from")
    fun sumSince(from: Long): Flow<Long>

    @Query("DELETE FROM records")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(list: List<RecordEntity>)
}

@Dao
interface FoodDao {
    @Upsert
    suspend fun upsert(item: FoodItemEntity)

    @Query("SELECT * FROM food_items WHERE id = :id")
    fun observe(id: String): Flow<FoodItemEntity?>

    @Query("SELECT * FROM food_items WHERE id = :id")
    suspend fun get(id: String): FoodItemEntity?

    @Query("DELETE FROM food_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM food_items")
    suspend fun all(): List<FoodItemEntity>

    @Query(
        """
        SELECT f.*, COUNT(l.recordId) AS recordCount, MAX(r.orderTime) AS lastTime
        FROM food_items f
        LEFT JOIN record_food_links l ON l.foodItemId = f.id
        LEFT JOIN records r ON r.id = l.recordId
        WHERE (:status IS NULL OR f.status = :status)
        GROUP BY f.id
        ORDER BY COALESCE(f.statusAt, 0) DESC, lastTime DESC, f.updatedAt DESC
        """
    )
    fun withStats(status: String?): Flow<List<FoodItemWithStats>>

    @Query(
        """SELECT * FROM food_items WHERE name LIKE :q ESCAPE '\' OR shopName LIKE :q ESCAPE '\'
           ORDER BY CASE status WHEN 'AVOID' THEN 0 WHEN 'REPURCHASE' THEN 1 ELSE 2 END, updatedAt DESC LIMIT 30"""
    )
    fun search(q: String): Flow<List<FoodItemEntity>>

    /** 关联档案时的候选：同店优先，其次全局同名相近 */
    @Query(
        """SELECT * FROM food_items WHERE name LIKE :q ESCAPE '\'
           ORDER BY CASE WHEN shopName = :shop THEN 0 ELSE 1 END, updatedAt DESC LIMIT 20"""
    )
    suspend fun candidates(q: String, shop: String): List<FoodItemEntity>

    @Query("SELECT * FROM food_items WHERE shopName = :shop AND status = 'AVOID'")
    fun avoidAtShop(shop: String): Flow<List<FoodItemEntity>>

    @Query("SELECT COUNT(*) FROM food_items WHERE status = :status")
    fun countByStatus(status: String): Flow<Int>

    @Query("DELETE FROM food_items")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(list: List<FoodItemEntity>)
}

@Dao
interface LinkDao {
    @Upsert
    suspend fun upsert(link: RecordFoodLinkEntity)

    @Delete
    suspend fun delete(link: RecordFoodLinkEntity)

    @Query("DELETE FROM record_food_links WHERE recordId = :recordId")
    suspend fun deleteForRecord(recordId: String)

    @Query("SELECT * FROM record_food_links")
    suspend fun all(): List<RecordFoodLinkEntity>

    @Query("SELECT l.*, $F_COLS FROM record_food_links l JOIN food_items f ON f.id = l.foodItemId WHERE l.recordId = :recordId ORDER BY l.createdAt")
    fun itemsForRecord(recordId: String): Flow<List<LinkWithItem>>

    @Query("SELECT l.*, $F_COLS FROM record_food_links l JOIN food_items f ON f.id = l.foodItemId WHERE l.recordId = :recordId ORDER BY l.createdAt")
    suspend fun itemsForRecordOnce(recordId: String): List<LinkWithItem>

    @Query("SELECT l.*, $R_COLS FROM record_food_links l JOIN records r ON r.id = l.recordId WHERE l.foodItemId = :itemId ORDER BY r.orderTime DESC")
    fun recordsForItem(itemId: String): Flow<List<LinkWithRecord>>

    /** 关联了“不再推荐”档案的记录 id，用于列表中显示醒目标记 */
    @Query("SELECT DISTINCT l.recordId FROM record_food_links l JOIN food_items f ON f.id = l.foodItemId WHERE f.status = 'AVOID'")
    fun recordIdsWithAvoid(): Flow<List<String>>

    /** 与某记录共享同一档案的其他记录（跨平台历史） */
    @Query(
        """SELECT DISTINCT r.* FROM records r JOIN record_food_links l ON l.recordId = r.id
           WHERE l.foodItemId IN (SELECT foodItemId FROM record_food_links WHERE recordId = :recordId)
             AND r.id != :recordId ORDER BY r.orderTime DESC"""
    )
    fun relatedByItem(recordId: String): Flow<List<RecordEntity>>

    @Query("DELETE FROM record_food_links")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(list: List<RecordFoodLinkEntity>)
}

@Dao
interface ImageDao {
    @Insert
    suspend fun insert(image: RecordImageEntity)

    @Query("SELECT * FROM record_images WHERE recordId = :recordId ORDER BY createdAt")
    fun forRecord(recordId: String): Flow<List<RecordImageEntity>>

    @Query("SELECT * FROM record_images WHERE recordId = :recordId")
    suspend fun forRecordOnce(recordId: String): List<RecordImageEntity>

    @Query("DELETE FROM record_images WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM record_images")
    suspend fun all(): List<RecordImageEntity>

    @Query("DELETE FROM record_images")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(list: List<RecordImageEntity>)
}
