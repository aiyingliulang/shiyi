package com.shiyi.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    /** 识别后默认保留原图（默认否） */
    val keepImagesByDefault: Boolean = false,
    /** 保存订单号指纹（SHA-256）用于去重（默认是；不保存明文） */
    val storeOrderFingerprint: Boolean = true,
    /** 在最近任务中隐藏内容并禁止截屏 */
    val secureWindow: Boolean = false,
    /** 扫条码后联网查询商品名称/品牌（只发送条码） */
    val onlineBarcode: Boolean = true,
    /** WebDAV（坚果云）设置；密码经 Keystore 加密保存 */
    val davUrl: String = DEFAULT_DAV_URL,
    val davUser: String = "",
    val davPasswordEnc: String = "",
    val davFolder: String = "食忆备份",
    val lastCloudBackup: Long = 0L,
    /** 云备份是否包含零食照片（默认是）/ 订单截图（默认否）；包含时会再压缩一档 */
    val cloudSnackImages: Boolean = true,
    val cloudRecordImages: Boolean = false,
    /** 上次手动备份到本机文件的时间 */
    val lastLocalBackup: Long = 0L,
    /** 定期自动备份：间隔天数（0 = 关闭）；密码经 Keystore 加密保存 */
    val autoDays: Int = 0,
    val autoPasswordEnc: String = "",
    val autoHint: String = "",
    val autoToCloud: Boolean = false,
    /** 本机备份文件夹（系统文件选择器授权的目录 URI），空 = 不备份到本机 */
    val autoDirUri: String = "",
    val lastAutoBackup: Long = 0L,
    /** 上次自动备份时的数据指纹，没有变化就跳过 */
    val lastAutoSignature: String = "",
    /** 最近一次自动备份失败的原因（成功后清空） */
    val autoError: String = "",
    val autoErrorAt: Long = 0L,
    /** 首页“该备份了”提醒：在此时间之前不再提醒 */
    val backupReminderSnoozeUntil: Long = 0L,
) {
    val davConfigured: Boolean get() = davUrl.isNotBlank() && davUser.isNotBlank() && davPasswordEnc.isNotBlank()
    val autoEnabled: Boolean get() = autoDays > 0 && autoPasswordEnc.isNotBlank() && (autoToCloud || autoDirUri.isNotBlank())
    /** 任意一种方式最近一次成功备份的时间 */
    val lastAnyBackup: Long get() = maxOf(lastCloudBackup, lastLocalBackup, lastAutoBackup)

    companion object { const val DEFAULT_DAV_URL = "https://dav.jianguoyun.com/dav/" }
}

class SettingsStore(private val context: Context) {
    private val keepKey = booleanPreferencesKey("keep_images_default")
    private val fpKey = booleanPreferencesKey("store_order_fingerprint")
    private val secureKey = booleanPreferencesKey("secure_window")
    private val onlineBarcodeKey = booleanPreferencesKey("online_barcode")
    private val davUrlKey = stringPreferencesKey("dav_url")
    private val davUserKey = stringPreferencesKey("dav_user")
    private val davPassKey = stringPreferencesKey("dav_pass_enc")
    private val davFolderKey = stringPreferencesKey("dav_folder")
    private val lastCloudKey = longPreferencesKey("last_cloud_backup")
    private val cloudSnackImgKey = booleanPreferencesKey("cloud_snack_images")
    private val cloudRecordImgKey = booleanPreferencesKey("cloud_record_images")
    private val lastLocalKey = longPreferencesKey("last_local_backup")
    private val autoDaysKey = intPreferencesKey("auto_days")
    private val autoPassKey = stringPreferencesKey("auto_pass_enc")
    private val autoHintKey = stringPreferencesKey("auto_hint")
    private val autoCloudKey = booleanPreferencesKey("auto_to_cloud")
    private val autoDirKey = stringPreferencesKey("auto_dir_uri")
    private val lastAutoKey = longPreferencesKey("last_auto_backup")
    private val autoSigKey = stringPreferencesKey("last_auto_signature")
    private val autoErrKey = stringPreferencesKey("auto_error")
    private val autoErrAtKey = longPreferencesKey("auto_error_at")
    private val snoozeKey = longPreferencesKey("backup_reminder_snooze")

