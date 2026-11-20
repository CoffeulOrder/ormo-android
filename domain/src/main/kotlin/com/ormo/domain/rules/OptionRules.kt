package com.ormo.domain.rules

import com.ormo.domain.model.MenuItem
import com.ormo.domain.model.OptionChoice
import com.ormo.domain.model.OptionGroup

/**
 * 메뉴 옵션 선택 규칙. RN 앱 `constants/options.ts` 의 동작을 그대로 옮기고,
 * 새 디자인 명세(2.2)가 추가한 것(품절 옵션, 최대 선택 개수)만 더했다.
 */
object OptionRules {

    fun hasOptions(item: MenuItem): Boolean = item.optionGroups.isNotEmpty()

    /**
     * 실질적인 품절 (디자인 2.2): 메뉴 자체가 품절이거나, **필수 옵션 그룹의 선택지가 모두 품절**이면 메뉴도 품절이다
     * (고를 수 있는 선택지가 없어 담을 수 없으므로). 선택지가 하나도 없는 필수 그룹도 마찬가지.
     */
    fun effectiveSoldOut(item: MenuItem): Boolean =
        item.soldOut || item.optionGroups.any { g -> g.required && g.choices.none { !it.soldOut } }

    /** 기본으로 고를 선택지: 첫 번째. 단, 품절이 아닌 선택지가 있으면 그중 첫 번째 (디자인 2.2: 품절 옵션은 고를 수 없음). */
    private fun defaultChoice(group: OptionGroup): OptionChoice? =
        group.choices.firstOrNull { !it.soldOut } ?: group.choices.firstOrNull()

    /** 필수 그룹마다 기본 선택지를 미리 골라 둔다 (예: 온도 HOT). */
    fun defaultChoiceIds(item: MenuItem): List<String> =
        item.optionGroups.filter { it.required }.mapNotNull { defaultChoice(it)?.id }

    data class Normalized(val ids: List<String>, val adjusted: Boolean)

    /**
     * 선택값을 현재 메뉴 규칙에 맞게 고친다: 없는 선택지는 버리고, 단일 그룹은 하나만(선택지 순서상 첫 번째),
     * 최대 개수를 넘으면 앞에서부터 자르고, 필수 그룹이 비면 기본 선택지로 채운다. 결과는 그룹 순서대로 정렬된다.
     * `adjusted` = 입력과 **내용**이 달라졌는가 (순서만 바뀐 것은 포함하지 않는다. 옵션이 바뀌었다는 안내용).
     */
    fun normalizeChoiceIds(item: MenuItem, ids: Collection<String>): Normalized {
        val out = ArrayList<String>()
        for (group in item.optionGroups) {
            var picked = group.choices.filter { it.id in ids }.map { it.id }
            if (!group.multi && picked.size > 1) picked = picked.take(1)
            val max = group.maxSelect
            if (group.multi && max != null && picked.size > max) picked = picked.take(max)
            if (group.required && picked.isEmpty()) {
                defaultChoice(group)?.let { picked = listOf(it.id) }
            }
            out += picked
        }
        val adjusted = out.size != ids.size || out.any { it !in ids }
        return Normalized(out, adjusted)
    }

    /** 같은 메뉴 + 같은 옵션이면 같은 키 (장바구니에서 한 줄로 합치는 기준). */
    fun lineKey(menuId: String, ids: Collection<String>): String =
        "$menuId|${ids.sorted().joinToString(",")}"

    fun optionsPrice(item: MenuItem, ids: Collection<String>): Int =
        item.optionGroups.sumOf { g -> g.choices.filter { it.id in ids }.sumOf { it.priceDelta } }

    /** 한 개당 표시 가격 = 메뉴 가격 + 옵션 추가금 (표시용) */
    fun unitPrice(item: MenuItem, ids: Collection<String>): Int = item.price + optionsPrice(item, ids)

    /** "ICE · 샷 추가" 처럼 고른 옵션 이름 (그룹 순서). 고른 게 없으면 빈 문자열. */
    fun optionSummary(item: MenuItem, ids: Collection<String>): String =
        item.optionGroups
            .flatMap { g -> g.choices.filter { it.id in ids }.map { it.name } }
            .joinToString(" · ")

    /** 아직 안 고른 필수 그룹 이름들 (비어 있으면 담을 수 있다) */
    fun missingRequired(item: MenuItem, ids: Collection<String>): List<String> =
        item.optionGroups
            .filter { g -> g.required && g.choices.none { it.id in ids } }
            .map { it.name }

    data class ToggleResult(val ids: List<String>, val limitExceeded: Boolean = false)

    /**
     * 선택지를 눌렀을 때의 새 선택값.
     * - 복수 그룹: 토글. 최대 개수를 넘기려 하면 선택은 그대로 두고 `limitExceeded = true` (스낵바용)
     * - 단일 필수 그룹: 교체 (해제 불가)
     * - 단일 선택 그룹: 같은 걸 다시 누르면 해제, 다른 걸 누르면 교체
     * - 품절 선택지(아직 선택 안 된 것)는 눌러도 변화 없음
     */
    fun toggle(item: MenuItem, ids: Collection<String>, groupId: String, choiceId: String): ToggleResult {
        val unchanged = ToggleResult(ids.toList())
        val group = item.optionGroups.find { it.id == groupId } ?: return unchanged
        val choice = group.choices.find { it.id == choiceId } ?: return unchanged
        val had = choiceId in ids
        if (choice.soldOut && !had) return unchanged

        val inGroup = group.choices.map { it.id }
        val others = ids.filter { it !in inGroup }

        if (group.multi) {
            if (had) return ToggleResult(ids.filter { it != choiceId })
            val max = group.maxSelect
            if (max != null && ids.count { it in inGroup } >= max) return ToggleResult(ids.toList(), limitExceeded = true)
            return ToggleResult(ids + choiceId)
        }
        if (had) return ToggleResult(if (group.required) ids.toList() else others)
        return ToggleResult(others + choiceId)
    }

    /** RN 과 같은 모양(목록만 반환)의 편의 함수. */
    fun toggleChoice(item: MenuItem, ids: Collection<String>, groupId: String, choiceId: String): List<String> =
        toggle(item, ids, groupId, choiceId).ids
}
