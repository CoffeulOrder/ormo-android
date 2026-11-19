package com.ormo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreTest {

    @Test fun `금액 포맷`() {
        assertEquals("12,000원", formatWon(12000))
        assertEquals("0원", formatWon(0))
        assertEquals("19,500원", formatWon(19500))
        assertEquals("999원", formatWon(999))
        assertEquals("1,000,000원", formatWon(1_000_000))
    }

    @Test fun `카운트다운 포맷`() {
        assertEquals("14:59", formatCountdown(899))
        assertEquals("4:59", formatCountdown(299))
        assertEquals("0:59", formatCountdown(59))
        assertEquals("10:00", formatCountdown(600))
        assertEquals("0:00", formatCountdown(0))
        assertEquals("0:00", formatCountdown(-5))
    }

    @Test fun `남은 초는 올림이고 끝나면 0`() {
        val c = Countdown(endAtMillis = 10_000)
        assertEquals(10, c.remainingSeconds(0))
        assertEquals(1, c.remainingSeconds(9_001))
        assertEquals(1, c.remainingSeconds(9_999))
        assertEquals(0, c.remainingSeconds(10_000))
        assertEquals(0, c.remainingSeconds(11_000))
        assertFalse(c.isFinished(9_999))
        assertTrue(c.isFinished(10_000))
    }

    @Test fun `베타 기능 플래그는 기본이 모두 꺼짐`() {
        val f = FeatureFlags()
        assertFalse(f.coupons || f.events || f.myMenu || f.referral)
    }

    @Test fun `세션 초기화는 등록된 모든 저장소를 비운다`() {
        class Fake : Resettable {
            var resets = 0
            override fun reset() { resets++ }
        }
        val a = Fake()
        val b = Fake()
        SessionResetter(listOf(a, b)).resetAll()
        assertEquals(1, a.resets)
        assertEquals(1, b.resets)
    }
}
