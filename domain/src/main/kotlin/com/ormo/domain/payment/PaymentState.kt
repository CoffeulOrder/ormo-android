package com.ormo.domain.payment

import com.ormo.domain.store.StoreStatus

/** 서버가 만든 주문 (PENDING_PAYMENT). 결제가 승인되어야 매장에 접수 대기로 간다 (O-34). */
data class PlacedOrder(
    val orderId: String,
    /** 서버가 다시 계산한 실제 결제 금액. 0원이면 PG 를 건너뛴다 */
    val amount: Int,
    /** [com.ormo.domain.order.OrderDraft.fingerprint] — 같은 주문인지 알아보는 값 (클라이언트가 채운다) */
    val fingerprint: String,
    /** 결제 대기 만료 시각 (D-29, 서비스 공통 {N}분). 모르면 null → 서버 응답으로만 안다 */
    val expiresAtMs: Long? = null,
    /** 서버가 품절이라 빼고 접수한 메뉴 id (품절은 막지 않고 빼고 결제, 2.3) */
    val removedMenuIds: List<String> = emptyList(),
)

/** 서버가 알려주는 결제 결과 (결제 확인 중 3.2.2) */
sealed interface PaymentStatus {
    data object Pending : PaymentStatus
    data object Paid : PaymentStatus
    data class Failed(val code: String? = null) : PaymentStatus
    data object Expired : PaymentStatus
}

/** [결제하기] 를 눌렀지만 결제창까지 못 간 이유 (토스트용). 영업 상태 때문에 막힌 경우는 [PaymentPhase.Blocked]. */
enum class PrepareError { NETWORK, ALL_SOLD_OUT, OTHER }

sealed interface PaymentPhase {
    /** 주문서 화면. 아직 결제 전이거나 결제창을 닫고 돌아온 상태 */
    data object Idle : PaymentPhase

    /** 서버에 주문을 만들고/영업·품절을 확인하는 중 (결제 버튼 중복 입력 방지) */
    data object Preparing : PaymentPhase

    /** 3.1.2 주문 중지 다이얼로그 (영업 종료·잠시 멈춤). [확인] → [PaymentState.dismissBlocked] */
    data class Blocked(val status: StoreStatus) : PaymentPhase

    /** PG 결제창이 떠 있음 */
    data object AwaitingPg : PaymentPhase

    /** 3.2.2 결제 확인 중. 뒤로가기 막음. [checkFailed] = 서버에 못 물어봤거나 아직 결과가 안 나옴 (다시 확인 필요) */
    data class Confirming(val checkFailed: Boolean = false) : PaymentPhase

    /** 4.1.1 로 이동 */
    data object Succeeded : PaymentPhase

    /** 3.3.1 결제 실패: [다시 결제하기] / [결제수단 변경하기] */
    data class Failed(val code: String? = null) : PaymentPhase

    /** 3.3.1 5번 실패: [장바구니로 이동] 하나. 이 주문으로는 더 결제할 수 없다 */
    data object Exhausted : PaymentPhase

    /** 3.3.2 결제 가능 시간이 지남: [다시 주문하기] */
    data object Expired : PaymentPhase
}

/**
 * 결제 상태 머신 (디자인 2.3, 3.1.2, 3.2.2, 3.3.x). 순수 불변 — IO 는 `PaymentCoordinator`.
 *
 * ```
 *  Idle ──pay──► Preparing ──주문 생성──► AwaitingPg ──돌아옴──► Confirming ──서버 확인──► Succeeded
 *    ▲               │                      │ 창 닫음                    ├─► Failed ──다시 결제──► Preparing …
 *    │               ├─ 영업 중지 ► Blocked  └──► Idle (주문 유지)         │      └─ 5번째 ► Exhausted
 *    └───────────────┴─ 네트워크/전부 품절 ► Idle (+ prepareError)        └─► Expired
 * ```
 *
 * 규칙
 * - **중복 입력 방지**: [begin] 은 Idle/Failed 에서만 받아들인다 → 결제 버튼을 여러 번 눌러도 결제는 한 번.
 * - **같은 주문 키**: 주문이 만들어지면 [order]/[key] 를 유지한다. 창을 닫거나 실패해서 다시 결제해도 같은 주문·같은 키 (O-42, O-43).
 * - **5번**: 같은 주문으로 실패가 [MAX_FAILURES] 번이 되면 [PaymentPhase.Exhausted]. (TODO(spec 3.3.1): "5번까지 다시 결제" 를
 *   실패 5회에서 막는 것으로 해석 — 첫 시도 + 재시도 5번인지 확인)
 * - 장바구니/수령 방법이 바뀌면 [invalidateOrder] 로 주문을 버리고 새 키로 시작.
 */
