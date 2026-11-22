package com.ormo.domain.orders

import com.ormo.core.formatWon
import com.ormo.domain.model.OrderStatus
import com.ormo.domain.payment.DialogText
import java.time.Instant
import java.time.ZoneId

/** STOPPED: 거절·매장 취소로 여기서 멈춤 (Figma 4.3.3: 첫 칸이 빨강) */
enum class StepState { DONE, CURRENT, PENDING, STOPPED }

data class StepView(val label: String, val state: StepState, val time: Instant?)

enum class CancelButton { HIDDEN, ENABLED, DISABLED }

/** 주문 상세 하단 동작 버튼 */
enum class OrderAction { REORDER, VIEW_RECEIPT, PICK_OTHER_MENU }

/**
 * 주문 상세(4.3.1~4.3.4)에 그릴 내용. 서버 상태 하나로 화면 전체가 정해진다 (상태별 화면).
 * 단계가 바뀌는 것은 매장이 버튼을 눌렀을 때뿐이다 (O-49) — 진행 중인 것처럼 꾸미지 않는다.
 */
data class OrderDetailView(
    val headline: String,
    /** 헤더 아래 보조 문구 (예: "내 앞에 3건") */
    val subline: String?,
    /** 픽업 번호를 가장 크게(display-2xl) 보여줄 때만 값이 있다 (픽업 대기) */
    val bigPickupNumber: Int?,
    /** 강조 상자: "13:30까지 받아주세요" */
    val deadlineBox: String?,
    /** 사유 상자 (거절·매장 취소) */
    val reasonBox: String?,
    /** 안내 상자: 환불 안내, 미수령 안내 */
    val infoBox: String?,
    /** 단계 바. null 이면 숨김 */
    val steps: List<StepView>?,
    val cancel: CancelButton,
    val actions: List<OrderAction>,
)

object OrderDetailRules {
    const val REFUND_NOTICE = "결제한 금액은 결제수단으로 환불해 드려요. 카드사에 따라 3~5일 걸려요"
    const val UNCLAIMED_NOTICE = "환불이 필요하면 매장에 문의해주세요"
    const val AUTO_REJECTED_REASON = "매장이 주문을 확인하지 못했어요"

    val STEP_LABELS = listOf("주문 확인 중", "만드는 중", "픽업 대기", "픽업 완료")
    const val UNCLAIMED_LABEL = "미수령"

    fun view(order: Order, zone: ZoneId = TimeFormat.SEOUL): OrderDetailView {
        val n = order.pickupNumber?.let { "${it}번 " } ?: ""
        return when (order.status) {
            OrderStatus.REQUESTED -> base(
                order, "${n}주문을 확인하고 있어요",
                subline = order.aheadCount?.let { "내 앞에 ${it}건" },
                cancel = CancelButton.ENABLED,
            )
            OrderStatus.MAKING -> base(order, "${n}주문을 만들고 있어요", cancel = CancelButton.DISABLED)
            OrderStatus.READY -> base(
                order, "${n}주문을 픽업해주세요",
                big = order.pickupNumber,
                deadlineBox = order.deadline?.let { "${TimeFormat.clock(it, zone)}까지 받아주세요" },
            )
            OrderStatus.COMPLETED -> base(
                order, "${n}주문을 픽업했어요",
                actions = listOf(OrderAction.REORDER, OrderAction.VIEW_RECEIPT),
            )
            OrderStatus.REJECTED -> base(
                order, "매장에서 주문을 받지 못했어요",
                reasonBox = reasonOf(order), infoBox = REFUND_NOTICE,
                actions = listOf(OrderAction.PICK_OTHER_MENU),
            )
            OrderStatus.CANCELED ->
                if (order.canceledBy == CancelActor.STORE) {
                    base(
                        order, "매장 사정으로 주문이 취소됐어요",
                        reasonBox = reasonOf(order), infoBox = REFUND_NOTICE,
                        actions = listOf(OrderAction.PICK_OTHER_MENU),
                    )
                } else {
                    // 직접 취소: 단계 바 숨김, 안내 상자에 환불 안내, 영수증은 취소 영수증
                    base(order, "주문을 취소했어요", infoBox = REFUND_NOTICE, actions = listOf(OrderAction.VIEW_RECEIPT))
                }
            OrderStatus.REFUNDED -> base(
                order, "${formatWon(order.refundAmount ?: order.totalAmount)}을 환불했어요",
                actions = listOf(OrderAction.VIEW_RECEIPT),
            )
            OrderStatus.UNCLAIMED -> base(
                order, "픽업 예정 시간이 지났어요",
                infoBox = UNCLAIMED_NOTICE,
                actions = listOf(OrderAction.REORDER),
            )
            // 고객에게 보이지 않는 상태 (3.2.2 에서 대기 / 주문 내역에서도 빠짐)
            OrderStatus.PENDING_PAYMENT -> base(order, "결제를 확인하고 있어요")
            OrderStatus.EXPIRED -> base(order, "결제 가능 시간이 지났어요")
        }
    }

