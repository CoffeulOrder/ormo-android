package com.ormo.data.payment

import com.ormo.core.Outcome
import com.ormo.data.remote.AppError
import com.ormo.domain.order.OrderDraft
import com.ormo.domain.order.OrderLineRequest
import com.ormo.domain.order.PaymentMethod
import com.ormo.domain.order.PickupMethod
import com.ormo.domain.payment.PaymentPhase
import com.ormo.domain.payment.PaymentStatus
import com.ormo.domain.payment.PlacedOrder
import com.ormo.domain.payment.PrepareError
import com.ormo.domain.store.StoreStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 서버 흉내: 같은 멱등 키는 같은 주문. 상태는 순서대로 돌려주는 큐 (다 쓰면 마지막 값 반복) */
private class FakeOrderApi : OrderApi {
    var placeResult: Outcome<PlacedOrder, PlaceOrderFailure> = Outcome.Ok(PlacedOrder("o1", 8000, "", expiresAtMs = null))
    var placeError: Exception? = null
    val placeCalls = mutableListOf<Pair<OrderDraft, String>>()
    var statuses = ArrayDeque<Any>() // PaymentStatus 또는 Exception
    var statusCalls = 0
    var placeGate: CompletableDeferred<Unit>? = null

    override suspend fun placeOrder(draft: OrderDraft, idempotencyKey: String): Outcome<PlacedOrder, PlaceOrderFailure> {
        placeCalls += draft to idempotencyKey
        placeGate?.await()
        placeError?.let { throw it }
        return placeResult
    }

    override suspend fun paymentStatus(orderId: String): PaymentStatus {
        statusCalls++
        val next = if (statuses.size > 1) statuses.removeFirst() else statuses.first()
        if (next is Exception) throw next
        return next as PaymentStatus
    }
}

class PaymentCoordinatorTest {
    private val draft = OrderDraft(listOf(OrderLineRequest("1", emptyList(), 2)), PickupMethod.TAKEOUT, 8000)
    private val card = PaymentMethod("card", "카드")

    private class Fixture(
        val api: FakeOrderApi = FakeOrderApi(),
        val gateway: FakePaymentGateway = FakePaymentGateway(),
        val store: InMemoryPendingPaymentStore = InMemoryPendingPaymentStore(),
    ) {
        var keys = 0
        val coordinator = PaymentCoordinator(
            api, gateway, store,
            keyFactory = { "key${++keys}" },
            pollIntervalMs = 0, maxPolls = 3, delayFn = { },
        )
        val phase get() = coordinator.state.value.phase
    }

    private fun fixture(statuses: List<Any> = listOf(PaymentStatus.Paid), pg: List<PgResult> = listOf(PgResult.Approved("tx"))) =
        Fixture(FakeOrderApi().also { it.statuses = ArrayDeque(statuses) }, FakePaymentGateway(pg))

    private fun blocking(block: suspend () -> Unit): Unit = runBlocking { block() }

    @Test fun `정상 결제 - 주문 생성 - 결제창 - 서버 확인 - 성공`() = blocking {
        val f = fixture()
        f.coordinator.pay(draft, card)
        assertEquals(PaymentPhase.Succeeded, f.phase)
        assertEquals(1, f.api.placeCalls.size)
        assertEquals("key1", f.api.placeCalls[0].second)
        assertEquals(PgRequest("o1", 8000, card), f.gateway.requests.single())
        assertNull(f.store.value) // 끝나면 저장 정리
    }

    @Test fun `서버가 확정한 금액으로 결제창을 연다 - 화면 금액이 아니라`() = blocking {
        val f = fixture()
        f.api.placeResult = Outcome.Ok(PlacedOrder("o1", 7500, "", null))
        f.coordinator.pay(draft, card)
        assertEquals(7500, f.gateway.requests.single().amount)
    }

    @Test fun `PG 가 승인해도 서버가 확정하기 전에는 성공이 아니다`() = blocking {
        val f = fixture(statuses = listOf(PaymentStatus.Pending, PaymentStatus.Pending, PaymentStatus.Pending))
        f.coordinator.pay(draft, card)
        assertEquals(PaymentPhase.Confirming(checkFailed = true), f.phase) // 계속 대기 → 다시 확인 표시
        assertEquals(3, f.api.statusCalls)
        assertTrue(f.store.value != null) // 앱이 꺼져도 이어갈 수 있게 저장 유지
    }

