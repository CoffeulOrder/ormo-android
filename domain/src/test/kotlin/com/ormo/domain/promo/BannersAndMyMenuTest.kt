package com.ormo.domain.promo

import com.ormo.core.BetaFeature
import com.ormo.core.FeatureFlags
import com.ormo.domain.model.Cafe
import com.ormo.domain.model.MenuItem
import com.ormo.domain.model.OptionChoice
import com.ormo.domain.model.OptionGroup
import com.ormo.domain.rules.ReferralRules
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureFlagsTest {
    @Test fun `베타는 전부 꺼져 있다`() {
        BetaFeature.entries.forEach { assertFalse(FeatureFlags.BETA.allows(it), it.name) }
    }

    @Test fun `켠 것만 켜진다`() {
        val f = FeatureFlags(coupons = true)
        assertTrue(f.allows(BetaFeature.COUPONS))
        assertFalse(f.allows(BetaFeature.EVENTS))
        assertFalse(f.allows(BetaFeature.MY_MENU))
        assertFalse(f.allows(BetaFeature.REFERRAL))
    }
}

class BannerRulesTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(h: Int, m: Int = 0, day: Int = 19): Instant = LocalDateTime.of(2026, 11, day, h, m).toInstant(ZoneOffset.ofHours(9))

    @Test fun `링크 - 허용 목록의 경로와 https 만`() {
        assertEquals(BannerLink.Route("/orders"), BannerRules.parseLink("route", "/orders"))
        assertEquals(BannerLink.Url("https://ormo.example/e"), BannerRules.parseLink("url", "https://ormo.example/e"))
        assertNull(BannerRules.parseLink("route", "/admin")) // 허용 목록 밖
        assertNull(BannerRules.parseLink("url", "http://ormo.example")) // https 아님
        assertNull(BannerRules.parseLink("url", "javascript:alert(1)"))
        assertNull(BannerRules.parseLink("route", null))
        assertNull(BannerRules.parseLink("deeplink", "/menu"))
        assertNull(BannerRules.parseLink(null, null))
    }

    @Test fun `우선순위 내림차순 - 같으면 받은 순서`() {
        fun b(id: String, p: Int) = Banner(id, id, "https://i/$id", p)
        assertEquals(listOf("c", "a", "b"), BannerRules.byPriority(listOf(b("a", 1), b("b", 1), b("c", 5))).map { it.id })
    }

    @Test fun `다음 자정은 기기 지역 시간 기준`() {
        assertEquals(at(0, 0, 20), BannerRules.nextMidnight(at(13, 0), seoul))
        assertEquals(at(0, 0, 20), BannerRules.nextMidnight(at(23, 59), seoul))
        assertEquals(at(0, 0, 21), BannerRules.nextMidnight(at(0, 0, 20), seoul)) // 정각이면 다음 날 자정
    }

    @Test fun `오늘 하루 보지 않기 - 자정 전까지만 숨김`() {
        val until = BannerRules.nextMidnight(at(13), seoul).toEpochMilli().toString()
        assertTrue(BannerRules.isHidden(until, at(13)))
        assertTrue(BannerRules.isHidden(until, at(23, 59)))
        assertFalse(BannerRules.isHidden(until, at(0, 0, 20))) // 자정이 지나면 다시 뜬다
    }

    @Test fun `숨김 값이 깨졌거나 너무 먼 미래면 숨기지 않는다`() {
        assertFalse(BannerRules.isHidden(null, at(13)))
        assertFalse(BannerRules.isHidden("abc", at(13)))
        assertFalse(BannerRules.isHidden("", at(13)))
        // 기기 시계를 앞당겼다 되돌린 경우: 25시간보다 먼 미래
        assertFalse(BannerRules.isHidden(at(13, 0, 25).toEpochMilli().toString(), at(13)))
        // 25시간 이내는 인정 (경계)
        assertTrue(BannerRules.isHidden((at(13).toEpochMilli() + BannerRules.MAX_HIDE_MS).toString(), at(13)))
    }

    @Test fun `숨김 키는 배너마다 독립`() {
        assertEquals("popup:u1:b1:hideUntil", BannerRules.hideKey("u1", "b1"))
        assertFalse(BannerRules.hideKey("u1", "b1") == BannerRules.hideKey("u1", "b2"))
        assertFalse(BannerRules.hideKey("u1", "b1") == BannerRules.hideKey("u2", "b1"))
    }
}

