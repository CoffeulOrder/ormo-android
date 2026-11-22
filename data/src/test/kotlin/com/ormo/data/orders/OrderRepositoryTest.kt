package com.ormo.data.orders

import com.ormo.core.Outcome
import com.ormo.data.remote.AppError
import com.ormo.domain.model.OrderStatus
import com.ormo.domain.orders.Order
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun o(id: String, status: OrderStatus = OrderStatus.COMPLETED, min: Long = 0) = Order(
    id = id, pickupNumber = 1, storeId = "s", storeName = "카페", status = status, lines = emptyList(), totalAmount = 1000,
    placedAt = Instant.ofEpochSecond(1_000_000 + min * 60),
)

private class FakeOrdersApi : MyOrdersApi {
    val orders = HashMap<String, Order>()
    var active: List<Order> = emptyList()
    var pages = ArrayDeque<OrderPage>()
    val pageCalls = mutableListOf<Pair<String?, Int>>()
    var error: Exception? = null
    var cancelError: Exception? = null
    var fetchCalls = 0
    /** 호출 순서대로 대기시킬 문 (없거나 null 이면 바로 진행) */
    val gates = ArrayDeque<CompletableDeferred<Unit>?>()

    override suspend fun fetchOrder(orderId: String): Order {
        fetchCalls++
        error?.let { throw it }
        return orders.getValue(orderId)
    }

    override suspend fun fetchActive(): List<Order> { error?.let { throw it }; return active }

    override suspend fun fetchHistoryPage(cursor: String?, limit: Int): OrderPage {
        pageCalls += cursor to limit
        val gate = gates.removeFirstOrNull()
        val page = if (error == null) pages.removeFirst() else null
        gate?.await()
        error?.let { throw it }
        return page!!
    }

    override suspend fun cancel(orderId: String): Order {
        cancelError?.let { throw it }
        return orders.getValue(orderId).copy(status = OrderStatus.CANCELED)
    }
}

class OrderRepositoryTest {
    private fun blocking(block: suspend () -> Unit): Unit = runBlocking { block() }

    // ---- 진행 중 ----
    @Test fun `진행 중 주문만 현황에 담고 실패하면 기존 값을 유지`() = blocking {
        val api = FakeOrdersApi().apply { active = listOf(o("a", OrderStatus.MAKING), o("b", OrderStatus.COMPLETED)) }
        val repo = OrderRepository(api)
        assertIs<Outcome.Ok<*>>(repo.refreshActive())
        assertEquals(listOf("a"), repo.active.value.map { it.id })

        api.error = AppError.Network()
        assertEquals(Outcome.Fail(LoadFailure.NETWORK), repo.refreshActive())
        assertEquals(listOf("a"), repo.active.value.map { it.id })
        api.error = AppError.Api(500)
        assertEquals(Outcome.Fail(LoadFailure.OTHER), repo.refreshActive())
    }

    @Test fun `로그아웃하면 모두 비운다`() = blocking {
        val api = FakeOrdersApi().apply {
            active = listOf(o("a", OrderStatus.MAKING))
            pages = ArrayDeque(listOf(OrderPage(listOf(o("h")), null)))
        }
        val repo = OrderRepository(api)
        repo.refreshActive()
        repo.loadFirstPage()
        repo.reset()
        assertTrue(repo.active.value.isEmpty())
        assertTrue(repo.history.value.orders.isEmpty())
        assertFalse(repo.history.value.loadedOnce)
    }

    @Test fun `로그아웃하는 동안 도착한 내역 응답은 버린다`() = runTest {
        val api = FakeOrdersApi().apply { pages = ArrayDeque(listOf(OrderPage(listOf(o("h")), null))) }
        val gate = CompletableDeferred<Unit>()
        api.gates += gate
        val repo = OrderRepository(api)
        val job = launch { repo.loadFirstPage() }
        runCurrent()
        repo.reset()
        gate.complete(Unit)
        job.join()
        assertTrue(repo.history.value.orders.isEmpty())
    }

    // ---- 상세 ----
    @Test fun `상세를 불러오면 현황과 내역에 반영된다`() = blocking {
        val api = FakeOrdersApi()
        val repo = OrderRepository(api)
        api.orders["a"] = o("a", OrderStatus.REQUESTED)
        repo.load("a")
        assertEquals(listOf("a"), repo.active.value.map { it.id })
        api.orders["a"] = o("a", OrderStatus.COMPLETED)
        repo.load("a")
        assertTrue(repo.active.value.isEmpty()) // 끝났으니 바에서 빠진다
    }

