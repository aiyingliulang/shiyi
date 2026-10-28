package com.shiyi.app.data

import androidx.room.withTransaction
import com.shiyi.app.domain.SnackVerdict
import com.shiyi.app.importing.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SnackRepository(private val db: AppDatabase, private val repo: Repository) {
    private val dao = db.snacks()

    fun search(q: String, verdict: SnackVerdict?): Flow<List<SnackWithCover>> =
        dao.search(q.trim().takeIf { it.isNotEmpty() }?.let(Repository::like), verdict?.name)

    fun observe(id: String) = dao.observe(id)
    fun images(id: String) = dao.images(id)
    fun avoidCount() = dao.countByVerdict(SnackVerdict.AVOID.name)
    fun count() = dao.count()

    suspend fun get(id: String) = dao.get(id)
    suspend fun imagesOnce(id: String) = dao.imagesOnce(id)
    suspend fun byBarcode(code: String) = if (code.isBlank()) emptyList() else dao.byBarcode(code.trim())
    suspend fun recentStores() = dao.recentStores()
    suspend fun brandSuggestions(q: String) = if (q.isBlank()) emptyList() else dao.brandSuggestions(Repository.like(q))

    /**
     * 保存零食。[newImages] 为拍照/相册得到的临时文件，会压缩成 JPEG 存入私有目录；
     * 临时文件由调用方清理。
     */
    suspend fun save(snack: SnackEntity, newImages: List<File>, removedImageIds: List<String>): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val saved = newImages.filter { it.exists() }.mapNotNull { src ->
            val name = "snack_" + UUID.randomUUID() + ".jpg"
            val dst = repo.imageFile(name)
            if (ImageUtil.compressTo(src, dst)) name else runCatching {
                // 无法解码（极少见）：按原样保存
                src.copyTo(dst, overwrite = true); name
            }.getOrNull()
        }
        val removedFiles = mutableListOf<String>()
        try {
            db.withTransaction {
                val existing = dao.get(snack.id)
                dao.upsert(snack.copy(createdAt = existing?.createdAt ?: now, updatedAt = now))
                saved.forEach { dao.insertImage(SnackImageEntity(UUID.randomUUID().toString(), snack.id, it, now)) }
                val current = dao.imagesOnce(snack.id)
                removedImageIds.forEach { id ->
                    current.firstOrNull { it.id == id }?.let { removedFiles += it.fileName }
                    dao.deleteImage(id)
                }
            }
        } catch (t: Throwable) {
            saved.forEach { repo.imageFile(it).delete() }
            throw t
        }
        removedFiles.forEach { repo.imageFile(it).delete() }
        snack.id
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val imgs = dao.imagesOnce(id)
        dao.delete(id)
        imgs.forEach { repo.imageFile(it.fileName).delete() }
    }
}
