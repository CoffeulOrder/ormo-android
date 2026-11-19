package com.ormo.domain.rules

import com.ormo.domain.model.MenuItem

/**
 * 장바구니 규칙 (순수 불변 상태). RN `use-cart.tsx` 의 동작을 옮겼다.
 * 한 줄 = 메뉴 + 옵션 조합. 같은 메뉴라도 옵션이 다르면 다른 줄이다 (`key` = [OptionRules.lineKey]).
 *
 * 새 디자인(2.3) 한도: 줄 20개, 줄당 수량 20개. — TODO(spec 2.3): "메뉴 20개, 수량 20개까지" 가
 * 줄당 수량인지 전체 수량인지 디자인 확인 필요. 지금은 줄당으로 구현.
 */
const val MAX_CART_LINES = 20
const val MAX_LINE_QTY = 20

data class CartLine(val key: String, val item: MenuItem, val qty: Int, val choiceIds: List<String>)

enum class CartLimit {
    /** 메뉴(줄)를 더 담을 수 없음 */
    TOO_MANY_LINES,

    /** "한 번에 20개까지 주문할 수 있어요" */
    QTY_EXCEEDED,
}

/** 변경 결과. `limit` 이 있으면 한도에 걸려 **상태가 바뀌지 않았다**. */
data class CartChange(val state: CartState, val limit: CartLimit? = null)

data class ReconcileResult(
    val state: CartState,
    val removed: List<String>,
    val priceChanged: List<String>,
    val optionChanged: List<String>,
)

data class CartState(val lines: List<CartLine> = emptyList()) {

    val count: Int get() = lines.sumOf { it.qty }

    /** 표시용 합계 (실제 결제 금액은 서버가 계산) */
    val total: Int get() = lines.sumOf { it.qty * OptionRules.unitPrice(it.item, it.choiceIds) }

    val isEmpty: Boolean get() = lines.isEmpty()

    /** 메뉴 담기. 같은 메뉴+같은 옵션이 이미 있으면 수량을 더한다. 옵션은 메뉴 규칙에 맞게 고쳐서 담는다. */
    fun add(item: MenuItem, choiceIds: Collection<String> = emptyList(), qty: Int = 1): CartChange {
        require(qty >= 1) { "qty must be >= 1" }
        val ids = OptionRules.normalizeChoiceIds(item, choiceIds).ids
        val key = OptionRules.lineKey(item.id, ids)
        val existing = lines.find { it.key == key }
        if (existing != null) {
            if (existing.qty + qty > MAX_LINE_QTY) return CartChange(this, CartLimit.QTY_EXCEEDED)
            return CartChange(copy(lines = lines.map { if (it.key == key) it.copy(qty = it.qty + qty) else it }))
        }
        if (qty > MAX_LINE_QTY) return CartChange(this, CartLimit.QTY_EXCEEDED)
        if (lines.size >= MAX_CART_LINES) return CartChange(this, CartLimit.TOO_MANY_LINES)
        return CartChange(copy(lines = lines + CartLine(key, item, qty, ids)))
    }

    /** 수량 바꾸기. 0 이하이면 줄을 삭제한다. */
    fun setQty(key: String, qty: Int): CartChange {
        if (qty <= 0) return CartChange(remove(key))
        if (qty > MAX_LINE_QTY) return CartChange(this, CartLimit.QTY_EXCEEDED)
        return CartChange(copy(lines = lines.map { if (it.key == key) it.copy(qty = qty) else it }))
    }

    fun remove(key: String): CartState = copy(lines = lines.filter { it.key != key })

    /** 삭제 되돌리기(4초 스낵바): 지웠던 줄을 원래 위치에 다시 넣는다. 이미 같은 줄이 있으면 그대로. */
    fun restore(line: CartLine, index: Int): CartState {
        if (lines.any { it.key == line.key }) return this
        val at = index.coerceIn(0, lines.size)
        return copy(lines = lines.toMutableList().apply { add(at, line) })
    }

    /** 한 줄의 옵션을 바꾼다. 바꾼 결과가 이미 담긴 다른 줄과 같으면 그 줄에 수량을 합친다. */
    fun setOptions(key: String, choiceIds: Collection<String>): CartChange {
        val target = lines.find { it.key == key } ?: return CartChange(this)
        val ids = OptionRules.normalizeChoiceIds(target.item, choiceIds).ids
        val nextKey = OptionRules.lineKey(target.item.id, ids)
        if (nextKey == key) return CartChange(this)
        val dup = lines.find { it.key == nextKey }
        if (dup != null) {
            if (dup.qty + target.qty > MAX_LINE_QTY) return CartChange(this, CartLimit.QTY_EXCEEDED)
            return CartChange(
                copy(
                    lines = lines
                        .filter { it.key != key }
                        .map { if (it.key == nextKey) it.copy(qty = it.qty + target.qty) else it },
                ),
            )
        }
        return CartChange(
            copy(lines = lines.map { if (it.key == key) it.copy(key = nextKey, choiceIds = ids) else it }),
        )
    }

    /**
     * 메뉴가 갱신된 뒤 장바구니를 최신 메뉴에 맞춘다: 사라진 메뉴는 빼고, 이름/가격/옵션은 최신 값으로 교체.
     * 무엇이 바뀌었는지 함께 돌려준다 (안내 문구용). 옵션이 고쳐져 같은 줄이 되면 합친다 (수량은 한도까지만).
     */
    fun reconcile(menus: Collection<MenuItem>): ReconcileResult {
        val byId = menus.associateBy { it.id }
        val removed = ArrayList<String>()
        val priceChanged = ArrayList<String>()
        val optionChanged = ArrayList<String>()
        val next = ArrayList<CartLine>()
        for (line in lines) {
            val latest = byId[line.item.id]
            if (latest == null) {
                removed += line.item.name
                continue
            }
            val (ids, adjusted) = OptionRules.normalizeChoiceIds(latest, line.choiceIds)
            if (adjusted) optionChanged += latest.name
            if (OptionRules.unitPrice(latest, ids) != OptionRules.unitPrice(line.item, line.choiceIds)) {
                priceChanged += latest.name
            }
            val key = OptionRules.lineKey(latest.id, ids)
            val idx = next.indexOfFirst { it.key == key }
            if (idx >= 0) {
                next[idx] = next[idx].copy(qty = minOf(next[idx].qty + line.qty, MAX_LINE_QTY))
            } else {
                next += CartLine(key, latest, line.qty, ids)
            }
        }
        return ReconcileResult(CartState(next), removed, priceChanged, optionChanged)
    }
}