data class PaymentState(
    val phase: PaymentPhase = PaymentPhase.Idle,
    val order: PlacedOrder? = null,
    /** 이 주문의 멱등 키 (주문이 없으면 null) */
    val key: String? = null,
    val failedCount: Int = 0,
    val prepareError: PrepareError? = null,
) {
    /** 결제 확인 중(3.2.2)·준비 중·결제창 중에는 뒤로가기를 막는다 */
    val backBlocked: Boolean
        get() = phase is PaymentPhase.Confirming || phase == PaymentPhase.Preparing || phase == PaymentPhase.AwaitingPg

    /** 결제 버튼을 받아들일 수 있는 상태 */
    val canBegin: Boolean get() = phase == PaymentPhase.Idle || phase is PaymentPhase.Failed

    /** 결제 중이라 버튼을 꺼야 하는가 */
    val busy: Boolean get() = !canBegin && phase !is PaymentPhase.Blocked && phase !is PaymentPhase.Exhausted &&
        phase != PaymentPhase.Succeeded && phase != PaymentPhase.Expired

    /**
     * 결제 시작. Idle/Failed 가 아니면 `null` (이미 진행 중 → 무시).
     * 이미 같은 주문이 있으면 [keepOrder] = true 로 기존 주문·멱등 키·실패 횟수를 유지하고, 아니면 [newKey] 로 새 주문을 시작한다.
     */
    fun begin(keepOrder: Boolean, newKey: () -> String): PaymentState? {
        if (!canBegin) return null
        return if (keepOrder && order != null && key != null) {
            copy(phase = PaymentPhase.Preparing, prepareError = null)
        } else {
            PaymentState(phase = PaymentPhase.Preparing, key = newKey())
        }
    }

    fun blocked(status: StoreStatus): PaymentState =
        if (phase == PaymentPhase.Preparing) copy(phase = PaymentPhase.Blocked(status)) else this

    fun dismissBlocked(): PaymentState = if (phase is PaymentPhase.Blocked) toIdleKeepingOrder() else this

    /** 결제창까지 못 갔다 (네트워크·전부 품절 등): 주문서로 돌아가 토스트. 이미 만든 주문은 유지. */
    fun prepareFailed(error: PrepareError): PaymentState =
        if (phase == PaymentPhase.Preparing) copy(phase = PaymentPhase.Idle, prepareError = error) else this

    fun clearPrepareError(): PaymentState = copy(prepareError = null)

    /** 서버가 주문을 만들었다. 0원이면 결제창 없이 서버 확인으로 */
    fun placed(placed: PlacedOrder): PaymentState {
        if (phase != PaymentPhase.Preparing) return this
        val next = if (placed.amount <= 0) PaymentPhase.Confirming() else PaymentPhase.AwaitingPg
        return copy(phase = next, order = placed)
    }

    /** 이미 있던 주문으로 결제창을 연다 (다시 결제하기 / 창을 닫았다 돌아온 뒤 서버 확인에서 아직 안 낸 걸로 확인) */
    fun openPg(): PaymentState =
        if (phase == PaymentPhase.Preparing && order != null) copy(phase = PaymentPhase.AwaitingPg) else this

    /** 창을 닫았다 돌아와 다시 눌렀다: 같은 주문의 결제 여부를 서버에 먼저 확인하는 동안 */
    fun reconfirm(): PaymentState =
        if (phase == PaymentPhase.Preparing && order != null) copy(phase = PaymentPhase.Confirming()) else this

    /** PG 결제창이 승인/실패 어느 쪽으로든 돌아왔다 → 서버에 결과를 묻는다 (3.2.2) */
    fun pgReturned(): PaymentState =
        if (phase == PaymentPhase.AwaitingPg) copy(phase = PaymentPhase.Confirming()) else this

    /** 결제창을 그냥 닫았다 → 주문서(2.3)로. 주문은 유지, 실패 횟수는 올리지 않는다 */
    fun pgCanceled(): PaymentState = if (phase == PaymentPhase.AwaitingPg) toIdleKeepingOrder() else this

    /** 앱을 나갔다 들어오거나 재시작했을 때: 저장해 둔 주문을 서버에 다시 묻는다 */
    fun resumed(order: PlacedOrder, key: String, failedCount: Int): PaymentState =
        if (phase == PaymentPhase.Idle && this.order == null) {
            PaymentState(PaymentPhase.Confirming(), order, key, failedCount)
        } else {
            this
        }

    /** 확인 중에 서버 응답을 못 받음/아직 대기 중 — 확인 중 화면에서 [다시 확인] */
    fun confirmUnresolved(): PaymentState =
        if (phase is PaymentPhase.Confirming) copy(phase = PaymentPhase.Confirming(checkFailed = true)) else this

    /** 서버가 다시 확인을 시작함(자동 재시도/버튼) */
    fun confirmRetrying(): PaymentState =
        if (phase is PaymentPhase.Confirming) copy(phase = PaymentPhase.Confirming(checkFailed = false)) else this

    /**
     * 확인이 계속 안 될 때(`checkFailed`)만: 주문서로 나간다 (주문은 유지 → 다시 누르면 서버 확인부터).
     * TODO(spec 3.2.2): 명세는 '뒤로가기 막음' 뿐이라 끝없이 갇히지 않게 하는 탈출구는 가정 — 디자인 확인.
     */
    fun giveUpWaiting(): PaymentState =
        if ((phase as? PaymentPhase.Confirming)?.checkFailed == true) copy(phase = PaymentPhase.Idle) else this

    fun paid(): PaymentState = if (phase is PaymentPhase.Confirming) copy(phase = PaymentPhase.Succeeded, failedCount = 0) else this

    fun failed(code: String?): PaymentState {
        if (phase !is PaymentPhase.Confirming) return this
        val count = failedCount + 1
        return if (count >= MAX_FAILURES) copy(phase = PaymentPhase.Exhausted, failedCount = count)
        else copy(phase = PaymentPhase.Failed(code), failedCount = count)
    }

    /** 결제 가능 시간이 지남 (서버 응답 또는 [checkExpiry]) */
    fun expired(): PaymentState = when (phase) {
        is PaymentPhase.Confirming, PaymentPhase.AwaitingPg, PaymentPhase.Preparing, is PaymentPhase.Failed, PaymentPhase.Idle ->
            if (order != null) copy(phase = PaymentPhase.Expired) else this
        else -> this
    }

    /** 결제창이 열려 있거나 주문서에 돌아와 있는 동안 만료 시각이 지났는지 본다. Confirming 은 서버가 정한다. */
    fun checkExpiry(nowMs: Long): PaymentState {
        val exp = order?.expiresAtMs ?: return this
        val waiting = phase == PaymentPhase.Idle || phase is PaymentPhase.Failed
        return if (waiting && nowMs >= exp) copy(phase = PaymentPhase.Expired) else this
    }

    /** 3.3.2 [다시 주문하기] / 3.3.1 [장바구니로 이동]: 이 주문은 버리고 새 주문으로 */
    fun leaveTerminal(): PaymentState =
        if (phase == PaymentPhase.Expired || phase == PaymentPhase.Exhausted || phase == PaymentPhase.Succeeded) PaymentState() else this

    /** 장바구니·수령 방법이 바뀌었다: 아직 진행 중이 아니면 주문을 버리고 새 키로 시작 */
    fun invalidateOrder(): PaymentState =
        if ((phase == PaymentPhase.Idle || phase is PaymentPhase.Failed) && order != null) PaymentState() else this

    private fun toIdleKeepingOrder() = copy(phase = PaymentPhase.Idle)

    companion object {
        const val MAX_FAILURES = 5
    }
}
