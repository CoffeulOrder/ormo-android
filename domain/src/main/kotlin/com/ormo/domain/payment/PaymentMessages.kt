package com.ormo.domain.payment

import com.ormo.domain.store.StoreRules
import com.ormo.domain.store.StoreStatus
import java.time.LocalDateTime

data class DialogText(val title: String, val body: String)

/** 결제 단계 문구 (디자인 3.1.2, 3.2.2, 3.3.x) */
object PaymentMessages {
    val CONFIRMING = DialogText("결제를 확인하고 있어요", "잠시만 기다려주세요")
    val FAILED = DialogText("결제하지 못했어요", "카드 상태를 확인하거나 다른 결제수단을 선택해주세요")
    val EXHAUSTED = DialogText("더 이상 결제할 수 없어요", "결제를 5번 실패했어요\n장바구니에서 다시 주문해주세요")
    val EXPIRED = DialogText("결제 가능 시간이 지났어요", "결제가 완료되지 않았어요\n장바구니에서 다시 주문해주세요")

    const val SOON = "잠시 후 다시 주문할 수 있어요"

    /** 3.1.2 주문 중지: 영업 종료 / 잠시 멈춤 (시각이 없으면 '잠시 후 다시 주문할 수 있어요'). 품절은 막지 않는다. */
    fun blocked(status: StoreStatus, now: LocalDateTime): DialogText {
        val title = if (status is StoreStatus.Closed) "오늘 영업이 끝났어요" else "매장이 잠시 주문을 받지 않아요"
        val msg = (StoreRules.availability(status, now) as? com.ormo.domain.store.OrderAvailability.Unavailable)?.message
        val body = if (msg == null || msg == StoreRules.REOPEN_SOON) SOON else msg
        return DialogText(title, body)
    }

    /**
     * PG 오류 코드 → 쉬운 문구. KG이니시스 코드 표는 아직 없다 (TODO(spec 3.3.1): 개발과 작성).
     * 표가 채워지기 전에는 모든 코드가 기본 문구([FAILED])를 쓴다.
     */
    private val friendlyByCode: Map<String, DialogText> = emptyMap()

    fun failed(code: String?): DialogText = code?.let { friendlyByCode[it] } ?: FAILED
}
