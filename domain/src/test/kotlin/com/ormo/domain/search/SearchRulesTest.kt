package com.ormo.domain.search

import com.ormo.domain.model.MenuItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecentSearchesTest {
    @Test fun `맨 앞에 추가되고 최대 10개`() {
        var list = emptyList<String>()
        (1..12).forEach { list = RecentSearches.add(list, "검색$it") }
        assertEquals(10, list.size)
        assertEquals("검색12", list.first())
        assertEquals("검색3", list.last())
    }

    @Test fun `같은 검색어는 맨 앞으로 옮기고 중복되지 않는다`() {
        val list = RecentSearches.add(listOf("라떼", "아메리카노", "티"), "아메리카노")
        assertEquals(listOf("아메리카노", "라떼", "티"), list)
    }

    @Test fun `띄어쓰기와 대소문자만 다르면 같은 검색어`() {
        val list = RecentSearches.add(listOf("Ice Latte"), "icelatte")
        assertEquals(listOf("icelatte"), list)
    }

    @Test fun `공백뿐이거나 빈 검색어는 기록하지 않는다`() {
        assertEquals(listOf("a"), RecentSearches.add(listOf("a"), "   "))
        assertEquals(listOf("a"), RecentSearches.add(listOf("a"), ""))
        assertEquals("라떼", RecentSearches.add(emptyList(), "  라떼 ").single())
    }

    @Test fun `개별 삭제와 전체 삭제`() {
        assertEquals(listOf("a", "c"), RecentSearches.remove(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("a"), RecentSearches.remove(listOf("a"), "없음"))
        assertEquals(emptyList(), RecentSearches.clear())
    }
}

class SearchRulesTest {
    private fun m(id: String, name: String, soldOut: Boolean = false) = MenuItem(id, name, "", 4000, "커피", soldOut)

    private val menus = listOf(
        m("1", "아메리카노"), m("2", "카페라떼"), m("3", "바닐라라떼", soldOut = true), m("4", "얼그레이"), m("5", "레몬티"),
        m("6", "초코라떼"), m("7", "딸기스무디"),
    )

    @Test fun `검색어가 없으면 시작 화면 - 인기 메뉴는 서버 순위 5개`() {
        val v = SearchRules.view(menus, "", listOf("a"), listOf("2", "1", "gone", "3", "4", "5", "6")) as SearchView.Start
        assertEquals(listOf("2", "1", "3", "4", "5"), v.popular.map { it.id })
        assertEquals(listOf("a"), v.recent)
        assertTrue(v.showsRecent)
        assertTrue(v.showsPopular)
    }

    @Test fun `기록이 없는 묶음은 숨긴다`() {
        val v = SearchRules.view(menus, "   ", emptyList(), emptyList()) as SearchView.Start
        assertFalse(v.showsRecent)
        assertFalse(v.showsPopular)
    }

    @Test fun `결과가 있으면 개수 라벨과 품절 포함`() {
        val v = SearchRules.view(menus, "라떼", emptyList(), emptyList())
        assertIs<SearchView.Results>(v)
        assertEquals(listOf("2", "3", "6"), v.hits.map { it.item.id })
        assertEquals("메뉴 3개", v.countLabel)
        assertTrue(v.hits.any { it.item.soldOut }) // 품절도 숨기지 않음
    }

    @Test fun `초성 검색과 띄어쓰기 무시`() {
        assertEquals(listOf("1"), (SearchRules.view(menus, "ㅇㅁㄹㅋㄴ", emptyList(), emptyList()) as SearchView.Results).hits.map { it.item.id })
        assertEquals(listOf("2"), (SearchRules.view(menus, "카 페 라 떼", emptyList(), emptyList()) as SearchView.Results).hits.map { it.item.id })
    }

    @Test fun `결과가 없으면 안내와 인기 메뉴 3개`() {
        val v = SearchRules.view(menus, "가을이", emptyList(), listOf("1", "2", "3", "4")) as SearchView.NoResult
        assertEquals("‘가을이’에 맞는 메뉴가 없어요", v.title)
        assertEquals("다른 이름으로 검색해보세요", v.hint)
        assertEquals(listOf("1", "2", "3"), v.popular.map { it.id })
    }

    @Test fun `인기 데이터가 없으면 결과 없음에서도 숨김`() {
        val v = SearchRules.view(menus, "가을이", emptyList(), emptyList()) as SearchView.NoResult
        assertFalse(v.showsPopular)
    }

    @Test fun `긴 검색어는 따옴표 안을 말줄임`() {
        val title = SearchRules.noResultTitle("가".repeat(30))
        assertEquals("‘" + "가".repeat(12) + "…’에 맞는 메뉴가 없어요", title)
        assertEquals("‘" + "가".repeat(12) + "’에 맞는 메뉴가 없어요", SearchRules.noResultTitle("가".repeat(12)))
    }

    @Test fun `결과 없음 제목은 앞뒤 공백을 지운다`() {
        assertEquals("‘가을이’에 맞는 메뉴가 없어요", SearchRules.noResultTitle("  가을이 "))
    }

    @Test fun `디바운스는 0점2초`() {
        assertEquals(200L, SearchRules.DEBOUNCE_MS)
    }
}
