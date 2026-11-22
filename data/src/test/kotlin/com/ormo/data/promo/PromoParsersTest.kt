package com.ormo.data.promo

import com.ormo.data.remote.AppError
import com.ormo.domain.promo.BannerLink
import com.ormo.domain.promo.Voucher
import com.ormo.domain.promo.VoucherStatus
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class PromoParsersTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    // ---- 쿠폰 ----
    @Test fun `쿠폰 - 교환권과 금액권을 읽고 안내 문구를 받는다`() {
        val r = PromoParsers.voucherList(j("""{"notice":"유의사항","vouchers":[
            {"id":"v1","kind":"MENU_EXCHANGE","title":"아메리카노 교환권","status":"AVAILABLE","expiresAt":"2026-12-31T14:59:59Z","cafeId":"c1","cafeName":"범석관","menuId":"m1","menuName":"아메리카노"},
            {"id":"v2","kind":"AMOUNT","title":"3,000원","status":"USED","expiresAt":"2026-12-31T14:59:59Z","amount":3000,"minOrderAmount":10000,"cafeId":"c1","cafeName":"범석관"}
        ]}"""))
        assertEquals("유의사항", r.notice)
        val ex = assertIs<Voucher.MenuExchange>(r.vouchers[0])
        assertEquals("m1", ex.menuId)
        val am = assertIs<Voucher.Amount>(r.vouchers[1])
        assertEquals(VoucherStatus.USED, am.status)
        assertEquals(10_000, am.minOrderAmount)
    }

    @Test fun `쿠폰 - 모르는 종류는 건너뛰고 나머지는 보여준다`() {
        val r = PromoParsers.voucherList(j("""{"vouchers":[
            {"id":"x","kind":"FREE_SHIPPING","title":"?"},
            {"id":"v2","kind":"AMOUNT","title":"3,000원","status":"AVAILABLE","expiresAt":"2026-12-31T00:00:00Z","amount":3000}
        ]}"""))
        assertEquals(listOf("v2"), r.vouchers.map { it.id })
    }

    @Test fun `쿠폰 - 금액이 0 이하거나 날짜가 틀리면 스키마 오류`() {
        fun amount(extra: String) = """{"vouchers":[{"id":"v","kind":"AMOUNT","title":"t","status":"AVAILABLE",$extra}]}"""
        assertFailsWith<AppError.Schema> { PromoParsers.voucherList(j(amount(""""expiresAt":"2026-12-31T00:00:00Z","amount":0"""))) }
        assertFailsWith<AppError.Schema> { PromoParsers.voucherList(j(amount(""""expiresAt":"내일","amount":100"""))) }
        assertFailsWith<AppError.Schema> { PromoParsers.voucherList(j("""{"vouchers":"none"}""")) }
        assertFailsWith<AppError.Schema> { PromoParsers.voucherList(null) }
    }

    @Test fun `쿠폰 - 최소 주문 금액 0 은 없는 것으로`() {
        val r = PromoParsers.voucherList(j("""{"vouchers":[{"id":"v","kind":"AMOUNT","title":"t","status":"AVAILABLE","expiresAt":"2026-12-31T00:00:00Z","amount":500,"minOrderAmount":0}]}"""))
        assertNull((r.vouchers.single() as Voucher.Amount).minOrderAmount)
    }

    // ---- 배너 ----
    @Test fun `배너 - 깨진 하나는 건너뛰고 링크는 허용 목록만`() {
        val list = PromoParsers.banners(j("""{"banners":[
            {"id":"b1","title":"가을 이벤트","imageUrl":"https://i/1.png","priority":5,"link":{"type":"route","path":"/orders"}},
            {"id":"b2","imageUrl":"https://i/2.png","link":{"type":"route","path":"/admin"}},
            {"id":"b3"},
            "broken",
            {"id":"b4","imageUrl":"https://i/4.png","link":{"type":"url","url":"https://ormo.example/e"}},
            {"id":"b5","imageUrl":"https://i/5.png","link":{"type":"url","url":"http://insecure"}}
        ]}"""))
        assertEquals(listOf("b1", "b2", "b4", "b5"), list.map { it.id })
        assertEquals(BannerLink.Route("/orders"), list[0].link)
        assertNull(list[1].link) // 허용되지 않은 경로 → 링크 없음, 팝업은 뜸
        assertEquals("이벤트", list[1].title) // 제목 없으면 기본
        assertEquals(0, list[1].priority)
        assertEquals(BannerLink.Url("https://ormo.example/e"), list[2].link)
        assertNull(list[3].link)
    }

    @Test fun `배너 - 목록 모양이 틀리면 스키마 오류`() {
        assertFailsWith<AppError.Schema> { PromoParsers.banners(j("""{"banners":{}}""")) }
    }

    // ---- 이벤트 ----
    @Test fun `이벤트 - 날짜가 틀린 하나는 건너뛴다`() {
        val list = PromoParsers.events(j("""{"events":[
            {"id":"e1","title":"가을","startsAt":"2026-11-01T00:00:00Z","endsAt":"2026-11-30T00:00:00Z","summary":"요약"},
            {"id":"e2","title":"?","startsAt":"어제","endsAt":"2026-11-30T00:00:00Z"}
        ]}"""))
        assertEquals(listOf("e1"), list.map { it.id })
        assertEquals("요약", list.single().summary)
    }

    // ---- 내 메뉴 ----
    @Test fun `내 메뉴 - 세트를 읽는다`() {
        val sets = PromoParsers.myMenuSets(j("""{"sets":[{"cafeId":"c1","items":[{"menuId":"m1","qty":2,"options":["ice","shot"]}]}]}"""))
        assertEquals(2, sets.single().items.single().qty)
        assertEquals(listOf("ice", "shot"), sets.single().items.single().options)
    }

    @Test fun `내 메뉴 - 수량이 1 미만이거나 옵션이 문자열 배열이 아니면 스키마 오류`() {
        fun item(s: String) = """{"sets":[{"cafeId":"c1","items":[$s]}]}"""
        assertFailsWith<AppError.Schema> { PromoParsers.myMenuSets(j(item("""{"menuId":"m","qty":0,"options":[]}"""))) }
        assertFailsWith<AppError.Schema> { PromoParsers.myMenuSets(j(item("""{"menuId":"m","qty":1,"options":[1]}"""))) }
        assertFailsWith<AppError.Schema> { PromoParsers.myMenuSets(j(item("""{"qty":1,"options":[]}"""))) }
        assertFailsWith<AppError.Schema> { PromoParsers.myMenuSets(j("""{"sets":[{"items":[]}]}""")) }
    }
}
