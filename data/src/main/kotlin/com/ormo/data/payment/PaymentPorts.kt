package com.ormo.data.payment

import com.ormo.core.Outcome
import com.ormo.domain.order.OrderDraft
import com.ormo.domain.order.PaymentMethod
import com.ormo.domain.payment.PaymentStatus
import com.ormo.domain.payment.PlacedOrder
import com.ormo.domain.store.StoreStatus

/** 주문 생성이 거절된 이유. 네트워크 오류는 예외([com.ormo.data.remote.AppError.Network])로 던진다. */
sealed interface PlaceOrderFailure {
    /** 서버가 영업 상태를 다시 확인해 막음 (3.1.2) — 결제창 전에 */
    data class StoreUnavailable(val status: StoreStatus) : PlaceOrderFailure

    /** 담은 메뉴가 모두 품절 (일부 품절은 서버가 빼고 접수한다) */
    data object AllSoldOut : PlaceOrderFailure

    data object Other : PlaceOrderFailure
}

/**
 * 주문·결제 서버 API.
 *
 * ⚠️ 서버 계약 미확정 (BE: 주문 생성·결제 상태 조회·멱등 키 지원). 인터페이스 뒤에 숨겨 두고 Fake 로 화면을 먼저 만든다.
 * - [placeOrder]: 같은 [idempotencyKey] 로 다시 부르면 **같은 주문**을 돌려줘야 한다 (O-42, O-43). 가격은 서버가 다시 계산.
 * - [paymentStatus]: PG 결과를 서버가 확정한 상태. 네트워크 오류는 예외로 던진다.
 */
interface OrderApi {
    suspend fun placeOrder(draft: OrderDraft, idempotencyKey: String): Outcome<PlacedOrder, PlaceOrderFailure>

    suspend fun paymentStatus(orderId: String): PaymentStatus
}

data class PgRequest(val orderId: String, val amount: Int, val method: PaymentMethod)

sealed interface PgResult {
    /** PG 가 승인했다고 알림 — 최종 확정은 서버 확인으로 한다 */
    data class Approved(val transactionId: String? = null) : PgResult

    data class Failed(val code: String? = null, val message: String? = null) : PgResult

    /** 사용자가 결제창을 닫음 */
    data object Canceled : PgResult
}

/**
 * PG 결제창 (KG이니시스 + 간편결제, 포트원 v2 — 확정 전). 실제 구현은 `:app`(Activity 필요).
 * 앱 프로세스가 죽었다 살아나도 결제 결과는 서버에 있으므로 [PaymentCoordinator.resume] 으로 맞춘다.
 */
interface PaymentGateway {
    suspend fun pay(request: PgRequest): PgResult
}

/** 결제 확인 중에 앱이 꺼져도 이어갈 수 있도록 저장해 두는 진행 중 주문 (3.2.2: "앱을 나갔다 들어와도 서버에 다시 묻고 상태 맞춤") */
data class PendingPayment(val order: PlacedOrder, val key: String, val failedCount: Int)

interface PendingPaymentStore {
    suspend fun load(): PendingPayment?
    suspend fun save(pending: PendingPayment)
    suspend fun clear()
}

class InMemoryPendingPaymentStore : PendingPaymentStore {
    var value: PendingPayment? = null
    override suspend fun load() = value
    override suspend fun save(pending: PendingPayment) { value = pending }
    override suspend fun clear() { value = null }
}

/** 마지막에 쓴 결제수단 기억 (2.3). 계정별 키는 호출하는 쪽이 정한다. */
interface PaymentMethodStore {
    suspend fun load(accountKey: String): PaymentMethod?
    suspend fun save(accountKey: String, method: PaymentMethod)
}

class InMemoryPaymentMethodStore : PaymentMethodStore {
    private val map = HashMap<String, PaymentMethod>()
    override suspend fun load(accountKey: String) = map[accountKey]
    override suspend fun save(accountKey: String, method: PaymentMethod) { map[accountKey] = method }
}

/** 개발·미리보기용 PG: 준비된 결과를 차례로 돌려준다. 다 쓰면 마지막 값을 반복. */
class FakePaymentGateway(results: List<PgResult> = listOf(PgResult.Approved("fake-tx"))) : PaymentGateway {
    private val queue = ArrayDeque(results)
    val requests = mutableListOf<PgRequest>()

    override suspend fun pay(request: PgRequest): PgResult {
        requests += request
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }
}
