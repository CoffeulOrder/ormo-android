package com.ormo.data.orders

import com.ormo.core.Outcome
import com.ormo.core.Resettable
import com.ormo.data.remote.AppError
import com.ormo.domain.orders.HistoryState
import com.ormo.domain.orders.Order
import com.ormo.domain.orders.OrderDetailRules
import com.ormo.domain.orders.OrderHistoryRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

data class OrderPage(val orders: List<Order>, val nextCursor: String?)

/**
 * 내 주문 서버 API.
 *
 * ⚠️ 서버 계약 미확정. 인터페이스 뒤에 숨기고 Fake 로 화면을 먼저 만든다. 실패는 [AppError] 로 던진다.
 * - [fetchActive]: 진행 중(접수 대기·제조 중·픽업 대기) 주문 — 홈 주문 현황 바용
 * - [fetchHistoryPage]: 최신순 [limit]개씩. 서버가 만료 주문은 빼 주지만 앱도 한 번 더 거른다.
 * - [cancel]: 접수 대기일 때만 성공. 이미 접수됐으면 **409**(`AppError.Api(status = 409)`) — 최종 판단은 서버 (D-28)
 */
interface MyOrdersApi {
    suspend fun fetchOrder(orderId: String): Order
    suspend fun fetchActive(): List<Order>
    suspend fun fetchHistoryPage(cursor: String?, limit: Int): OrderPage
    suspend fun cancel(orderId: String): Order
}

enum class LoadFailure { NETWORK, OTHER }

sealed interface CancelFailure {
    /** 접수되어 취소할 수 없음 (4.4.2) — [latest] 로 화면을 최신 상태로 바꾼다. 못 받았으면 null */
    data class NotCancelable(val latest: Order?) : CancelFailure
    data object Network : CancelFailure
    data object Other : CancelFailure
}

/**
 * 주문 조회·취소·내역 페이징 저장소.
 * 로그아웃/세션 만료 때 [reset] 으로 비운다 (`SessionResetter` 에 등록). 서버가 기준이므로 로컬에 영구 저장하지 않는다.
 */
