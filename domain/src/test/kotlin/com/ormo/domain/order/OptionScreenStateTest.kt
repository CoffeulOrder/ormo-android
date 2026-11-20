package com.ormo.domain.order

import com.ormo.domain.model.MenuItem
import com.ormo.domain.model.OptionChoice
import com.ormo.domain.model.OptionGroup
import com.ormo.domain.rules.CartLimit
import com.ormo.domain.rules.CartState
import com.ormo.domain.rules.OptionRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptionScreenStateTest {
    private val temp = OptionGroup("temp", "온도", required = true, multi = false, choices = listOf(OptionChoice("hot", "HOT", 0), OptionChoice("ice", "ICE", 0)))
    private val size = OptionGroup("size", "사이즈", required = true, multi = false, choices = listOf(OptionChoice("m", "M", 0), OptionChoice("l", "L", 900)))
    private val shot = OptionGroup(
        "shot", "샷", required = false, multi = true, maxSelect = 2,
        choices = listOf(OptionChoice("s1", "샷 추가", 500), OptionChoice("s2", "연하게", 0), OptionChoice("s3", "진하게", 600)),
    )
    private val latte = MenuItem("m1", "카페라떼", "", 4000, "커피", optionGroups = listOf(temp, size, shot))
    private val plain = MenuItem("m2", "쿠키", "", 2500, "디저트")

    @Test fun `필수 옵션은 기본값을 미리 고른 채 시작`() {
        val s = OptionScreenState.start(latte)
        assertEquals(listOf("hot", "m"), s.choiceIds)
        assertTrue(s.canAdd)
        assertEquals(1, s.qty)
    }

    @Test fun `버튼 금액은 기본가 더하기 옵션 추가금 곱하기 수량`() {
        // (4,000 + 900 + 1,600) × 3 = 19,500 — 디자인 명세 예시와 같은 형태
        val big = MenuItem("m3", "x", "", 4000, "c", optionGroups = listOf(
            OptionGroup("g", "g", true, false, listOf(OptionChoice("a", "a", 900))),
            OptionGroup("h", "h", false, true, listOf(OptionChoice("b", "b", 1600))),
        ))
        var s = OptionScreenState.start(big).toggle("h", "b").state
        repeat(2) { s = s.increase().state }
        assertEquals(6500, s.unitPrice)
        assertEquals(19_500, s.amount)
        assertEquals("19,500원 담기", s.addLabel)
    }

    @Test fun `수량은 1에서 20 사이 - 한도에서 버튼이 꺼지고 안내`() {
        var s = OptionScreenState.start(plain)
        assertFalse(s.canDecrease)
        assertEquals(s, s.decrease().state)
        assertNull(s.decrease().notice)
        repeat(19) { s = s.increase().state }
        assertEquals(20, s.qty)
        assertFalse(s.canIncrease)
        val over = s.increase()
        assertEquals(20, over.state.qty)
        assertEquals(OptionNotice.QtyLimit, over.notice)
        assertEquals("한 번에 20개까지 주문할 수 있어요", over.notice!!.text)
    }

    @Test fun `선택 그룹 최대 개수를 넘기면 안내하고 체크는 유지`() {
        var s = OptionScreenState.start(latte)
        s = s.toggle("shot", "s1").state
        s = s.toggle("shot", "s2").state
        val third = s.toggle("shot", "s3")
        assertEquals(s.choiceIds, third.state.choiceIds)
        assertEquals(OptionNotice.MaxSelect(2), third.notice)
        assertEquals("최대 2개까지 선택할 수 있어요", third.notice!!.text)
        assertTrue(third.state.isSelected("s1"))
    }

    @Test fun `필수 그룹은 해제할 수 없고 다른 선택지로 교체된다`() {
        var s = OptionScreenState.start(latte)
        assertEquals(s.choiceIds, s.toggle("temp", "hot").state.choiceIds)
        s = s.toggle("temp", "ice").state
        assertTrue(s.isSelected("ice"))
        assertFalse(s.isSelected("hot"))
    }

    @Test fun `품절 옵션은 가격 자리에 품절, 추가금이 없으면 0원`() {
        assertEquals("품절", OptionScreenState.priceLabel(OptionChoice("x", "x", 500, soldOut = true)))
        assertEquals("+0원", OptionScreenState.priceLabel(OptionChoice("x", "x", 0)))
        assertEquals("+900원", OptionScreenState.priceLabel(OptionChoice("x", "x", 900)))
    }

    @Test fun `필수 옵션이 모두 품절이면 메뉴도 품절이라 담을 수 없다`() {
        val soldTemp = temp.copy(choices = temp.choices.map { it.copy(soldOut = true) })
        val item = latte.copy(optionGroups = listOf(soldTemp, size))
        assertTrue(OptionRules.effectiveSoldOut(item))
        assertFalse(OptionScreenState.start(item).canAdd)
        // 선택 그룹이 모두 품절이어도 메뉴는 품절이 아니다
        val soldShot = shot.copy(choices = shot.choices.map { it.copy(soldOut = true) })
        assertFalse(OptionRules.effectiveSoldOut(latte.copy(optionGroups = listOf(temp, size, soldShot))))
        assertTrue(OptionRules.effectiveSoldOut(latte.copy(soldOut = true)))
    }

    @Test fun `HOT만 되는 메뉴는 그 하나만 선택된 상태`() {
        val hotOnly = latte.copy(optionGroups = listOf(temp.copy(choices = listOf(OptionChoice("hot", "HOT", 0))), size))
        assertEquals(listOf("hot", "m"), OptionScreenState.start(hotOnly).choiceIds)
    }

    @Test fun `품절 선택지는 눌러도 선택되지 않고 기본값은 품절이 아닌 첫 선택지`() {
        val hotSold = temp.copy(choices = listOf(OptionChoice("hot", "HOT", 0, soldOut = true), OptionChoice("ice", "ICE", 0)))
        val item = latte.copy(optionGroups = listOf(hotSold, size))
        val s = OptionScreenState.start(item)
        assertEquals(listOf("ice", "m"), s.choiceIds)
        assertEquals(s.choiceIds, s.toggle("temp", "hot").state.choiceIds)
    }

    @Test fun `옵션이 없는 메뉴`() {
        val s = OptionScreenState.start(plain)
        assertFalse(s.hasOptions)
        assertTrue(s.canAdd)
        assertEquals(2500, s.amount)
    }

    @Test fun `장바구니에 담기 - 같은 옵션이면 합치고 20개를 넘으면 변화 없이 한도`() {
        var cart = CartState()
        val s = OptionScreenState.start(latte).copy(qty = 12)
        cart = s.addTo(cart).state
        assertEquals(12, cart.count)
        val again = s.addTo(cart)
        assertEquals(CartLimit.QTY_EXCEEDED, again.limit)
        assertEquals(12, again.state.count)
    }
}
