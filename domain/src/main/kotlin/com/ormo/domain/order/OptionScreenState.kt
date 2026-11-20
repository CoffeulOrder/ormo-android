package com.ormo.domain.order

import com.ormo.core.formatWon
import com.ormo.domain.model.MenuItem
import com.ormo.domain.model.OptionChoice
import com.ormo.domain.rules.CartChange
import com.ormo.domain.rules.CartState
import com.ormo.domain.rules.MAX_LINE_QTY
import com.ormo.domain.rules.OptionRules

/** 옵션 화면 스낵바/안내 (2.2) */
sealed interface OptionNotice {
    val text: String

    /** 선택 그룹에서 최대 개수를 넘기려 함 — 버튼 위 스낵바, 체크는 유지 */
    data class MaxSelect(val max: Int) : OptionNotice {
        override val text: String get() = "최대 ${max}개까지 선택할 수 있어요"
    }

    /** 수량 20개 초과 */
    data object QtyLimit : OptionNotice {
        override val text: String get() = "한 번에 ${MAX_LINE_QTY}개까지 주문할 수 있어요"
    }
}

data class OptionEdit(val state: OptionScreenState, val notice: OptionNotice? = null)

/**
 * 옵션 선택 전체 화면(2.2) 상태. 순수 불변.
 * - 필수 옵션은 기본값을 미리 고른 채로 시작한다 ([start]).
 * - 버튼 금액 = (기본가 + 옵션 추가금) × 수량. 바뀔 때마다 다시 계산.
 * - 수량 1~20. 1개일 때 −, 20개일 때 + 가 꺼진다.
 * - 필수 옵션이 모두 품절이면 메뉴도 품절 → 담기 불가.
 */
data class OptionScreenState(
    val item: MenuItem,
    val choiceIds: List<String>,
    val qty: Int = 1,
) {
    val soldOut: Boolean get() = OptionRules.effectiveSoldOut(item)
    val hasOptions: Boolean get() = OptionRules.hasOptions(item)

    val unitPrice: Int get() = OptionRules.unitPrice(item, choiceIds)

    /** 버튼에 표시할 금액 */
    val amount: Int get() = unitPrice * qty

    val canDecrease: Boolean get() = qty > 1
    val canIncrease: Boolean get() = qty < MAX_LINE_QTY

    val canAdd: Boolean get() = !soldOut && OptionRules.missingRequired(item, choiceIds).isEmpty()

    val addLabel: String get() = "${formatWon(amount)} 담기"

    fun isSelected(choiceId: String): Boolean = choiceId in choiceIds

    fun toggle(groupId: String, choiceId: String): OptionEdit {
        val r = OptionRules.toggle(item, choiceIds, groupId, choiceId)
        val max = item.optionGroups.find { it.id == groupId }?.maxSelect
        return OptionEdit(
            copy(choiceIds = r.ids),
            if (r.limitExceeded && max != null) OptionNotice.MaxSelect(max) else null,
        )
    }

    fun increase(): OptionEdit =
        if (canIncrease) OptionEdit(copy(qty = qty + 1)) else OptionEdit(this, OptionNotice.QtyLimit)

    fun decrease(): OptionEdit = if (canDecrease) OptionEdit(copy(qty = qty - 1)) else OptionEdit(this)

    /** 장바구니에 담기. 같은 메뉴+옵션이 이미 있어 합계가 20을 넘으면 변화 없이 한도 안내 ([OptionNotice.QtyLimit] 로 보여준다). */
    fun addTo(cart: CartState): CartChange = cart.add(item, choiceIds, qty)

    companion object {
        /** 새로 담기: 필수 그룹 기본값을 미리 고른다 */
        fun start(item: MenuItem): OptionScreenState = OptionScreenState(item, OptionRules.defaultChoiceIds(item))

        /** 선택지 옆에 보일 가격 문구: 품절이면 '품절', 추가금이 없으면 '+0원' (2.2) */
        fun priceLabel(choice: OptionChoice): String = when {
            choice.soldOut -> "품절"
            else -> "+${formatWon(choice.priceDelta)}"
        }
    }
}
