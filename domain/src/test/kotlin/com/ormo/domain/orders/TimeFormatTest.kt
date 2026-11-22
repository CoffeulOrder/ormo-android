package com.ormo.domain.orders

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeFormatTest {
    // T0 = 2026-11-19(목) 13:00 (Asia/Seoul)
    @Test fun `날짜는 yy_MM_dd 와 요일`() {
        assertEquals("26.11.19 (목)", TimeFormat.dateDow(T0))
        assertEquals("26.11.19 (목) 13:00", TimeFormat.dateDowClock(T0))
        assertEquals("2026.11.19 13:00", TimeFormat.fullDateTime(T0))
    }

    @Test fun `요일은 월요일부터 일요일까지`() {
        assertEquals("26.11.16 (월)", TimeFormat.dateDow(T0.minusSeconds(3 * 86_400)))
        assertEquals("26.11.22 (일)", TimeFormat.dateDow(T0.plusSeconds(3 * 86_400)))
    }
}

class LiveBarNumberTest {
    @Test fun `주문 현황 바의 번호 상자는 픽업 번호`() {
        assertEquals(27, LiveOrderRules.bar(listOf(order()))!!.pickupNumber)
        assertEquals(null, LiveOrderRules.bar(listOf(order(number = null)))!!.pickupNumber)
    }
}