class MyMenuRulesTest {
    private fun group(id: String, vararg choices: String, required: Boolean = true, multi: Boolean = false) =
        OptionGroup(id, id, required, multi, choices.map { OptionChoice(it, it, if (it == "shot") 500 else 0, false) })

    private val americano = MenuItem("m1", "아메리카노", "", 3500, "커피", optionGroups = listOf(group("temp", "hot", "ice"), group("extra", "shot", required = false, multi = true)))
    private val tea = MenuItem("m2", "얼그레이", "", 4000, "티")
    private val cafe = Cafe("c1", "범석관 카페", "B", listOf(americano, tea))

    @Test fun `현재 메뉴에 맞춰 풀고 없는 메뉴는 센다`() {
        val r = MyMenuRules.resolve(cafe, listOf(MyMenuItem("m1", 2, listOf("ice", "shot")), MyMenuItem("m2", 1, emptyList()), MyMenuItem("gone", 1, emptyList())))
        assertEquals(2, r.lines.size)
        assertEquals(1, r.missing)
        assertEquals(listOf("ice", "shot"), r.lines[0].choiceIds)
        assertEquals(2 * (3500 + 500) + 4000, r.estimatedTotal)
    }

    @Test fun `옵션이 바뀌었으면 고치고 메뉴 이름을 알린다 - 필수 그룹은 첫 선택지`() {
        val r = MyMenuRules.resolve(cafe, listOf(MyMenuItem("m1", 1, listOf("removed-choice"))))
        assertEquals(listOf("hot"), r.lines.single().choiceIds)
        assertEquals(listOf("아메리카노"), r.adjusted)
    }

    @Test fun `고친 결과 같은 줄이 되면 수량을 합친다 - 이름은 한 번만`() {
        val r = MyMenuRules.resolve(cafe, listOf(MyMenuItem("m1", 1, listOf("x")), MyMenuItem("m1", 2, listOf("y"))))
        assertEquals(1, r.lines.size)
        assertEquals(3, r.lines.single().qty)
        assertEquals(listOf("아메리카노"), r.adjusted)
    }

    @Test fun `세트 한도 - 줄 10개 수량 9개`() {
        fun items(n: Int, qty: Int = 1) = List(n) { MyMenuItem("m$it", qty, emptyList()) }
        assertTrue(MyMenuRules.isValid(items(10)))
        assertFalse(MyMenuRules.isValid(items(11)))
        assertTrue(MyMenuRules.isValid(items(1, 9)))
        assertFalse(MyMenuRules.isValid(items(1, 10)))
        assertFalse(MyMenuRules.isValid(items(1, 0)))
        assertFalse(MyMenuRules.isValid(listOf(MyMenuItem(" ", 1, emptyList()))))
    }
}

class ReferralRulesTest {
    @Test fun `영문 숫자 4~12자`() {
        assertTrue(ReferralRules.isValid("ORMO2026"))
        assertTrue(ReferralRules.isValid("abcd"))
        assertTrue(ReferralRules.isValid("abcdefgh1234"))
        assertFalse(ReferralRules.isValid("abc"))
        assertFalse(ReferralRules.isValid("abcdefgh12345"))
        assertFalse(ReferralRules.isValid("ab-cd"))
        assertFalse(ReferralRules.isValid("가나다라"))
    }

    @Test fun `입력 중에는 영문 숫자만 남기고 빈 칸은 오류가 아니다`() {
        assertEquals("ORMO2026", ReferralRules.sanitize("OR-MO 2026!"))
        assertFalse(ReferralRules.isBad(""))
        assertFalse(ReferralRules.isBad("   "))
        assertTrue(ReferralRules.isBad("ab"))
        assertFalse(ReferralRules.isBad(" ORMO2026 "))
    }

    @Test fun `요청에 실을 값 - 플래그가 꺼져 있거나 비면 보내지 않는다`() {
        assertNull(ReferralRules.requestValue("ORMO2026", enabled = false))
        assertNull(ReferralRules.requestValue("  ", enabled = true))
        assertEquals("ORMO2026", ReferralRules.requestValue(" ORMO2026 ", enabled = true))
    }
}
