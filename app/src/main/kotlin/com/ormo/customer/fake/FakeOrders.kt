package com.ormo.customer.fake

import com.ormo.data.orders.MyOrdersApi
import com.ormo.data.orders.OrderPage
import com.ormo.data.remote.AppError
import com.ormo.domain.model.OrderStatus
import com.ormo.domain.order.PickupMethod
import com.ormo.domain.orders.CancelActor
import com.ormo.domain.orders.Order
import com.ormo.domain.orders.OrderLine
import java.time.Instant

/**
 * 개발용 가짜 '내 주문' 서버. 결제가 승인되면 접수 대기 주문이 생기고, **개발용 상태 패널**(주문 상세 하단)이
 * 매장 쪽에서 일어나는 일(받음·만듦·준비됨·완료·거절·매장 취소·미수령·환불)을 대신 일으킨다.
 * 단계는 매장이 버튼을 눌러야만 바뀐다 (O-49) — 시간이 지난다고 저절로 바뀌지 않는다.
 * M8 에서 실제 API 로 바꾸고 이 파일을 지운다.
 */
class FakeOrderServer(private val clock: () -> Instant = Instant::now) : MyOrdersApi {
    private val orders = LinkedHashMap<String, Order>()
    private var pickupCounter = 26

    @Synchronized
    fun create(
        id: String,
        storeId: String,
        storeName: String,
        lines: List<OrderLine>,
        total: Int,
        pickup: PickupMethod,
        paymentMethod: String?,
    ): Order {
        orders[id]?.let { return it }
        val order = Order(
            id = id,
            pickupNumber = ++pickupCounter,
            storeId = storeId,
            storeName = storeName,
            status = OrderStatus.REQUESTED,
            lines = lines,
            totalAmount = total,
            pickup = pickup,
            placedAt = clock(),
            aheadCount = 3, // 가짜: 항상 앞에 3건
            paymentMethod = paymentMethod,
            cardMasked = if (paymentMethod?.contains("카드") == true) "1234-56**-****-7890" else null,
            approvalNumber = (10_000_000 + pickupCounter * 137).toString(),
        )
        orders[id] = order
        return order
    }

    @Synchronized
    private fun edit(id: String, f: (Order) -> Order): Order {
        val cur = orders[id] ?: throw AppError.Api(404)
        return f(cur).also { orders[id] = it }
    }

    // ---- 개발용 상태 패널: 매장 쪽 동작 ----
    /** 다음 단계: 접수 대기 → 만드는 중 → 픽업 대기 → 픽업 완료 */
    fun advance(id: String) {
        val now = clock()
        edit(id) { o ->
            when (o.status) {
                OrderStatus.REQUESTED -> o.copy(status = OrderStatus.MAKING, makingAt = now, aheadCount = null)
                OrderStatus.MAKING -> o.copy(status = OrderStatus.READY, readyAt = now, discardMinutes = 20)
                OrderStatus.READY -> o.copy(status = OrderStatus.COMPLETED, completedAt = now)
                else -> o
            }
        }
    }

    fun reject(id: String, auto: Boolean) {
        val now = clock()
        edit(id) { o ->
            if (o.status != OrderStatus.REQUESTED) o
            else o.copy(status = OrderStatus.REJECTED, closedAt = now, aheadCount = null, autoRejected = auto, reason = if (auto) null else "재료가 소진됐어요")
        }
    }

    fun cancelByStore(id: String) {
        val now = clock()
        edit(id) { o ->
            if (!o.status.isActive) o
            else o.copy(status = OrderStatus.CANCELED, canceledBy = CancelActor.STORE, closedAt = now, aheadCount = null, reason = "기계 점검으로 주문을 만들 수 없어요")
        }
    }

    fun unclaimed(id: String) {
        val now = clock()
        edit(id) { o -> if (o.status == OrderStatus.READY) o.copy(status = OrderStatus.UNCLAIMED, closedAt = now) else o }
    }

    fun refund(id: String) {
        val now = clock()
        edit(id) { o ->
            if (o.status == OrderStatus.COMPLETED || o.status == OrderStatus.UNCLAIMED) o.copy(status = OrderStatus.REFUNDED, closedAt = now, refundAmount = o.totalAmount) else o
        }
    }

    // ---- API ----
    override suspend fun fetchOrder(orderId: String): Order = synchronized(this) { orders[orderId] } ?: throw AppError.Api(404)

    override suspend fun fetchActive(): List<Order> = synchronized(this) { orders.values.filter { it.status.isActive } }

    override suspend fun fetchHistoryPage(cursor: String?, limit: Int): OrderPage = synchronized(this) {
        OrderPage(orders.values.filter { it.status.showsInHistory }.sortedByDescending { it.placedAt }.take(limit), nextCursor = null)
    }

    /** 접수 대기일 때만 취소된다. 그 사이 매장이 받았으면 409 (4.4.2) */
    override suspend fun cancel(orderId: String): Order {
        val now = clock()
        return synchronized(this) {
            val o = orders[orderId] ?: throw AppError.Api(404)
            if (o.status != OrderStatus.REQUESTED) throw AppError.Api(409)
            o.copy(status = OrderStatus.CANCELED, canceledBy = CancelActor.CUSTOMER, closedAt = now, aheadCount = null).also { orders[orderId] = it }
        }
    }
}
