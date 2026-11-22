package com.ormo.domain.orders

import com.ormo.domain.model.OrderRules
import com.ormo.domain.model.OrderStatus
import com.ormo.domain.order.PickupMethod
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class CancelActor { CUSTOMER, STORE }

/**
 * 주문에 담긴 한 줄. 재주문(4.2.1)을 위해 메뉴 id 와 옵션 선택지 id 를 함께 가진다.
 * 이름·옵션 문구·금액은 **주문 당시 값** (이후 메뉴가 바뀌어도 내역은 그대로).
 */
data class OrderLine(
    val menuId: String,
    val name: String,
    val choiceIds: List<String>,
    val optionSummary: String,
    val qty: Int,
    /** 줄 금액 (수량 곱한 금액) */
    val amount: Int,
)

/**
 * 서버가 알려주는 주문. 주문 상세(4.3), 내 주문(4.2), 주문 현황 바(2.1.4), 푸시(4.5)가 모두 이 모델에서 계산한다.
 *
 * ⚠️ BE 계약 미확정 — 필드 이름·단위는 매퍼(M8)에서 맞춘다. 시각은 모두 절대 시각(Instant); 화면에 쓸 때 [ZoneId] 를 넘긴다.
 */
data class Order(
    val id: String,
    /** 픽업 번호: 매장과 영업일마다 1번부터 (4.1.1). 아직 모르면 null */
    val pickupNumber: Int?,
    val storeId: String,
    val storeName: String,
    val status: OrderStatus,
    val lines: List<OrderLine>,
    val totalAmount: Int,
    /** 환불 금액 (환불 완료 4.3.4, 부분 환불은 보류 D-01) */
    val refundAmount: Int? = null,
    val pickup: PickupMethod = PickupMethod.TAKEOUT,
    /** 결제가 승인되어 접수 대기가 된 시각 = '주문 확인 중' 단계 시각 */
    val placedAt: Instant,
    val makingAt: Instant? = null,
    val readyAt: Instant? = null,
    val completedAt: Instant? = null,
    /** 거절·취소·환불·미수령 처리된 시각 */
    val closedAt: Instant? = null,
    /** 서버가 직접 주는 받을 기한. 없으면 [readyAt] + [discardMinutes] */
    val pickupDeadline: Instant? = null,
    /** 매장 설정 미수령 기준 시간(분, 10~30). 없으면 기본 20 */
    val discardMinutes: Int? = null,
    /** 내 앞 N건 = 접수 대기 + 제조 중 (서버 기준, 확인 필요) */
    val aheadCount: Int? = null,
    val canceledBy: CancelActor? = null,
    /** 거절/매장 취소 사유 (데이터) */
    val reason: String? = null,
    /** 매장이 확인하지 못해 자동 거절됨 → 문구 '매장이 주문을 확인하지 못했어요' */
    val autoRejected: Boolean = false,
    /** 결제 정보 (4.3.1 '결제 정보', 4.3.6 영수증): 결제수단 이름, 마스킹된 카드 번호, 승인 번호. 서버가 주는 값 그대로 보여준다. */
    val paymentMethod: String? = null,
    val cardMasked: String? = null,
    val approvalNumber: String? = null,
) {
    /** 받을 기한 (4.3.2): 서버 값 우선, 없으면 준비된 시각 + 미수령 기준 */
    val deadline: Instant?
        get() = pickupDeadline ?: readyAt?.let { OrderRules.pickupDeadline(it, discardMinutes) }

    /** 카드 제목: "아메리카노 외 2건" */
    val title: String
        get() = when {
            lines.isEmpty() -> ""
            lines.size == 1 -> lines[0].name
            else -> "${lines[0].name} 외 ${lines.size - 1}건"
        }

    val isTerminal: Boolean
        get() = !status.isActive && status != OrderStatus.PENDING_PAYMENT
}

object TimeFormat {
    val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

    /** "13:30" */
    fun clock(t: Instant, zone: ZoneId = SEOUL): String {
        val z = t.atZone(zone)
        return String.format(Locale.US, "%02d:%02d", z.hour, z.minute)
    }

    private val DOW = arrayOf("월", "화", "수", "목", "금", "토", "일")

    /** "26.08.24 (월)" (Figma 4.2.1 카드, 4.3 상세) */
    fun dateDow(t: Instant, zone: ZoneId = SEOUL): String {
        val z = t.atZone(zone)
        return String.format(Locale.US, "%02d.%02d.%02d (%s)", z.year % 100, z.monthValue, z.dayOfMonth, DOW[z.dayOfWeek.value - 1])
    }

    /** "26.08.24 (월) 13:12" (Figma 4.3 상세 보조 문구, 결제 일시) */
    fun dateDowClock(t: Instant, zone: ZoneId = SEOUL): String = "${dateDow(t, zone)} ${clock(t, zone)}"

    /** "2026.08.24 13:02" (영수증 승인 일시) */
    fun fullDateTime(t: Instant, zone: ZoneId = SEOUL): String {
        val z = t.atZone(zone)
        return String.format(Locale.US, "%04d.%02d.%02d %02d:%02d", z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute)
    }

    /** "11월 19일 13:30" (내 주문 카드 날짜). TODO(spec 4.2.1): 날짜 표기 Figma 확인 */
    fun dateTime(t: Instant, zone: ZoneId = SEOUL): String {
        val z = t.atZone(zone)
        return String.format(Locale.US, "%d월 %d일 %02d:%02d", z.monthValue, z.dayOfMonth, z.hour, z.minute)
    }
}
