package com.ormo.domain.orders

import com.ormo.domain.model.LiveBarStage
import com.ormo.domain.model.OrderStatus
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveOrderRulesTest {
    @Test fun `진행 중 주문이 없으면 바가 없다`() {
        assertNull(LiveOrderRules.bar(emptyList()))
        assertNull(LiveOrderRules.bar(listOf(order(status = OrderStatus.COMPLETED), order("o2", OrderStatus.REJECTED), order("o3", OrderStatus.PENDING_PAYMENT))))
    }

    @Test fun `주문 확인 중 - 내 앞 N건과 접근성 문구`() {
        val bar = LiveOrderRules.bar(listOf(order()))!!
        assertEquals(LiveBarStage.CONFIRMING, bar.stage)
        assertEquals(1, bar.filledCells)
        assertEquals("주문 확인 중이에요", bar.title)
        assertEquals("범석관 카페 · 내 앞에 3건", bar.subtitle)
        assertEquals("주문 현황, 27번, 주문 확인 중이에요, 범석관 카페 내 앞에 3건, 버튼", bar.contentDescription)
    }

    @Test fun `만드는 중 - 두 칸`() {
        val bar = LiveOrderRules.bar(listOf(order(status = OrderStatus.MAKING)))!!
        assertEquals(2, bar.filledCells)
        assertEquals("음료를 만들고 있어요", bar.title)
    }

    @Test fun `픽업 대기 - 전체 채움과 받을 기한`() {
        val bar = LiveOrderRules.bar(listOf(order(status = OrderStatus.READY, readyAt = at(10))))!!
        assertEquals(3, bar.filledCells)
        assertEquals("음료가 준비됐어요", bar.title)
        assertEquals("13:30까지 받아주세요", bar.subtitle)
        assertEquals("주문 현황, 27번, 음료가 준비됐어요, 13:30까지 받아주세요, 버튼", bar.contentDescription)
    }

    @Test fun `내 앞 건수를 모르면 매장 이름만`() {
        assertEquals("범석관 카페", LiveOrderRules.bar(listOf(order(ahead = null)))!!.subtitle)
    }

    @Test fun `여러 건이면 픽업 대기 우선 그다음 만드는 중 같은 단계면 먼저 주문한 것`() {
        val a = order("a", OrderStatus.REQUESTED, placedAt = at(0))
        val b = order("b", OrderStatus.MAKING, placedAt = at(1))
        val c = order("c", OrderStatus.READY, placedAt = at(5), readyAt = at(8))
        val d = order("d", OrderStatus.READY, placedAt = at(3), readyAt = at(9))
        assertEquals("d", LiveOrderRules.select(listOf(a, b, c, d))!!.id) // 같은 READY 면 먼저 주문한 d
        assertEquals("b", LiveOrderRules.select(listOf(a, b))!!.id)
        val bar = LiveOrderRules.bar(listOf(a, b, c))!!
        assertEquals("c", bar.orderId)
        assertEquals(2, bar.otherActiveCount)
    }

    // ---- 진행 중 알림 (Android 잠금 화면 현황) ----
    private val now = at(5)

    @Test fun `확인 중과 만드는 중은 조용히 - 픽업 대기만 알린다`() {
        val a = OngoingNotificationRules.from(order(), now, enabled = true)!!
        assertEquals("주문 확인 중이에요", a.title)
        assertFalse(a.alert)
        val b = OngoingNotificationRules.from(order(status = OrderStatus.MAKING), now, true)!!
        assertEquals("음료를 만들고 있어요", b.title)
        assertFalse(b.alert)
        val c = OngoingNotificationRules.from(order(status = OrderStatus.READY, readyAt = at(10)), now, true)!!
        assertEquals("음료가 준비됐어요", c.title)
        assertEquals("13:30까지 받아주세요", c.text)
        assertTrue(c.alert)
    }

    @Test fun `실시간 현황을 끄면 시작하지 않는다`() {
        assertNull(OngoingNotificationRules.from(order(), now, enabled = false))
    }

    @Test fun `픽업 완료는 15분만 남긴다`() {
        val o = order(status = OrderStatus.COMPLETED, completedAt = at(12))
        val n = OngoingNotificationRules.from(o, at(20), true)!!
        assertEquals("음료를 받았어요", n.title)
        assertEquals("범석관 카페 · 맛있게 드세요", n.text)
        assertTrue(n.finished)
        assertEquals(at(27), n.endsAt)
        assertNull(OngoingNotificationRules.from(o, at(27), true))
        assertTrue(OngoingNotificationRules.from(o, at(26), true) != null)
    }

    @Test fun `시작 후 8시간이 지나면 지운다`() {
        val o = order(status = OrderStatus.MAKING)
        assertTrue(OngoingNotificationRules.from(o, at(0).plus(Duration.ofHours(7)), true) != null)
        assertNull(OngoingNotificationRules.from(o, at(0).plus(Duration.ofHours(8)), true))
    }

    @Test fun `거절 취소 환불은 진행 중 알림 대상이 아니다 - 푸시가 안내`() {
        listOf(OrderStatus.REJECTED, OrderStatus.CANCELED, OrderStatus.REFUNDED, OrderStatus.UNCLAIMED).forEach {
            assertNull(OngoingNotificationRules.from(order(status = it), now, true))
        }
    }
}
