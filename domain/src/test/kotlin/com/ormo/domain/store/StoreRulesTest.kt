package com.ormo.domain.store

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class StoreRulesTest {
    private val now = LocalDateTime.of(2026, 11, 19, 13, 0)

    private fun store(id: String, menus: Int = 5, status: StoreStatus = StoreStatus.Open, lat: Double? = null, lon: Double? = null) =
        Store(id, "매장$id", status, menus, latitude = lat, longitude = lon)

    private fun msg(status: StoreStatus, at: LocalDateTime = now) =
        (StoreRules.availability(status, at) as OrderAvailability.Unavailable).message

    // ---- 목록 ----
    @Test fun `메뉴가 없는 매장은 목록에서 빠지고 순서는 유지된다`() {
        val list = StoreRules.listable(listOf(store("a"), store("b", menus = 0), store("c")))
        assertEquals(listOf("a", "c"), list.map { it.id })
    }

    @Test fun `영업 종료 매장만 흐리게`() {
        assertEquals(false, StoreRules.dimmed(StoreStatus.Open))
        assertEquals(false, StoreRules.dimmed(StoreStatus.Break(null)))
        assertEquals(true, StoreRules.dimmed(StoreStatus.Closed(null)))
    }

    @Test fun `상태 라벨`() {
        assertEquals(StatusLabel("영업 중"), StoreRules.statusLabel(StoreStatus.Open, now))
        assertEquals(StatusLabel("영업 종료"), StoreRules.statusLabel(StoreStatus.Closed(now.plusDays(1)), now))
        assertEquals(
            StatusLabel("준비 중", "14:30에 다시 열어요"),
            StoreRules.statusLabel(StoreStatus.Break(now.withHour(14).withMinute(30)), now),
        )
        assertEquals(StatusLabel("준비 중"), StoreRules.statusLabel(StoreStatus.Break(null), now))
        // 이미 지난 재개 시각은 보여주지 않는다 (서버가 아직 상태를 안 바꾼 경우)
        assertEquals(StatusLabel("준비 중"), StoreRules.statusLabel(StoreStatus.Break(now.minusMinutes(5)), now))
    }

    // ---- 주문 가능 여부 (2.1.2) ----
    @Test fun `영업 중이면 주문 가능`() {
        assertEquals(OrderAvailability.Available, StoreRules.availability(StoreStatus.Open, now))
    }

    @Test fun `잠시 쉼 - 같은 날 다시 여는 시각`() {
        assertEquals("14:30부터 다시 주문할 수 있어요", msg(StoreStatus.Break(now.withHour(14).withMinute(30))))
        assertIs<OrderAvailability.Unavailable>(StoreRules.availability(StoreStatus.Break(now.plusHours(1)), now))
    }

    @Test fun `영업 종료 - 내일 여는 시각`() {
        val next = now.plusDays(1).withHour(8).withMinute(30)
        assertEquals("내일 08:30부터 주문할 수 있어요", msg(StoreStatus.Closed(next)))
        assertEquals(UnavailableKind.CLOSED, (StoreRules.availability(StoreStatus.Closed(next), now) as OrderAvailability.Unavailable).kind)
    }

    @Test fun `영업 종료 - 같은 날이나 며칠 뒤`() {
        assertEquals("15:00부터 주문할 수 있어요", msg(StoreStatus.Closed(now.withHour(15).withMinute(0))))
        assertEquals("11월 23일 08:30부터 주문할 수 있어요", msg(StoreStatus.Closed(now.plusDays(4).withHour(8).withMinute(30))))
    }

    @Test fun `자정을 넘기면 내일 로 바뀐다`() {
        val late = LocalDateTime.of(2026, 11, 19, 23, 50)
        assertEquals("내일 00:10부터 다시 주문할 수 있어요", msg(StoreStatus.Break(late.plusMinutes(20)), late))
    }

    @Test fun `시각을 모르거나 이미 지났으면 잠시 후 다시 열어요`() {
        assertEquals("잠시 후 다시 열어요", msg(StoreStatus.Break(null)))
        assertEquals("잠시 후 다시 열어요", msg(StoreStatus.Break(now.minusMinutes(1))))
        assertEquals("잠시 후 다시 열어요", msg(StoreStatus.Break(now)))
        assertEquals("잠시 후 다시 열어요", msg(StoreStatus.Closed(null)))
    }

    @Test fun `보는 동안 상태가 바뀌면 바로 반영된다 - 새 상태로 다시 계산`() {
        val brk = StoreStatus.Break(now.plusHours(1))
        assertIs<OrderAvailability.Unavailable>(StoreRules.availability(brk, now))
        assertEquals(OrderAvailability.Available, StoreRules.availability(StoreStatus.Open, now.plusMinutes(1)))
    }

    // ---- 매장 변경 (1.1.2) ----
    @Test fun `다른 매장 메뉴가 담겨 있으면 경고`() {
        val d = StoreRules.decideStoreChange(cartStoreId = "a", cartItemCount = 3, targetStoreId = "b")
        assertEquals(StoreChangeDecision.ConfirmClearCart(3), d)
        assertEquals("매장을 변경하면 장바구니의 메뉴가 삭제돼요", (d as StoreChangeDecision.ConfirmClearCart).title)
        assertEquals("담아 둔 메뉴 3개가 사라져요", d.detail)
    }

    @Test fun `같은 매장이거나 장바구니가 비었으면 경고 없음`() {
        assertEquals(StoreChangeDecision.Proceed, StoreRules.decideStoreChange("a", 3, "a"))
        assertEquals(StoreChangeDecision.Proceed, StoreRules.decideStoreChange("a", 0, "b"))
        assertEquals(StoreChangeDecision.Proceed, StoreRules.decideStoreChange(null, 0, "b"))
    }

    // ---- 진입 ----
    @Test fun `기억한 매장이 있으면 바로 메뉴로`() {
        val all = listOf(store("a"), store("b"))
        assertEquals(StoreEntry.MenuHome("b"), StoreRules.resolveEntry("b", all))
    }

    @Test fun `기억한 매장이 없거나 사라졌으면 매장 선택`() {
        val all = listOf(store("a"))
        assertEquals(StoreEntry.StoreSelect(false), StoreRules.resolveEntry(null, all))
        assertEquals(StoreEntry.StoreSelect(false), StoreRules.resolveEntry("zzz", all))
    }

    @Test fun `가입 직후 첫 진입은 뒤로가기를 숨긴다`() {
        assertEquals(StoreEntry.StoreSelect(true), StoreRules.resolveEntry(null, listOf(store("a")), firstEntryAfterSignup = true))
    }

    @Test fun `기억한 매장이 메뉴 0개여도 메뉴 홈으로 간다 - 빈 상태 안내용`() {
        val all = listOf(store("a", menus = 0))
        assertEquals(StoreEntry.MenuHome("a"), StoreRules.resolveEntry("a", all))
    }

    // ---- 거리 ----
    @Test fun `같은 위치는 0m`() {
        assertEquals(0, StoreRules.distanceMeters(37.0, 127.0, 37.0, 127.0))
    }

    @Test fun `위도 0_001도는 약 111m`() {
        val m = StoreRules.distanceMeters(37.000, 127.0, 37.001, 127.0)
        assertEquals(111, m)
    }

    @Test fun `거리와 도보 시간 라벨`() {
        val s = store("a", lat = 37.001, lon = 127.0)
        assertEquals("111m · 도보 2분", StoreRules.distanceLabel(s, 37.000, 127.0))
        val far = store("b", lat = 37.0135, lon = 127.0) // 약 1.5km
        assertEquals("1.5km · 도보 19분", StoreRules.distanceLabel(far, 37.0, 127.0))
        val same = store("c", lat = 37.0, lon = 127.0)
        assertEquals("0m · 도보 1분", StoreRules.distanceLabel(same, 37.0, 127.0)) // 최소 1분
    }

    @Test fun `위치 권한이 없거나 매장 위치를 모르면 거리를 안 보인다`() {
        val s = store("a", lat = 37.001, lon = 127.0)
        assertNull(StoreRules.distanceLabel(s, null, null))
        assertNull(StoreRules.distanceLabel(s, 37.0, null))
        assertNull(StoreRules.distanceLabel(store("b"), 37.0, 127.0))
    }
}