    @Test fun `observe - 진행 중이면 간격마다 다시 조회하고 끝나면 멈춘다`() = runTest {
        val api = FakeOrdersApi()
        val repo = OrderRepository(api)
        api.orders["a"] = o("a", OrderStatus.REQUESTED)
        val seen = mutableListOf<OrderStatus>()
        val job = launch {
            repo.observe("a").collect { r -> seen += (r as Outcome.Ok).value.status }
        }
        runCurrent()
        assertEquals(listOf(OrderStatus.REQUESTED), seen)
        api.orders["a"] = o("a", OrderStatus.MAKING)
        advanceTimeBy(4_999); runCurrent()
        assertEquals(1, seen.size) // 5초 전엔 안 부른다
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf(OrderStatus.REQUESTED, OrderStatus.MAKING), seen)
        api.orders["a"] = o("a", OrderStatus.COMPLETED)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(OrderStatus.COMPLETED, seen.last())
        val callsAtEnd = api.fetchCalls
        advanceTimeBy(60_000); runCurrent()
        assertEquals(callsAtEnd, api.fetchCalls) // 끝난 주문은 더 부르지 않는다
        assertTrue(job.isCompleted)
    }

    @Test fun `observe - 일시적 실패는 알리고 계속 다시 시도`() = runTest {
        val api = FakeOrdersApi()
        val repo = OrderRepository(api)
        api.orders["a"] = o("a", OrderStatus.READY)
        api.error = AppError.Network()
        val seen = mutableListOf<Outcome<Order, LoadFailure>>()
        val job = launch { repo.observe("a").collect { seen += it } }
        runCurrent()
        assertEquals(Outcome.Fail(LoadFailure.NETWORK), seen.single())
        api.error = null
        advanceTimeBy(5_001); runCurrent()
        assertIs<Outcome.Ok<Order>>(seen.last())
        job.cancel() // 화면을 떠나면 폴링도 멈춘다
    }

    @Test fun `observe - 수집을 멈추면 더 조회하지 않는다`() = runTest {
        val api = FakeOrdersApi()
        val repo = OrderRepository(api)
        api.orders["a"] = o("a", OrderStatus.REQUESTED)
        val job = launch { repo.observe("a").collect { } }
        runCurrent()
        job.cancel()
        val n = api.fetchCalls
        advanceTimeBy(60_000); runCurrent()
        assertEquals(n, api.fetchCalls)
    }

    // ---- 취소 ----
    @Test fun `취소 성공`() = blocking {
        val api = FakeOrdersApi()
        val repo = OrderRepository(api)
        api.orders["a"] = o("a", OrderStatus.REQUESTED)
        repo.load("a")
        val r = repo.cancel("a")
        assertEquals(OrderStatus.CANCELED, (r as Outcome.Ok).value.status)
        assertTrue(repo.active.value.isEmpty())
    }

    @Test fun `취소를 누른 순간 매장이 받았으면 409 - 최신 상태와 함께 취소 불가`() = blocking {
        val api = FakeOrdersApi()
        val repo = OrderRepository(api)
        api.orders["a"] = o("a", OrderStatus.MAKING)
        api.cancelError = AppError.Api(409, "ORDER_NOT_CANCELABLE")
        val r = repo.cancel("a") as Outcome.Fail
        val f = assertIs<CancelFailure.NotCancelable>(r.failure)
        assertEquals(OrderStatus.MAKING, f.latest?.status)
    }

    @Test fun `취소 불가인데 최신 상태도 못 받으면 latest 는 null`() = blocking {
        val api = FakeOrdersApi()
        val repo = OrderRepository(api)
        api.orders["a"] = o("a", OrderStatus.MAKING)
        api.cancelError = AppError.Api(409)
        api.error = AppError.Network()
        val f = (repo.cancel("a") as Outcome.Fail).failure
        assertNull((f as CancelFailure.NotCancelable).latest)
    }

    @Test fun `취소 중 네트워크 오류와 그 밖의 오류`() = blocking {
        val api = FakeOrdersApi()
        val repo = OrderRepository(api)
        api.cancelError = AppError.Network()
        assertEquals(Outcome.Fail(CancelFailure.Network), repo.cancel("a"))
        api.cancelError = AppError.Api(500)
        assertEquals(Outcome.Fail(CancelFailure.Other), repo.cancel("a"))
    }

    // ---- 내 주문 ----
    @Test fun `첫 페이지는 20개 단위로 요청하고 지난 주문만 담는다`() = blocking {
        val api = FakeOrdersApi().apply { pages = ArrayDeque(listOf(OrderPage(listOf(o("a", min = 3), o("b", OrderStatus.MAKING, 2), o("c", min = 1)), null))) }
        val repo = OrderRepository(api)
        repo.loadFirstPage()
        assertEquals(listOf<Pair<String?, Int>>(null to 20), api.pageCalls)
        assertEquals(listOf("a", "c"), repo.history.value.orders.map { it.id })
        assertFalse(repo.history.value.hasMore)
        assertFalse(repo.history.value.loading)
    }

    @Test fun `다음 페이지를 이어 붙이고 더 없으면 요청하지 않는다`() = blocking {
        val api = FakeOrdersApi().apply {
            pages = ArrayDeque(listOf(OrderPage(listOf(o("a", min = 5)), "c1"), OrderPage(listOf(o("b", min = 4)), null)))
        }
        val repo = OrderRepository(api)
        repo.loadFirstPage()
        assertTrue(repo.history.value.hasMore)
        repo.loadMore()
        assertEquals(listOf("a", "b"), repo.history.value.orders.map { it.id })
        assertEquals(listOf<Pair<String?, Int>>(null to 20, "c1" to 20), api.pageCalls)
        repo.loadMore() // 더 없음
        assertEquals(2, api.pageCalls.size)
    }

    @Test fun `페이지가 통째로 걸러져도 이어서 불러와 화면을 채운다`() = blocking {
        val api = FakeOrdersApi().apply {
            pages = ArrayDeque(listOf(
                OrderPage(listOf(o("x", OrderStatus.MAKING, 9)), "c1"),
                OrderPage(listOf(o("y", OrderStatus.EXPIRED, 8)), "c2"),
                OrderPage(listOf(o("z", min = 7)), null),
            ))
        }
        val repo = OrderRepository(api)
        repo.loadFirstPage()
        assertEquals(listOf("z"), repo.history.value.orders.map { it.id })
        assertEquals(3, api.pageCalls.size)
    }

    @Test fun `자동으로 이어 불러오는 횟수에는 상한이 있다`() = blocking {
        val api = FakeOrdersApi().apply {
            pages = ArrayDeque((1..10).map { OrderPage(listOf(o("x$it", OrderStatus.EXPIRED, it.toLong())), "c$it") })
        }
        val repo = OrderRepository(api)
        repo.loadFirstPage()
        assertEquals(OrderRepository.MAX_AUTO_PAGES, api.pageCalls.size)
        assertFalse(repo.history.value.loading)
        assertTrue(repo.history.value.hasMore)
    }

    @Test fun `내역 불러오기 실패 - 표시와 다시 시도`() = blocking {
        val api = FakeOrdersApi().apply { error = AppError.Network() }
        val repo = OrderRepository(api)
        assertEquals(Outcome.Fail(LoadFailure.NETWORK), repo.loadFirstPage())
        assertTrue(repo.history.value.failed)
        assertFalse(repo.history.value.loading)
        api.error = null
        api.pages = ArrayDeque(listOf(OrderPage(listOf(o("a")), null)))
        repo.loadFirstPage()
        assertFalse(repo.history.value.failed)
        assertEquals(1, repo.history.value.orders.size)
    }

    @Test fun `불러오는 중에는 다음 페이지를 중복 요청하지 않는다`() = runTest {
        val api = FakeOrdersApi().apply {
            pages = ArrayDeque(listOf(OrderPage(listOf(o("a", min = 5)), "c1"), OrderPage(listOf(o("b", min = 4)), null)))
        }
        val repo = OrderRepository(api)
        repo.loadFirstPage()
        val gate = CompletableDeferred<Unit>()
        api.gates += gate
        val first = launch { repo.loadMore() }
        runCurrent()
        val second = launch { repo.loadMore() } // 로딩 중 → 무시
        runCurrent()
        assertEquals(2, api.pageCalls.size)
        assertTrue(second.isCompleted)
        gate.complete(Unit)
        first.join()
        assertEquals(2, api.pageCalls.size)
        assertEquals(listOf("a", "b"), repo.history.value.orders.map { it.id })
    }

    @Test fun `처음부터 다시 불러오는 동안 도착한 이전 응답은 버린다`() = runTest {
        val api = FakeOrdersApi().apply {
            pages = ArrayDeque(listOf(OrderPage(listOf(o("old", min = 1)), "c1"), OrderPage(listOf(o("new", min = 2)), null)))
        }
        val oldGate = CompletableDeferred<Unit>()
        api.gates += oldGate // 첫 요청만 대기, 두 번째(새로고침)는 바로
        val repo = OrderRepository(api)
        val first = launch { repo.loadFirstPage() }
        runCurrent()
        repo.loadFirstPage() // 새로고침이 먼저 끝난다
        assertEquals(listOf("new"), repo.history.value.orders.map { it.id })
        oldGate.complete(Unit)
        first.join()
        assertEquals(listOf("new"), repo.history.value.orders.map { it.id }) // 낡은 응답이 덮어쓰지 않음
        assertFalse(repo.history.value.hasMore)
    }

    @Test fun `알림 권한 안내 시트 횟수는 계정별`() = blocking {
        val s = InMemoryNotificationPromptStore()
        assertEquals(0, s.dismissCount("u1"))
        s.recordDismissal("u1"); s.recordDismissal("u1"); s.recordDismissal("u2")
        assertEquals(2, s.dismissCount("u1"))
        assertEquals(1, s.dismissCount("u2"))
    }
}

