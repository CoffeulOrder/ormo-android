package com.ormo.domain.orders

import com.ormo.domain.model.LiveBarStage
import com.ormo.domain.model.OrderStatus
import java.time.Instant
import java.time.ZoneId

/** 메뉴 홈 하단 주문 현황 바 (2.1.4) 내용 */
data class LiveBarView(
    val orderId: String,
    val stage: LiveBarStage,
    /** 3칸 단계 바 중 채워진 칸 수: 확인 중 1, 만드는 중 2, 픽업 대기 3(전체 Fill/Brand) */
    val filledCells: Int,
    val title: String,
    val subtitle: String?,
    /** 접근성 문구: "주문 현황, 27번, 주문 확인 중이에요, 범석관 카페 내 앞에 3건, 버튼" */
    val contentDescription: String,
    /** 같은 때 진행 중인 다른 주문 수 (표시 방법은 디자인 미정) */
    val otherActiveCount: Int,
    /** 바 왼쪽의 번호 상자 (Figma 2.1.4 '27') */
    val pickupNumber: Int? = null,
    /** 내 앞 N건 (헤더 현황의 '· 내 앞 2건'). 모르면 null */
    val aheadCount: Int? = null,
)

object LiveOrderRules {
    /**
     * 진행 중 주문이 둘 이상일 때 어느 것을 바에 보여줄지. 명세는 바 하나만 그리므로 가정:
     * **픽업해야 하는 것(픽업 대기) > 만드는 중 > 확인 중**, 같은 단계면 먼저 주문한 것.
     * TODO(spec 2.1.4): 여러 건일 때의 표시 방식(외 N건 등) 디자인 확인.
     */
    fun select(orders: List<Order>): Order? =
        orders.filter { it.status.isActive }
            .sortedWith(compareByDescending<Order> { priority(it.status) }.thenBy { it.placedAt })
            .firstOrNull()

    private fun priority(s: OrderStatus) = when (s) {
        OrderStatus.READY -> 3
        OrderStatus.MAKING -> 2
        else -> 1
    }

    /**
     * 진행 중 주문 전부를 **먼저 받을 순서**대로 (2.1.4 플로팅 버튼을 누르면 열리는 큰 바들).
     * 맨 앞이 가장 먼저 받을 주문이다 — 화면에서는 이것이 버튼과 가장 가까운(맨 아래) 바가 된다.
     * 순서: 픽업 대기 > 만드는 중 > 주문 확인 중, 같은 단계면 먼저 주문한 것. 진행 중 주문이 없으면 빈 목록 → 버튼을 그리지 않는다.
     */
    fun bars(orders: List<Order>, zone: ZoneId = TimeFormat.SEOUL): List<LiveBarView> {
        val active = orders.filter { it.status.isActive }
        val others = active.size - 1
        return active
            .sortedWith(compareByDescending<Order> { priority(it.status) }.thenBy { it.placedAt })
            .mapNotNull { view(it, others, zone) }
    }

    /** 단계 하나만 보여줄 때 (가장 먼저 받을 주문). 진행 중 주문이 없으면 null. */
    fun bar(orders: List<Order>, zone: ZoneId = TimeFormat.SEOUL): LiveBarView? {
        val order = select(orders) ?: return null
        return view(order, orders.count { it.status.isActive } - 1, zone)
    }

