package com.ormo.data.payment

import com.ormo.core.Outcome
import com.ormo.domain.order.OrderDraft
import com.ormo.domain.order.PaymentMethod
import com.ormo.domain.payment.PaymentPhase
import com.ormo.domain.payment.PaymentState
import com.ormo.domain.payment.PaymentStatus
import com.ormo.domain.payment.PlacedOrder
import com.ormo.domain.payment.PrepareError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * 결제 진행을 맡는 곳: [PaymentState] 상태 머신을 서버·PG 호출과 이어 준다.
 *
 * - **앱 범위(싱글톤)** 로 만들 것. 화면(ViewModel)이 사라져도 결제 확인은 이어져야 하고, 상태는 [state] 로 다시 구독한다.
 * - 결제 버튼 연타: [pay] 는 Idle/Failed 가 아니면 즉시 무시한다 (상태 전이가 동기식 잠금 안에서 일어난다).
 * - 서버가 최종 결정권자: PG 가 승인/실패를 알려도 항상 [OrderApi.paymentStatus] 로 확정한 뒤 화면을 바꾼다 (3.2.2).
 * - 결제창을 닫고 돌아와 다시 누르면, 새 주문을 만들지 않고 **같은 주문**의 상태를 먼저 확인한다 (그 사이 결제가 승인됐을 수 있음).
 * - 확인이 늦어지면 [maxPolls] 번까지 [pollIntervalMs] 간격으로 묻고, 그래도 결론이 없으면 `Confirming(checkFailed = true)` 로 두어
 *   화면이 [다시 확인] 을 보이게 한다 (앱 복귀 시에도 [recheck]).
 *
 * @param delayFn 테스트에서 시간 건너뛰기용
 */
