package com.ormo.customer.fake

import com.ormo.data.orders.CancelFailure
import com.ormo.data.orders.OrderRepository
import com.ormo.core.Outcome
import com.ormo.data.remote.AppError
import com.ormo.domain.model.OrderStatus
import com.ormo.domain.order.PickupMethod
import com.ormo.domain.orders.CancelActor
import com.ormo.domain.orders.OrderLine
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 가짜 내 주문 서버를 실제 [OrderRepository] 에 연결해, 개발용 상태 패널이 일으키는 모든 상태가 화면 규칙대로 보이는지 확인한다 */
class FakeOrderServerTest {
    private fun newOrder(server: FakeOrderServer, id: String = "o1") = server.create(
        id, "s1", "범석관 카페", listOf(OrderLine("m1", "아메리카노", listOf("hot"), "HOT | 레귤러", 1, 3500)), 3500, PickupMethod.TAKEOUT, "신용·체크카드",
    )

    @Test fun `접수 대기에서 시작해 번호가 매겨진다`() = runTest {
        val s = FakeOrderServer()
        val a = newOrder(s, "a")
        val b = newOrder(s, "b")
        assertEquals(OrderStatus.REQUESTED, a.status)
        assertEquals(a.pickupNumber!! + 1, b.pickupNumber)
        assertEquals(3, a.aheadCount)
    }

    @Test fun `같은 주문을 다시 만들어도 하나`() = runTest {
        val s = FakeOrderServer()
        newOrder(s); newOrder(s)
        assertEquals(1, s.fetchActive().size)
    }

    @Test fun `다음 단계는 만드는 중 - 픽업 대기 - 완료 순서, 시각이 채워진다`() = runTest {
        val s = FakeOrderServer()
        newOrder(s)
        s.advance("o1"); assertEquals(OrderStatus.MAKING, s.fetchOrder("o1").status)
        s.advance("o1"); val ready = s.fetchOrder("o1")
        assertEquals(OrderStatus.READY, ready.status)
        assertNotNull(ready.deadline)
        s.advance("o1"); val done = s.fetchOrder("o1")
        assertEquals(OrderStatus.COMPLETED, done.status)
        assertNotNull(done.completedAt)
        assertTrue(s.fetchActive().isEmpty())
        assertEquals(1, s.fetchHistoryPage(null, 20).orders.size)
    }

    @Test fun `접수 대기일 때만 직접 취소된다, 그 뒤에는 409`() = runTest {
        val s = FakeOrderServer()
        newOrder(s, "a"); newOrder(s, "b")
        assertEquals(CancelActor.CUSTOMER, s.cancel("a").canceledBy)
        s.advance("b")
        val e = assertFailsWith<AppError.Api> { s.cancel("b") }
        assertEquals(409, e.status)
    }

    @Test fun `저장소 취소 - 수락 직후 취소하면 취소 불가 + 최신 상태`() = runTest {
        val s = FakeOrderServer()
        newOrder(s)
        val repo = OrderRepository(s)
        s.advance("o1") // 취소를 누르기 직전에 매장이 받았다
        val r = repo.cancel("o1")
        val f = (r as Outcome.Fail).failure
        assertIs<CancelFailure.NotCancelable>(f)
        assertEquals(OrderStatus.MAKING, f.latest?.status)
    }

    @Test fun `거절 - 자동 거절과 사유`() = runTest {
        val s = FakeOrderServer()
        newOrder(s, "a"); newOrder(s, "b")
        s.reject("a", auto = false); s.reject("b", auto = true)
        assertEquals("재료가 소진됐어요", s.fetchOrder("a").reason)
        assertTrue(s.fetchOrder("b").autoRejected)
        assertEquals(OrderStatus.REJECTED, s.fetchOrder("a").status)
    }

    @Test fun `매장 취소, 미수령, 환불`() = runTest {
        val s = FakeOrderServer()
        newOrder(s, "a"); newOrder(s, "b"); newOrder(s, "c")
        s.cancelByStore("a")
        assertEquals(CancelActor.STORE, s.fetchOrder("a").canceledBy)
        s.advance("b"); s.advance("b"); s.unclaimed("b")
        assertEquals(OrderStatus.UNCLAIMED, s.fetchOrder("b").status)
        s.refund("b")
        assertEquals(OrderStatus.REFUNDED, s.fetchOrder("b").status)
        assertEquals(3500, s.fetchOrder("b").refundAmount)
        s.refund("c") // 접수 대기는 환불 대상 아님
        assertEquals(OrderStatus.REQUESTED, s.fetchOrder("c").status)
    }

    @Test fun `없는 주문은 404`() = runTest {
        val s = FakeOrderServer()
        assertFailsWith<AppError.Api> { s.fetchOrder("none") }
        assertNull(s.fetchActive().firstOrNull())
    }
}
