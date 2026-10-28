package com.shiyi.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.shiyi.app.data.LinkDraft
import com.shiyi.app.data.RecordEntity
import com.shiyi.app.data.SearchFilter
import com.shiyi.app.domain.ItemStatus
import com.shiyi.app.domain.Platform
import com.shiyi.app.domain.Scenario
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 在真实 Room/SQLite 上验证：保存 → 跨平台关联 → 不再推荐 → 搜索 → 加密备份 → 清空 → 恢复 → 数据一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupRestoreRoundTripTest {

    private fun rec(id: String, shop: String, platform: Platform, cents: Long, rating: Int?, t: Long, keywords: String = "") = RecordEntity(
        id = id, shopName = shop, title = "", scenario = Scenario.DELIVERY.name, platform = platform.name,
        paidCents = cents, orderTime = t, rating = rating, keywords = keywords, createdAt = t, updatedAt = t,
    )

    @Test fun fullRoundTrip() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ShiYiApp>()
        val c = app.container
        val repo = c.repo

        // 1. 单品订单（美团）建立档案
        val img = File(c.staging.dir, "shot.png").apply { writeBytes(kotlin.random.Random(7).nextBytes(4096)) }
        repo.saveRecord(
            rec("r1", "老王牛肉面", Platform.MEITUAN, 2800, 9, 1_000_000L),
            listOf(LinkDraft(null, "红烧牛肉面", "老王牛肉面", "大碗", true, 2800)),
            listOf(img), emptyList(),
        )
        val itemId = repo.linksForRecordOnce("r1").single().item.id
        // 2. 同一商品在抖音购买，关联到同一档案，价格独立
        repo.saveRecord(
            rec("r2", "老王牛肉面", Platform.DOUYIN, 2500, 3, 2_000_000L),
            listOf(LinkDraft(itemId, "红烧牛肉面", "老王牛肉面", "大碗", true, 2500)),
            emptyList(), emptyList(),
        )
        // 3. 多菜订单：不建档案，菜名只做关键词
        repo.saveRecord(rec("r3", "川味小馆", Platform.MEITUAN, 8800, 2, 3_000_000L, keywords = "水煮鱼 夫妻肺片"), emptyList(), emptyList(), emptyList())
        repo.setItemStatus(itemId, ItemStatus.AVOID, "太咸")

        // 跨平台关联后两条记录各自保留
        assertEquals(2800L, repo.getRecord("r1")!!.paidCents)
        assertEquals(2500L, repo.getRecord("r2")!!.paidCents)
        assertEquals(2, repo.recordsForItem(itemId).first().size)
        // 低分多菜订单不会自动产生不再推荐
        assertEquals(1, c.db.foods().all().count { it.status == ItemStatus.AVOID.name })
        // 搜索：按菜名关键词、按档案名
        assertEquals(listOf("r3"), repo.search(SearchFilter(query = "肺片")).first().map { it.id })
        assertEquals(setOf("r1", "r2"), repo.search(SearchFilter(query = "红烧")).first().map { it.id }.toSet())
        assertEquals(listOf("r2"), repo.search(SearchFilter(platform = Platform.DOUYIN.name)).first().map { it.id })
        assertEquals(listOf("r3"), repo.search(SearchFilter(minCents = 5000)).first().map { it.id })
        assertEquals(setOf("r2", "r3"), repo.search(SearchFilter(ratingRange = 1..3)).first().map { it.id }.toSet())
        // 统计来自数据库
        val stats = repo.stats.first()
        assertEquals(3, stats.recordCount); assertEquals(2, stats.avoidCount) // r2=3分、r3=2分 自动归入不再推荐
        // 订单号指纹去重
        assertNotNull(repo.findDuplicate(rec("rX", "老王牛肉面", Platform.MEITUAN, 2800, null, 1_000_000L + 60_000)))

        // 零食：带包装照片和条码
        val snackPhoto = File(c.staging.dir, "pack.jpg").apply { writeBytes(kotlin.random.Random(9).nextBytes(3000)) }
        c.snacks.save(
            com.shiyi.app.data.SnackEntity(id = "s1", name = "番茄味薯片", brand = "某牌", barcode = "6901234567890",
                purchaseDate = 5_000L, rating = 2, verdict = com.shiyi.app.domain.SnackVerdict.AVOID.name,
                tags = "TOO_SWEET", note = "包装好看", createdAt = 5_000L, updatedAt = 5_000L),
            listOf(snackPhoto), emptyList(),
        )
        assertEquals(1, c.snacks.byBarcode("6901234567890").size)
        assertEquals(listOf("s1"), c.snacks.search("薯片", null).first().map { it.snack.id })
        assertEquals(1, c.snacks.avoidCount().first())
        val snackImgName = c.snacks.imagesOnce("s1").single().fileName

        val savedImage = repo.imagesForRecordOnce("r1").single()
        val imageBytes = repo.imageFile(savedImage.fileName).readBytes()

        // 4. 加密备份
        val backupFile = File(app.cacheDir, "test.shiyibak")
        val uri = Uri.fromFile(backupFile)
        val s = c.backup.export(uri, "password123".toCharArray(), "hint")
        assertEquals(3, s.records); assertEquals(2, s.images); assertEquals(1, s.snacks)
        assertTrue(backupFile.length() > 2048)
        assertEquals("hint", c.backup.readHint(uri))

        // 5. 错误密码不改动数据
        val wrong = runCatching { c.backup.restore(uri, "nope-nope".toCharArray()) }
        assertTrue(wrong.isFailure)
        assertEquals(3, c.db.records().all().size)

        // 6. 清空当前数据后恢复
        listOf("r1", "r2", "r3").forEach { repo.deleteRecord(it) }
        repo.deleteItem(itemId)
        c.snacks.delete("s1")
        assertEquals(0, c.db.records().all().size)
        assertEquals(0, c.db.snacks().all().size)
        assertTrue(repo.imagesDir.listFiles().isNullOrEmpty())

        val v = c.backup.verify(uri, "password123".toCharArray())
        assertEquals(3, v.records); assertEquals(0, v.missingImages)
        val r = c.backup.restore(uri, "password123".toCharArray())
        assertEquals(3, r.records); assertEquals(1, r.snacks); assertEquals(0, r.missingImages)
        val sn = c.db.snacks().get("s1")!!
        assertEquals("AVOID", sn.verdict); assertEquals("6901234567890", sn.barcode)
        assertEquals(snackImgName, c.snacks.imagesOnce("s1").single().fileName)
        assertTrue(repo.imageFile(snackImgName).exists())

        // 7. 校验恢复后的数据完全一致
        assertEquals(setOf("r1", "r2", "r3"), c.db.records().all().map { it.id }.toSet())
        val item = c.db.foods().get(itemId)!!
        assertEquals(ItemStatus.AVOID.name, item.status); assertEquals("太咸", item.statusReason)
        assertEquals(2, repo.recordsForItem(itemId).first().size)
        assertEquals("水煮鱼 夫妻肺片", repo.getRecord("r3")!!.keywords)
        val restoredImg = repo.imagesForRecordOnce("r1").single()
        assertArrayEquals(imageBytes, repo.imageFile(restoredImg.fileName).readBytes())
        assertNull(repo.getRecord("r3")!!.rating?.takeIf { it != 2 })

        // 8. CSV 导出
        val csv = File(app.cacheDir, "out.csv")
        assertEquals(3, c.backup.exportCsv(Uri.fromFile(csv)))
        val text = csv.readText(Charsets.UTF_8)
        assertTrue(text.startsWith("﻿"))
        assertTrue(text.contains("红烧牛肉面(大碗)[不再推荐]"))
    }
}

