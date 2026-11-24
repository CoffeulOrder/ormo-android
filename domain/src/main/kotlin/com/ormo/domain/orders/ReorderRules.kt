package com.ormo.domain.orders

import com.ormo.domain.model.MenuItem
import com.ormo.domain.payment.DialogText
import com.ormo.domain.rules.CartLimit
import com.ormo.domain.rules.CartState
import com.ormo.domain.rules.OptionRules

/** 재주문으로 담을 한 줄 */
data class ReorderLine(val item: MenuItem, val choiceIds: List<String>, val qty: Int, val soldOut: Boolean)

/**
 * 재주문 계획 (4.2.1, 4.3.4).
 * - 같은 메뉴·옵션을 **현재 메뉴 기준**으로 다시 담는다 (옵션은 현재 규칙에 맞게 고침, 가격은 최신).
 * - **모두 품절이면** 장바구니로 가지 않고 다이얼로그 ([ALL_SOLD_OUT]).
 * - **일부만 품절이면** 장바구니로 가고, 품절 메뉴는 장바구니의 '품절' 뱃지로 알린다 (결제 때 빠짐, 2.3).
 *   → 품절 줄도 장바구니에 담는다. 메뉴에서 사라진 것은 담을 수 없으므로 [missing] 으로 따로 센다.
 */
data class ReorderPlan(
    val lines: List<ReorderLine>,
    /** 메뉴에서 사라져 담을 수 없는 주문 줄 이름 */
    val missing: List<String>,
) {
    val allSoldOut: Boolean get() = lines.all { it.soldOut }
    val soldOutNames: List<String> get() = lines.filter { it.soldOut }.map { it.item.name }
    val hasSoldOut: Boolean get() = lines.any { it.soldOut } || missing.isNotEmpty()

    /** 장바구니로 보낼 수 있는가: 담을 줄이 있고 모두 품절이 아닐 때 */
    val canGoToCart: Boolean get() = lines.isNotEmpty() && !allSoldOut

    /**
     * 장바구니에 담는다. 줄/수량 한도에 걸리면 거기서 멈추고 [ReorderResult.limit] 을 알린다.
     * 같은 메뉴·옵션이 이미 담겨 있으면 수량이 합쳐진다.
     */
    fun applyTo(cart: CartState): ReorderResult {
        var state = cart
        var added = 0
        var limit: CartLimit? = null
        for (l in lines) {
            val r = state.add(l.item, l.choiceIds, l.qty)
            if (r.limit != null) { limit = r.limit; break }
            state = r.state
            added++
        }
        return ReorderResult(state, added, limit)
    }

    companion object {
        val ALL_SOLD_OUT = DialogText("메뉴가 모두 품절이에요", "다른 메뉴를 선택해주세요")
    }
}

data class ReorderResult(val cart: CartState, val addedLines: Int, val limit: CartLimit?)

object ReorderRules {
    /** @param menus 주문한 **그 매장**의 현재 메뉴 (다른 매장이면 호출하는 쪽이 1.1.2 장바구니 경고를 먼저 거친다) */
    fun plan(order: Order, menus: Collection<MenuItem>): ReorderPlan {
        val byId = menus.associateBy { it.id }
        val byName = menus.associateBy { it.name }
        val lines = ArrayList<ReorderLine>()
        val missing = ArrayList<String>()
        for (line in order.lines) {
            // 실서버의 주문 줄에는 메뉴 id 가 없어(BE 상세) 이름으로 찾는다
            val item = (if (line.menuId.isNotEmpty()) byId[line.menuId] else null) ?: byName[line.name]
            if (item == null) {
                missing += line.name
                continue
            }
            // 선택지 id 도 없으면 옵션 요약("HOT | 샷 추가")의 이름으로 찾는다 (같은 이름이 여러 그룹에 있으면 첫 번째)
            val picked = if (line.choiceIds.isEmpty() && line.optionSummary.isNotBlank()) {
                line.optionSummary.split(" | ").mapNotNull { n -> item.optionGroups.firstNotNullOfOrNull { g -> g.choices.firstOrNull { it.name == n.trim() }?.id } }
            } else {
                line.choiceIds
            }
            val ids = OptionRules.normalizeChoiceIds(item, picked).ids
            lines += ReorderLine(item, ids, line.qty.coerceIn(1, com.ormo.domain.rules.MAX_LINE_QTY), OptionRules.effectiveSoldOut(item))
        }
        return ReorderPlan(lines, missing)
    }
}
