package com.ormo.customer.fake

import com.ormo.core.Outcome
import com.ormo.data.payment.OrderApi
import com.ormo.data.payment.PaymentGateway
import com.ormo.data.payment.PgRequest
import com.ormo.data.payment.PgResult
import com.ormo.data.payment.PlaceOrderFailure
import com.ormo.data.store.StoreDirectory
import com.ormo.domain.order.PaymentMethod
import com.ormo.domain.order.OrderDraft
import com.ormo.domain.payment.PaymentStatus
import com.ormo.domain.payment.PlacedOrder
import com.ormo.domain.store.StoreStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 개발용 가짜 주문·결제 서버 + 가짜 PG 창. BE·PG(포트원 v2 / KG이니시스)가 확정되기 전에 결제 흐름 전체를 눌러 볼 수 있게 한다.
 * 가짜 PG 창([pgWindow])에서 시험하는 사람이 결과를 고른다: 승인 / 승인(서버 반영 지연) / 실패 / 닫기 / 만료시키기.
 * M8 에서 실제 `OrderApi`·`PaymentGateway` 로 바꾸고 이 파일을 지운다.
 */
class FakePaymentServer(
    private val stores: StoreDirectory,
    private val currentStoreId: () -> String?,
    private val latencyMs: Long = 400,
    private val expiresInMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private sealed interface Server {
        data object Pending : Server
        data class SlowPaid(var pollsLeft: Int) : Server
        data object Paid : Server
        data class Failed(val code: String) : Server
        data object Expired : Server
    }

    private val byKey = HashMap<String, PlacedOrder>()
    private val drafts = HashMap<String, OrderDraft>()

    /** 결제가 승인되면 호출: 가짜 '내 주문' 서버에 접수 대기 주문을 만든다 (M5) */
    var onPaid: ((orderId: String, draft: OrderDraft, method: PaymentMethod?) -> Unit)? = null
    private val status = HashMap<String, Server>()
    private var counter = 0

    val orderApi: OrderApi = object : OrderApi {
        override suspend fun placeOrder(draft: OrderDraft, idempotencyKey: String): Outcome<PlacedOrder, PlaceOrderFailure> {
            delay(latencyMs)
            // 같은 멱등 키는 같은 주문 (O-42, O-43)
            byKey[idempotencyKey]?.let { return Outcome.Ok(it) }
            // 결제창 전에 서버가 영업 상태를 다시 확인한다 (3.1.2)
            val store = currentStoreId()?.let { id -> stores.fetchStores().firstOrNull { it.id == id } }
            if (store != null && store.status != StoreStatus.Open) {
                return Outcome.Fail(PlaceOrderFailure.StoreUnavailable(store.status))
            }
            val order = PlacedOrder(
                orderId = "ord-${++counter}",
                amount = draft.expectedAmount, // 서버가 다시 계산한 금액 (가짜 서버는 같은 값)
                fingerprint = "",
                expiresAtMs = clock() + expiresInMs,
            )
            byKey[idempotencyKey] = order
            drafts[order.orderId] = draft
            status[order.orderId] = Server.Pending
            return Outcome.Ok(order)
        }

        override suspend fun paymentStatus(orderId: String): PaymentStatus {
            delay(latencyMs / 2)
            val order = byKey.values.firstOrNull { it.orderId == orderId } ?: return PaymentStatus.Expired
            return when (val s = status[orderId]) {
                Server.Paid -> PaymentStatus.Paid
                is Server.SlowPaid -> if (s.pollsLeft-- <= 0) { status[orderId] = Server.Paid; PaymentStatus.Paid } else PaymentStatus.Pending
                is Server.Failed -> PaymentStatus.Failed(s.code)
                Server.Expired -> PaymentStatus.Expired
                else -> if (clock() >= (order.expiresAtMs ?: Long.MAX_VALUE)) PaymentStatus.Expired else PaymentStatus.Pending
            }
        }
    }

    /** 지금 떠 있는 가짜 PG 창 (없으면 null). 화면이 이 값을 보고 창을 그린다. */
    private val _pgWindow = MutableStateFlow<PgRequest?>(null)
    val pgWindow: StateFlow<PgRequest?> = _pgWindow

    private var waiting: CompletableDeferred<PgResult>? = null

    val gateway: PaymentGateway = object : PaymentGateway {
        override suspend fun pay(request: PgRequest): PgResult {
            val d = CompletableDeferred<PgResult>()
            waiting = d
            _pgWindow.value = request
            return try {
                d.await()
            } finally {
                _pgWindow.value = null
                waiting = null
            }
        }
    }

    /** 시험하는 사람이 PG 창에서 고른 결과 */
    private fun placeInOrders(orderId: String) {
        val draft = drafts[orderId] ?: return
        onPaid?.invoke(orderId, draft, _pgWindow.value?.method)
    }

    fun approve(orderId: String) { status[orderId] = Server.Paid; placeInOrders(orderId); waiting?.complete(PgResult.Approved("fake-tx")) }
    fun approveSlow(orderId: String) { status[orderId] = Server.SlowPaid(pollsLeft = 12); placeInOrders(orderId); waiting?.complete(PgResult.Approved("fake-tx")) }
    fun fail(orderId: String) { status[orderId] = Server.Failed("CARD_LIMIT"); waiting?.complete(PgResult.Failed("CARD_LIMIT", "한도 초과")) }
    fun close() { waiting?.complete(PgResult.Canceled) }
    fun expire(orderId: String) { status[orderId] = Server.Expired; waiting?.complete(PgResult.Canceled) }
}
