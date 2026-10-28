package com.shiyi.app

import com.shiyi.app.domain.Platform
import com.shiyi.app.domain.Scenario
import com.shiyi.app.ocr.OrderParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class OrderParserTest {
    private fun t(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis

    private val now = t(2026, 10, 25, 12, 0)

    @Test fun meituanDelivery() {
        val lines = listOf(
            "订单已送达",
            "感谢您对美团外卖的信任",
            "老王牛肉面（科技园店） >",
            "招牌红烧牛肉面 x1 ¥28",
            "卤蛋 x2 ¥6",
            "打包费 ¥2",
            "配送费 ¥3",
            "满减优惠 -¥8",
            "红包 -¥3",
            "实付 ¥28",
            "订单号 3301 2345 6789 0123",
            "下单时间 2026-10-20 12:03:45",
            "期望时间 立即送达",
        )
        val p = OrderParser.parse(lines, now)
        assertEquals(Platform.MEITUAN, p.platform)
        assertEquals(Scenario.DELIVERY, p.scenario)
        assertEquals("老王牛肉面（科技园店）", p.shopName)
        assertEquals(2800L, p.bestAmountCents)
        assertEquals(t(2026, 10, 20, 12, 3, 45), p.orderTime)
        assertEquals(listOf("招牌红烧牛肉面", "卤蛋"), p.itemNames)
        assertEquals("招牌红烧牛肉面 等2件", p.title)
        assertEquals("3301234567890123", p.orderNo)
        // 优惠/配送费不作为候选
        assertTrue(p.amountCandidates.none { it.cents == 300L && it.confidence >= 2 })
        assertTrue(p.amountCandidates.none { it.cents == 800L })
    }

    @Test fun douyinGroupBuy() {
        val lines = listOf(
            "抖音团购",
            "店铺: 川味小馆",
            "双人超值套餐 x1",
            "有效期 2026-10-01 至 2026-12-31",
            "实付款",
            "¥99.9",
            "购买时间 2026/10/02 19:20",
            "核销时间 2026年10月10日 18:05",
            "券码 1234 5678 9012",
        )
        val p = OrderParser.parse(lines, now)
        assertEquals(Platform.DOUYIN, p.platform)
        assertEquals(Scenario.GROUP_BUY, p.scenario)
        assertEquals("川味小馆", p.shopName)
        assertEquals(9990L, p.bestAmountCents)
        assertEquals(t(2026, 10, 2, 19, 20), p.orderTime)
        assertEquals(t(2026, 10, 10, 18, 5), p.consumeTime)
        assertEquals("双人超值套餐", p.title)
    }

    @Test fun unlabeledAmountsAreNotGuessed() {
        val p = OrderParser.parse(listOf("某某烧烤", "羊肉串 ¥30", "鸡翅 ¥18"), now)
        assertNull(p.bestAmountCents)
        assertTrue(p.amountCandidates.map { it.cents }.containsAll(listOf(3000L, 1800L)))
    }

    @Test fun shortDateUsesCurrentOrPreviousYear() {
        assertEquals(t(2026, 10, 3, 9, 30), OrderParser.parseTime("10-03 09:30", now))
        // 12 月的无年份时间晚于当前 → 视为去年
        assertEquals(t(2025, 12, 28, 20, 0), OrderParser.parseTime("12月28日 20:00", now))
    }

    @Test fun emptyInputIsSafe() {
        val p = OrderParser.parse(emptyList(), now)
        assertNull(p.shopName); assertNull(p.bestAmountCents); assertNull(p.platform); assertNull(p.orderTime)
    }

    @Test fun totalLabelWorksWhenNoPaidLabel() {
        val p = OrderParser.parse(listOf("饿了么", "合计 ¥45.50"), now)
        assertEquals(Platform.ELEME, p.platform)
        assertEquals(4550L, p.bestAmountCents)
    }
}

/** 用户提供的真实截图（美团外卖“订单已完成”页）按 OCR 行合并后的文字 */
class RealScreenshotTest {
    @Test fun meituanCompletedOrder() {
        val lines = listOf(
            "19:59 8: 2.00 KB/S 84",
            "订单已完成",
            "外卖 新一小厨",
            "进店 >",
            "丁字沽街快餐便当第1名 30日回头客432人",
            "黑椒牛肉盖浇饭假装 ⓘ到手 ¥11.17",
            "¥24.8",
            "默认（1克）",
            "数量 ×1",
            "商品总价 ¥24.8",
            "打包费 ¥1",
            "运费 ⓘ 已减¥4 ¥4.2 ¥0.2",
            "超级月卡 - ¥0.63",
            "优惠券 - ¥13",
            "合计 共减¥17.63(超级月卡省0.63元) ¥12.37",
            "点击收起",
            "快速解决问题",
            "怎么查看我的发... 怎么申请售后 更多",
            "安心保障 查看全部 >",
            "售后保障",
            "其他保险服务 提供多险种保险服务 去查看 >",
            "你的13元超级餐补券已到账，快去看看吧",
        )
        val p = OrderParser.parse(lines)
        assertEquals(Scenario.DELIVERY, p.scenario)
        assertEquals("新一小厨", p.shopName)
        assertEquals(1237L, p.bestAmountCents)
        assertEquals(listOf("黑椒牛肉盖浇饭假装"), p.itemNames)
        assertEquals("黑椒牛肉盖浇饭假装", p.title)
        // 优惠、运费、打包费、共减都不能成为候选
        val cents = p.amountCandidates.map { it.cents }
        listOf(1763L, 63L, 1300L, 400L, 420L, 20L, 100L).forEach { assertTrue("不应出现 $it", it !in cents) }
    }
}
