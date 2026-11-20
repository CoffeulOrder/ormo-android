package com.ormo.domain.home

import com.ormo.domain.model.MenuItem
import com.ormo.domain.rules.CartState
import com.ormo.domain.rules.OptionRules
import com.ormo.domain.store.OrderAvailability

/** 카테고리 탭 하나. [key] 가 [RECENT_KEY] 면 '최근 주문' 탭. */
data class HomeSection(val key: String, val title: String, val items: List<MenuItem>) {
    val isRecent: Boolean get() = key == RECENT_KEY
}

/** 메뉴 줄 하나의 표시 규칙 (2.1.1, 2.1.2) */
data class MenuRowState(
    /** 품절은 눌러지지 않는다. 주문 불가여도 줄은 눌려 2.2.1 이 열린다 (담기만 꺼짐). */
    val tappable: Boolean,
    /** 품절은 흐리게 */
    val dimmed: Boolean,
    /** 2.2.1 의 담기 버튼이 켜져 있는가 */
    val canAdd: Boolean,
)

/** 메뉴 목록을 탭/스크롤로 보여주기 위한 계산 (순수 함수). 화면은 헤더 1줄 + 메뉴 N줄의 평평한 리스트로 그린다고 가정. */
class HomeLayout(val sections: List<HomeSection>) {
    val tabs: List<HomeSection> get() = sections
    val isEmpty: Boolean get() = sections.isEmpty()

    /** 섹션 i 의 헤더가 평평한 리스트에서 갖는 인덱스 (탭을 누르면 여기로 스크롤) */
    fun headerIndexOf(sectionIndex: Int): Int {
        require(sectionIndex in sections.indices) { "section $sectionIndex" }
        return sections.take(sectionIndex).sumOf { 1 + it.items.size }
    }

    /** 평평한 리스트의 [firstVisibleIndex] 번째 줄이 속한 섹션 — 스크롤하면 탭 강조를 따라가게 한다. 범위 밖은 양 끝으로 맞춘다. */
    fun sectionAt(firstVisibleIndex: Int): Int {
        if (sections.isEmpty()) return 0
        var start = 0
        for ((i, s) in sections.withIndex()) {
            val end = start + 1 + s.items.size // 다음 섹션 헤더 인덱스
            if (firstVisibleIndex < end) return i
            start = end
        }
        return sections.lastIndex
    }

    val flatSize: Int get() = sections.sumOf { 1 + it.items.size }
}

const val RECENT_KEY = "__recent__"
const val RECENT_TITLE = "최근 주문"

object MenuHomeRules {
    /**
     * 메뉴 홈의 탭/묶음을 만든다 (2.1.1).
     * - 카테고리: 매장 데이터대로(처음 나온 순서), 메뉴가 없는 카테고리는 숨김. 빈 카테고리 이름은 '기타'.
     * - 최근 주문: **주문 이력이 0건이면 그리지 않는다.** 이력이 있으면 맨 앞. 단, 지금 메뉴에 남아 있는 것이 하나도 없으면
     *   빈 묶음을 그리지 않는다 (명세는 이력 0건만 다루므로 이 경우는 가정).
     * @param recentOrderedIds 최근 주문한 메뉴 id (최신순). 이 매장의 현재 메뉴에서 찾는다.
     */
    fun layout(menus: List<MenuItem>, recentOrderedIds: List<String> = emptyList(), orderCount: Int = recentOrderedIds.size): HomeLayout {
        val byCategory = LinkedHashMap<String, MutableList<MenuItem>>()
        for (m in menus) byCategory.getOrPut(m.category.trim().ifEmpty { UNCATEGORIZED }) { mutableListOf() }.add(m)
        val sections = byCategory.map { (name, items) -> HomeSection(name, name, items) }.toMutableList()

        if (orderCount > 0) {
            val index = menus.associateBy { it.id }
            val recent = recentOrderedIds.distinct().mapNotNull { index[it] }
            if (recent.isNotEmpty()) sections.add(0, HomeSection(RECENT_KEY, RECENT_TITLE, recent))
        }
        return HomeLayout(sections)
    }

    const val UNCATEGORIZED = "기타"

    fun rowState(item: MenuItem, availability: OrderAvailability): MenuRowState {
        val soldOut = OptionRules.effectiveSoldOut(item) // 필수 옵션이 모두 품절이면 메뉴도 품절 (2.2)
        return MenuRowState(
            tappable = !soldOut,
            dimmed = soldOut,
            canAdd = !soldOut && availability is OrderAvailability.Available,
        )
    }

    /** 장바구니 바는 주문 불가일 때 숨기고, 비었으면 안 보인다 (2.1.2, 2.1.1) */
    fun showCartBar(availability: OrderAvailability, cartItemCount: Int): Boolean =
        availability is OrderAvailability.Available && cartItemCount > 0

    /** 메뉴 줄의 수량 뱃지: 메뉴 id → 담은 총 수량 (옵션이 달라 줄이 여럿이어도 합산) */
    fun quantityBadges(cart: CartState): Map<String, Int> =
        cart.lines.groupBy { it.item.id }.mapValues { (_, lines) -> lines.sumOf { it.qty } }

    /**
     * 메모리에 있는 품절 정보(메뉴 repository 의 soldOut)를 메뉴에 덮어쓴다. 품절은 캐시하지 않으므로 (A §4.4) 목록은 항상 이걸 거쳐 그린다.
     * 서버 메뉴에 이미 품절 표시가 있으면 유지한다.
     */
    fun applySoldOut(menus: List<MenuItem>, soldOutIds: Set<String>): List<MenuItem> =
        if (soldOutIds.isEmpty()) menus else menus.map { if (!it.soldOut && it.id in soldOutIds) it.copy(soldOut = true) else it }

    /** 메뉴 하단 여백: 주문 현황 바(2.1.4)와 장바구니 바 높이 + 16dp (dp 값 계산은 UI 가 하고, 여기서는 어느 바가 있는지만 정한다) */
    fun bottomBars(availability: OrderAvailability, cartItemCount: Int, hasActiveOrder: Boolean): BottomBars =
        BottomBars(showCartBar = showCartBar(availability, cartItemCount), showOrderBar = hasActiveOrder)
}

/** 메뉴 홈 하단 바 구성. 둘 다 있으면 주문 현황 바가 장바구니 바 위 8dp 에 쌓인다 (2.1.4) */
data class BottomBars(val showCartBar: Boolean, val showOrderBar: Boolean) {
    val any: Boolean get() = showCartBar || showOrderBar
}
