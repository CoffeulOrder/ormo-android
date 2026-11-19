package com.ormo.domain.rules

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MenuBadgeRulesTest {
    private val today = LocalDate.of(2026, 11, 19)

    @Test fun `품절이 신규와 인기보다 우선`() {
        assertEquals(MenuBadge.SOLD_OUT, MenuBadgeRules.badge(true, today, true, today))
    }

    @Test fun `신규가 인기보다 우선`() {
        assertEquals(MenuBadge.NEW, MenuBadgeRules.badge(false, today.minusDays(3), true, today))
    }

    @Test fun `등록일부터 14일 동안만 신규`() {
        assertEquals(MenuBadge.NEW, MenuBadgeRules.badge(false, today, false, today))
        assertEquals(MenuBadge.NEW, MenuBadgeRules.badge(false, today.minusDays(13), false, today))
        assertNull(MenuBadgeRules.badge(false, today.minusDays(14), false, today))
    }

    @Test fun `미래 등록일은 신규가 아니다`() {
        assertNull(MenuBadgeRules.badge(false, today.plusDays(1), false, today))
    }

    @Test fun `인기만 해당하면 인기`() {
        assertEquals(MenuBadge.POPULAR, MenuBadgeRules.badge(false, today.minusDays(30), true, today))
        assertEquals(MenuBadge.POPULAR, MenuBadgeRules.badge(false, null, true, today))
    }

    @Test fun `아무것도 아니면 뱃지 없음`() {
        assertNull(MenuBadgeRules.badge(false, null, false, today))
    }
}
