package com.ormo.customer.fake

import com.ormo.data.menu.MenuApi
import com.ormo.data.store.StoreDirectory
import com.ormo.domain.model.Cafe
import com.ormo.domain.model.MenuItem
import com.ormo.domain.model.MenuPayload
import com.ormo.domain.model.OptionChoice
import com.ormo.domain.model.OptionGroup
import com.ormo.domain.store.DayHours
import com.ormo.domain.store.SellerInfo
import com.ormo.domain.store.Store
import com.ormo.domain.store.StoreInfo
import com.ormo.domain.store.StoreStatus
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 개발용 가짜 매장·메뉴 데이터 (BE 가 준비되면 지운다 — [FakeBackend] 와 같은 이유).
 *
 * 매장 4곳: 범석관 카페(영업 중, 메뉴 12개) / 학생회관 카페(잠시 쉼, 30분 뒤 재개) / 도서관 카페(영업 종료, 내일 08:30) /
 * 신관 카페(메뉴 없음 → 목록에서 제외되지만 기억한 매장으로 들어오면 '아직 메뉴가 없어요').
 */
object FakeCatalog {
    private val temp = OptionGroup(
        "temp", "온도", required = true, multi = false,
        choices = listOf(OptionChoice("hot", "HOT", 0), OptionChoice("ice", "ICE", 0)),
    )
    private val size = OptionGroup(
        "size", "사이즈", required = true, multi = false,
        choices = listOf(OptionChoice("reg", "레귤러", 0), OptionChoice("large", "라지", 500)),
    )
    private val shot = OptionGroup(
        "shot", "샷", required = false, multi = true, maxSelect = 2,
        choices = listOf(OptionChoice("shot1", "샷 추가", 500), OptionChoice("shot2", "연하게", 0), OptionChoice("shot3", "디카페인", 600)),
    )

    private fun m(id: String, name: String, desc: String, price: Int, cat: String, groups: List<OptionGroup> = emptyList(), soldOut: Boolean = false) =
        MenuItem(id, name, desc, price, cat, soldOut, optionGroups = groups)

    private val c1 = listOf(
        m("c1-americano", "아메리카노", "깔끔하고 진한 에스프레소에 물을 더한 기본 커피", 3500, "커피", listOf(temp, size, shot)),
        m("c1-latte", "카페라떼", "부드러운 우유와 에스프레소가 어우러진 라떼", 4300, "커피", listOf(temp, size, shot)),
        m("c1-vanilla", "바닐라라떼", "달콤한 바닐라 시럽을 넣은 라떼. 달콤하고 부드러워서 누구나 좋아해요", 4800, "커피", listOf(temp, size, shot)),
        m("c1-coldbrew", "콜드브루", "12시간 저온 추출한 깊고 부드러운 커피", 4500, "커피", listOf(size), soldOut = true),
        m("c1-earl", "얼그레이", "향긋한 베르가못 향의 홍차", 4000, "티·음료", listOf(temp)),
        m("c1-lemon", "레몬티", "상큼한 레몬과 따뜻한 차", 4200, "티·음료", listOf(temp)),
        m("c1-strawberry", "딸기스무디", "생딸기를 갈아 만든 시원한 스무디", 5500, "티·음료"),
        m("c1-choco", "초코라떼", "진한 초콜릿과 우유", 4800, "티·음료", listOf(temp)),
        m("c1-cookie", "초코칩 쿠키", "겉은 바삭 속은 촉촉한 수제 쿠키", 2500, "디저트"),
        m("c1-cake", "치즈케이크", "꾸덕한 크림치즈 케이크 한 조각", 5800, "디저트"),
        m("c1-scone", "플레인 스콘", "버터 향 가득한 스콘", 3200, "디저트"),
        m("c1-brownie", "브라우니", "진한 초콜릿 브라우니", 3500, "디저트"),
    )
    private val c2 = listOf(
        m("c2-americano", "아메리카노", "기본 커피", 3000, "커피", listOf(temp)),
        m("c2-latte", "카페라떼", "우유가 들어간 라떼", 3800, "커피", listOf(temp)),
        m("c2-green", "녹차라떼", "진한 말차 라떼", 4300, "티·음료", listOf(temp)),
        m("c2-sandwich", "샌드위치", "햄치즈 샌드위치", 4500, "디저트"),
    )
    private val c3 = listOf(
        m("c3-americano", "아메리카노", "기본 커피", 3200, "커피", listOf(temp)),
        m("c3-toast", "토스트", "달걀 토스트", 3500, "디저트"),
    )

