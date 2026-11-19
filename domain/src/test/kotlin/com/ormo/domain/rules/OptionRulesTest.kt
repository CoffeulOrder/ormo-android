package com.ormo.domain.rules

import com.ormo.domain.Fixtures
import com.ormo.domain.Fixtures.americano
import com.ormo.domain.model.OptionChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OptionRulesTest {

    // ---- defaultChoiceIds ----

    @Test fun `필수 그룹마다 첫 선택지가 기본값`() {
        assertEquals(listOf("hot"), OptionRules.defaultChoiceIds(americano))
    }

    @Test fun `기본값은 품절이 아닌 첫 선택지`() {
        val item = americano.copy(
            optionGroups = listOf(
                Fixtures.temp.copy(
                    choices = listOf(OptionChoice("hot", "HOT", 0, soldOut = true), OptionChoice("ice", "ICE", 0)),
                ),
            ),
        )
        assertEquals(listOf("ice"), OptionRules.defaultChoiceIds(item))
    }

    @Test fun `옵션 없는 메뉴는 기본값이 없다`() {
        assertEquals(emptyList(), OptionRules.defaultChoiceIds(Fixtures.plain("p")))
        assertFalse(OptionRules.hasOptions(Fixtures.plain("p")))
        assertTrue(OptionRules.hasOptions(americano))
    }

    // ---- normalizeChoiceIds ----

    @Test fun `단일 그룹에 둘이 들어오면 선택지 순서상 첫 번째만`() {
        val r = OptionRules.normalizeChoiceIds(americano, listOf("ice", "hot"))
        assertEquals(listOf("hot"), r.ids)
        assertTrue(r.adjusted)
    }

    @Test fun `없는 선택지는 버린다`() {
        val r = OptionRules.normalizeChoiceIds(americano, listOf("ghost", "ice"))
        assertEquals(listOf("ice"), r.ids)
        assertTrue(r.adjusted)
    }

    @Test fun `필수 그룹이 비면 기본값으로 채운다`() {
        val r = OptionRules.normalizeChoiceIds(americano, emptyList())
        assertEquals(listOf("hot"), r.ids)
        assertTrue(r.adjusted)
    }

    @Test fun `그룹 순서대로 정렬하되 순서만 바뀐 것은 adjusted 가 아니다`() {
        val r = OptionRules.normalizeChoiceIds(americano, listOf("syrup", "shot", "ice"))
        assertEquals(listOf("ice", "shot", "syrup"), r.ids)
        assertFalse(r.adjusted)
    }

    @Test fun `이미 올바른 선택값은 그대로`() {
        val r = OptionRules.normalizeChoiceIds(americano, listOf("hot", "l", "shot"))
        assertEquals(listOf("hot", "l", "shot"), r.ids)
        assertFalse(r.adjusted)
    }

    @Test fun `최대 선택 개수를 넘으면 앞에서부터 자른다`() {
        val item = Fixtures.withExtraMax(2)
        val r = OptionRules.normalizeChoiceIds(item, listOf("hot", "shot", "syrup", "vanilla"))
        assertEquals(listOf("hot", "shot", "syrup"), r.ids)
        assertTrue(r.adjusted)
    }

    // ---- lineKey / price / summary ----

    @Test fun `같은 조합은 선택 순서와 무관하게 같은 키`() {
        assertEquals("m1|ice,shot", OptionRules.lineKey("m1", listOf("shot", "ice")))
        assertEquals(OptionRules.lineKey("m1", listOf("shot", "ice")), OptionRules.lineKey("m1", listOf("ice", "shot")))
        assertEquals("m1|", OptionRules.lineKey("m1", emptyList()))
    }

    @Test fun `단가 = 메뉴 가격 + 선택지 추가금`() {
        assertEquals(4000, OptionRules.unitPrice(americano, listOf("hot")))
        assertEquals(4800, OptionRules.unitPrice(americano, listOf("hot", "shot", "syrup")))
        assertEquals(800, OptionRules.optionsPrice(americano, listOf("shot", "syrup")))
    }

    @Test fun `옵션 요약은 그룹 순서의 이름`() {
        assertEquals("ICE · 샷 추가", OptionRules.optionSummary(americano, listOf("shot", "ice")))
        assertEquals("", OptionRules.optionSummary(americano, emptyList()))
    }

    @Test fun `안 고른 필수 그룹 이름`() {
        assertEquals(listOf("온도"), OptionRules.missingRequired(americano, emptyList()))
        assertEquals(emptyList(), OptionRules.missingRequired(americano, listOf("ice")))
    }

    // ---- toggle ----

    @Test fun `복수 그룹은 토글`() {
        assertEquals(listOf("hot", "shot"), OptionRules.toggleChoice(americano, listOf("hot"), "extra", "shot"))
        assertEquals(listOf("hot"), OptionRules.toggleChoice(americano, listOf("hot", "shot"), "extra", "shot"))
    }

    @Test fun `단일 필수 그룹은 교체하고 해제할 수 없다`() {
        assertEquals(listOf("shot", "ice"), OptionRules.toggleChoice(americano, listOf("hot", "shot"), "temp", "ice"))
        assertEquals(listOf("hot"), OptionRules.toggleChoice(americano, listOf("hot"), "temp", "hot"))
    }

    @Test fun `단일 선택 그룹은 다시 누르면 해제`() {
        assertEquals(listOf("hot"), OptionRules.toggleChoice(americano, listOf("hot", "l"), "size", "l"))
        assertEquals(listOf("hot", "l"), OptionRules.toggleChoice(americano, listOf("hot"), "size", "l"))
    }

    @Test fun `없는 그룹이나 선택지는 변화 없음`() {
        assertEquals(listOf("hot"), OptionRules.toggleChoice(americano, listOf("hot"), "nope", "x"))
        assertEquals(listOf("hot"), OptionRules.toggleChoice(americano, listOf("hot"), "extra", "nope"))
    }

    @Test fun `최대 개수를 넘기면 선택은 유지하고 limitExceeded`() {
        val item = Fixtures.withExtraMax(2)
        val r = OptionRules.toggle(item, listOf("hot", "shot", "syrup"), "extra", "vanilla")
        assertEquals(listOf("hot", "shot", "syrup"), r.ids)
        assertTrue(r.limitExceeded)
        // 해제는 언제나 가능
        val off = OptionRules.toggle(item, listOf("hot", "shot", "syrup"), "extra", "syrup")
        assertEquals(listOf("hot", "shot"), off.ids)
        assertFalse(off.limitExceeded)
    }

    @Test fun `품절 선택지는 고를 수 없다`() {
        val item = americano.copy(
            optionGroups = listOf(
                Fixtures.temp,
                Fixtures.extra.copy(
                    choices = listOf(OptionChoice("shot", "샷 추가", 500, soldOut = true)),
                ),
            ),
        )
        val r = OptionRules.toggle(item, listOf("hot"), "extra", "shot")
        assertEquals(listOf("hot"), r.ids)
        assertFalse(r.limitExceeded)
    }
}
