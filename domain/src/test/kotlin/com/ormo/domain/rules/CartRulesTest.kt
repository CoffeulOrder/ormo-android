package com.ormo.domain.rules

import com.ormo.domain.Fixtures
import com.ormo.domain.Fixtures.americano
import com.ormo.domain.Fixtures.plain
import com.ormo.domain.model.OptionChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CartRulesTest {

    private fun CartChange.ok(): CartState {
        assertNull(limit, "한도에 걸리지 않아야 한다")
        return state
    }

    @Test fun `같은 메뉴와 같은 옵션은 한 줄로 합쳐진다`() {
        val s = CartState()
            .add(americano, listOf("ice"), 1).ok()
            .add(americano, listOf("ice"), 2).ok()
        assertEquals(1, s.lines.size)
        assertEquals(3, s.lines[0].qty)
    }

    @Test fun `옵션이 다르면 따로 담긴다`() {
        val s = CartState()
            .add(americano, listOf("ice")).ok()
            .add(americano, listOf("ice", "shot")).ok()
        assertEquals(2, s.lines.size)
    }

    @Test fun `담을 때 옵션을 메뉴 규칙에 맞게 고친다`() {
        val s = CartState().add(americano, emptyList()).ok()
        assertEquals(listOf("hot"), s.lines[0].choiceIds)
        assertEquals("m1|hot", s.lines[0].key)
    }

    @Test fun `수량과 합계`() {
        val s = CartState()
            .add(americano, listOf("ice"), 2).ok() // 4000 x 2
            .add(americano, listOf("hot", "shot"), 1).ok() // 4500
        assertEquals(3, s.count)
        assertEquals(12500, s.total)
    }

    @Test fun `수량을 0 이하로 하면 줄이 삭제된다`() {
        val s0 = CartState().add(americano, listOf("ice")).ok()
        val key = s0.lines[0].key
        assertTrue(s0.setQty(key, 0).ok().isEmpty)
        assertTrue(s0.setQty(key, -3).ok().isEmpty)
    }

    @Test fun `수량 변경`() {
        val s0 = CartState().add(americano, listOf("ice")).ok()
        val s1 = s0.setQty(s0.lines[0].key, 5).ok()
        assertEquals(5, s1.lines[0].qty)
    }

    @Test fun `줄당 수량 20개 한도`() {
        val s0 = CartState().add(americano, listOf("ice"), 20).ok()
        val over = s0.add(americano, listOf("ice"), 1)
        assertEquals(CartLimit.QTY_EXCEEDED, over.limit)
        assertSame(s0, over.state)

        val over2 = s0.setQty(s0.lines[0].key, 21)
        assertEquals(CartLimit.QTY_EXCEEDED, over2.limit)

        assertEquals(CartLimit.QTY_EXCEEDED, CartState().add(americano, listOf("ice"), 21).limit)
    }

    @Test fun `메뉴 20줄 한도`() {
        var s = CartState()
        for (i in 1..20) s = s.add(plain("p$i")).ok()
        assertEquals(20, s.lines.size)
        val over = s.add(plain("p21"))
        assertEquals(CartLimit.TOO_MANY_LINES, over.limit)
        assertEquals(20, over.state.lines.size)
        // 이미 있는 줄에 수량을 더하는 것은 가능
        s.add(plain("p1")).ok()
    }

    @Test fun `삭제 후 되돌리기는 원래 위치`() {
        var s = CartState()
        for (i in 1..3) s = s.add(plain("p$i")).ok()
        val removed = s.lines[1]
        val after = s.remove(removed.key)
        assertEquals(listOf("p1|", "p3|"), after.lines.map { it.key })
        val restored = after.restore(removed, 1)
        assertEquals(listOf("p1|", "p2|", "p3|"), restored.lines.map { it.key })
        // 이미 같은 줄이 있으면 그대로
        assertSame(restored, restored.restore(removed, 0))
    }

    @Test fun `옵션을 바꿔 같은 줄이 되면 수량을 합친다`() {
        val s = CartState()
            .add(americano, listOf("hot"), 1).ok()
            .add(americano, listOf("ice"), 2).ok()
        val hotKey = s.lines[0].key
        val merged = s.setOptions(hotKey, listOf("ice")).ok()
        assertEquals(1, merged.lines.size)
        assertEquals(3, merged.lines[0].qty)
        assertEquals("m1|ice", merged.lines[0].key)
    }

    @Test fun `옵션을 바꾸면 키와 옵션만 교체된다`() {
        val s = CartState().add(americano, listOf("hot"), 2).ok()
        val changed = s.setOptions(s.lines[0].key, listOf("hot", "shot")).ok()
        assertEquals(1, changed.lines.size)
        assertEquals(2, changed.lines[0].qty)
        assertEquals("m1|hot,shot", changed.lines[0].key)
        assertEquals(listOf("hot", "shot"), changed.lines[0].choiceIds)
    }

    @Test fun `옵션이 그대로이거나 없는 줄이면 변화 없음`() {
        val s = CartState().add(americano, listOf("hot")).ok()
        assertSame(s, s.setOptions(s.lines[0].key, listOf("hot")).state)
        assertSame(s, s.setOptions("nope", listOf("ice")).state)
    }

    @Test fun `옵션 합치기가 한도를 넘으면 거절`() {
        val s = CartState()
            .add(americano, listOf("hot"), 15).ok()
            .add(americano, listOf("ice"), 10).ok()
        val r = s.setOptions(s.lines[0].key, listOf("ice"))
        assertEquals(CartLimit.QTY_EXCEEDED, r.limit)
        assertSame(s, r.state)
    }

    // ---- reconcile ----

    @Test fun `갱신된 메뉴에 없는 줄은 제거되고 이름이 보고된다`() {
        val s = CartState().add(americano, listOf("ice")).ok().add(plain("p1", "치즈케이크")).ok()
        val r = s.reconcile(listOf(americano))
        assertEquals(listOf("치즈케이크"), r.removed)
        assertEquals(1, r.state.lines.size)
    }

    @Test fun `가격이 바뀌면 최신 가격으로 교체되고 보고된다`() {
        val s = CartState().add(americano, listOf("ice")).ok()
        val r = s.reconcile(listOf(americano.copy(price = 4500)))
        assertEquals(listOf("아메리카노"), r.priceChanged)
        assertEquals(4500, r.state.total)
        assertEquals(emptyList(), r.optionChanged)
    }

    @Test fun `사라진 옵션은 빠지고 옵션 변경과 가격 변경이 보고된다`() {
        val s = CartState().add(americano, listOf("ice", "shot")).ok()
        val latest = americano.copy(
            optionGroups = listOf(Fixtures.temp, Fixtures.extra.copy(choices = listOf(OptionChoice("syrup", "시럽 추가", 300)))),
        )
        val r = s.reconcile(listOf(latest))
        assertEquals(listOf("ice"), r.state.lines[0].choiceIds)
        assertEquals(listOf("아메리카노"), r.optionChanged)
        assertEquals(listOf("아메리카노"), r.priceChanged) // 4500 -> 4000
    }

    @Test fun `옵션이 고쳐져 같은 줄이 되면 합친다`() {
        val s = CartState()
            .add(americano, listOf("ice"), 1).ok()
            .add(americano, listOf("ice", "shot"), 2).ok()
        val latest = americano.copy(optionGroups = listOf(Fixtures.temp)) // 추가 옵션이 전부 사라짐
        val r = s.reconcile(listOf(latest))
        assertEquals(1, r.state.lines.size)
        assertEquals(3, r.state.lines[0].qty)
        assertEquals("m1|ice", r.state.lines[0].key)
    }

    @Test fun `변화가 없으면 아무것도 보고되지 않는다`() {
        val s = CartState().add(americano, listOf("ice", "shot")).ok()
        val r = s.reconcile(listOf(americano))
        assertEquals(emptyList(), r.removed)
        assertEquals(emptyList(), r.priceChanged)
        assertEquals(emptyList(), r.optionChanged)
        assertEquals(s.lines.map { it.key to it.qty }, r.state.lines.map { it.key to it.qty })
    }
}
