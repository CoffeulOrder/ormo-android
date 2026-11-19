package com.ormo.domain.rules

import com.ormo.domain.Fixtures.plain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MenuSearchTest {

    @Test fun `초성 검색`() {
        assertEquals(0..4, MenuSearch.match("아메리카노", "ㅇㅁㄹㅋㄴ"))
        assertEquals(0..1, MenuSearch.match("카페라떼", "ㅋㅍ"))
        assertEquals(1..1, MenuSearch.match("아메", "ㅁ"))
    }

    @Test fun `초성과 글자를 섞어 검색`() {
        assertEquals(0..2, MenuSearch.match("아메리카노", "아ㅁㄹ"))
    }

    @Test fun `부분 일치와 강조 구간은 원래 문자열 인덱스`() {
        // 아0 이1 스2 ' '3 아4 메5 리6 카7 노8
        assertEquals(4..5, MenuSearch.match("아이스 아메리카노", "아메"))
    }

    @Test fun `띄어쓰기는 양쪽 모두 무시한다`() {
        assertEquals(2..4, MenuSearch.match("아이스 아메리카노", "스아"))
        assertEquals(0..5, MenuSearch.match("아이스 아메리카노", "아이스 아메"))
    }

    @Test fun `영문은 대소문자를 구분하지 않는다`() {
        assertEquals(0..3, MenuSearch.match("Cafe Latte", "cafe"))
        assertEquals(5..9, MenuSearch.match("Cafe Latte", "LATTE"))
    }

    @Test fun `일치하지 않으면 null`() {
        assertNull(MenuSearch.match("아메리카노", "ㅇㅁㄹㅋㅌ"))
        assertNull(MenuSearch.match("아메리카노", "라떼"))
        assertNull(MenuSearch.match("아메", "아메리카노")) // 검색어가 더 긺
    }

    @Test fun `빈 검색어는 null`() {
        assertNull(MenuSearch.match("아메리카노", ""))
        assertNull(MenuSearch.match("아메리카노", "   "))
    }

    @Test fun `검색 결과는 메뉴 순서를 유지하고 품절도 숨기지 않는다`() {
        val menus = listOf(
            plain("1", "아메리카노"),
            plain("2", "카페라떼"),
            plain("3", "아이스 아메리카노").copy(soldOut = true),
        )
        val hits = MenuSearch.search(menus, "ㅇㅁ")
        assertEquals(listOf("1", "3"), hits.map { it.item.id })
        assertEquals(true, hits[1].item.soldOut)
        assertEquals(emptyList(), MenuSearch.search(menus, "케이크"))
    }
}
