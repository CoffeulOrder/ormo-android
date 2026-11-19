package com.ormo.domain.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderStatusTest {

    @Test fun `서버 상태 이름과 별칭을 해석한다`() {
        assertEquals(OrderStatus.REQUESTED, OrderStatus.parse("REQUESTED"))
        assertEquals(OrderStatus.REQUESTED, OrderStatus.parse("PENDING"))
        assertEquals(OrderStatus.MAKING, OrderStatus.parse("ACCEPTED"))
        assertEquals(OrderStatus.COMPLETED, OrderStatus.parse("PICKED_UP"))
        assertEquals(OrderStatus.UNCLAIMED, OrderStatus.parse("DISCARDED"))
        assertEquals(OrderStatus.PENDING_PAYMENT, OrderStatus.parse("PENDING_PAYMENT"))
    }

    @Test fun `대소문자와 공백은 무시한다`() {
        assertEquals(OrderStatus.READY, OrderStatus.parse(" ready "))
    }

    @Test fun `모르는 값과 null 은 null`() {
        assertNull(OrderStatus.parse("TELEPORTED"))
        assertNull(OrderStatus.parse(""))
        assertNull(OrderStatus.parse(null))
    }

    @Test fun `모르는 상태의 주문은 목록에서 건너뛴다`() {
        val parsed = listOf("REQUESTED", "FUTURE_STATE", "COMPLETED").mapNotNull { OrderStatus.parse(it) }
        assertEquals(listOf(OrderStatus.REQUESTED, OrderStatus.COMPLETED), parsed)
    }

    @Test fun `진행 중 상태`() {
        val active = OrderStatus.entries.filter { it.isActive }
        assertEquals(listOf(OrderStatus.REQUESTED, OrderStatus.MAKING, OrderStatus.READY), active)
    }

    @Test fun `취소는 접수 대기에서만 가능`() {
        assertEquals(listOf(OrderStatus.REQUESTED), OrderStatus.entries.filter { it.isCancelable })
    }

    @Test fun `내 주문 목록에는 지난 주문만`() {
        val history = OrderStatus.entries.filter { it.showsInHistory }.toSet()
        assertEquals(
            setOf(
                OrderStatus.COMPLETED, OrderStatus.REJECTED, OrderStatus.CANCELED,
                OrderStatus.REFUNDED, OrderStatus.UNCLAIMED,
            ),
            history,
        )
        assertFalse(OrderStatus.EXPIRED.showsInHistory)
        assertFalse(OrderStatus.PENDING_PAYMENT.showsInHistory)
        assertFalse(OrderStatus.MAKING.showsInHistory)
    }

    @Test fun `진행 중 상태는 목록에도 현황 바에도 동시에 나오지 않는다`() {
        OrderStatus.entries.forEach { s -> assertFalse(s.isActive && s.showsInHistory, s.name) }
    }

    @Test fun `주문 현황 바 단계`() {
        assertEquals(LiveBarStage.CONFIRMING, OrderStatus.REQUESTED.liveBarStage)
        assertEquals(LiveBarStage.MAKING, OrderStatus.MAKING.liveBarStage)
        assertEquals(LiveBarStage.READY, OrderStatus.READY.liveBarStage)
        assertNull(OrderStatus.COMPLETED.liveBarStage)
        assertNull(OrderStatus.PENDING_PAYMENT.liveBarStage)
        assertTrue(OrderStatus.entries.filter { it.liveBarStage != null }.all { it.isActive })
    }

    @Test fun `받을 기한은 준비 시각 더하기 폐기 시간`() {
        val ready = Instant.parse("2026-11-19T04:30:00Z")
        assertEquals(Instant.parse("2026-11-19T04:50:00Z"), OrderRules.pickupDeadline(ready, null))
        assertEquals(Instant.parse("2026-11-19T04:40:00Z"), OrderRules.pickupDeadline(ready, 10))
    }
}