    private fun view(order: Order, others: Int, zone: ZoneId): LiveBarView? {
        val stage = order.status.liveBarStage ?: return null
        val num = order.pickupNumber?.let { "${it}번" }
        return (when (stage) {
            LiveBarStage.CONFIRMING -> {
                val ahead = order.aheadCount?.let { "내 앞에 ${it}건" }
                LiveBarView(
                    order.id, stage, 1, "주문 확인 중이에요",
                    subtitle = listOfNotNull(order.storeName, ahead).joinToString(" · "),
                    contentDescription = describe(num, "주문 확인 중이에요", listOfNotNull(order.storeName, ahead).joinToString(" ")),
                    otherActiveCount = others,
                )
            }
            LiveBarStage.MAKING -> {
                // Figma 2.1.4 Flat: 만드는 중에도 '범석관 카페 · 내 앞에 2건'
                val ahead = order.aheadCount?.let { "내 앞에 ${it}건" }
                val sub = listOfNotNull(order.storeName, ahead).joinToString(" · ")
                LiveBarView(
                    order.id, stage, 2, "음료를 만들고 있어요", sub,
                    describe(num, "음료를 만들고 있어요", listOfNotNull(order.storeName, ahead).joinToString(" ")), others,
                )
            }
            LiveBarStage.READY -> {
                val sub = order.deadline?.let { "${TimeFormat.clock(it, zone)}까지 받아 주세요" } // Figma: 받아 주세요 (띄어쓰기)
                LiveBarView(
                    order.id, stage, 3, "음료가 준비됐어요", sub,
                    describe(num, "음료가 준비됐어요", sub.orEmpty()), others,
                )
            }
        }).copy(pickupNumber = order.pickupNumber, aheadCount = order.aheadCount)
    }

    private fun describe(num: String?, title: String, sub: String): String =
        listOfNotNull("주문 현황", num, title, sub.takeIf { it.isNotEmpty() }, "버튼").joinToString(", ")
}

/** 진행 중 알림(Android 의 잠금 화면 실시간 현황 대응, 4.5.2). iOS Live Activity 규격은 적용하지 않는다. */
data class OngoingNotification(
    val orderId: String,
    val title: String,
    val text: String,
    /** 화면 켜짐·소리를 낼 단계(픽업 대기)만 true. 확인 중 → 만드는 중은 조용히 바뀐다 */
    val alert: Boolean,
    /** 이 시각이 지나면 알림을 지운다 (픽업 완료 15분 뒤, 시작 후 최대 8시간) */
    val endsAt: Instant,
    /** 픽업 대기를 브랜드 색, 픽업 완료를 회색으로 */
    val finished: Boolean,
)

object OngoingNotificationRules {
    const val KEEP_AFTER_COMPLETE_MIN = 15L
    const val MAX_HOURS = 8L

    /**
     * 진행 중 알림 내용. 실시간 현황을 끈 사람은 시작하지 않는다 ([enabled] = false → null): 이때 픽업 대기는 푸시로 알린다.
     * 진행 중 단계가 아니고 픽업 완료도 아니면(거절·취소 등) null — 그 경우는 푸시가 안내한다.
     */
    fun from(order: Order, now: Instant, enabled: Boolean, zone: ZoneId = TimeFormat.SEOUL): OngoingNotification? {
        if (!enabled) return null
        val maxEnd = order.placedAt.plusSeconds(MAX_HOURS * 3600)
        val n = order.pickupNumber?.let { "${it}번 " } ?: ""
        val result = when (order.status) {
            OrderStatus.REQUESTED -> OngoingNotification(order.id, "주문 확인 중이에요", order.storeName, false, maxEnd, false)
            OrderStatus.MAKING -> OngoingNotification(order.id, "음료를 만들고 있어요", order.storeName, false, maxEnd, false)
            OrderStatus.READY -> OngoingNotification(
                order.id, "음료가 준비됐어요",
                order.deadline?.let { "${TimeFormat.clock(it, zone)}까지 받아주세요" } ?: "${n}픽업해주세요",
                true, maxEnd, false,
            )
            OrderStatus.COMPLETED -> {
                val done = order.completedAt ?: return null
                val end = minOf(done.plusSeconds(KEEP_AFTER_COMPLETE_MIN * 60), maxEnd)
                OngoingNotification(order.id, "음료를 받았어요", "${order.storeName} · 맛있게 드세요", false, end, true)
            }
            else -> return null
        }
        return result.takeIf { now < it.endsAt }
    }
}
