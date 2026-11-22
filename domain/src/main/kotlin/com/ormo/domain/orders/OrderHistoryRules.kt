package com.ormo.domain.orders

import com.ormo.core.formatWon
import com.ormo.domain.model.OrderStatus
import java.time.ZoneId

/** '내 주문' 카드 한 장 (4.2.1) */
data class HistoryCardView(
    val orderId: String,
    val title: String,
    val statusLabel: String,
    val dateLabel: String,
    val amountLabel: String,
    /** 취소·거절·환불 주문은 금액에 취소선 */
    val strikeAmount: Boolean,
    /** 취소·거절·환불은 [메뉴 보러 가기], 나머지는 [재주문하기] */
    val primary: HistoryAction,
)

enum class HistoryAction { REORDER, BROWSE_MENU }

/**
 * '내 주문' 페이지 상태 (4.2.1): **지난 주문만**, 최신순 20개씩. 순수 불변.
 * 서버가 한 페이지(20)를 주면 거기서 보이면 안 되는 것(진행 중·결제 대기·만료)을 뺀다.
 * 그래서 한 페이지가 통째로 걸러져도 다음 페이지가 있으면 계속 불러와야 한다 → [needsAutoLoad].
 */
data class HistoryState(
    val orders: List<Order> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val failed: Boolean = false,
    /** 첫 페이지를 한 번이라도 받았는가 (빈 상태와 첫 로딩 구분) */
    val loadedOnce: Boolean = false,
) {
    val isEmpty: Boolean get() = loadedOnce && orders.isEmpty() && !hasMore
    val canLoadMore: Boolean get() = hasMore && !loading

    fun started(): HistoryState = copy(loading = true, failed = false)

    /** 한 페이지를 받았다: 보여줄 상태만 추리고, 이미 있는 주문은 새 값으로 바꾼다 (중복 방지). */
    fun loaded(page: List<Order>, next: String?, hasMore: Boolean): HistoryState {
        val visible = page.filter { it.status.showsInHistory }
        val byId = LinkedHashMap<String, Order>()
        orders.forEach { byId[it.id] = it }
        visible.forEach { byId[it.id] = it }
        val merged = byId.values.sortedByDescending { it.placedAt }
        return HistoryState(merged, next, hasMore && next != null, loading = false, failed = false, loadedOnce = true)
    }

    fun failedToLoad(): HistoryState = copy(loading = false, failed = true)

    /** 받은 페이지가 모두 걸러져 화면이 비어 있는데 더 있으면 바로 다음 페이지를 받아야 한다 */
    val needsAutoLoad: Boolean get() = loadedOnce && orders.isEmpty() && hasMore && !loading && !failed

    /** 목록에서 한 주문을 새 값으로 바꾼다 (상세에서 취소 후 돌아왔을 때 등). 진행 중이 되면 목록에서 빠진다. */
    fun updated(order: Order): HistoryState {
        val idx = orders.indexOfFirst { it.id == order.id }
        if (idx < 0) return this
        return if (order.status.showsInHistory) copy(orders = orders.toMutableList().also { it[idx] = order }) else copy(orders = orders.filterIndexed { i, _ -> i != idx })
    }
}

object OrderHistoryRules {
    const val PAGE_SIZE = 20
    const val EMPTY_TITLE = "주문 내역이 없어요"

    fun statusLabel(order: Order): String = when (order.status) {
        OrderStatus.COMPLETED -> "픽업 완료"
        OrderStatus.REJECTED -> "주문 거절"
        OrderStatus.CANCELED -> if (order.canceledBy == CancelActor.STORE) "매장 주문 취소" else "주문 취소"
        OrderStatus.REFUNDED -> "환불 완료"
        OrderStatus.UNCLAIMED -> "미수령"
        else -> ""
    }

    fun card(order: Order, zone: ZoneId = TimeFormat.SEOUL): HistoryCardView {
        val strike = order.status == OrderStatus.CANCELED || order.status == OrderStatus.REJECTED || order.status == OrderStatus.REFUNDED
        return HistoryCardView(
            orderId = order.id,
            title = order.title,
            statusLabel = statusLabel(order),
            dateLabel = TimeFormat.dateTime(order.placedAt, zone),
            amountLabel = formatWon(order.totalAmount),
            strikeAmount = strike,
            primary = if (strike) HistoryAction.BROWSE_MENU else HistoryAction.REORDER,
        )
    }
}
