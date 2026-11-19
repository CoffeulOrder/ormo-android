package com.ormo.domain.rules

import com.ormo.domain.model.MenuItem

/**
 * 메뉴 이름 검색 (디자인 2.1.3): 이름 부분 일치 + 초성 검색(ㅇㅁㄹㅋㄴ → 아메리카노) + 띄어쓰기 무시.
 * 서버 검색 없이 앱에서 거른다. 한 글자씩 입력할 때마다 호출하되 0.2초 디바운스는 UI(ViewModel)가 담당한다.
 * 줄임말(아아, 뜨아)은 베타 이후.
 */
object MenuSearch {
    private const val CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

    data class Hit(val item: MenuItem, val range: IntRange)

    private fun choseong(c: Char): Char? =
        if (c in '가'..'힣') CHOSEONG[(c.code - 0xAC00) / 588] else null

    private fun isConsonantJamo(c: Char): Boolean = c in 'ㄱ'..'ㅎ'

    private fun charMatches(nameChar: Char, queryChar: Char): Boolean =
        nameChar == queryChar || (isConsonantJamo(queryChar) && choseong(nameChar) == queryChar)

    /**
     * `name` 에서 `query` 와 일치하는 구간을 **원래 문자열의 인덱스**로 돌려준다 (파란색 강조용). 없으면 null.
     * 띄어쓰기는 양쪽 모두 무시하고, 영문은 대소문자를 구분하지 않는다. 빈 검색어는 null.
     * 초성은 글자 단위로 섞어 쓸 수 있다 ("아ㅁㄹ" → 아메리카노).
     */
    fun match(name: String, query: String): IntRange? {
        val q = query.filterNot { it.isWhitespace() }.lowercase()
        if (q.isEmpty()) return null

        val origIndex = ArrayList<Int>()
        val chars = StringBuilder()
        name.forEachIndexed { i, c ->
            if (!c.isWhitespace()) {
                origIndex += i
                chars.append(c.lowercaseChar())
            }
        }
        if (q.length > chars.length) return null
        for (start in 0..chars.length - q.length) {
            if (q.indices.all { charMatches(chars[start + it], q[it]) }) {
                return origIndex[start]..origIndex[start + q.length - 1]
            }
        }
        return null
    }

    /** 검색 결과 (품절 메뉴도 숨기지 않고, 메뉴 원래 순서를 유지한다). */
    fun search(menus: List<MenuItem>, query: String): List<Hit> =
        menus.mapNotNull { item -> match(item.name, query)?.let { Hit(item, it) } }
}
