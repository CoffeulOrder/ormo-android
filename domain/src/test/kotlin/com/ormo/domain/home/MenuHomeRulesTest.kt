package com.ormo.domain.home

import com.ormo.domain.model.MenuItem
import com.ormo.domain.rules.CartState
import com.ormo.domain.store.OrderAvailability
import com.ormo.domain.store.UnavailableKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MenuHomeRulesTest {
    private fun m(id: String, cat: String, soldOut: Boolean = false) = MenuItem(id, "메뉴$id", "", 4000, cat, soldOut)

    private val menus = listOf(m("1", "커피"), m("2", "커피"), m("3", "티"), m("4", "디저트"), m("5", "티"))
    private val closed = OrderAvailability.Unavailable(UnavailableKind.CLOSED, "x")

    @Test fun `카테고리는 매장 데이터에 처음 나온 순서대로`() {
        val l = MenuHomeRules.layout(menus)
        assertEquals(listOf("커피", "티", "디저트"), l.tabs.map { it.title })
        assertEquals(listOf("3", "5"), l.sections[1].items.map { it.id })
        assertFalse(l.sections.any { it.isRecent })
    }

    @Test fun `메뉴가 없는 카테고리는 만들어지지 않는다`() {
        assertTrue(MenuHomeRules.layout(emptyList()).isEmpty)
    }

    @Test fun `빈 카테고리 이름은 기타`() {
        val l = MenuHomeRules.layout(listOf(m("1", "  ")))
        assertEquals("기타", l.tabs.single().title)
    }

    @Test fun `주문 이력이 0건이면 최근 주문 탭이 없다`() {
        val l = MenuHomeRules.layout(menus, recentOrderedIds = emptyList(), orderCount = 0)
        assertFalse(l.tabs.any { it.isRecent })
        assertEquals("커피", l.tabs.first().title) // 첫 카테고리부터
    }

    @Test fun `이력이 있으면 맨 앞에 최근 주문 - 중복 제거, 사라진 메뉴는 건너뜀`() {
        val l = MenuHomeRules.layout(menus, recentOrderedIds = listOf("4", "gone", "1", "4"))
        assertTrue(l.tabs.first().isRecent)
        assertEquals("최근 주문", l.tabs.first().title)
        assertEquals(listOf("4", "1"), l.tabs.first().items.map { it.id })
        assertEquals(4, l.tabs.size)
    }

    @Test fun `이력은 있지만 지금 메뉴에 남은 게 없으면 빈 묶음을 그리지 않는다`() {
        val l = MenuHomeRules.layout(menus, recentOrderedIds = listOf("gone"), orderCount = 3)
        assertFalse(l.tabs.any { it.isRecent })
    }

    @Test fun `탭을 누르면 해당 헤더 위치로 스크롤`() {
        val l = MenuHomeRules.layout(menus) // 커피(헤더0, 1, 2) 티(헤더3, 4, 5) 디저트(헤더6, 7)
        assertEquals(0, l.headerIndexOf(0))
        assertEquals(3, l.headerIndexOf(1))
        assertEquals(6, l.headerIndexOf(2))
        assertEquals(8, l.flatSize)
    }

    @Test fun `스크롤 위치에서 현재 섹션을 찾는다`() {
        val l = MenuHomeRules.layout(menus)
        assertEquals(0, l.sectionAt(0))
        assertEquals(0, l.sectionAt(2))
        assertEquals(1, l.sectionAt(3))
        assertEquals(1, l.sectionAt(5))
        assertEquals(2, l.sectionAt(6))
        assertEquals(2, l.sectionAt(99)) // 범위 밖은 끝
        assertEquals(0, l.sectionAt(-1))
        assertEquals(0, HomeLayout(emptyList()).sectionAt(3))
    }

    @Test fun `품절은 흐리고 눌러지지 않는다`() {
        val r = MenuHomeRules.rowState(m("1", "커피", soldOut = true), OrderAvailability.Available)
        assertFalse(r.tappable)
        assertTrue(r.dimmed)
        assertFalse(r.canAdd)
    }

    @Test fun `주문 불가여도 줄은 열리지만 담기는 꺼진다`() {
        val r = MenuHomeRules.rowState(m("1", "커피"), closed)
        assertTrue(r.tappable)
        assertFalse(r.dimmed)
        assertFalse(r.canAdd)
        assertTrue(MenuHomeRules.rowState(m("1", "커피"), OrderAvailability.Available).canAdd)
    }

    @Test fun `장바구니 바는 주문 가능하고 담은 게 있을 때만`() {
        assertTrue(MenuHomeRules.showCartBar(OrderAvailability.Available, 1))
        assertFalse(MenuHomeRules.showCartBar(OrderAvailability.Available, 0))
        assertFalse(MenuHomeRules.showCartBar(closed, 3))
    }

    @Test fun `수량 뱃지는 옵션이 달라도 같은 메뉴는 합산`() {
        val a = m("1", "커피")
        val b = m("2", "커피")
        var cart = CartState()
        cart = cart.add(a, qty = 2).state
        cart = cart.add(b, qty = 1).state
        cart = cart.add(a, choiceIds = listOf("opt-x"), qty = 3).state
        assertEquals(mapOf("1" to 5, "2" to 1), MenuHomeRules.quantityBadges(cart))
        assertEquals(emptyMap(), MenuHomeRules.quantityBadges(CartState()))
    }

    @Test fun `메모리 품절 정보를 덮어쓴다`() {
        val result = MenuHomeRules.applySoldOut(menus, setOf("2", "nope"))
        assertEquals(listOf("2"), result.filter { it.soldOut }.map { it.id })
        // 이미 품절인 것은 유지, 변경 없으면 같은 리스트
        val withSold = listOf(m("1", "커피", soldOut = true))
        assertTrue(MenuHomeRules.applySoldOut(withSold, emptySet())[0].soldOut)
        assertTrue(MenuHomeRules.applySoldOut(menus, emptySet()) === menus)
    }

    @Test fun `하단 바 구성`() {
        assertEquals(BottomBars(true, true), MenuHomeRules.bottomBars(OrderAvailability.Available, 2, true))
        assertEquals(BottomBars(false, true), MenuHomeRules.bottomBars(closed, 2, true))
        assertFalse(MenuHomeRules.bottomBars(OrderAvailability.Available, 0, false).any)
    }
}
