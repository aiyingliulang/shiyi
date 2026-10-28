package com.shiyi.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import com.shiyi.app.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v1.0.0 → v1.0.1：数据库 1 → 2（新增零食表），原有数据必须完整保留 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test fun migrate1To2KeepsData() {
        val name = "migration-test.db"
        helper.createDatabase(name, 1).apply {
            execSQL("""INSERT INTO records (id, shopName, title, scenario, platform, paidCents, orderTime, consumeTime, rating,
                reasonTags, note, repurchaseIntent, keywords, orderNoHash, createdAt, updatedAt)
                VALUES ('r1','老王牛肉面','','DELIVERY','MEITUAN',2800,1000,NULL,9,'','好吃',NULL,'牛肉面',NULL,1000,1000)""")
            execSQL("""INSERT INTO food_items (id, name, shopName, spec, status, statusReason, statusAt, note, createdAt, updatedAt)
                VALUES ('f1','红烧牛肉面','老王牛肉面','大碗','AVOID','太咸',2000,'',1000,2000)""")
            execSQL("INSERT INTO record_food_links (recordId, foodItemId, isSole, itemPriceCents, createdAt) VALUES ('r1','f1',1,2800,1000)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 2, true)
        db.query("SELECT paidCents, note FROM records WHERE id='r1'").use { c ->
            c.moveToFirst(); assertEquals(2800L, c.getLong(0)); assertEquals("好吃", c.getString(1))
        }
        db.query("SELECT status, statusReason FROM food_items WHERE id='f1'").use { c ->
            c.moveToFirst(); assertEquals("AVOID", c.getString(0)); assertEquals("太咸", c.getString(1))
        }
        db.query("SELECT COUNT(*) FROM record_food_links").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        // 新表可用
        db.execSQL("INSERT INTO snacks (id, name, brand, flavor, barcode, store, priceCents, purchaseDate, rating, verdict, tags, note, createdAt, updatedAt) VALUES ('s1','薯片','','','6901234567890','',NULL,1,2,'AVOID','','',1,1)")
        db.query("SELECT COUNT(*) FROM snacks WHERE barcode='6901234567890'").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.close()
        ApplicationProvider.getApplicationContext<ShiYiApp>().deleteDatabase(name)
    }
}
