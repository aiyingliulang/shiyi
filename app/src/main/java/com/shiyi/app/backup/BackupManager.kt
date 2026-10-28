package com.shiyi.app.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.shiyi.app.BuildConfig
import com.shiyi.app.data.FoodItemEntity
import com.shiyi.app.data.RecordEntity
import com.shiyi.app.data.RecordFoodLinkEntity
import com.shiyi.app.data.RecordImageEntity
import com.shiyi.app.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupData(
    /** 1 = v1.0.0；2 = 增加零食（旧备份缺少的字段按空列表处理） */
    val formatVersion: Int = 2,
    val appVersion: String,
    val dbVersion: Int,
    val exportedAt: Long,
    val records: List<RecordEntity>,
    val foodItems: List<FoodItemEntity>,
    val links: List<RecordFoodLinkEntity>,
    val images: List<RecordImageEntity>,
    val snacks: List<com.shiyi.app.data.SnackEntity> = emptyList(),
    val snackImages: List<com.shiyi.app.data.SnackImageEntity> = emptyList(),
    /** 是否包含订单截图 / 零食照片。不包含的那类，恢复时保留本机已有的。 */
    val includesRecordImages: Boolean = true,
    val includesSnackImages: Boolean = true,
)

/** 备份时的图片选项。[recompressMaxDim] 非空时，把图片再压缩一档写入备份（不改动本机原图）。 */
data class ImageOptions(
    val recordImages: Boolean = true,
    val snackImages: Boolean = true,
    val recompressMaxDim: Int? = null,
) {
    companion object { val ALL = ImageOptions() }
}

data class BackupSummary(
    val records: Int, val foodItems: Int, val links: Int, val images: Int,
    val missingImages: Int, val exportedAt: Long, val appVersion: String,
    val snacks: Int = 0,
)

/**
 * 完整备份 = 结构化数据(JSON) + 用户选择保留的图片，打成 zip 后整体加密。
 * 恢复流程：解密到临时文件 → 校验结构与图片 → 事务内替换全部数据 → 替换图片目录。
 * 任一步骤失败都不会改动现有数据。
 */