    @Test fun `확인 중 다시 확인 - 이번엔 승인`() = blocking {
        val f = fixture(statuses = listOf(PaymentStatus.Pending, PaymentStatus.Pending, PaymentStatus.Pending, PaymentStatus.Paid))
        f.coordinator.pay(draft, card)
        assertEquals(PaymentPhase.Confirming(true), f.phase)
        f.coordinator.recheck()
        assertEquals(PaymentPhase.Succeeded, f.phase)
        assertNull(f.store.value)
    }

    @Test fun `확인 중 네트워크가 끊기면 확인 필요로 두고 복귀 후 다시 확인`() = blocking {
        val f = fixture(statuses = listOf(AppError.Network(), PaymentStatus.Paid))
        f.coordinator.pay(draft, card)
        assertEquals(PaymentPhase.Confirming(true), f.phase)
        f.coordinator.recheck()
        assertEquals(PaymentPhase.Succeeded, f.phase)
    }

    @Test fun `결제 버튼 연타해도 주문과 결제창은 한 번`() = blocking {
        val f = fixture()
        val gate = CompletableDeferred<Unit>()
        f.api.placeGate = gate
        val results = kotlinx.coroutines.coroutineScope {
            val jobs = (1..5).map { async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.pay(draft, card) } }
            gate.complete(Unit)
            jobs.awaitAll()
        }
        assertEquals(5, results.size)
        assertEquals(1, f.api.placeCalls.size)
        assertEquals(1, f.gateway.requests.size)
        assertEquals(PaymentPhase.Succeeded, f.phase)
    }

    @Test fun `영업이 멈춰 있으면 결제창 전에 막는다`() = blocking {
        val f = fixture()
        f.api.placeResult = Outcome.Fail(PlaceOrderFailure.StoreUnavailable(StoreStatus.Closed(null)))
        f.coordinator.pay(draft, card)
        assertIs<PaymentPhase.Blocked>(f.phase)
        assertTrue(f.gateway.requests.isEmpty())
        f.coordinator.dismissBlocked()
        assertEquals(PaymentPhase.Idle, f.phase)
    }

    @Test fun `전부 품절 네트워크 오류 기타 오류는 주문서로 돌아가 토스트용 오류를 남긴다`() = blocking {
        val f = fixture()
        f.api.placeResult = Outcome.Fail(PlaceOrderFailure.AllSoldOut)
        f.coordinator.pay(draft, card)
        assertEquals(PrepareError.ALL_SOLD_OUT, f.coordinator.state.value.prepareError)

        f.api.placeError = AppError.Network()
        f.coordinator.pay(draft, card)
        assertEquals(PrepareError.NETWORK, f.coordinator.state.value.prepareError)

        f.api.placeError = AppError.Api(500)
        f.coordinator.pay(draft, card)
        assertEquals(PrepareError.OTHER, f.coordinator.state.value.prepareError)
        assertEquals(PaymentPhase.Idle, f.phase)
        assertTrue(f.gateway.requests.isEmpty())
    }

    @Test fun `주문 생성이 네트워크로 실패한 뒤 다시 누르면 새 키가 아니라 처음부터 - 주문이 없었으므로`() = blocking {
        val f = fixture()
        f.api.placeError = AppError.Network()
        f.coordinator.pay(draft, card)
        f.api.placeError = null
        f.coordinator.pay(draft, card)
        assertEquals(PaymentPhase.Succeeded, f.phase)
        assertEquals(2, f.api.placeCalls.size)
    }

    @Test fun `결제창을 닫으면 주문서로 - 다시 누르면 새 주문 없이 서버 확인 후 같은 주문으로 결제창`() = blocking {
        val f = fixture(statuses = listOf(PaymentStatus.Pending, PaymentStatus.Paid), pg = listOf(PgResult.Canceled, PgResult.Approved("tx")))
        f.coordinator.pay(draft, card)
        assertEquals(PaymentPhase.Idle, f.phase)
        assertEquals(0, f.coordinator.state.value.failedCount)
        assertEquals(0, f.api.statusCalls)

        f.coordinator.pay(draft, card)
        assertEquals(PaymentPhase.Succeeded, f.phase)
        assertEquals(1, f.api.placeCalls.size) // 같은 주문 재사용
        assertEquals(2, f.gateway.requests.size)
        assertEquals(listOf("o1", "o1"), f.gateway.requests.map { it.orderId })
    }

    @Test fun `창을 닫았다 돌아왔는데 그 사이 이미 결제가 끝났으면 결제창 없이 성공`() = blocking {
        val f = fixture(statuses = listOf(PaymentStatus.Paid), pg = listOf(PgResult.Canceled))
        f.coordinator.pay(draft, card)
        f.coordinator.pay(draft, card)
        assertEquals(PaymentPhase.Succeeded, f.phase)
        assertEquals(1, f.gateway.requests.size)
    }

    @Test fun `창을 닫았다 돌아왔는데 주문이 만료됐으면 만료`() = blocking {
        val f = fixture(statuses = listOf(PaymentStatus.Expired), pg = listOf(PgResult.Canceled))
        f.coordinator.pay(draft, card)
        f.coordinator.pay(draft, card)
        assertEquals(PaymentPhase.Expired, f.phase)
        f.coordinator.leaveTerminal()
        assertEquals(PaymentPhase.Idle, f.phase)
        assertNull(f.store.value)
    }

    @Test fun `실패하면 같은 주문으로 다시 결제 - 같은 키 같은 주문 그리고 결제수단은 바꿀 수 있다`() = blocking {
        val f = fixture(
            statuses = listOf(PaymentStatus.Failed("E1"), PaymentStatus.Paid),
            pg = listOf(PgResult.Failed("E1"), PgResult.Approved("tx")),
        )
        f.coordinator.pay(draft, card)
        assertEquals(PaymentPhase.Failed("E1"), f.phase)
        assertEquals(1, f.coordinator.state.value.failedCount)
        assertEquals(1, f.store.value!!.failedCount)

        val other = PaymentMethod("kakao", "카카오페이")
        f.coordinator.pay(draft, other)
        assertEquals(PaymentPhase.Succeeded, f.phase)
        assertEquals(1, f.api.placeCalls.size)
        assertEquals(listOf(card, other), f.gateway.requests.map { it.method })
        assertEquals(0, f.api.statusCalls - 2) // 실패 후 재시도는 상태 확인을 한 번 더 하지 않는다 (확인 루프 2번만)
    }

    @Test fun `5번 실패하면 더 이상 결제할 수 없다`() = blocking {
        val f = fixture(statuses = listOf(PaymentStatus.Failed("E")), pg = listOf(PgResult.Failed("E")))
        repeat(5) { f.coordinator.pay(draft, card) }
        assertEquals(PaymentPhase.Exhausted, f.phase)
        assertEquals(5, f.gateway.requests.size)
        f.coordinator.pay(draft, card) // 무시
        assertEquals(5, f.gateway.requests.size)
        assertEquals(1, f.api.placeCalls.size)
        assertNull(f.store.value)
        f.coordinator.leaveTerminal()
        assertEquals(PaymentPhase.Idle, f.phase)
    }

    @Test fun `PG 가 예외를 던지면 결과를 모르니 서버에 확인한다`() = blocking {
        val gateway = object : PaymentGateway {
            override suspend fun pay(request: PgRequest): PgResult = throw IllegalStateException("sdk crash")
        }
        val api = FakeOrderApi().also { it.statuses = ArrayDeque(listOf<Any>(PaymentStatus.Paid)) }
        val c = PaymentCoordinator(api, gateway, InMemoryPendingPaymentStore(), keyFactory = { "k" }, delayFn = { })
        c.pay(draft, card)
        assertEquals(PaymentPhase.Succeeded, c.state.value.phase)
    }

    @Test fun `0원 주문은 결제창 없이 서버 확인`() = blocking {
        val f = fixture()
        f.api.placeResult = Outcome.Ok(PlacedOrder("o0", 0, "", null))
        f.coordinator.pay(draft, card)
        assertEquals(PaymentPhase.Succeeded, f.phase)
        assertTrue(f.gateway.requests.isEmpty())
    }

    @Test fun `주문서가 바뀌면 이전 주문을 버리고 새 키로 새 주문`() = blocking {
        val f = fixture(pg = listOf(PgResult.Canceled, PgResult.Approved("tx")))
        f.coordinator.pay(draft, card)
        assertTrue(f.store.value != null)

        val changed = draft.copy(lines = listOf(OrderLineRequest("1", emptyList(), 3)), expectedAmount = 12000)
        f.coordinator.onDraftChanged(changed)
        assertNull(f.coordinator.state.value.order)
        assertNull(f.store.value)

        f.api.placeResult = Outcome.Ok(PlacedOrder("o2", 12000, "", null))
        f.coordinator.pay(changed, card)
        assertEquals(listOf("key1", "key2"), f.api.placeCalls.map { it.second })
        assertEquals("o2", f.gateway.requests.last().orderId)
    }

    @Test fun `같은 주문서면 바뀐 게 아니다 - 결제수단만 바꿔도 주문 유지`() = blocking {
        val f = fixture(pg = listOf(PgResult.Canceled))
        f.coordinator.pay(draft, card)
        f.coordinator.onDraftChanged(draft.copy(expectedAmount = 8000))
        assertEquals("o1", f.coordinator.state.value.order?.orderId)
    }

    @Test fun `앱을 다시 켜면 저장된 주문을 서버에 물어 이어간다`() = blocking {
        val f = fixture(statuses = listOf(PaymentStatus.Pending, PaymentStatus.Pending, PaymentStatus.Pending))
        f.coordinator.pay(draft, card) // 확인 중에서 멈춘 채 앱 종료라고 가정
        assertTrue(f.store.value != null)

        val api2 = FakeOrderApi().also { it.statuses = ArrayDeque(listOf<Any>(PaymentStatus.Paid)) }
        val c2 = PaymentCoordinator(api2, FakePaymentGateway(), f.store, keyFactory = { "k" }, delayFn = { })
        c2.resume()
        assertEquals(PaymentPhase.Succeeded, c2.state.value.phase)
        assertNull(f.store.value)
        assertEquals(0, api2.placeCalls.size)
    }

    @Test fun `저장된 주문이 없으면 resume 은 아무것도 하지 않는다`() = blocking {
        val f = fixture()
        f.coordinator.resume()
        assertEquals(PaymentPhase.Idle, f.phase)
        assertEquals(0, f.api.statusCalls)
    }

    @Test fun `확인 중 복귀했는데 이미 실패로 끝났으면 실패 횟수까지 이어서 센다`() = blocking {
        val f = fixture()
        f.store.value = PendingPayment(PlacedOrder("o1", 8000, "fp"), "k", failedCount = 4)
        f.api.statuses = ArrayDeque(listOf<Any>(PaymentStatus.Failed("E")))
        f.coordinator.resume()
        assertEquals(PaymentPhase.Exhausted, f.phase) // 4 + 1 = 5
        assertNull(f.store.value)
    }

    @Test fun `만료 시각이 지난 주문서는 만료로`() = blocking {
        val f = fixture(pg = listOf(PgResult.Canceled))
        f.api.placeResult = Outcome.Ok(PlacedOrder("o1", 8000, "", expiresAtMs = 1_000))
        f.coordinator.pay(draft, card)
        f.coordinator.checkExpiry(999)
        assertEquals(PaymentPhase.Idle, f.phase)
        f.coordinator.checkExpiry(1_000)
        assertEquals(PaymentPhase.Expired, f.phase)
    }

    @Test fun `확인이 계속 안 될 때 주문서로 나가면 주문은 유지`() = blocking {
        val f = fixture(statuses = listOf(PaymentStatus.Pending))
        f.coordinator.pay(draft, card)
        f.coordinator.giveUpWaiting()
        assertEquals(PaymentPhase.Idle, f.phase)
        assertEquals("o1", f.coordinator.state.value.order?.orderId)
    }

    @Test fun `화면이 사라져 취소돼도 상태가 멈춘 채 남지 않는다`() = runTest {
        val f = fixture()
        f.api.placeGate = CompletableDeferred() // 영원히 대기
        val job = launch { f.coordinator.pay(draft, card) }
        testScheduler.runCurrent()
        assertEquals(PaymentPhase.Preparing, f.phase)
        job.cancel()
        job.join()
        assertEquals(PaymentPhase.Idle, f.phase)
        assertNull(f.coordinator.state.value.prepareError)
    }
}
