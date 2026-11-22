package com.ormo.customer.fake

import com.ormo.data.payment.InMemoryPendingPaymentStore
import com.ormo.data.payment.PaymentCoordinator
import com.ormo.domain.order.OrderDraft
import com.ormo.domain.order.OrderLineRequest
import com.ormo.domain.order.PaymentMethod
import com.ormo.domain.order.PickupMethod
import com.ormo.domain.payment.PaymentPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 가짜 주문·결제 서버를 실제 `PaymentCoordinator` 에 연결해, 화면이 보는 결제 상태가 시험 시나리오대로 나오는지 확인한다
 * (가짜 PG 창에서 승인 / 지연 승인 / 실패 / 닫기 / 만료를 고르는 것과 같은 흐름).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakePaymentFlowTest {
    private val draft = OrderDraft(listOf(OrderLineRequest("c1-americano", listOf("hot", "reg"), 1)), PickupMethod.TAKEOUT, 3500)
    private val card = PaymentMethod("card", "신용·체크카드")

    private class Rig(scope: TestScope, storeId: String) {
        val server = FakePaymentServer(FakeStoreDirectory(latencyMs = 0), { storeId }, latencyMs = 0)
        val coordinator = PaymentCoordinator(server.orderApi, server.gateway, InMemoryPendingPaymentStore(), pollIntervalMs = 0, maxPolls = 3, delayFn = { })
    }

    @Test fun `영업 중인 매장에서 결제창을 열고 승인하면 성공`() = runTest {
        val r = Rig(this, "c1")
        backgroundScope.launch { r.coordinator.pay(draft, card) }
        runCurrent()
        val pg = assertNotNull(r.server.pgWindow.value) // 결제창이 떠 있다
        assertEquals(3500, pg.amount)
        r.server.approve(pg.orderId)
        runCurrent()
        assertEquals(PaymentPhase.Succeeded, r.coordinator.state.value.phase)
    }

    @Test fun `영업이 멈춘 매장은 결제창 전에 막는다`() = runTest {
        val r = Rig(this, "c3") // 도서관 카페: 영업 종료
        backgroundScope.launch { r.coordinator.pay(draft, card) }
        runCurrent()
        assertIs<PaymentPhase.Blocked>(r.coordinator.state.value.phase)
        assertNull(r.server.pgWindow.value)
    }

    @Test fun `결제창을 닫으면 주문서로 - 다시 결제해도 같은 주문`() = runTest {
        val r = Rig(this, "c1")
        backgroundScope.launch { r.coordinator.pay(draft, card) }
        runCurrent()
        val first = r.server.pgWindow.value!!.orderId
        r.server.close()
        runCurrent()
        assertEquals(PaymentPhase.Idle, r.coordinator.state.value.phase)
        backgroundScope.launch { r.coordinator.pay(draft, card) }
        runCurrent()
        assertEquals(first, r.server.pgWindow.value!!.orderId) // 새 주문이 아니라 같은 주문
    }

    @Test fun `서버 반영이 늦으면 확인 중에서 멈췄다가 다시 확인하면 성공`() = runTest {
        val r = Rig(this, "c1")
        backgroundScope.launch { r.coordinator.pay(draft, card) }
        runCurrent()
        r.server.approveSlow(r.server.pgWindow.value!!.orderId)
        runCurrent()
        assertEquals(PaymentPhase.Confirming(checkFailed = true), r.coordinator.state.value.phase)
        repeat(6) { backgroundScope.launch { r.coordinator.recheck() }; runCurrent() }
        assertEquals(PaymentPhase.Succeeded, r.coordinator.state.value.phase)
    }

    @Test fun `같은 주문으로 5번 실패하면 더 이상 결제할 수 없다`() = runTest {
        val r = Rig(this, "c1")
        repeat(5) {
            backgroundScope.launch { r.coordinator.pay(draft, card) }
            runCurrent()
            r.server.fail(r.server.pgWindow.value!!.orderId)
            runCurrent()
        }
        assertEquals(PaymentPhase.Exhausted, r.coordinator.state.value.phase)
        assertTrue(r.coordinator.state.value.failedCount >= 5)
    }

    @Test fun `주문이 만료되면 만료 화면`() = runTest {
        val r = Rig(this, "c1")
        backgroundScope.launch { r.coordinator.pay(draft, card) }
        runCurrent()
        r.server.expire(r.server.pgWindow.value!!.orderId)
        runCurrent()
        // 창을 닫고 돌아온 뒤 다시 누르면 서버가 만료를 알려 준다
        backgroundScope.launch { r.coordinator.pay(draft, card) }
        runCurrent()
        assertEquals(PaymentPhase.Expired, r.coordinator.state.value.phase)
    }
}
