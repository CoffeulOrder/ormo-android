package com.ormo.domain.orders

import com.ormo.core.formatWon
import java.time.ZoneId

/**
 * 알림 권한 안내 시트 (4.5.1). "[다음에]를 두 번 누르면 다시 묻지 않음", "첫 주문에서 4.1.1 의 [주문 상태 보기]를 누르면 시트".
 * 해석(TODO(spec 4.5.1) 확인): 첫 주문에서 한 번 묻고, [다음에]를 누르면 **다음 주문**에서 한 번 더 묻고, 두 번 누르면 그만 묻는다.
 * OS 권한이 이미 있으면 묻지 않는다.
 */
object NotificationPromptRules {
    const val MAX_DISMISSALS = 2

    val TITLE = "음료가 준비되면 바로 알려드려요"
    val BODY = "알림을 켜면 주문 진행 상황을 바로 알 수 있어요"

    fun shouldShow(osPermissionGranted: Boolean, dismissCount: Int, isFirstOrder: Boolean): Boolean = when {
        osPermissionGranted -> false
        dismissCount >= MAX_DISMISSALS -> false
        dismissCount == 0 -> isFirstOrder
        else -> true
    }
}

/** 푸시가 알리는 일 (4.5.2). 알림을 누르면 [OrderDeepLink] 로 주문 상세를 연다 — 어떤 상세 화면인지는 서버 상태가 정한다. */
enum class PushType { READY, REJECTED, CANCELED_BY_STORE, REFUNDED }

data class OrderDeepLink(val orderId: String)

data class PushMessage(val type: PushType, val title: String, val body: String, val link: OrderDeepLink)

/**
 * 푸시 문구와 페이로드 해석. 제목은 짧게, **앱 이름 미포함**.
 *
 * ⚠️ 페이로드 키는 앱이 가정한 것 (BE FCM 계약 미확정): `type`, `orderId`, `storeName`, `pickupNumber`, `deadline`(HH:mm),
 * `reason`, `amount`. 모르는 type 이나 필수 값(orderId)이 없으면 null → 무시 (구버전 앱 보호).
 */
object PushRules {
    fun parse(data: Map<String, String>): PushMessage? {
        val type = when (data["type"]?.trim()?.uppercase()) {
            "READY", "ORDER_READY" -> PushType.READY
            "REJECTED", "ORDER_REJECTED" -> PushType.REJECTED
            "CANCELED_BY_STORE", "STORE_CANCELED", "ORDER_CANCELED_BY_STORE" -> PushType.CANCELED_BY_STORE
            "REFUNDED", "ORDER_REFUNDED" -> PushType.REFUNDED
            else -> return null
        }
        val orderId = data["orderId"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val store = data["storeName"]?.trim().orEmpty()
        val number = data["pickupNumber"]?.trim()?.takeIf { it.isNotEmpty() }
        val reason = data["reason"]?.trim()?.takeIf { it.isNotEmpty() }
        return when (type) {
            PushType.READY -> {
                val where = if (store.isNotEmpty()) "${store}에서 " else ""
                val what = if (number != null) "${number}번을 찾아가세요." else "음료를 찾아가세요."
                val deadline = data["deadline"]?.trim()?.takeIf { it.isNotEmpty() }?.let { " ${it}까지 받아주세요." } ?: ""
                PushMessage(type, "음료가 준비됐어요", "$where$what$deadline", OrderDeepLink(orderId))
            }
            PushType.REJECTED -> PushMessage(
                type, "매장에서 주문을 받지 못했어요",
                reason ?: "결제한 금액은 전액 환불해 드려요", OrderDeepLink(orderId),
            )
            PushType.CANCELED_BY_STORE -> PushMessage(
                type, "매장 사정으로 주문이 취소됐어요",
                reason ?: "결제한 금액은 전액 환불해 드려요", OrderDeepLink(orderId),
            )
            PushType.REFUNDED -> {
                val amount = data["amount"]?.trim()?.toIntOrNull()
                PushMessage(
                    type,
                    if (amount != null) "${formatWon(amount)}을 환불했어요" else "환불이 완료됐어요",
                    "결제수단으로 환불됐어요", OrderDeepLink(orderId),
                )
            }
        }
    }

    /**
     * 푸시를 앱이 직접 띄워야 하는가. 실시간 현황(진행 중 알림)을 켠 사람에게 음료 준비 푸시는 따로 보내지 않는다 (같은 내용 중복 방지).
     * 거절·매장 취소·환불은 모든 사람에게 보낸다. 주문 알림 자체를 끈 사람은 서버가 안 보낸다 — 혹시 와도 [orderPushEnabled] 로 한 번 더 거른다.
     */
    fun shouldDisplay(type: PushType, orderPushEnabled: Boolean, liveEnabled: Boolean): Boolean {
        if (!orderPushEnabled) return false
        return type != PushType.READY || !liveEnabled
    }
}
