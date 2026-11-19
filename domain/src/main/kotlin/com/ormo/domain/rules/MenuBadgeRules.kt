package com.ormo.domain.rules

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class MenuBadge { SOLD_OUT, NEW, POPULAR }

/**
 * 메뉴 뱃지 (디자인 2.1.1): 한 메뉴에는 하나만, 우선순위 품절 > 신규 > 인기.
 * - 신규: 등록일부터 14일 (등록일 당일이 0일째 → 13일째까지)
 * - 인기: 서버가 매일 새벽 계산한 결과를 `isPopular` 로 받는다 (최근 7일 주문 상위 3개 중 최소 주문 수 초과 — 설정값)
 */
object MenuBadgeRules {
    const val NEW_DAYS = 14

    fun badge(soldOut: Boolean, registeredOn: LocalDate?, isPopular: Boolean, today: LocalDate): MenuBadge? {
        if (soldOut) return MenuBadge.SOLD_OUT
        if (registeredOn != null) {
            val days = ChronoUnit.DAYS.between(registeredOn, today)
            if (days in 0 until NEW_DAYS) return MenuBadge.NEW
        }
        return if (isPopular) MenuBadge.POPULAR else null
    }
}
