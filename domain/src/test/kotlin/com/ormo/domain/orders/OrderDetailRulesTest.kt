package com.ormo.domain.orders

import com.ormo.domain.model.OrderStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderDetailRulesTest {
    private fun view(o: Order) = OrderDetailRules.view(o)

    @Test fun `주문 확인 중 - 내 앞 대기 건수와 취소 버튼`() {
        val v = view(order())
        assertEquals("27번 주문을 확인하고 있어요", v.headline)
        assertEquals("내 앞에 3건", v.subline)
        assertEquals(CancelButton.ENABLED, v.cancel)
        assertNull(v.bigPickupNumber)
    }

    @Test fun `내 앞 건수를 모르면 보조 문구 없음 - 번호를 모르면 번호 없이`() {
        val v = view(order(ahead = null, number = null))
        assertNull(v.subline)
        assertEquals("주문을 확인하고 있어요", v.headline)
    }

    @Test fun `만드는 중 - 취소 버튼은 꺼짐`() {
        val v = view(order(OrderStatus.MAKING, makingAt = at(2)))
        assertEquals("27번 주문을 만들고 있어요", v.headline)
        assertEquals(CancelButton.DISABLED, v.cancel)
    }

    @Test fun `픽업 대기 - 번호를 크게 받을 기한 상자 (준비 시각 + 20분)`() {
        val v = view(order(OrderStatus.READY, makingAt = at(2), readyAt = at(10)))
        assertEquals("27번 주문을 픽업해주세요", v.headline)
        assertEquals(27, v.bigPickupNumber)
        assertEquals("13:30까지 받아주세요", v.deadlineBox)
        assertEquals(CancelButton.HIDDEN, v.cancel)
    }

    @Test fun `받을 기한은 서버 값 우선 그다음 매장 설정 분`() {
        assertEquals("13:40까지 받아주세요", view(order(OrderStatus.READY, readyAt = at(10), discard = 30)).deadlineBox)
        assertEquals("13:15까지 받아주세요", view(order(OrderStatus.READY, readyAt = at(10), discard = 30, deadline = at(15))).deadlineBox)
    }

    @Test fun `픽업 완료 - 재주문과 영수증`() {
        val v = view(order(OrderStatus.COMPLETED, makingAt = at(2), readyAt = at(10), completedAt = at(12)))
        assertEquals("27번 주문을 픽업했어요", v.headline)
        assertEquals(listOf(OrderAction.REORDER, OrderAction.VIEW_RECEIPT), v.actions)
    }

    @Test fun `거절 - 사유 상자와 환불 안내`() {
        val v = view(order(OrderStatus.REJECTED, reason = "재료가 소진됐어요"))
        assertEquals("매장에서 주문을 받지 못했어요", v.headline)
        assertEquals("재료가 소진됐어요", v.reasonBox)
        assertEquals("결제한 금액은 결제수단으로 환불해 드려요. 카드사에 따라 3~5일 걸려요", v.infoBox)
        assertEquals(listOf(OrderAction.PICK_OTHER_MENU), v.actions)
        // Figma 4.3.3: 첫 칸만 빨강(멈춤)
        assertEquals(listOf(StepState.STOPPED, StepState.PENDING, StepState.PENDING, StepState.PENDING), v.steps!!.map { it.state })
    }

    @Test fun `자동 거절은 정해진 문구`() {
        val v = view(order(OrderStatus.REJECTED, reason = "무시됨", auto = true))
        assertEquals("매장이 주문을 확인하지 못했어요", v.reasonBox)
    }

    @Test fun `사유가 없거나 공백이면 사유 상자 없음`() {
        assertNull(view(order(OrderStatus.REJECTED, reason = "  ")).reasonBox)
        assertNull(view(order(OrderStatus.REJECTED)).reasonBox)
    }

    @Test fun `매장 취소와 직접 취소는 다르다`() {
        val store = view(order(OrderStatus.CANCELED, canceledBy = CancelActor.STORE, reason = "곧 영업이 끝나요"))
        assertEquals("매장 사정으로 주문이 취소됐어요", store.headline)
        assertEquals("곧 영업이 끝나요", store.reasonBox)
        assertEquals(listOf(OrderAction.PICK_OTHER_MENU), store.actions)
        assertEquals(StepState.STOPPED, store.steps!!.first().state)

        val mine = view(order(OrderStatus.CANCELED, canceledBy = CancelActor.CUSTOMER))
        assertEquals("주문을 취소했어요", mine.headline)
        assertNull(mine.reasonBox)
        assertNull(mine.steps) // 단계 바 숨김
        assertEquals(OrderDetailRules.CANCELED_NOTICE, mine.infoBox)
        assertEquals(listOf(OrderAction.VIEW_RECEIPT), mine.actions)
    }

    @Test fun `환불 완료 - 금액과 진행된 곳까지의 단계 바`() {
        val v = view(order(OrderStatus.REFUNDED, refund = 3000, makingAt = at(2)))
        assertEquals("3,000원을 환불했어요", v.headline)
        val steps = v.steps!!
        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.PENDING, StepState.PENDING), steps.map { it.state })
        // 환불 금액이 없으면 결제 금액
        assertEquals("4,000원을 환불했어요", view(order(OrderStatus.REFUNDED)).headline)
    }

    @Test fun `미수령 - 마지막 칸이 미수령`() {
        val v = view(order(OrderStatus.UNCLAIMED, makingAt = at(2), readyAt = at(10), closedAt = at(30)))
        assertEquals("픽업 예정 시간이 지났어요", v.headline)
        assertEquals("환불이 필요하면 매장에 문의해주세요", v.infoBox)
        val last = v.steps!!.last()
        assertEquals("미수령", last.label)
        assertEquals(StepState.MISSED, last.state)
        assertEquals(at(30), last.time)
    }

    @Test fun `단계 바 - 현재 단계와 각 단계 시각`() {
        val steps = OrderDetailRules.steps(order(OrderStatus.MAKING, makingAt = at(2)))!!
        assertEquals(listOf("주문 확인 중", "만드는 중", "픽업 대기", "픽업 완료"), steps.map { it.label })
        assertEquals(listOf(StepState.DONE, StepState.CURRENT, StepState.PENDING, StepState.PENDING), steps.map { it.state })
        assertEquals(listOf(at(0), at(2), null, null), steps.map { it.time })
    }

    @Test fun `픽업 완료 단계 바는 막대 모두 채우고 마지막 칸이 현재 단계 (Figma)`() {
        val steps = OrderDetailRules.steps(order(OrderStatus.COMPLETED, makingAt = at(2), readyAt = at(10), completedAt = at(12)))!!
        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.DONE, StepState.CURRENT), steps.map { it.state })
        assertEquals(at(12), steps.last().time)
    }

    @Test fun `결제 대기와 만료 주문은 단계 바가 없다`() {
        assertNull(OrderDetailRules.steps(order(OrderStatus.PENDING_PAYMENT)))
        assertNull(OrderDetailRules.steps(order(OrderStatus.EXPIRED)))
    }

    @Test fun `취소 규칙과 문구`() {
        assertTrue(OrderDetailRules.canCancel(OrderStatus.REQUESTED))
        assertFalse(OrderDetailRules.canCancel(OrderStatus.MAKING))
        assertFalse(OrderDetailRules.canCancel(OrderStatus.READY))
        assertEquals("주문을 취소할까요?", OrderDetailRules.CANCEL_CONFIRM.title)
        assertEquals("결제 금액 전액을 환불해 드려요", OrderDetailRules.CANCEL_CONFIRM.body)
        assertEquals("주문이 접수되어 취소할 수 없어요", OrderDetailRules.NOT_CANCELABLE_TOAST)
        assertEquals(3000L, OrderDetailRules.TOAST_MS)
    }

    @Test fun `자동 새로고침은 진행 중일 때만`() {
        assertEquals(5000L, OrderDetailRules.pollIntervalMs(OrderStatus.REQUESTED))
        assertEquals(5000L, OrderDetailRules.pollIntervalMs(OrderStatus.READY))
        assertNull(OrderDetailRules.pollIntervalMs(OrderStatus.COMPLETED))
        assertNull(OrderDetailRules.pollIntervalMs(OrderStatus.REJECTED))
    }

    @Test fun `카드 제목`() {
        assertEquals("아메리카노", order().title)
        assertEquals("아메리카노 외 2건", order(lines = listOf(line(), line("2", "라떼"), line("3", "티"))).title)
        assertEquals("", order(lines = emptyList()).title)
    }
}
