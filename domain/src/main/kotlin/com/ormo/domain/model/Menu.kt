package com.ormo.domain.model

/**
 * 메뉴 모델. 선택값은 **선택지 id 목록**만 다룬다 (이름·가격은 항상 최신 메뉴에서 찾는다).
 * 가격은 표시용이고 실제 결제 금액은 서버가 계산한다.
 *
 * id 는 지금은 String 이다. BE 가 숫자 id 를 쓰면 매퍼에서 toString() 으로 넘긴다 (BE 계약 확정 전).
 */
data class OptionChoice(
    val id: String,
    val name: String,
    /** 추가금 (표시용) */
    val priceDelta: Int,
    /** 품절 옵션: 흐리게 표시하고 고를 수 없다 (디자인 2.2) */
    val soldOut: Boolean = false,
)

data class OptionGroup(
    val id: String,
    val name: String,
    /** true 면 반드시 하나 이상 골라야 한다 (예: 온도) */
    val required: Boolean,
    /** true 면 여러 개 선택 가능 (예: 샷 추가), false 면 하나만 */
    val multi: Boolean,
    val choices: List<OptionChoice>,
    /** 복수 선택 그룹의 최대 개수. null 이면 제한 없음 (디자인 2.2: "최대 2개까지 선택할 수 있어요") */
    val maxSelect: Int? = null,
)

/** 지점(카페). `code` 는 주문번호 접두어. */
data class Cafe(
    val id: String,
    val name: String,
    val code: String,
    val menus: List<MenuItem>,
)

/** 학교 단위 메뉴 데이터. `version` 이 바뀔 때만 메뉴를 다시 받는다 (menu_version). */
data class MenuPayload(val version: Long, val cafes: List<Cafe>)

data class MenuItem(
    val id: String,
    val name: String,
    val desc: String,
    /** 표시용 가격 */
    val price: Int,
    val category: String,
    val soldOut: Boolean = false,
    val thumbnailUrl: String? = null,
    val imageUrl: String? = null,
    val optionGroups: List<OptionGroup> = emptyList(),
)
