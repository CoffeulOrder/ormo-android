package com.ormo.domain.store

import java.time.DayOfWeek
import java.time.LocalTime

data class InfoLine(val label: String, val value: String)

/** 매장 정보 화면(1.2.1)에 그릴 내용 */
data class StoreInfoView(
    val location: String?,
    /** 월~일 순서 7줄 */
    val hours: List<InfoLine>,
    /** 공지가 없으면 묶음 전체를 숨긴다 → null */
    val notice: String?,
    val seller: List<InfoLine>,
    /** 하단 고지: 전자상거래법상 필수, 항상 보여줘야 한다 */
    val disclosure: String,
)

object StoreInfoRules {
    /** 전자상거래법상 필수 고지 (1.2.1). 문구를 바꾸려면 법무 확인. */
    const val DISCLOSURE = "ORMO는 통신판매중개자로 주문과 결제를 중개해요. 음료와 환불에 대한 책임은 판매자에게 있어요."

    const val MAIL_ORDER_PENDING = "준비 중"
    const val CLOSED_DAY = "쉬어요"

    private val DAY_ORDER = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
    )

    fun dayName(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "월"
        DayOfWeek.TUESDAY -> "화"
        DayOfWeek.WEDNESDAY -> "수"
        DayOfWeek.THURSDAY -> "목"
        DayOfWeek.FRIDAY -> "금"
        DayOfWeek.SATURDAY -> "토"
        DayOfWeek.SUNDAY -> "일"
    }

    private fun clock(t: LocalTime) = String.format(java.util.Locale.US, "%02d:%02d", t.hour, t.minute)

    /**
     * 요일별 운영시간 (Figma 1.2.1·1.1.2): **같은 시간이 이어지는 요일은 묶어서** "월~금  08:30~18:00", "토  10:00~15:00",
     * 쉬는 날은 "일·공휴일  쉬어요" (일요일이 쉬면 공휴일도 같이 안내). 데이터에 없는 요일은 쉬는 날로 본다.
     */
    fun hoursLines(weekly: List<DayHours>): List<InfoLine> {
        val perDay = DAY_ORDER.map { day ->
            val h = weekly.firstOrNull { it.day == day }
            day to (if (h == null || h.closed) CLOSED_DAY else "${clock(h.open!!)}~${clock(h.close!!)}")
        }
        val out = ArrayList<InfoLine>()
        var i = 0
        while (i < perDay.size) {
            var j = i
            while (j + 1 < perDay.size && perDay[j + 1].second == perDay[i].second) j++
            val first = dayName(perDay[i].first)
            var label = if (j > i) "$first~${dayName(perDay[j].first)}" else first
            // 일요일이 쉬면 공휴일도 같이 (월~금 평일 근무 + 일·공휴일 휴무 형태)
            if (perDay[j].first == DayOfWeek.SUNDAY && perDay[i].second == CLOSED_DAY) label += "·공휴일"
            out += InfoLine(label, perDay[i].second)
            i = j + 1
        }
        return out
    }

    /** 판매자 정보 6줄 (Figma 순서). 값이 없는 줄은 '-' 로, 통신판매업 신고번호만 '준비 중' (1.2.1). */
    fun sellerLines(seller: SellerInfo): List<InfoLine> = listOf(
        InfoLine("상호", seller.businessName.orDash()),
        InfoLine("대표자", seller.representative.orDash()),
        InfoLine("사업자등록번호", seller.businessRegistrationNumber.orDash()),
        InfoLine("주소", seller.address.orDash()),
        InfoLine("연락처", seller.phone.orDash()),
        InfoLine("통신판매업 신고번호", seller.mailOrderNumber?.trim()?.takeIf { it.isNotEmpty() } ?: MAIL_ORDER_PENDING),
    )

    fun view(info: StoreInfo): StoreInfoView = StoreInfoView(
        location = info.location?.trim()?.takeIf { it.isNotEmpty() },
        hours = hoursLines(info.weeklyHours),
        notice = info.notice?.trim()?.takeIf { it.isNotEmpty() },
        seller = sellerLines(info.seller),
        disclosure = DISCLOSURE,
    )

    private fun String?.orDash(): String = this?.trim()?.takeIf { it.isNotEmpty() } ?: "-"
}