class BackupManager(private val context: Context, private val repo: Repository) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val work: File get() = File(context.cacheDir, "backup_work").apply { mkdirs() }

    /** 备份/验证/恢复共用同一个临时目录；自动备份可能在后台同时触发，所以串行执行 */
    private val lock = Mutex()
    private suspend fun <T> locked(block: suspend () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }

    /**
     * 数据指纹：各表的行数 + 最近修改时间。数据没变时自动备份可以跳过。
     * 只用于判断“是否有变化”，不追求精确。
     */
    suspend fun dataSignature(): String = withContext(Dispatchers.IO) {
        val sql = "SELECT " + listOf(
            "(SELECT COUNT(*) || ':' || IFNULL(MAX(updatedAt), 0) FROM records)",
            "(SELECT COUNT(*) || ':' || IFNULL(MAX(updatedAt), 0) FROM food_items)",
            "(SELECT COUNT(*) FROM record_food_links)",
            "(SELECT COUNT(*) FROM record_images)",
            "(SELECT COUNT(*) || ':' || IFNULL(MAX(updatedAt), 0) FROM snacks)",
            "(SELECT COUNT(*) FROM snack_images)",
        ).joinToString(" || '|' || ")
        repo.db.openHelper.readableDatabase.query(sql).use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" }
    }

    suspend fun export(target: Uri, password: CharArray, hint: String, opts: ImageOptions = ImageOptions.ALL): BackupSummary = locked {
        val db = repo.db
        val data = BackupData(
            appVersion = BuildConfig.VERSION_NAME,
            dbVersion = db.openHelper.readableDatabase.version,
            exportedAt = System.currentTimeMillis(),
            records = db.records().all(),
            foodItems = db.foods().all(),
            links = db.links().all(),
            images = if (opts.recordImages) db.images().all() else emptyList(),
            snacks = db.snacks().all(),
            snackImages = if (opts.snackImages) db.snacks().allImages() else emptyList(),
            includesRecordImages = opts.recordImages,
            includesSnackImages = opts.snackImages,
        )
        val zip = File(work, "export.zip")
        try {
            ZipOutputStream(BufferedOutputStream(zip.outputStream())).use { zos ->
                zos.putNextEntry(ZipEntry(DATA_ENTRY))
                zos.write(json.encodeToString(BackupData.serializer(), data).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                (data.images.map { it.fileName } + data.snackImages.map { it.fileName }).forEach { fileName ->
                    val f = repo.imageFile(fileName)
                    if (f.exists()) {
                        zos.putNextEntry(ZipEntry(IMAGE_PREFIX + fileName))
                        val small = opts.recompressMaxDim?.let { dim ->
                            val isScreenshot = !fileName.startsWith("snack_")
                            com.shiyi.app.importing.ImageUtil.compressToBytes(f, dim, isScreenshot)
                        }
                        if (small != null && small.size < f.length()) zos.write(small)
                        else f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            val out = context.contentResolver.openOutputStream(target, "wt") ?: error("无法写入所选位置")
            BufferedOutputStream(out).use { o ->
                BufferedInputStream(zip.inputStream()).use { i -> BackupCrypto.encrypt(i, o, password, hint) }
            }
            BackupSummary(data.records.size, data.foodItems.size, data.links.size, data.images.size + data.snackImages.size, 0, data.exportedAt, data.appVersion, data.snacks.size)
        } finally {
            zip.delete()
            password.fill('\u0000')
        }
    }

    suspend fun readHint(source: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(source)?.use { BackupCrypto.readHeader(BufferedInputStream(it)).hint }.orEmpty()
    }

    /** 解密并完整校验，但不修改任何数据。返回解析结果与解压目录。 */
    private fun decryptAndValidate(source: Uri, password: CharArray): Pair<BackupData, File> {
        val zip = File(work, "restore.zip")
        val dir = File(work, "restore_images").apply { deleteRecursively(); mkdirs() }
        val input = context.contentResolver.openInputStream(source) ?: error("无法读取所选文件")
        BufferedInputStream(input).use { i ->
            BufferedOutputStream(zip.outputStream()).use { o -> BackupCrypto.decrypt(i, o, password) }
        }
        var data: BackupData? = null
        try {
            ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    when {
                        e.name == DATA_ENTRY -> data = json.decodeFromString(BackupData.serializer(), zis.readBytes().toString(Charsets.UTF_8))
                        e.name.startsWith(IMAGE_PREFIX) -> {
                            val name = e.name.removePrefix(IMAGE_PREFIX)
                            // 防止 zip 路径穿越
                            require(name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && !name.contains("..")) { "备份内含非法文件名" }
                            File(dir, name).outputStream().use { zis.copyTo(it) }
                        }
                    }
                }
            }
        } finally {
            zip.delete()
        }
        val d = data ?: error("备份中缺少数据文件")
        require(d.formatVersion <= 2) { "备份来自更新版本的食忆（格式 ${d.formatVersion}），请先升级 App" }
        val ids = d.records.map { it.id }.toSet()
        val itemIds = d.foodItems.map { it.id }.toSet()
        require(ids.size == d.records.size && itemIds.size == d.foodItems.size) { "备份数据存在重复主键" }
        require(d.links.all { it.recordId in ids && it.foodItemId in itemIds }) { "备份中的关联数据不完整" }
        require(d.images.all { it.recordId in ids }) { "备份中的图片记录不完整" }
        val snackIds = d.snacks.map { it.id }.toSet()
        require(snackIds.size == d.snacks.size) { "备份数据存在重复主键" }
        require(d.snackImages.all { it.snackId in snackIds }) { "备份中的零食图片记录不完整" }
        return d to dir
    }

    suspend fun verify(source: Uri, password: CharArray): BackupSummary = locked {
        try {
            val (d, dir) = decryptAndValidate(source, password)
            val all = d.images.map { it.fileName } + d.snackImages.map { it.fileName }
            val missing = all.count { !File(dir, it).exists() }
            dir.deleteRecursively()
            BackupSummary(d.records.size, d.foodItems.size, d.links.size, all.size, missing, d.exportedAt, d.appVersion, d.snacks.size)
        } finally {
            password.fill('\u0000')
        }
    }

    /** 用备份替换当前全部数据 */
    suspend fun restore(source: Uri, password: CharArray): BackupSummary = locked {
        try {
            val (d, dir) = decryptAndValidate(source, password)
            val db = repo.db
            val ids = d.records.map { it.id }.toSet()
            val snackIds = d.snacks.map { it.id }.toSet()
            // 备份里没有的那类图片：保留本机已有、且对应记录仍存在的
            val localImgs = if (d.includesRecordImages) emptyList() else db.images().all().filter { it.recordId in ids && repo.imageFile(it.fileName).exists() }
            val localSnackImgs = if (d.includesSnackImages) emptyList() else db.snacks().allImages().filter { it.snackId in snackIds && repo.imageFile(it.fileName).exists() }
            val present = d.images.filter { File(dir, it.fileName).exists() } + localImgs
            val presentSnack = d.snackImages.filter { File(dir, it.fileName).exists() } + localSnackImgs
            // 先把图片准备到新目录，避免复制中途失败时丢图
            val fresh = File(context.filesDir, "images_restoring").apply { deleteRecursively(); mkdirs() }
            (present.map { it.fileName } + presentSnack.map { it.fileName }).forEach { name ->
                val src = File(dir, name).takeIf { it.exists() } ?: repo.imageFile(name)
                src.copyTo(File(fresh, name), overwrite = true)
            }
            db.withTransaction { replaceAll(d, present, presentSnack) }
            // 数据库替换成功后再交换图片目录
            val current = File(context.filesDir, "images")
            val old = File(context.filesDir, "images_old").apply { deleteRecursively() }
            if (current.exists() && !current.renameTo(old)) error("替换图片目录失败")
            if (!fresh.renameTo(current)) { old.renameTo(current); error("替换图片目录失败") }
            old.deleteRecursively()
            dir.deleteRecursively()
            val totalImages = d.images.size + d.snackImages.size
            val restoredImages = present.size + presentSnack.size - localImgs.size - localSnackImgs.size
            BackupSummary(d.records.size, d.foodItems.size, d.links.size, restoredImages, totalImages - restoredImages, d.exportedAt, d.appVersion, d.snacks.size)
        } finally {
            password.fill('\u0000')
        }
    }

    private suspend fun replaceAll(
        d: BackupData, images: List<RecordImageEntity>, snackImages: List<com.shiyi.app.data.SnackImageEntity>,
    ) {
        val db = repo.db
        db.links().deleteAll()
        db.images().deleteAll()
        db.records().deleteAll()
        db.foods().deleteAll()
        db.snacks().deleteAllImages()
        db.snacks().deleteAll()
        db.records().insertAll(d.records)
        db.foods().insertAll(d.foodItems)
        db.links().insertAll(d.links)
        db.images().insertAll(images)
        db.snacks().insertAll(d.snacks)
        db.snacks().insertAllImages(snackImages)
    }

    suspend fun exportCsv(target: Uri): Int = withContext(Dispatchers.IO) {
        val db = repo.db
        val records = db.records().all()
        val csv = CsvExporter.build(records, db.links().all(), db.foods().all())
        val out = context.contentResolver.openOutputStream(target, "wt") ?: error("无法写入所选位置")
        out.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
        records.size
    }

    suspend fun exportSnackCsv(target: Uri): Int = withContext(Dispatchers.IO) {
        val list = repo.db.snacks().all()
        val out = context.contentResolver.openOutputStream(target, "wt") ?: error("无法写入所选位置")
        out.use { it.write(CsvExporter.buildSnacks(list).toByteArray(Charsets.UTF_8)) }
        list.size
    }

    companion object {
        const val FORMAT_VERSION = 2
        const val DATA_ENTRY = "data.json"
        const val IMAGE_PREFIX = "images/"
        const val MIME = "application/octet-stream"
    }
}
