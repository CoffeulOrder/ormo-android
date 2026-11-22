package com.ormo.domain.store

import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreInfoRulesTest {
    private val seller = SellerInfo("ORMO 카페", "홍길동", "123-45-67890", "2026-서울-0001", "서울시 어딘가", "02-000-0000")

    private fun info(
        notice: String? = "공지",
        hours: List<DayHours> = emptyList(),
        seller: SellerInfo = this.seller,
        location: String? = "범석관 1층",
    ) = StoreInfo("s1", "범석관 카페", location, hours, notice, seller)

    @Test fun `같은 시간이 이어지는 요일은 묶고 일요일이 쉬면 공휴일도 같이 안내`() {
        val weekday = listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        ).map { DayHours(it, LocalTime.of(8, 30), LocalTime.of(18, 0)) }
        val lines = StoreInfoRules.hoursLines(weekday + DayHours(DayOfWeek.SATURDAY, LocalTime.of(10, 0), LocalTime.of(15, 0)))
        assertEquals(
            listOf(InfoLine("월~금", "08:30~18:00"), InfoLine("토", "10:00~15:00"), InfoLine("일·공휴일", "쉬어요")),
            lines,
        )
    }

    @Test fun `데이터에 없는 요일이나 시간이 반쪽인 요일은 쉬는 날`() {
        val lines = StoreInfoRules.hoursLines(
            listOf(
                DayHours(DayOfWeek.MONDAY, LocalTime.of(8, 30), LocalTime.of(18, 0)),
                DayHours(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0), null),
            ),
        )
        assertEquals(
            listOf(InfoLine("월", "08:30~18:00"), InfoLine("화~일·공휴일", "쉬어요")),
            lines,
        )
    }

    @Test fun `일요일만 영업하면 공휴일 문구는 붙지 않는다`() {
        val lines = StoreInfoRules.hoursLines(listOf(DayHours(DayOfWeek.SUNDAY, LocalTime.of(10, 0), LocalTime.of(14, 0))))
        assertEquals(InfoLine("일", "10:00~14:00"), lines.last())
        assertEquals(InfoLine("월~토", "쉬어요"), lines.first())
    }

    @Test fun `공지가 없으면 묶음을 숨긴다`() {
        assertNull(StoreInfoRules.view(info(notice = null)).notice)
        assertNull(StoreInfoRules.view(info(notice = "  ")).notice)
        assertEquals("공지", StoreInfoRules.view(info()).notice)
    }

    @Test fun `통신판매업 신고번호가 없으면 준비 중`() {
        val noNumber = StoreInfoRules.sellerLines(seller.copy(mailOrderNumber = null))
        assertEquals("준비 중", noNumber.first { it.label == "통신판매업 신고번호" }.value)
        val blank = StoreInfoRules.sellerLines(seller.copy(mailOrderNumber = "  "))
        assertEquals("준비 중", blank.first { it.label == "통신판매업 신고번호" }.value)
        val ok = StoreInfoRules.sellerLines(seller)
        assertEquals("2026-서울-0001", ok.first { it.label == "통신판매업 신고번호" }.value)
    }

    @Test fun `판매자 정보는 Figma 순서의 6줄이고 빈 값은 대시`() {
        val lines = StoreInfoRules.sellerLines(seller.copy(representative = null, phone = ""))
        assertEquals(listOf("상호", "대표자", "사업자등록번호", "주소", "연락처", "통신판매업 신고번호"), lines.map { it.label })
        assertEquals("-", lines.first { it.label == "대표자" }.value)
        assertEquals("-", lines.first { it.label == "연락처" }.value)
    }

    @Test fun `필수 고지는 항상 포함된다`() {
        val v = StoreInfoRules.view(info(notice = null, location = null))
        assertTrue(v.disclosure.startsWith("ORMO는 통신판매중개자로"))
        assertTrue(v.disclosure.endsWith("책임은 판매자에게 있어요."))
        assertNull(v.location)
    }
}
