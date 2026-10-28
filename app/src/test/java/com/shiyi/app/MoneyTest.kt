package com.shiyi.app

import com.shiyi.app.domain.Money
import com.shiyi.app.domain.ReasonTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {
    @Test fun parse() {
        assertEquals(2350L, Money.parseYuan("23.5"))
        assertEquals(1L, Money.parseYuan("0.01"))
        assertEquals(1000L, Money.parseYuan("¥10"))
        assertEquals(30L, Money.parseYuan("0.3")) // 浮点 0.1+0.2 问题不存在
        assertNull(Money.parseYuan("1.234"))
        assertNull(Money.parseYuan("abc"))
        assertNull(Money.parseYuan(""))
    }

    @Test fun format() {
        assertEquals("¥23.5", Money.format(2350))
        assertEquals("¥10", Money.format(1000))
        assertEquals("¥0.05", Money.format(5))
        assertEquals("23.50", Money.plain(2350))
        assertEquals("23.5", Money.editable(2350))
    }

    @Test fun deliveryAttribution() {
        assertTrue(ReasonTag.mostlyDelivery(setOf(ReasonTag.SLOW_DELIVERY, ReasonTag.COLD, ReasonTag.TASTY)))
        assertFalse(ReasonTag.mostlyDelivery(setOf(ReasonTag.SLOW_DELIVERY, ReasonTag.BAD_TASTE)))
        assertFalse(ReasonTag.mostlyDelivery(emptySet()))
        assertEquals(setOf(ReasonTag.TASTY, ReasonTag.PRICEY), ReasonTag.parse(ReasonTag.encode(listOf(ReasonTag.PRICEY, ReasonTag.TASTY))))
    }
}

class SnackVerdictTest {
    @org.junit.Test fun suggest() {
        org.junit.Assert.assertEquals(com.shiyi.app.domain.SnackVerdict.AVOID, com.shiyi.app.domain.SnackVerdict.suggest(2))
        org.junit.Assert.assertEquals(com.shiyi.app.domain.SnackVerdict.OK, com.shiyi.app.domain.SnackVerdict.suggest(6))
        org.junit.Assert.assertEquals(com.shiyi.app.domain.SnackVerdict.BUY_AGAIN, com.shiyi.app.domain.SnackVerdict.suggest(9))
        org.junit.Assert.assertEquals(com.shiyi.app.domain.SnackVerdict.NONE, com.shiyi.app.domain.SnackVerdict.suggest(null))
    }
}
