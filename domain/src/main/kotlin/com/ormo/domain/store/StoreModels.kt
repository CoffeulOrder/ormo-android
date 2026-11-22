package com.ormo.domain.store

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 매장 영업 상태 (디자인 1.1.1, 2.1.2). **주문 가능 여부는 운영시간이 아니라 이 상태로 판단한다** (1.2.1 개발 메모).
 * 시각은 매장 현지 시각(LocalDateTime) 이다. 서버가 UTC/오프셋으로 주면 매퍼에서 바꿔 넘긴다.
 */
sealed interface StoreStatus {
    data object Open : StoreStatus

    /** 잠시 쉼. 다시 여는 시각을 모르면(직접 해제하는 경우) null */
    data class Break(val reopenAt: LocalDateTime?) : StoreStatus

    /** 영업 종료. 다음 영업 시작 시각을 모르면 null */
    data class Closed(val nextOpenAt: LocalDateTime?) : StoreStatus
}

/** 매장 목록(1.1.1)의 한 줄 */
data class Store(
    val id: String,
    val name: String,
    val status: StoreStatus,
    /** 주문할 수 있는 메뉴 수. 0이면 목록에서 제외한다 (1.1.1) */
    val menuCount: Int,
    /** 대기 건수 — 노출 여부는 서버와 확인 중(C-14-1). 값이 없으면 null */
    val waitingCount: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** 매장 주소 (매장 선택 카드, Figma 1.1.1) */
    val address: String? = null,
    /** 학교 이름 — 매장 선택 카드 제목은 '을지대학교 범석관 카페' 처럼 학교 이름이 앞에 붙는다 */
    val schoolName: String? = null,
) {
    /** 목록·시트에 보이는 이름: "을지대학교 범석관 카페" */
    val displayName: String get() = listOfNotNull(schoolName, name).joinToString(" ")
}

data class DayHours(val day: DayOfWeek, val open: LocalTime?, val close: LocalTime?) {
    /** 휴무: 시각이 하나라도 없으면 쉬는 날로 본다 */
    val closed: Boolean get() = open == null || close == null
}

/** 판매자 정보 6줄 (Figma 1.2.1 확인: 상호, 대표자, 사업자등록번호, 주소, 연락처, 통신판매업 신고번호 — 이 순서) */
data class SellerInfo(
    val businessName: String?,
    val representative: String?,
    val businessRegistrationNumber: String?,
    /** 통신판매업 신고번호 — 없으면 '준비 중' (1.2.1) */
    val mailOrderNumber: String?,
    val address: String?,
    val phone: String?,
)

/** 매장 정보 화면 (1.2.1) */
data class StoreInfo(
    val storeId: String,
    val name: String,
    /** 위치 설명 (예: "범석관 1층") */
    val location: String?,
    val weeklyHours: List<DayHours>,
    val notice: String?,
    val seller: SellerInfo,
)
