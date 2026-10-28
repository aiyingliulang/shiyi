package com.shiyi.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * 一款零食（超市/便利店买的包装食品）。一条记录对应一款商品，
 * 再次购买时直接改评分/结论即可。
 */
@Serializable
@Entity(tableName = "snacks", indices = [Index("barcode"), Index("name"), Index("verdict")])
data class SnackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val brand: String = "",
    /** 口味/规格，如“番茄味 70g” */
    val flavor: String = "",
    /** 包装上的商品条码（EAN-13 等），用于在超市扫码查询 */
    val barcode: String = "",
    /** 在哪买的，如“盒马”“711” */
    val store: String = "",
    val priceCents: Long? = null,
    val purchaseDate: Long,
    /** 1～10，可空 */
    val rating: Int? = null,
    /** SnackVerdict 名称 */
    val verdict: String = "NONE",
    /** SnackTag 名称，逗号分隔 */
    val tags: String = "",
    val note: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "snack_images",
    foreignKeys = [ForeignKey(SnackEntity::class, ["id"], ["snackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("snackId")]
)
data class SnackImageEntity(
    @PrimaryKey val id: String,
    val snackId: String,
    /** files/images/ 下的文件名 */
    val fileName: String,
    val createdAt: Long,
)

/** 列表展示：零食 + 封面图 */
data class SnackWithCover(
    @androidx.room.Embedded val snack: SnackEntity,
    val cover: String?,
)

@Dao
interface SnackDao {
    @Upsert
    suspend fun upsert(s: SnackEntity)

    @Query("SELECT * FROM snacks WHERE id = :id")
    fun observe(id: String): Flow<SnackEntity?>

    @Query("SELECT * FROM snacks WHERE id = :id")
    suspend fun get(id: String): SnackEntity?

    @Query("DELETE FROM snacks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM snacks")
    suspend fun all(): List<SnackEntity>

    @Query(
        """
        SELECT s.*, (SELECT i.fileName FROM snack_images i WHERE i.snackId = s.id ORDER BY i.createdAt LIMIT 1) AS cover
        FROM snacks s
        WHERE (:q IS NULL OR s.name LIKE :q ESCAPE '\' OR s.brand LIKE :q ESCAPE '\' OR s.flavor LIKE :q ESCAPE '\'
               OR s.barcode LIKE :q ESCAPE '\' OR s.store LIKE :q ESCAPE '\' OR s.note LIKE :q ESCAPE '\')
          AND (:verdict IS NULL OR s.verdict = :verdict)
        ORDER BY CASE s.verdict WHEN 'AVOID' THEN 0 ELSE 1 END, s.updatedAt DESC
        """
    )
    fun search(q: String?, verdict: String?): Flow<List<SnackWithCover>>

    @Query(
        """SELECT s.*, (SELECT i.fileName FROM snack_images i WHERE i.snackId = s.id ORDER BY i.createdAt LIMIT 1) AS cover
           FROM snacks s WHERE s.barcode = :barcode ORDER BY s.updatedAt DESC"""
    )
    suspend fun byBarcode(barcode: String): List<SnackWithCover>

    @Query("SELECT COUNT(*) FROM snacks WHERE verdict = :verdict")
    fun countByVerdict(verdict: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM snacks")
    fun count(): Flow<Int>

    @Query("SELECT DISTINCT store FROM snacks WHERE store != '' ORDER BY updatedAt DESC LIMIT 8")
    suspend fun recentStores(): List<String>

    @Query("""SELECT DISTINCT brand FROM snacks WHERE brand != '' AND brand LIKE :q ESCAPE '\' ORDER BY updatedAt DESC LIMIT 6""")
    suspend fun brandSuggestions(q: String): List<String>

    @Query("DELETE FROM snacks")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(list: List<SnackEntity>)

    // ---- 图片 ----
    @Insert
    suspend fun insertImage(img: SnackImageEntity)

    @Query("SELECT * FROM snack_images WHERE snackId = :snackId ORDER BY createdAt")
    fun images(snackId: String): Flow<List<SnackImageEntity>>

    @Query("SELECT * FROM snack_images WHERE snackId = :snackId ORDER BY createdAt")
    suspend fun imagesOnce(snackId: String): List<SnackImageEntity>

    @Query("DELETE FROM snack_images WHERE id = :id")
    suspend fun deleteImage(id: String)

    @Query("SELECT * FROM snack_images")
    suspend fun allImages(): List<SnackImageEntity>

    @Query("DELETE FROM snack_images")
    suspend fun deleteAllImages()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAllImages(list: List<SnackImageEntity>)
}
