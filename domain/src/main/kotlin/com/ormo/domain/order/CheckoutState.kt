package com.ormo.domain.order

import com.ormo.core.Korean
import com.ormo.core.formatWon
import com.ormo.domain.rules.CartChange
import com.ormo.domain.rules.CartLimit
import com.ormo.domain.rules.CartLine
import com.ormo.domain.rules.CartState
import com.ormo.domain.rules.OptionRules
import com.ormo.domain.rules.ReconcileResult

/** 수령 방법: 꼭 골라야 한다 (2.3) */
enum class PickupMethod { DINE_IN, TAKEOUT }

/**
 * 결제수단. PG(KG이니시스 + 간편결제, 포트원 v2)가 확정되기 전이라 id/라벨만 둔다. 마지막에 쓴 것을 기억한다 (2.3).
 */
data class PaymentMethod(val id: String, val label: String)

/** 삭제 직후 4초 동안 되돌릴 수 있는 줄 (2.3) */
data class RemovedLine(val line: CartLine, val index: Int, val expiresAtMs: Long) {
    /** "아메리카노를 삭제했어요" */
    val message: String get() = "${line.item.name}${Korean.objectParticle(line.item.name)} 삭제했어요"
}

data class CheckoutChange(val state: CheckoutState, val limit: CartLimit? = null)

data class CheckoutReconcile(val state: CheckoutState, val result: ReconcileResult)

/**
 * 장바구니 + 주문서 한 화면(2.3) 상태. 순수 불변 — 시각은 호출하는 쪽이 넘긴다.
 *
 * - 결제 버튼 금액 = **품절이 아닌 줄만** 합한 금액. 모두 품절일 때만 버튼이 꺼진다 (품절은 안 막고 빼고 결제).
 * - 결제하려면 수령 방법 + 결제수단 + '매장이 주문을 받은 뒤에는 취소할 수 없어요' 동의(청약철회 제한 고지, 법적 필수)가 필요하다.
 *   동의는 **주문마다 다시 체크** ([afterOrderPlaced]).
 * - 삭제(수량을 0으로 줄이거나 휴지통): 바로 삭제 + 4초 되돌리기. 가장 최근 삭제 하나만 되돌릴 수 있다.
 * - 가격·품절은 결제 때 서버가 다시 계산한다 (여기 금액은 표시용).
 *
 * 줄에 담긴 메뉴는 최신 메뉴·품절 정보로 맞춰 두어야 한다 → 메뉴가 갱신될 때마다 [reconcile].
 */
