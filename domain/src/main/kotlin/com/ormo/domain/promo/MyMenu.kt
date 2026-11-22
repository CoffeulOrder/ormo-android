package com.ormo.domain.promo

import com.ormo.domain.model.Cafe
import com.ormo.domain.model.MenuItem
import com.ormo.domain.rules.OptionRules

/*
 * 내 메뉴 규칙 — **베타에서는 꺼져 있다** (FeatureFlags.myMenu). 매장마다 '자주 시키는 주문' 한 세트. RN `use-my-menu.tsx`.
 * 선택값은 선택지 id 만 저장한다 (이름·가격은 항상 최신 메뉴에서 찾는다).
 */

data class MyMenuItem(val menuId: String, val qty: Int, val options: List<String>)

data class MyMenuSet(val cafeId: String, val items: List<MyMenuItem>)

data class ResolvedLine(val item: MenuItem, val qty: Int, val choiceIds: List<String>)

data class ResolvedMyMenu(
    /** 이 매장에 지금 있는 메뉴들 (옵션은 최신 규칙으로 고침, 같은 줄이 되면 수량 합침) */
    val lines: List<ResolvedLine>,
    /** 지금은 판매하지 않아(단종 등) 풀 수 없는 줄 수 */
    val missing: Int,
    /** 옵션이 바뀌어 고친 메뉴 이름들 */
    val adjusted: List<String>,
) {
    /** 표시용 예상 금액 (실제 금액은 주문 때 서버가 계산) */
    val estimatedTotal: Int get() = lines.sumOf { it.qty * OptionRules.unitPrice(it.item, it.choiceIds) }
}

object MyMenuRules {
    /** 한 세트에 담을 수 있는 줄 수 (메뉴+옵션 조합 기준) / 한 줄의 최대 수량 */
    const val MAX_LINES = 10
    const val MAX_QTY = 9

    /**
     * 저장된 세트를 **현재 메뉴 데이터**에 맞춰 풀어낸다. 품절 여부는 여기서 보지 않는다 (호출하는 쪽이 걸러낸다).
     */
    fun resolve(cafe: Cafe, saved: List<MyMenuItem>): ResolvedMyMenu {
        val byId = cafe.menus.associateBy { it.id }
        val lines = ArrayList<ResolvedLine>()
        val adjusted = ArrayList<String>()
        var missing = 0
        for (s in saved) {
            val item = byId[s.menuId]
            if (item == null) { missing++; continue }
            val n = OptionRules.normalizeChoiceIds(item, s.options)
            if (n.adjusted && item.name !in adjusted) adjusted += item.name
            val key = OptionRules.lineKey(item.id, n.ids)
            val idx = lines.indexOfFirst { OptionRules.lineKey(it.item.id, it.choiceIds) == key }
            if (idx >= 0) lines[idx] = lines[idx].copy(qty = lines[idx].qty + s.qty) else lines += ResolvedLine(item, s.qty, n.ids)
        }
        return ResolvedMyMenu(lines, missing, adjusted)
    }

    /** 저장할 수 있는 세트인가: 줄 수·수량 한도 */
    fun isValid(items: List<MyMenuItem>): Boolean =
        items.size <= MAX_LINES && items.all { it.qty in 1..MAX_QTY && it.menuId.isNotBlank() }
}
