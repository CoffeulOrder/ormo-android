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

    @Test fun `운영시간은 월부터 일까지 7줄 - 없는 요일은 휴무`() {
        val lines = StoreInfoRules.hoursLines(
            listOf(
                DayHours(DayOfWeek.SUNDAY, null, null),
                DayHours(DayOfWeek.MONDAY, LocalTime.of(8, 30), LocalTime.of(18, 0)),
                DayHours(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0), null), // 한쪽만 있으면 휴무
            ),
        )
        assertEquals(7, lines.size)
        assertEquals(listOf("월", "화", "수", "목", "금", "토", "일"), lines.map { it.label })
        assertEquals("08:30 ~ 18:00", lines[0].value)
        assertEquals("휴무", lines[1].value)
        assertEquals("휴무", lines[2].value)
        assertEquals("휴무", lines[6].value)
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

    @Test fun `판매자 정보는 6줄이고 빈 값은 대시`() {
        val lines = StoreInfoRules.sellerLines(seller.copy(representative = null, phone = ""))
        assertEquals(6, lines.size)
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