class PaymentCoordinator(
    private val orders: OrderApi,
    private val gateway: PaymentGateway,
    private val pending: PendingPaymentStore,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
    private val pollIntervalMs: Long = 1_000,
    private val maxPolls: Int = 10,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Any()
    private val _state = MutableStateFlow(PaymentState())
    val state: StateFlow<PaymentState> = _state

    private fun update(f: (PaymentState) -> PaymentState): PaymentState = synchronized(lock) {
        f(_state.value).also { _state.value = it }
    }

    /**
     * 결제 시작. [draft] = 화면에 보이는 주문서. 이미 진행 중이면 아무것도 하지 않는다.
     * 끝나면(성공/실패/중지 등) 상태가 [state] 에 반영돼 있다.
     */
    suspend fun pay(draft: OrderDraft, method: PaymentMethod) {
        // 1) 동기 구간에서 시작을 확정 (연타 방지)
        var wasFailed = false
        var existing: PlacedOrder? = null
        var accepted = false
        synchronized(lock) {
            val s = _state.value
            val keep = s.order?.fingerprint == draft.fingerprint
            val next = s.begin(keepOrder = keep, newKey = keyFactory) ?: return@synchronized
            wasFailed = s.phase is PaymentPhase.Failed && keep
            existing = if (keep) s.order else null
            _state.value = next
            accepted = true
        }
        if (!accepted) return

        try {
            var order = existing
            if (order == null) {
                if (!place(draft)) return
                order = _state.value.order!!
            } else if (!wasFailed) {
                // 창을 닫고 돌아온 뒤 다시 누름: 그 사이 결제가 됐는지 서버에 먼저 확인
                when (checkBeforeRetry(order)) {
                    Next.STOP -> return
                    Next.PAY -> update { it.openPg() }
                }
            } else {
                update { it.openPg() }
            }
            if (_state.value.phase == PaymentPhase.AwaitingPg) {
                openGateway(order, method)
            }
            if (_state.value.phase is PaymentPhase.Confirming) confirmLoop(order)
        } catch (e: CancellationException) {
            // 화면이 사라져도 상태가 멈춘 채 남지 않게 한다. 주문은 유지해 다음 결제에서 서버 확인부터 한다.
            update { s ->
                when (s.phase) {
                    PaymentPhase.Preparing -> s.prepareFailed(PrepareError.OTHER).clearPrepareError()
                    PaymentPhase.AwaitingPg -> s.pgCanceled()
                    else -> s
                }
            }
            throw e
        }
    }

    private enum class Next { PAY, STOP }

    /** 새 주문 만들기. 성공하면 true (이후 진행), 아니면 상태를 정리하고 false */
    private suspend fun place(draft: OrderDraft): Boolean {
        val key = _state.value.key!!
        val result = try {
            orders.placeOrder(draft, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.ormo.data.remote.AppError.Network) {
            update { it.prepareFailed(PrepareError.NETWORK) }
            return false
        } catch (e: Exception) {
            update { it.prepareFailed(PrepareError.OTHER) }
            return false
        }
        return when (result) {
            is Outcome.Ok -> {
                val placed = result.value.copy(fingerprint = draft.fingerprint)
                val s = update { it.placed(placed) }
                persist(s)
                true
            }
            is Outcome.Fail -> {
                when (val f = result.failure) {
                    is PlaceOrderFailure.StoreUnavailable -> update { it.blocked(f.status) }
                    PlaceOrderFailure.AllSoldOut -> update { it.prepareFailed(PrepareError.ALL_SOLD_OUT) }
                    PlaceOrderFailure.Other -> update { it.prepareFailed(PrepareError.OTHER) }
                }
                false
            }
        }
    }

    private suspend fun checkBeforeRetry(order: PlacedOrder): Next {
        val status = try {
            orders.paymentStatus(order.orderId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update { it.prepareFailed(PrepareError.NETWORK) }
            return Next.STOP
        }
        return when (status) {
            PaymentStatus.Paid -> { update { it.reconfirm() }; update { it.paid() }; finish(); Next.STOP }
            PaymentStatus.Expired -> { update { it.expired() }; finish(); Next.STOP }
            // 아직 내지 않았거나(Pending), 실패로 끝났어도 5번 안이면 같은 주문으로 다시 결제창을 연다
            is PaymentStatus.Failed, PaymentStatus.Pending -> Next.PAY
        }
    }

    private suspend fun openGateway(order: PlacedOrder, method: PaymentMethod) {
        val result = try {
            gateway.pay(PgRequest(order.orderId, order.amount, method))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PgResult.Failed(code = null, message = e.message) // 결과를 모르니 서버에 확인
        }
        when (result) {
            PgResult.Canceled -> update { it.pgCanceled() }
            is PgResult.Approved, is PgResult.Failed -> update { it.pgReturned() }
        }
    }

    /** 서버가 확정할 때까지 묻는다. */
    private suspend fun confirmLoop(order: PlacedOrder) {
        for (i in 0 until maxPolls) {
            val status = try {
                orders.paymentStatus(order.orderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.confirmUnresolved() }
                return
            }
            when (status) {
                PaymentStatus.Paid -> { update { it.paid() }; finish(); return }
                is PaymentStatus.Failed -> {
                    val s = update { it.failed(status.code) }
                    if (s.phase is PaymentPhase.Exhausted) finish() else persist(s)
                    return
                }
                PaymentStatus.Expired -> { update { it.expired() }; finish(); return }
                PaymentStatus.Pending -> if (i < maxPolls - 1) delayFn(pollIntervalMs)
            }
        }
        update { it.confirmUnresolved() }
    }

    /** "확인 중" 에서 [다시 확인] 을 누르거나 앱이 포그라운드로 돌아왔을 때 */
    suspend fun recheck() {
        val order = synchronized(lock) {
            val s = _state.value
            val o = s.order
            if (s.phase !is PaymentPhase.Confirming || o == null) return
            _state.value = s.confirmRetrying()
            o
        }
        confirmLoop(order)
    }

    /** 앱 시작 때: 저장된 진행 중 주문이 있으면 서버에 물어 상태를 맞춘다. 없으면 아무것도 안 한다. */
    suspend fun resume() {
        val saved = pending.load() ?: return
        val started = synchronized(lock) {
            val next = _state.value.resumed(saved.order, saved.key, saved.failedCount)
            val changed = next !== _state.value
            _state.value = next
            changed
        }
        if (started) confirmLoop(saved.order)
    }

    /** 확인이 계속 안 될 때 주문서로 나가기 (주문은 유지) */
    fun giveUpWaiting() { update { it.giveUpWaiting() } }

    /** 로그아웃·탈퇴 때: 진행 중이던 결제 상태를 버린다 (다른 계정의 주문을 이어받지 않도록). 저장해 둔 진행 중 주문은 [pending] 쪽에서 지운다. */
    suspend fun reset() {
        update { PaymentState() }
        pending.clear()
    }

    fun dismissBlocked() { update { it.dismissBlocked() } }

    fun clearPrepareError() { update { it.clearPrepareError() } }

    /** 3.3.1 [장바구니로 이동] / 3.3.2 [다시 주문하기] / 4.1.1 으로 넘어간 뒤: 이 주문은 끝 */
    suspend fun leaveTerminal() {
        val s = update { it.leaveTerminal() }
        if (s.order == null) pending.clear()
    }

    /** 주문서가 바뀔 때마다 부른다: 다른 주문이 되면 이전 주문을 버리고 새 키로 시작 (진행 중이면 무시) */
    suspend fun onDraftChanged(draft: OrderDraft?) {
        val s = update { cur ->
            if (cur.order?.let { it.fingerprint != draft?.fingerprint } == true) cur.invalidateOrder() else cur
        }
        if (s.order == null) pending.clear()
    }

    /** 주문서로 돌아와 있는 동안 만료 시각이 지났는지 확인 (타이머/복귀 시) */
    fun checkExpiry(nowMs: Long) { update { it.checkExpiry(nowMs) } }

    private suspend fun persist(s: PaymentState) {
        val o = s.order ?: return
        val k = s.key ?: return
        pending.save(PendingPayment(o, k, s.failedCount))
    }

    private suspend fun finish() { pending.clear() }
}