    val payload = MenuPayload(
        version = 1,
        cafes = listOf(
            Cafe("c1", "범석관 카페", "A", c1),
            Cafe("c2", "학생회관 카페", "B", c2),
            Cafe("c3", "도서관 카페", "C", c3),
        ),
    )

    /** 많이 찾는 메뉴 (최근 7일 주문 많은 순 — 서버가 줄 값 대신) */
    val popularIds = mapOf("c1" to listOf("c1-americano", "c1-latte", "c1-cookie", "c1-vanilla", "c1-cake"))

    /** 매장에서 쓰는 '최근 주문한 메뉴' (주문 이력은 M5 — 지금은 비어 있음 → 최근 주문 탭이 없다) */
    val recentOrdered: List<String> = emptyList()
}

class FakeMenuApi(private val latencyMs: Long = 400) : MenuApi {
    override suspend fun fetchVersion(schoolId: String): Long {
        delay(latencyMs)
        return FakeCatalog.payload.version
    }

    override suspend fun fetchMenus(schoolId: String): MenuPayload {
        delay(latencyMs)
        return FakeCatalog.payload
    }

    override suspend fun fetchSoldOutIds(cafeId: String): List<String> {
        delay(latencyMs / 2)
        // 품절은 캐시하지 않고 따로 받는다 (메뉴 데이터의 품절 표시는 저장 때 빠지므로, 여기서 알려줘야 앱을 다시 켜도 보인다)
        return FakeCatalog.payload.cafes.firstOrNull { it.id == cafeId }?.menus?.filter { it.soldOut }?.map { it.id }.orEmpty()
    }
}

class FakeStoreDirectory(private val latencyMs: Long = 400) : StoreDirectory {
    override suspend fun fetchStores(): List<Store> {
        delay(latencyMs)
        val now = LocalDateTime.now()
        return listOf(
            Store("c1", "범석관 카페", StoreStatus.Open, menuCount = 12, waitingCount = 3),
            Store("c2", "학생회관 카페", StoreStatus.Break(now.plusMinutes(30)), menuCount = 4),
            Store("c3", "도서관 카페", StoreStatus.Closed(now.plusDays(1).withHour(8).withMinute(30).withSecond(0).withNano(0)), menuCount = 2),
            Store("c4", "신관 카페", StoreStatus.Open, menuCount = 0),
        )
    }

    override suspend fun fetchInfo(storeId: String): StoreInfo {
        delay(latencyMs)
        val weekday = (DayOfWeek.MONDAY..DayOfWeek.FRIDAY).map { DayHours(it, LocalTime.of(8, 30), LocalTime.of(18, 0)) }
        val saturday = DayHours(DayOfWeek.SATURDAY, LocalTime.of(10, 0), LocalTime.of(15, 0))
        val name = FakeCatalog.payload.cafes.firstOrNull { it.id == storeId }?.name ?: "신관 카페"
        return StoreInfo(
            storeId = storeId,
            name = name,
            location = "범석관 1층 로비",
            weeklyHours = weekday + saturday, // 일요일은 휴무
            notice = if (storeId == "c1") "12시~13시 사이에는 주문이 몰려 조금 늦을 수 있어요" else null,
            seller = SellerInfo(
                businessName = "ORMO 카페",
                representative = "홍길동",
                businessRegistrationNumber = "123-45-67890",
                mailOrderNumber = if (storeId == "c1") "2026-경기성남-0001" else null, // 없으면 '준비 중'
                address = "경기도 성남시 수정구 산성대로 553",
                phone = "031-000-0000",
            ),
        )
    }
}

private operator fun DayOfWeek.rangeTo(other: DayOfWeek): List<DayOfWeek> =
    DayOfWeek.entries.filter { it.ordinal in this.ordinal..other.ordinal }
