package com.shiyi.app

import androidx.test.core.app.ApplicationProvider
import com.shiyi.app.data.AppSettings
import com.shiyi.app.data.RecordEntity
import com.shiyi.app.data.SnackEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** 定期自动备份：到期判断、数据指纹（没变化就跳过） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutoBackupTest {
    private val day = TimeUnit.DAYS.toMillis(1)

    @Test fun dueOnlyWhenEnabledAndIntervalPassed() {
        val auto = ApplicationProvider.getApplicationContext<ShiYiApp>().container.autoBackup
        val now = 100 * day
        val on = AppSettings(autoDays = 3, autoPasswordEnc = "x", autoDirUri = "content://tree")
        assertTrue(auto.isDue(on, now)) // 从没备份过
        assertFalse(auto.isDue(on.copy(lastAutoBackup = now - 2 * day), now))
        assertTrue(auto.isDue(on.copy(lastAutoBackup = now - 3 * day), now))
        // 留 1 小时余量：系统稍早几分钟调度也算到期
        assertTrue(auto.isDue(on.copy(lastAutoBackup = now - 3 * day + TimeUnit.MINUTES.toMillis(30)), now))
        // 没开、没密码、没有备份位置：都不算开启
        assertFalse(auto.isDue(on.copy(autoDays = 0), now))
        assertFalse(auto.isDue(on.copy(autoPasswordEnc = ""), now))
        assertFalse(auto.isDue(on.copy(autoDirUri = "", autoToCloud = false), now))
    }

    @Test fun signatureChangesWithData() = runBlocking {
        val c = ApplicationProvider.getApplicationContext<ShiYiApp>().container
        val empty = c.backup.dataSignature()
        assertEquals(empty, c.backup.dataSignature())

        val r = RecordEntity(id = "r1", shopName = "店", scenario = "DELIVERY", platform = "MEITUAN", paidCents = 100,
            orderTime = 1, rating = 8, createdAt = 1, updatedAt = 1)
        c.repo.saveRecord(r, emptyList(), emptyList(), emptyList())
        val one = c.backup.dataSignature()
        assertNotEquals(empty, one)

        // 修改（保存时 updatedAt 取当前时间，隔几毫秒确保不同）
        Thread.sleep(5)
        c.repo.saveRecord(r.copy(rating = 3, updatedAt = 2), emptyList(), emptyList(), emptyList())
        val edited = c.backup.dataSignature()
        assertNotEquals(one, edited)

        // 新增零食
        c.snacks.save(SnackEntity(id = "s1", name = "薯片", purchaseDate = 1, createdAt = 1, updatedAt = 1), emptyList(), emptyList())
        assertNotEquals(edited, c.backup.dataSignature())
    }

    @Test fun lastAnyBackupTakesLatest() {
        val s = AppSettings(lastCloudBackup = 5, lastLocalBackup = 9, lastAutoBackup = 7)
        assertEquals(9L, s.lastAnyBackup)
    }
}
