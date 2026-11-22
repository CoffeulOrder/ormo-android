package com.ormo.domain.promo

import java.time.Instant
import java.time.ZoneOffset
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VouchersTest {
    private val now: Instant = LocalDateTime.of(2026, 11, 19, 13, 0).toInstant(ZoneOffset.ofHours(9))
    private val future: Instant = now.plusSeconds(10 * 86_400)

    private fun exchange(
        id: String = "v1", cafe: String = "c1", menu: String = "m1", status: VoucherStatus = VoucherStatus.AVAILABLE, expires: Instant = future, cafeName: String = "범석관 카페",
    ) = Voucher.MenuExchange(id, "아메리카노 교환권", status, expires, cafe, cafeName, menu, "아메리카노")

    private fun amount(id: String = "a1", amount: Int = 3000, min: Int? = null, cafe: String? = null, cafeName: String? = null, status: VoucherStatus = VoucherStatus.AVAILABLE, expires: Instant = future) =
        Voucher.Amount(id, "3,000원 금액권", status, expires, amount, min, cafe, cafeName)

    private fun ctx(cafe: String = "c1", menus: Set<String> = setOf("m1", "m2"), sold: Set<String> = emptySet(), cart: Set<String> = setOf("m1"), subtotal: Int = 5000) =
        CouponContext(cafe, menus, sold, cart, subtotal)

    private fun reason(u: Usability) = (u as Usability.No).reason

    // ---- 공통 ----
    @Test fun `쓸 수 없는 상태나 기간이 지난 쿠폰은 막는다`() {
        assertEquals("사용할 수 없는 쿠폰이에요", reason(CouponRules.usability(exchange(status = VoucherStatus.USED), ctx(), now)))
        assertEquals("사용할 수 없는 쿠폰이에요", reason(CouponRules.usability(amount(status = VoucherStatus.EXPIRED), ctx(), now)))
        assertEquals("유효기간이 지났어요", reason(CouponRules.usability(exchange(expires = now.minusSeconds(2 * 86_400)), ctx(), now)))
        assertEquals("유효기간이 지났어요", reason(CouponRules.usability(amount(expires = now.minusSeconds(86_400 + 1)), ctx(), now)))
    }

    @Test fun `오늘 만료되는 쿠폰은 쓸 수 있다 - 남은 일수 0`() {
        val today = exchange(expires = now.plusSeconds(3600))
        assertEquals(1, CouponRules.daysLeft(today.expiresAt, now)) // 올림: 1시간 남아도 1
        assertEquals(0, CouponRules.daysLeft(now, now))
        assertTrue(CouponRules.usability(today, ctx(), now).ok)
        // RN 과 같게 '일' 단위 올림: 방금 지난(1시간 전) 것은 아직 0일로 본다 — 서버보다 느슨하게 (서버가 최종 판단)
        assertEquals(0, CouponRules.daysLeft(now.minusSeconds(3600), now))
        assertEquals(-1, CouponRules.daysLeft(now.minusSeconds(25 * 3600), now))
    }

    // ---- 교환권: 사유 순서 ----
    @Test fun `교환권 - 다른 매장 - 판매 안 함 - 품절 - 장바구니에 없음 순서로 사유를 준다`() {
        assertEquals("범석관 카페 쿠폰이에요", reason(CouponRules.usability(exchange(cafe = "c2"), ctx(menus = emptySet(), sold = setOf("m1"), cart = emptySet()), now)))
        assertEquals("지금은 판매하지 않는 메뉴예요", reason(CouponRules.usability(exchange(), ctx(menus = setOf("m2"), sold = setOf("m1"), cart = emptySet()), now)))
        assertEquals("지금은 품절이에요", reason(CouponRules.usability(exchange(), ctx(sold = setOf("m1"), cart = emptySet()), now)))
        assertEquals("장바구니에 '아메리카노'을(를) 담아 주세요", reason(CouponRules.usability(exchange(), ctx(cart = setOf("m2")), now)))
        assertTrue(CouponRules.usability(exchange(), ctx(), now).ok)
    }

    @Test fun `교환권 - 매장 이름이 비면 다른 매장`() {
        assertEquals("다른 매장 쿠폰이에요", reason(CouponRules.usability(exchange(cafe = "c2", cafeName = ""), ctx(), now)))
    }

    // ---- 금액권 ----
    @Test fun `금액권 - 매장 제한과 최소 주문 금액`() {
        assertEquals("학생회관에서만 쓸 수 있어요", reason(CouponRules.usability(amount(cafe = "c2", cafeName = "학생회관"), ctx(), now)))
        assertEquals("다른 매장에서만 쓸 수 있어요", reason(CouponRules.usability(amount(cafe = "c2"), ctx(), now)))
        assertEquals("10,000원 이상 주문할 때 쓸 수 있어요", reason(CouponRules.usability(amount(min = 10_000), ctx(subtotal = 9_999), now)))
        assertTrue(CouponRules.usability(amount(min = 10_000), ctx(subtotal = 10_000), now).ok) // 경계
        assertTrue(CouponRules.usability(amount(), ctx(subtotal = 0), now).ok) // 제한 없음
        assertTrue(CouponRules.usability(amount(cafe = "c1"), ctx(), now).ok)
    }

    @Test fun `금액권 - 최소 금액 0 은 제한 없음`() {
        assertTrue(CouponRules.usability(amount(min = 0), ctx(subtotal = 100), now).ok)
    }

    // ---- 표시 ----
    @Test fun `한 줄 설명 - 교환권과 금액권`() {
        val until = "2026.11.29까지"
        assertEquals("범석관 카페 · 아메리카노 1개 · $until", CouponRules.detail(exchange()))
        assertEquals("모든 매장 · $until", CouponRules.detail(amount()))
        assertEquals("모든 매장 · 5,000원 이상 주문 · $until", CouponRules.detail(amount(min = 5000)))
        assertEquals("학생회관 · $until", CouponRules.detail(amount(cafe = "c2", cafeName = "학생회관")))
        assertEquals("지정 매장 · $until", CouponRules.detail(amount(cafe = "c2")))
    }

    @Test fun `종류 이름`() {
        assertEquals("교환권", exchange().kind.label)
        assertEquals("금액권", amount().kind.label)
    }

    // ---- 선택 ----
    private val mine = listOf(exchange("e1"), exchange("e2"), amount("a1"), amount("a2", min = 99_999))
    private fun kindOf(id: String) = mine.firstOrNull { it.id == id }?.kind

    @Test fun `같은 종류는 1장 - 다른 걸 고르면 바뀐다`() {
        var sel = VoucherSelection()
        sel = sel.toggle(mine[0], blocked = false, ::kindOf); assertEquals(listOf("e1"), sel.ids)
        sel = sel.toggle(mine[2], blocked = false, ::kindOf); assertEquals(listOf("e1", "a1"), sel.ids) // 다른 종류는 같이
        sel = sel.toggle(mine[1], blocked = false, ::kindOf); assertEquals(listOf("a1", "e2"), sel.ids) // 같은 종류는 교체
    }

    @Test fun `이미 고른 걸 다시 누르면 해제 - 막힌 쿠폰이어도 해제는 가능`() {
        val sel = VoucherSelection(listOf("e1", "a1"))
        assertEquals(listOf("a1"), sel.toggle(mine[0], blocked = true, ::kindOf).ids)
    }

    @Test fun `쓸 수 없는 쿠폰은 선택되지 않는다`() {
        val sel = VoucherSelection(listOf("e1"))
        assertEquals(sel, sel.toggle(mine[1], blocked = true, ::kindOf))
        assertEquals(VoucherSelection(), VoucherSelection().toggle(mine[3], blocked = true, ::kindOf))
    }

    @Test fun `장바구니가 바뀌어 못 쓰게 된 선택은 자동 해제 + 안내`() {
        val sel = VoucherSelection(listOf("e1", "a1"))
        // 장바구니에서 메뉴 m1 을 뺌 → 교환권 e1 은 못 씀, 금액권 a1 은 계속
        val r = sel.pruneUnusable(mine, ctx(cart = setOf("m2")), now)
        assertEquals(listOf("a1"), r.selection.ids)
        assertEquals(listOf("e1"), r.dropped)
        assertEquals("장바구니가 바뀌어서 사용할 수 없는 쿠폰은 선택에서 뺐어요.", r.notice)
    }

    @Test fun `바뀐 게 없으면 안내도 없다 - 모르는 id 는 건드리지 않는다`() {
        val r = VoucherSelection(listOf("e1", "ghost")).pruneUnusable(mine, ctx(), now)
        assertEquals(listOf("e1", "ghost"), r.selection.ids)
        assertNull(r.notice)
    }

    @Test fun `종류 판별`() {
        assertIs<Voucher.MenuExchange>(mine[0])
        assertFalse(VoucherSelection().ids.isNotEmpty())
    }
}
