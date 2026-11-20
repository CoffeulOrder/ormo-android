package com.ormo.domain.search

import com.ormo.domain.model.MenuItem
import com.ormo.domain.rules.MenuSearch

/**
 * 최근 검색어 목록 연산 (디자인 2.1.3): 이 매장 기준 최대 10개, 개별 삭제, 전체 삭제.
 * 같은 검색어를 다시 검색하면 맨 앞으로 옮긴다. 순수 함수 — 저장은 `RecentSearchRepository`.
 */
object RecentSearches {
    const val MAX = 10

    /** 앞뒤 공백 제거. 빈 문자열이면 null. 중간의 공백은 그대로 둔다 (띄어쓰기는 검색 때 무시되지만 기록은 사용자가 친 그대로). */
    fun normalize(term: String): String? = term.trim().takeIf { it.isNotEmpty() }

    /** 맨 앞에 추가. 띄어쓰기·대소문자만 다른 같은 검색어는 하나로 본다. */
    fun add(list: List<String>, term: String): List<String> {
        val t = normalize(term) ?: return list
        val key = identity(t)
        return (listOf(t) + list.filterNot { identity(it) == key }).take(MAX)
    }

    fun remove(list: List<String>, term: String): List<String> {
        val key = identity(term)
        return list.filterNot { identity(it) == key }
    }

    fun clear(): List<String> = emptyList()

    private fun identity(s: String) = s.filterNot { it.isWhitespace() }.lowercase()
}

/** 검색 화면이 그릴 내용 */
sealed interface SearchView {
    /** 검색어가 비었음: 최근 검색어 + 많이 찾는 메뉴. **기록이 없는 묶음은 그리지 않는다** (빈 리스트로 온다). */
    data class Start(val recent: List<String>, val popular: List<MenuItem>) : SearchView {
        val showsRecent: Boolean get() = recent.isNotEmpty()
        val showsPopular: Boolean get() = popular.isNotEmpty()
    }

    /** 결과 있음: '메뉴 N개' + 일치 구간 강조. 품절도 숨기지 않고 흐리게 (메뉴 줄 규칙 그대로) */
    data class Results(val hits: List<MenuSearch.Hit>) : SearchView {
        val countLabel: String get() = "메뉴 ${hits.size}개"
    }

    /** 결과 없음: 제목 + 안내 + (있으면) 이 매장 인기 메뉴 3개 */
    data class NoResult(val title: String, val hint: String, val popular: List<MenuItem>) : SearchView {
        val showsPopular: Boolean get() = popular.isNotEmpty()
    }
}

object SearchRules {
    const val START_HINT = "메뉴 이름으로 검색해보세요"
    const val NO_RESULT_HINT = "다른 이름으로 검색해보세요"
    const val POPULAR_ON_START = 5
    const val POPULAR_ON_EMPTY = 3

    /** 검색어가 길면 따옴표 안을 말줄임 (2.1.3). TODO(spec 2.1.3): 몇 글자부터 줄일지 Figma 확인 */
    const val MAX_QUOTED_LENGTH = 12

    /** 한 번에 하나만 매장 메뉴를 거른다. [query] 가 공백뿐이면 시작 화면. */
    fun view(menus: List<MenuItem>, query: String, recent: List<String>, popularIds: List<String>): SearchView {
        if (query.isBlank()) {
            return SearchView.Start(recent, popular(menus, popularIds, POPULAR_ON_START))
        }
        val hits = MenuSearch.search(menus, query)
        return if (hits.isNotEmpty()) {
            SearchView.Results(hits)
        } else {
            SearchView.NoResult(noResultTitle(query), NO_RESULT_HINT, popular(menus, popularIds, POPULAR_ON_EMPTY))
        }
    }

    /**
     * 많이 찾는 메뉴: 서버가 준 순위(최근 7일 주문 많은 순)대로 현재 메뉴에서 찾는다.
     * 메뉴에서 사라진 것은 건너뛰고, 품절은 흐리게 보이도록 남긴다.
     */
    fun popular(menus: List<MenuItem>, popularIds: List<String>, limit: Int): List<MenuItem> {
        val index = menus.associateBy { it.id }
        return popularIds.distinct().mapNotNull { index[it] }.take(limit)
    }

    /** 결과 없음 제목: ‘가을이’에 맞는 메뉴가 없어요. 긴 검색어는 따옴표 안을 말줄임. */
    fun noResultTitle(query: String): String {
        val q = query.trim()
        val shown = if (q.length > MAX_QUOTED_LENGTH) q.take(MAX_QUOTED_LENGTH) + "…" else q
        return "‘$shown’에 맞는 메뉴가 없어요"
    }

    /** 0.2초 디바운스 (2.1.3) — 입력이 멈춘 뒤 이 시간이 지나야 거른다. ViewModel 이 쓴다. */
    const val DEBOUNCE_MS = 200L
}
