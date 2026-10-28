package com.shiyi.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.shiyi.app.backup.BackupCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** v1.0.0 生成的备份（formatVersion=1，没有零食字段）在新版本里仍能恢复 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OldBackupCompatTest {
    @Test fun restoreV1Backup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ShiYiApp>()
        val json = """{"formatVersion":1,"appVersion":"1.0.0","dbVersion":1,"exportedAt":1,
            "records":[{"id":"r1","shopName":"店A","title":"","scenario":"DINE_IN","platform":"OFFLINE","paidCents":1234,
              "orderTime":1,"consumeTime":null,"rating":7,"reasonTags":"","note":"","repurchaseIntent":null,"keywords":"",
              "orderNoHash":null,"createdAt":1,"updatedAt":1}],
            "foodItems":[],"links":[],"images":[]}"""
        val zip = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z -> z.putNextEntry(ZipEntry("data.json")); z.write(json.toByteArray()); z.closeEntry() }
        }.toByteArray()
        val f = File(app.cacheDir, "old.shiyibak")
        f.outputStream().use { BackupCrypto.encrypt(zip.inputStream(), it, "oldpassword".toCharArray(), "", 1000) }
        val s = app.container.backup.restore(Uri.fromFile(f), "oldpassword".toCharArray())
        assertEquals(1, s.records); assertEquals(0, s.snacks)
        assertEquals(1234L, app.container.repo.getRecord("r1")!!.paidCents)
    }
}
