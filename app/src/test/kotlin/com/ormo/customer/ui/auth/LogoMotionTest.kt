package com.ormo.customer.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.0 스플래시 로고 모션 (Figma 키프레임): 구간·순서·끝 상태 */
class LogoMotionTest {
    private val eps = 0.001f

    @Test fun `시작 - 아무것도 보이지 않고 컵은 28 위, 김은 10 아래, 워드마크는 12 왼쪽`() {
        val s = LogoMotion.at(0f)
        assertEquals(0f, s.cup.opacity, eps); assertEquals(-28f, s.cup.offsetY, eps)
        assertEquals(10f, s.steamLeft.offsetY, eps); assertEquals(10f, s.steamRight.offsetY, eps)
        assertEquals(-12f, s.wordmark.offsetX, eps); assertEquals(0f, s.wordmark.opacity, eps)
    }

    @Test fun `컵은 0_05초에 시작하고 0_3초에 완전히 보인다`() {
        assertEquals(0f, LogoMotion.at(50f).cup.opacity, eps)
        assertTrue(LogoMotion.at(150f).cup.opacity in 0.01f..0.99f)
        assertEquals(1f, LogoMotion.at(300f).cup.opacity, eps)
    }

    @Test fun `컵은 내려앉은 뒤 2만큼 튕겼다가 0 으로 돌아온다`() {
        assertEquals(0f, LogoMotion.at(420f).cup.offsetY, eps)
        assertEquals(2f, LogoMotion.at(500f).cup.offsetY, eps)
        assertEquals(0f, LogoMotion.at(600f).cup.offsetY, eps)
    }

    @Test fun `순서 - 컵 다음 왼쪽 김, 오른쪽 김, 워드마크 (먼저 나타나는 쪽이 더 보인다)`() {
        val s = LogoMotion.at(520f)
        assertTrue(s.cup.opacity > s.steamLeft.opacity)
        assertTrue(s.steamLeft.opacity > s.steamRight.opacity)
        assertTrue(s.steamRight.opacity > s.wordmark.opacity)
        assertEquals(0f, s.wordmark.opacity, eps) // 워드마크는 0.6초부터
    }

    @Test fun `워드마크는 0_6초부터 밀려 들어와 1초에 제자리`() {
        assertEquals(-12f, LogoMotion.at(600f).wordmark.offsetX, eps)
        assertTrue(LogoMotion.at(800f).wordmark.offsetX in -11.9f..-0.1f)
        assertEquals(0f, LogoMotion.at(1000f).wordmark.offsetX, eps)
    }

    @Test fun `끝난 뒤에는 값이 변하지 않고 이것이 완성 로고`() {
        assertEquals(LogoMotion.FINAL, LogoMotion.at(1000f))
        assertEquals(LogoMotion.FINAL, LogoMotion.at(5000f))
        val f = LogoMotion.FINAL
        listOf(f.cup, f.steamLeft, f.steamRight, f.wordmark).forEach {
            assertEquals(1f, it.opacity, eps); assertEquals(0f, it.offsetX, eps); assertEquals(0f, it.offsetY, eps)
        }
    }

    @Test fun `불투명도는 시간이 지나면 줄지 않는다`() {
        var prev = 0f
        for (t in 0..1100 step 10) {
            val o = LogoMotion.at(t.toFloat()).wordmark.opacity
            assertTrue(o >= prev - eps); prev = o
        }
    }
}