/** 所有带 LIKE/ESCAPE 的查询都要在真实 SQLite 上执行一遍（含 % _ \ 等特殊字符） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AllLikeQueriesTest {
    @Test fun everyLikeQueryRuns() = runBlocking {
        val c = ApplicationProvider.getApplicationContext<ShiYiApp>().container
        c.repo.saveRecord(
            RecordEntity(id = "x1", shopName = "新一小厨", scenario = "DELIVERY", platform = "MEITUAN", paidCents = 1237,
                orderTime = 1, createdAt = 1, updatedAt = 1),
            listOf(LinkDraft(null, "黑椒牛肉盖浇饭", "新一小厨", "", true, 1237)), emptyList(), emptyList(),
        )
        c.snacks.save(com.shiyi.app.data.SnackEntity(id = "s9", name = "100%_薯片\\", brand = "乐_事%", purchaseDate = 1, createdAt = 1, updatedAt = 1), emptyList(), emptyList())
        for (q in listOf("新一", "%", "_", "\\", "a'b", "乐_事%")) {
            c.repo.shopSuggestions(q)
            c.repo.itemCandidates(q, "新一小厨")
            c.repo.search(SearchFilter(query = q)).first()
            c.repo.searchItems(q).first()
            c.snacks.search(q, null).first()
            c.snacks.brandSuggestions(q)
        }
        assertEquals(listOf("新一小厨"), c.repo.shopSuggestions("新一"))
        assertEquals(listOf("乐_事%"), c.snacks.brandSuggestions("_事%"))
        assertEquals(listOf("s9"), c.snacks.search("100%_", null).first().map { it.snack.id })
        assertEquals(1, c.repo.itemCandidates("黑椒", "新一小厨").size)
    }
}