    private fun reasonOf(order: Order): String? =
        if (order.autoRejected) AUTO_REJECTED_REASON else order.reason?.trim()?.takeIf { it.isNotEmpty() }

    private fun base(
        order: Order,
        headline: String,
        subline: String? = null,
        big: Int? = null,
        deadlineBox: String? = null,
        reasonBox: String? = null,
        infoBox: String? = null,
        cancel: CancelButton = CancelButton.HIDDEN,
        actions: List<OrderAction> = emptyList(),
    ) = OrderDetailView(headline, subline, big, deadlineBox, reasonBox, infoBox, steps(order), cancel, actions)

    /**
     * 단계 바: 주문 확인 중 → 만드는 중 → 픽업 대기 → 픽업 완료, 각 단계 시각 (4.3).
     * - 진행 중/완료: 지금 단계까지 채움
     * - 환불 완료: 진행된 곳까지 그대로 (시각이 있는 마지막 단계까지)
     * - 미수령: 마지막 칸이 '미수령'
     * - 거절·매장 취소: 첫 칸만 빨강(멈춤), 직접 취소·결제 대기·만료: 숨김
     */
    fun steps(order: Order): List<StepView>? {
        val times = listOf(order.placedAt, order.makingAt, order.readyAt, order.completedAt)
        return when (order.status) {
            OrderStatus.REQUESTED -> build(times, current = 0)
            OrderStatus.MAKING -> build(times, current = 1)
            OrderStatus.READY -> build(times, current = 2)
            OrderStatus.COMPLETED -> build(times, current = null, doneThrough = 3)
            OrderStatus.REFUNDED -> {
                val last = times.indexOfLast { it != null }.coerceAtLeast(0)
                build(times, current = null, doneThrough = last)
            }
            OrderStatus.UNCLAIMED -> STEP_LABELS.mapIndexed { i, label ->
                if (i < 3) StepView(label, StepState.DONE, times[i]) else StepView(UNCLAIMED_LABEL, StepState.CURRENT, order.closedAt)
            }
            // Figma 4.3.3: 거절·매장 취소는 첫 칸만 빨간 단계 바. 직접 취소는 숨김.
            OrderStatus.REJECTED -> stopped(order)
            OrderStatus.CANCELED -> if (order.canceledBy == CancelActor.STORE) stopped(order) else null
            else -> null
        }
    }

    private fun stopped(order: Order): List<StepView> =
        STEP_LABELS.mapIndexed { i, label -> if (i == 0) StepView(label, StepState.STOPPED, order.placedAt) else StepView(label, StepState.PENDING, null) }

    private fun build(times: List<Instant?>, current: Int?, doneThrough: Int = (current ?: 0) - 1): List<StepView> =
        STEP_LABELS.mapIndexed { i, label ->
            val state = when {
                current != null && i == current -> StepState.CURRENT
                i <= doneThrough -> StepState.DONE
                else -> StepState.PENDING
            }
            StepView(label, state, times[i].takeIf { state != StepState.PENDING })
        }

    // ---- 취소 (4.4) ----
    val CANCEL_CONFIRM = DialogText("주문을 취소할까요?", "결제 금액 전액을 환불해 드려요")
    const val NOT_CANCELABLE_TOAST = "주문이 접수되어 취소할 수 없어요"
    const val CANCELED_TOAST = "주문을 취소했어요"
    const val TOAST_MS = 3_000L

    /** 매장이 받기 전(접수 대기)까지만 취소 가능 (D-28). 최종 판단은 서버 */
    fun canCancel(status: OrderStatus): Boolean = status.isCancelable

    /** 상세 화면을 보는 동안 자동 새로고침 간격. 진행 중일 때만 (끝난 주문은 한 번만). TODO(spec 4.3): 간격 미정 */
    fun pollIntervalMs(status: OrderStatus): Long? = if (status.isActive) POLL_MS else null

    const val POLL_MS = 5_000L
}