class OrderRepository(
    private val api: MyOrdersApi,
    private val pageSize: Int = OrderHistoryRules.PAGE_SIZE,
) : Resettable {
    private val _active = MutableStateFlow<List<Order>>(emptyList())

    /** 진행 중 주문 (홈 주문 현황 바) */
    val active: StateFlow<List<Order>> = _active

    private val _history = MutableStateFlow(HistoryState())
    val history: StateFlow<HistoryState> = _history

    private val lock = Any()
    private var generation = 0

    override fun reset() {
        synchronized(lock) {
            generation++
            _active.value = emptyList()
            _history.value = HistoryState()
        }
    }

    // ---- 진행 중 ----
    /** 홈 진입·포그라운드 복귀·푸시 수신 때. 실패하면 기존 값을 유지한다 ("일부만 실패" 는 조용히/스낵바). */
    suspend fun refreshActive(): Outcome<List<Order>, LoadFailure> {
        val gen = synchronized(lock) { generation }
        return try {
            val list = api.fetchActive().filter { it.status.isActive }
            synchronized(lock) { if (generation == gen) _active.value = list }
            Outcome.Ok(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Fail(e.toFailure())
        }
    }

    // ---- 상세 ----
    suspend fun load(orderId: String): Outcome<Order, LoadFailure> = try {
        Outcome.Ok(api.fetchOrder(orderId).also(::sync))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Fail(e.toFailure())
    }

    /**
     * 주문 상세를 보는 동안 자동 새로고침 (4.3). 첫 조회는 바로, 진행 중이면 [OrderDetailRules.pollIntervalMs] 간격으로 반복하고
     * **끝난 주문(픽업 완료·거절 등)이면 멈춘다**. 수집을 멈추면(화면을 떠나면) 폴링도 멈춘다 — 앱 백그라운드에서는 푸시/복귀 시 조회.
     * 일시적 실패는 [Outcome.Fail] 로 알리고 계속 다시 시도한다.
     */
    fun observe(orderId: String, intervalOverrideMs: Long? = null): Flow<Outcome<Order, LoadFailure>> = flow {
        while (true) {
            val r = load(orderId)
            emit(r)
            val interval = when (r) {
                is Outcome.Ok -> intervalOverrideMs ?: OrderDetailRules.pollIntervalMs(r.value.status) ?: return@flow
                is Outcome.Fail -> intervalOverrideMs ?: OrderDetailRules.POLL_MS
            }
            delay(interval)
        }
    }

    // ---- 취소 ----
    suspend fun cancel(orderId: String): Outcome<Order, CancelFailure> = try {
        Outcome.Ok(api.cancel(orderId).also(::sync))
    } catch (e: CancellationException) {
        throw e
    } catch (e: AppError.Api) {
        if (e.status == 409) {
            // 취소를 누른 순간 매장이 받은 경우: 최신 상태를 받아 화면을 바꾼다
            val latest = (load(orderId) as? Outcome.Ok)?.value
            Outcome.Fail(CancelFailure.NotCancelable(latest))
        } else {
            Outcome.Fail(CancelFailure.Other)
        }
    } catch (e: AppError.Network) {
        Outcome.Fail(CancelFailure.Network)
    } catch (e: Exception) {
        Outcome.Fail(CancelFailure.Other)
    }

    // ---- 내 주문 (지난 주문) ----
    /** 처음부터 다시 (진입·당겨서 새로고침). 진행 중인 페이지 요청은 버린다. */
    suspend fun loadFirstPage(): Outcome<Unit, LoadFailure> {
        synchronized(lock) {
            generation++
            _history.value = HistoryState().started()
        }
        return loadPage(cursor = null)
    }

    /** 다음 20개. 이미 불러오는 중이거나 더 없으면 무시. 페이지가 통째로 걸러져 비어 있으면 이어서 불러온다 (최대 [MAX_AUTO_PAGES]번). */
    suspend fun loadMore(): Outcome<Unit, LoadFailure> {
        var cursor: String?
        synchronized(lock) {
            val s = _history.value
            if (!s.canLoadMore) return Outcome.Ok(Unit)
            cursor = s.nextCursor
            _history.value = s.started()
        }
        return loadPage(cursor)
    }

    private suspend fun loadPage(cursor: String?): Outcome<Unit, LoadFailure> {
        var next = cursor
        repeat(MAX_AUTO_PAGES) {
            val gen = synchronized(lock) { generation }
            val page = try {
                api.fetchHistoryPage(next, pageSize)
            } catch (e: CancellationException) {
                synchronized(lock) { if (generation == gen) _history.value = _history.value.failedToLoad() }
                throw e
            } catch (e: Exception) {
                synchronized(lock) { if (generation == gen) _history.value = _history.value.failedToLoad() }
                return Outcome.Fail(e.toFailure())
            }
            val state = synchronized(lock) {
                if (generation != gen) return Outcome.Ok(Unit) // 그 사이 reset/처음부터 다시 → 낡은 응답 버림
                _history.value.loaded(page.orders, page.nextCursor, hasMore = page.nextCursor != null).also { _history.value = it }
            }
            if (!state.needsAutoLoad) return Outcome.Ok(Unit)
            next = state.nextCursor
            synchronized(lock) { _history.value = _history.value.started() }
        }
        synchronized(lock) { _history.value = _history.value.copy(loading = false) }
        return Outcome.Ok(Unit)
    }

    /** 상세에서 바뀐 주문을 목록·현황 바에 반영 (취소 후 돌아왔을 때 등) */
    private fun sync(order: Order) {
        synchronized(lock) {
            _history.value = _history.value.updated(order)
            val cur = _active.value
            _active.value = when {
                order.status.isActive -> if (cur.any { it.id == order.id }) cur.map { if (it.id == order.id) order else it } else cur + order
                else -> cur.filterNot { it.id == order.id }
            }
        }
    }

    private fun Exception.toFailure(): LoadFailure = if (this is AppError.Network) LoadFailure.NETWORK else LoadFailure.OTHER

    companion object {
        const val MAX_AUTO_PAGES = 5
    }
}

/** 알림 권한 안내 시트에서 [다음에]를 누른 횟수 (4.5.1). 실제 구현은 :app 의 DataStore. 계정별. */
interface NotificationPromptStore {
    suspend fun dismissCount(accountKey: String): Int
    suspend fun recordDismissal(accountKey: String)
}

class InMemoryNotificationPromptStore : NotificationPromptStore {
    private val map = HashMap<String, Int>()
    override suspend fun dismissCount(accountKey: String) = map[accountKey] ?: 0
    override suspend fun recordDismissal(accountKey: String) { map[accountKey] = (map[accountKey] ?: 0) + 1 }
}
