package com.ormo.domain.model

import java.time.Instant

/** 주문 상태 (디자인 명세 8.2). UNCLAIMED(미수령)는 앱 전용 표시 상태. */
enum class OrderStatus {
    PENDING_PAYMENT, REQUESTED, MAKING, READY, COMPLETED,
    REJECTED, CANCELED, REFUNDED, EXPIRED, UNCLAIMED,
    ;

    /** 진행 중: 홈 주문 현황 바와 주문 상세에서만 보이고, '내 주문' 목록에는 없다 (결정 11/16). */
    val isActive: Boolean get() = this == REQUESTED || this == MAKING || this == READY

    /** 고객이 직접 취소할 수 있는 유일한 단계 (최종 판단은 서버, 이미 접수됐으면 409). */
    val isCancelable: Boolean get() = this == REQUESTED

    /** '내 주문' 목록(지난 주문)에 보이는 상태. 결제 대기·만료·진행 중은 제외. */
    val showsInHistory: Boolean
        get() = when (this) {
            COMPLETED, REJECTED, CANCELED, REFUNDED, UNCLAIMED -> true
            else -> false
        }

    /** 홈 주문 현황 바 3단계 (디자인 2.1.4). 진행 중이 아니면 null → 바를 그리지 않는다. */
    val liveBarStage: LiveBarStage?
        get() = when (this) {
            REQUESTED -> LiveBarStage.CONFIRMING
            MAKING -> LiveBarStage.MAKING
            READY -> LiveBarStage.READY
            else -> null
        }

    companion object {
        /** 서버가 다른 이름을 쓰면 여기 한 곳에서 맞춘다. 모르는 값은 null (구버전 앱 보호). */
        private val ALIASES = mapOf(
            "PENDING" to REQUESTED,
            "ACCEPTED" to MAKING,
            "PICKED_UP" to COMPLETED,
            "DISCARDED" to UNCLAIMED,
        )

        fun parse(raw: String?): OrderStatus? {
            val key = raw?.trim()?.uppercase() ?: return null
            return ALIASES[key] ?: entries.firstOrNull { it.name == key }
        }
    }
}

enum class LiveBarStage { CONFIRMING, MAKING, READY }

object OrderRules {
    /** 서버가 폐기(미수령) 시간을 주지 않을 때만 쓰는 기본값 (분). 매장 설정 10~30분. */
    const val DEFAULT_DISCARD_MINUTES = 20

    /** 받을 기한 = 준비된 시각 + 미수령 기준 시간 (디자인 4.3.2). 서버가 시각을 직접 주면 그 값을 쓴다. */
    fun pickupDeadline(readyAt: Instant, discardMinutes: Int?): Instant =
        readyAt.plusSeconds((discardMinutes ?: DEFAULT_DISCARD_MINUTES) * 60L)
}