data class CheckoutState(
    val cart: CartState = CartState(),
    val pickup: PickupMethod? = null,
    val paymentMethod: PaymentMethod? = null,
    val consented: Boolean = false,
    val removed: RemovedLine? = null,
) {
    val isEmpty: Boolean get() = cart.isEmpty

    fun isSoldOut(line: CartLine): Boolean = OptionRules.effectiveSoldOut(line.item)

    val payableLines: List<CartLine> get() = cart.lines.filterNot(::isSoldOut)
    val soldOutLines: List<CartLine> get() = cart.lines.filter(::isSoldOut)
    val allSoldOut: Boolean get() = !cart.isEmpty && payableLines.isEmpty()

    /** 결제 버튼 금액 (품절 제외) */
    val payAmount: Int get() = payableLines.sumOf { it.qty * OptionRules.unitPrice(it.item, it.choiceIds) }

    /** 줄 하나의 금액 (수량을 곱한 금액) */
    fun lineAmount(line: CartLine): Int = line.qty * OptionRules.unitPrice(line.item, line.choiceIds)

    val payLabel: String get() = "${formatWon(payAmount)} 결제하기"

    /** 결제 중이 아니고 필요한 선택을 모두 마쳤는가. 영업 상태는 [결제하기] 를 누를 때 서버가 막는다 (3.1.2). */
    fun canPay(paying: Boolean = false): Boolean =
        !paying && !cart.isEmpty && payableLines.isNotEmpty() && pickup != null && paymentMethod != null && consented

    /** 비활성 버튼 옆에 안내할 빠진 항목 (접근성·툴팁용) */
    val missing: List<Missing>
        get() = buildList {
            if (pickup == null) add(Missing.PICKUP)
            if (paymentMethod == null) add(Missing.PAYMENT_METHOD)
            if (!consented) add(Missing.CONSENT)
        }

    enum class Missing { PICKUP, PAYMENT_METHOD, CONSENT }

    // ---- 선택 ----
    fun choosePickup(method: PickupMethod): CheckoutState = copy(pickup = method)
    fun choosePayment(method: PaymentMethod?): CheckoutState = copy(paymentMethod = method)
    fun setConsent(checked: Boolean): CheckoutState = copy(consented = checked)

    // ---- 장바구니 편집 ----
    /** 수량 바꾸기. 0 이하면 삭제(+ 되돌리기). 20개를 넘기면 변화 없이 한도 안내. */
    fun setQty(key: String, qty: Int, nowMs: Long): CheckoutChange =
        if (qty <= 0) CheckoutChange(remove(key, nowMs)) else cart.setQty(key, qty).let { CheckoutChange(copy(cart = it.state), it.limit) }

    /** 줄 삭제: 바로 지우고 4초 되돌리기를 연다. 없는 줄이면 변화 없음. */
    fun remove(key: String, nowMs: Long): CheckoutState {
        val index = cart.lines.indexOfFirst { it.key == key }
        if (index < 0) return this
        return copy(
            cart = cart.remove(key),
            removed = RemovedLine(cart.lines[index], index, nowMs + UNDO_MS),
        )
    }

    /** 지금 되돌릴 수 있는 삭제 (4초 안) */
    fun activeUndo(nowMs: Long): RemovedLine? = removed?.takeIf { nowMs < it.expiresAtMs }

    fun undo(nowMs: Long): CheckoutState {
        val r = activeUndo(nowMs) ?: return copy(removed = null)
        return copy(cart = cart.restore(r.line, r.index), removed = null)
    }

    /** 스낵바가 사라진 뒤 정리 */
    fun dismissUndo(): CheckoutState = copy(removed = null)

    fun addMenu(change: CartChange): CheckoutChange = CheckoutChange(copy(cart = change.state), change.limit)

    // ---- 메뉴 갱신 ----
    /** 최신 메뉴(품절 반영)에 장바구니를 맞춘다. 사라진 메뉴는 빠지고 가격·옵션은 최신 값. 되돌리기 대기 중인 줄도 같이 맞춘다. */
    fun reconcile(menus: Collection<com.ormo.domain.model.MenuItem>): CheckoutReconcile {
        val r = cart.reconcile(menus)
        return CheckoutReconcile(copy(cart = r.state), r)
    }

    // ---- 주문 후 ----
    /** 결제가 끝나 주문이 접수됐다: 결제된 줄은 비우고 (품절이라 빠진 줄은 남긴다), 동의·되돌리기는 초기화. 수령 방법·결제수단은 유지(결제수단은 기억). */
    fun afterOrderPlaced(): CheckoutState = copy(
        cart = CartState(soldOutLines),
        consented = false,
        removed = null,
    )

    // ---- 주문 요청 ----
    /** 서버에 보낼 주문 초안. 필요한 선택이 빠졌거나 결제할 줄이 없으면 null */
    fun toDraft(): OrderDraft? {
        val method = pickup ?: return null
        val lines = payableLines
        if (lines.isEmpty()) return null
        return OrderDraft(
            lines = lines.map { OrderLineRequest(it.item.id, it.choiceIds, it.qty) },
            pickup = method,
            expectedAmount = payAmount,
        )
    }

    companion object {
        const val UNDO_MS = 4_000L
    }
}

data class OrderLineRequest(val menuId: String, val choiceIds: List<String>, val qty: Int)

/**
 * 주문 요청. [expectedAmount] 는 화면에 보인 금액이고 실제 결제 금액은 서버가 다시 계산한다.
 * 결제수단은 주문이 아니라 PG 호출에 속한다 → 결제수단을 바꿔도 같은 주문으로 다시 결제할 수 있다 (3.3.1).
 */
data class OrderDraft(val lines: List<OrderLineRequest>, val pickup: PickupMethod, val expectedAmount: Int) {
    /** 같은 주문인지 알아보는 값: 메뉴·옵션·수량·수령 방법 (금액·결제수단은 제외). 줄 순서는 무시. */
    val fingerprint: String
        get() = lines.map { "${it.menuId}|${it.choiceIds.sorted().joinToString(",")}|${it.qty}" }.sorted().joinToString(";") + "#" + pickup.name
}