    val settings: Flow<AppSettings> = context.dataStore.data.map {
        AppSettings(
            keepImagesByDefault = it[keepKey] ?: false,
            storeOrderFingerprint = it[fpKey] ?: true,
            secureWindow = it[secureKey] ?: false,
            onlineBarcode = it[onlineBarcodeKey] ?: true,
            davUrl = it[davUrlKey] ?: AppSettings.DEFAULT_DAV_URL,
            davUser = it[davUserKey].orEmpty(),
            davPasswordEnc = it[davPassKey].orEmpty(),
            davFolder = it[davFolderKey] ?: "食忆备份",
            lastCloudBackup = it[lastCloudKey] ?: 0L,
            cloudSnackImages = it[cloudSnackImgKey] ?: true,
            cloudRecordImages = it[cloudRecordImgKey] ?: false,
            lastLocalBackup = it[lastLocalKey] ?: 0L,
            autoDays = it[autoDaysKey] ?: 0,
            autoPasswordEnc = it[autoPassKey].orEmpty(),
            autoHint = it[autoHintKey].orEmpty(),
            autoToCloud = it[autoCloudKey] ?: false,
            autoDirUri = it[autoDirKey].orEmpty(),
            lastAutoBackup = it[lastAutoKey] ?: 0L,
            lastAutoSignature = it[autoSigKey].orEmpty(),
            autoError = it[autoErrKey].orEmpty(),
            autoErrorAt = it[autoErrAtKey] ?: 0L,
            backupReminderSnoozeUntil = it[snoozeKey] ?: 0L,
        )
    }

    suspend fun setKeepImages(v: Boolean) = context.dataStore.edit { it[keepKey] = v }
    suspend fun setStoreFingerprint(v: Boolean) = context.dataStore.edit { it[fpKey] = v }
    suspend fun setSecureWindow(v: Boolean) = context.dataStore.edit { it[secureKey] = v }
    suspend fun setOnlineBarcode(v: Boolean) = context.dataStore.edit { it[onlineBarcodeKey] = v }
    suspend fun setCloudSnackImages(v: Boolean) = context.dataStore.edit { it[cloudSnackImgKey] = v }
    suspend fun setCloudRecordImages(v: Boolean) = context.dataStore.edit { it[cloudRecordImgKey] = v }
    suspend fun setLastCloudBackup(v: Long) = context.dataStore.edit { it[lastCloudKey] = v }
    suspend fun setLastLocalBackup(v: Long) = context.dataStore.edit { it[lastLocalKey] = v }
    suspend fun snoozeBackupReminder(until: Long) = context.dataStore.edit { it[snoozeKey] = until }

    /** 保存自动备份设置。[passwordPlain] 为空表示沿用已保存的密码。 */
    suspend fun setAutoBackup(days: Int, toCloud: Boolean, dirUri: String, passwordPlain: String?, hint: String) = context.dataStore.edit {
        it[autoDaysKey] = days
        it[autoCloudKey] = toCloud
        it[autoDirKey] = dirUri
        it[autoHintKey] = hint.trim()
        if (passwordPlain != null) {
            it[autoPassKey] = SecretStore.encrypt(passwordPlain)
            // 换了密码：下次无论数据是否变化都重新备份一份
            it.remove(autoSigKey)
        }
        it.remove(autoErrKey); it.remove(autoErrAtKey)
    }

    suspend fun turnOffAutoBackup() = context.dataStore.edit {
        it[autoDaysKey] = 0
        it[autoCloudKey] = false
        it.remove(autoDirKey); it.remove(autoPassKey); it.remove(autoHintKey)
        it.remove(autoSigKey); it.remove(autoErrKey); it.remove(autoErrAtKey)
    }

    suspend fun setAutoResult(at: Long, signature: String) = context.dataStore.edit {
        it[lastAutoKey] = at; it[autoSigKey] = signature
        it.remove(autoErrKey); it.remove(autoErrAtKey)
    }

    suspend fun setAutoError(message: String, at: Long) = context.dataStore.edit {
        it[autoErrKey] = message.take(300); it[autoErrAtKey] = at
    }

    suspend fun setDav(url: String, user: String, passwordPlain: String?, folder: String) = context.dataStore.edit {
        it[davUrlKey] = url.trim()
        it[davUserKey] = user.trim()
        it[davFolderKey] = folder.trim().trim('/').ifBlank { "食忆备份" }
        if (passwordPlain != null) it[davPassKey] = SecretStore.encrypt(passwordPlain)
    }

    suspend fun clearDav() = context.dataStore.edit {
        it.remove(davUserKey); it.remove(davPassKey); it.remove(lastCloudKey)
        it[autoCloudKey] = false
    }
}
