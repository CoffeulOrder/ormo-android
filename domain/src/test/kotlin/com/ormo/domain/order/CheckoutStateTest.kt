package com.ormo.domain.order

import com.ormo.domain.model.MenuItem
import com.ormo.domain.model.OptionChoice
import com.ormo.domain.model.OptionGroup
import com.ormo.domain.rules.CartLimit
import com.ormo.domain.rules.CartState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckoutStateTest {
    private fun m(id: String, name: String, price: Int, soldOut: Boolean = false) = MenuItem(id, name, "", price, "c", soldOut)

    private val americano = m("1", "아메리카노", 4000)
    private val latte = m("2", "카페라떼", 5000)
    private val card = PaymentMethod("card", "카드")

    private fun filled(vararg items: Pair<MenuItem, Int>): CheckoutState {
        var cart = CartState()
        items.forEach { (it, q) -> cart = cart.add(it, qty = q).state }
        return CheckoutState(cart)
    }

    private fun ready(s: CheckoutState) = s.choosePickup(PickupMethod.TAKEOUT).choosePayment(card).setConsent(true)

    // ---- 결제 조건 ----
    @Test fun `수령 방법 결제수단 동의가 모두 있어야 결제 가능`() {
        val s = filled(americano to 2)
        assertFalse(s.canPay())
        assertEquals(listOf(CheckoutState.Missing.PICKUP, CheckoutState.Missing.PAYMENT_METHOD, CheckoutState.Missing.CONSENT), s.missing)
        assertFalse(s.choosePickup(PickupMethod.DINE_IN).canPay())
        assertFalse(s.choosePickup(PickupMethod.DINE_IN).choosePayment(card).canPay()) // 동의 없음
        assertTrue(ready(s).canPay())
        assertTrue(ready(s).missing.isEmpty())
    }

    @Test fun `결제 중이거나 빈 장바구니면 결제 불가`() {
        assertFalse(ready(filled(americano to 1)).canPay(paying = true))
        assertFalse(ready(CheckoutState()).canPay())
        assertTrue(CheckoutState().isEmpty)
    }

    @Test fun `결제 버튼 금액은 품절을 뺀 금액이고 모두 품절일 때만 비활성`() {
        val s = ready(filled(americano to 2, m("3", "품절메뉴", 12000, soldOut = true) to 1, latte to 2)) // 8,000 + 10,000
        assertEquals(18_000, s.payAmount)
        assertEquals("18,000원 결제하기", s.payLabel)
        assertEquals(1, s.soldOutLines.size)
        assertTrue(s.canPay()) // 품절이 섞여 있어도 켜짐
        assertFalse(s.allSoldOut)

        val all = ready(filled(m("3", "품절메뉴", 12000, soldOut = true) to 1))
        assertTrue(all.allSoldOut)
        assertEquals(0, all.payAmount)
        assertFalse(all.canPay())
        assertNull(all.toDraft())
    }

    @Test fun `필수 옵션이 모두 품절인 메뉴도 품절로 센다`() {
        val g = OptionGroup("t", "온도", true, false, listOf(OptionChoice("hot", "HOT", 0, soldOut = true)))
        val s = ready(filled(americano.copy(optionGroups = listOf(g)) to 1))
        assertTrue(s.allSoldOut)
    }

    @Test fun `줄 금액은 수량을 곱한 금액`() {
        val s = filled(americano to 3)
        assertEquals(12_000, s.lineAmount(s.cart.lines.single()))
    }

    // ---- 삭제 되돌리기 ----
    @Test fun `수량을 0으로 줄이면 삭제되고 4초 동안 되돌릴 수 있다`() {
        var s = filled(americano to 1, latte to 1)
        val key = s.cart.lines[0].key
        s = s.setQty(key, 0, nowMs = 1_000).state
        assertEquals(1, s.cart.lines.size)
        assertEquals("아메리카노를 삭제했어요", s.activeUndo(1_000)!!.message)
        assertNotNull(s.activeUndo(4_999))
        assertNull(s.activeUndo(5_000)) // 4초 뒤엔 불가
        val back = s.undo(4_000)
        assertEquals(listOf("1", "2"), back.cart.lines.map { it.item.id }) // 원래 위치
        assertNull(back.removed)
    }

    @Test fun `시간이 지난 뒤 되돌리기는 아무 일도 하지 않고 기록만 지운다`() {
        val s = filled(americano to 1).let { it.remove(it.cart.lines[0].key, 0) }
        val late = s.undo(10_000)
        assertTrue(late.cart.isEmpty)
        assertNull(late.removed)
    }

    @Test fun `새로 삭제하면 이전 되돌리기는 사라진다`() {
        var s = filled(americano to 1, latte to 1)
        s = s.remove(s.cart.lines[0].key, 0)
        s = s.remove(s.cart.lines[0].key, 1_000) // 라떼
        assertEquals("카페라떼를 삭제했어요", s.activeUndo(1_000)!!.message)
        assertTrue(s.isEmpty) // 모두 삭제 → 빈 상태
    }

    @Test fun `없는 줄을 지워도 변화 없음`() {
        val s = filled(americano to 1)
        assertEquals(s, s.remove("nope", 0))
    }

    @Test fun `수량 한도`() {
        val s = filled(americano to 20)
        val r = s.setQty(s.cart.lines[0].key, 21, 0)
        assertEquals(CartLimit.QTY_EXCEEDED, r.limit)
        assertEquals(20, r.state.cart.count)
    }

    // ---- 갱신 ----
    @Test fun `메뉴가 갱신되면 품절이 줄에 반영된다`() {
        val s = ready(filled(americano to 2, latte to 1))
        val r = s.reconcile(listOf(americano.copy(soldOut = true), latte))
        assertEquals(listOf("1"), r.state.soldOutLines.map { it.item.id })
        assertEquals(5000, r.state.payAmount)
        assertTrue(r.state.consented) // 선택은 그대로
    }

    @Test fun `사라진 메뉴는 빠진다`() {
        val r = filled(americano to 1, latte to 1).reconcile(listOf(latte))
        assertEquals(listOf("아메리카노"), r.result.removed)
    }

    // ---- 주문 후 ----
    @Test fun `주문이 접수되면 결제된 줄은 비우고 품절 줄은 남기고 동의는 초기화`() {
        val s = ready(filled(americano to 1, m("3", "품절", 3000, soldOut = true) to 1))
        val after = s.afterOrderPlaced()
        assertEquals(listOf("3"), after.cart.lines.map { it.item.id })
        assertFalse(after.consented) // 주문마다 다시 체크
        assertEquals(PickupMethod.TAKEOUT, after.pickup)
        assertEquals(card, after.paymentMethod) // 결제수단은 기억
    }

    // ---- 주문 초안 ----
    @Test fun `주문 초안은 결제할 줄만 담고 수령 방법이 없으면 null`() {
        val s = filled(americano to 2, m("3", "품절", 3000, soldOut = true) to 1)
        assertNull(s.toDraft())
        val d = s.choosePickup(PickupMethod.DINE_IN).toDraft()!!
        assertEquals(listOf(OrderLineRequest("1", emptyList(), 2)), d.lines)
        assertEquals(8000, d.expectedAmount)
    }

    @Test fun `같은 주문 알아보기 - 순서와 결제수단은 무시, 수량과 수령 방법은 반영`() {
        val a = filled(americano to 1, latte to 2).choosePickup(PickupMethod.DINE_IN).toDraft()!!
        val reordered = filled(latte to 2, americano to 1).choosePickup(PickupMethod.DINE_IN).choosePayment(card).toDraft()!!
        assertEquals(a.fingerprint, reordered.fingerprint)
        assertFalse(a.fingerprint == filled(americano to 1, latte to 3).choosePickup(PickupMethod.DINE_IN).toDraft()!!.fingerprint)
        assertFalse(a.fingerprint == filled(americano to 1, latte to 2).choosePickup(PickupMethod.TAKEOUT).toDraft()!!.fingerprint)
    }
}
