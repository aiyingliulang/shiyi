package com.shiyi.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.shiyi.app.backup.ImageOptions
import com.shiyi.app.data.RecordEntity
import com.shiyi.app.data.SnackEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 云备份：只带零食照片（不带订单截图）；恢复时零食照片来自备份，订单截图保留本机已有的 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudImageOptionsTest {
    @Test fun snackImagesOnly() = runBlocking {
        val c = ApplicationProvider.getApplicationContext<ShiYiApp>().container
        val shot = File(c.staging.dir, "s.png").apply { writeBytes(kotlin.random.Random(1).nextBytes(5000)) }
        c.repo.saveRecord(RecordEntity(id = "r1", shopName = "店", scenario = "DELIVERY", platform = "MEITUAN", paidCents = 100,
            orderTime = 1, rating = 2, createdAt = 1, updatedAt = 1), emptyList(), listOf(shot), emptyList())
        val pack = File(c.staging.dir, "p.jpg").apply { writeBytes(kotlin.random.Random(2).nextBytes(4000)) }
        c.snacks.save(SnackEntity(id = "s1", name = "薯片", purchaseDate = 1, createdAt = 1, updatedAt = 1), listOf(pack), emptyList())
        val recImg = c.repo.imagesForRecordOnce("r1").single().fileName
        val snackImg = c.snacks.imagesOnce("s1").single().fileName

        val f = File(c.appContext.cacheDir, "cloud.shiyibak")
        val sum = c.backup.export(Uri.fromFile(f), "password1".toCharArray(), "", ImageOptions(recordImages = false, snackImages = true, recompressMaxDim = 1080))
        assertEquals(1, sum.images) // 只有零食照片

        // 本机零食照片丢了、记录也改了；恢复后：零食照片从备份回来，订单截图保留
        c.repo.imageFile(snackImg).delete()
        c.snacks.delete("s1")
        val r = c.backup.restore(Uri.fromFile(f), "password1".toCharArray())
        assertEquals(1, r.snacks)
        assertTrue(c.repo.imageFile(snackImg).exists())
        assertEquals(snackImg, c.snacks.imagesOnce("s1").single().fileName)
        assertEquals(recImg, c.repo.imagesForRecordOnce("r1").single().fileName)
        assertTrue(c.repo.imageFile(recImg).exists())
    }
}
