package com.ormo.domain.orders

import com.ormo.domain.model.MenuItem
import com.ormo.domain.model.OptionChoice
import com.ormo.domain.model.OptionGroup
import com.ormo.domain.rules.CartLimit
import com.ormo.domain.rules.CartState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReorderRulesTest {
    private fun m(id: String, name: String, price: Int = 4000, soldOut: Boolean = false) = MenuItem(id, name, "", price, "c", soldOut)

    private val americano = m("1", "아메리카노")
    private val latte = m("2", "카페라떼", 5000)

    private fun orderOf(vararg l: OrderLine) = order(lines = l.toList())

    @Test fun `같은 메뉴를 현재 가격으로 다시 담는다`() {
        val plan = ReorderRules.plan(orderOf(line("1", qty = 2), line("2", "카페라떼", 1)), listOf(americano.copy(price = 4500), latte))
        assertEquals(listOf(2, 1), plan.lines.map { it.qty })
        assertTrue(plan.canGoToCart)
        val cart = plan.applyTo(CartState())
        assertEquals(3, cart.cart.count)
        assertEquals(2 * 4500 + 5000, cart.cart.total)
        assertEquals(2, cart.addedLines)
        assertNull(cart.limit)
    }

    @Test fun `모두 품절이면 장바구니로 가지 않고 다이얼로그`() {
        val plan = ReorderRules.plan(orderOf(line("1"), line("2", "카페라떼")), listOf(americano.copy(soldOut = true), latte.copy(soldOut = true)))
        assertTrue(plan.allSoldOut)
        assertFalse(plan.canGoToCart)
        assertEquals("메뉴가 모두 품절이에요", ReorderPlan.ALL_SOLD_OUT.title)
        assertEquals("다른 메뉴를 선택해주세요", ReorderPlan.ALL_SOLD_OUT.body)
    }

    @Test fun `일부만 품절이면 장바구니로 가고 품절 메뉴도 담아 뱃지로 알린다`() {
        val plan = ReorderRules.plan(orderOf(line("1"), line("2", "카페라떼")), listOf(americano, latte.copy(soldOut = true)))
        assertTrue(plan.canGoToCart)
        assertEquals(listOf("카페라떼"), plan.soldOutNames)
        val cart = plan.applyTo(CartState()).cart
        assertEquals(2, cart.lines.size)
        assertTrue(plan.hasSoldOut)
    }

    @Test fun `메뉴에서 사라진 줄은 건너뛰고 따로 센다`() {
        val plan = ReorderRules.plan(orderOf(line("1"), line("gone", "단종메뉴")), listOf(americano))
        assertEquals(listOf("단종메뉴"), plan.missing)
        assertEquals(1, plan.lines.size)
        assertTrue(plan.canGoToCart)
        assertTrue(plan.hasSoldOut)
    }

    @Test fun `전부 사라졌으면 담을 게 없다`() {
        val plan = ReorderRules.plan(orderOf(line("gone")), emptyList())
        assertFalse(plan.canGoToCart)
        assertTrue(plan.lines.isEmpty())
    }

    @Test fun `필수 옵션 모두 품절인 메뉴도 품절로 센다`() {
        val g = OptionGroup("t", "온도", true, false, listOf(OptionChoice("hot", "HOT", 0, soldOut = true)))
        val plan = ReorderRules.plan(orderOf(line("1", choices = listOf("hot"))), listOf(americano.copy(optionGroups = listOf(g))))
        assertTrue(plan.allSoldOut)
    }

    @Test fun `옵션은 현재 메뉴 규칙에 맞게 고친다`() {
        val g = OptionGroup("t", "온도", true, false, listOf(OptionChoice("hot", "HOT", 0), OptionChoice("ice", "ICE", 0)))
        // 주문 당시 선택지(old)는 사라졌으므로 필수 기본값으로
        val plan = ReorderRules.plan(orderOf(line("1", choices = listOf("old"))), listOf(americano.copy(optionGroups = listOf(g))))
        assertEquals(listOf("hot"), plan.lines.single().choiceIds)
    }

    @Test fun `이미 담긴 같은 줄은 수량이 합쳐진다`() {
        val cart = CartState().add(americano, qty = 1).state
        val r = ReorderRules.plan(orderOf(line("1", qty = 2)), listOf(americano)).applyTo(cart)
        assertEquals(3, r.cart.count)
        assertEquals(1, r.cart.lines.size)
    }

    @Test fun `수량 한도에 걸리면 거기서 멈추고 알린다`() {
        val cart = CartState().add(americano, qty = 19).state
        val r = ReorderRules.plan(orderOf(line("2", "카페라떼"), line("1", qty = 2)), listOf(americano, latte)).applyTo(cart)
        assertEquals(CartLimit.QTY_EXCEEDED, r.limit)
        assertEquals(1, r.addedLines) // 라떼는 담김, 아메리카노(19+2)에서 멈춤
        assertEquals(20, r.cart.count)
    }
}
