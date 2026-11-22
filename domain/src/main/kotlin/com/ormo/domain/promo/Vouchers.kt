package com.ormo.domain.promo

import com.ormo.core.formatWon
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/*
 * 쿠폰(교환권·금액권) 규칙 — **베타에서는 꺼져 있다** (FeatureFlags.coupons). 새 디자인(Figma)에 쿠폰 화면이 없어 UI 는 만들지 않고,
 * 규칙과 저장소만 이식해 둔다 (이식 계획 §4.3, M7). RN `constants/coupons.ts`·`use-vouchers.tsx` 와 같은 동작.
 */

enum class VoucherStatus { AVAILABLE, USED, EXPIRED }

sealed interface Voucher {
    val id: String

    /** 서버가 주는 표시 이름. 예: "아메리카노 교환권", "3,000원 금액권" */
    val title: String
    val status: VoucherStatus
    val expiresAt: Instant

    /** 사용 매장 제한. null 이면 학교 안 모든 매장 (금액권만 null 가능) */
    val cafeId: String?

    /** 메뉴 교환권: 한 매장의 한 메뉴 1개와 바꿔 준다. 메뉴가 단종돼도 보여줄 수 있게 이름을 함께 받는다. */
    data class MenuExchange(
        override val id: String,
        override val title: String,
        override val status: VoucherStatus,
        override val expiresAt: Instant,
        override val cafeId: String,
        val cafeName: String,
        val menuId: String,
        val menuName: String,
    ) : Voucher

    /** 금액권: [amount] 원을 차감. [minOrderAmount] 이상 주문해야 쓸 수 있다. */
    data class Amount(
        override val id: String,
        override val title: String,
        override val status: VoucherStatus,
        override val expiresAt: Instant,
        val amount: Int,
        val minOrderAmount: Int? = null,
        override val cafeId: String? = null,
        val cafeName: String? = null,
    ) : Voucher
}

enum class VoucherKind { MENU_EXCHANGE, AMOUNT }

val Voucher.kind: VoucherKind
    get() = when (this) {
        is Voucher.MenuExchange -> VoucherKind.MENU_EXCHANGE
        is Voucher.Amount -> VoucherKind.AMOUNT
    }

val VoucherKind.label: String get() = if (this == VoucherKind.MENU_EXCHANGE) "교환권" else "금액권"

/** 결제 화면이 쿠폰 사용 가능 여부를 판단할 때 넘기는 지금의 장바구니·매장 상태 */
data class CouponContext(
    /** 지금 보고 있는 매장 */
    val cafeId: String,
    /** 이 매장에서 판매 중인 메뉴 id */
    val menuIds: Set<String>,
    /** 이 매장에서 품절로 확인된 메뉴 id */
    val soldOut: Set<String>,
    /** 장바구니에 담긴 메뉴 id */
    val cartMenuIds: Set<String>,
    /** 쿠폰 적용 전 상품 금액 (옵션 추가금 포함) */
    val subtotal: Int,
)

sealed interface Usability {
    data object Ok : Usability
    data class No(val reason: String) : Usability

    val ok: Boolean get() = this is Ok
}

object CouponRules {
    /** 한 주문에 종류별로 쓸 수 있는 쿠폰 수 (API 는 배열이라 늘려도 요청 형태가 안 바뀐다) */
    const val MAX_VOUCHERS_PER_KIND = 1

    const val NOT_USABLE = "사용할 수 없는 쿠폰이에요"
    const val EXPIRED = "유효기간이 지났어요"
    const val NOT_ON_SALE = "지금은 판매하지 않는 메뉴예요"
    const val SOLD_OUT = "지금은 품절이에요"
    const val STALE_SELECTION_NOTICE = "장바구니가 바뀌어서 사용할 수 없는 쿠폰은 선택에서 뺐어요."

    /**
     * 지금 이 장바구니에 쓸 수 있는 쿠폰만 고르게 하는 **1차 판정**(편의용 필터). 실제 가능 여부는 견적·주문 때 서버가 다시 판단한다.
     * 앱이 가능하다고 했는데 서버가 거절하는 건 괜찮고 반대는 안 되므로, 규칙은 서버보다 **느슨하거나 같게** 둔다.
     */
    fun usability(v: Voucher, ctx: CouponContext, now: Instant): Usability {
        if (v.status != VoucherStatus.AVAILABLE) return Usability.No(NOT_USABLE)
        if (daysLeft(v.expiresAt, now) < 0) return Usability.No(EXPIRED)

        return when (v) {
            is Voucher.MenuExchange -> when {
                v.cafeId != ctx.cafeId -> Usability.No("${v.cafeName.ifBlank { "다른 매장" }} 쿠폰이에요")
                v.menuId !in ctx.menuIds -> Usability.No(NOT_ON_SALE)
                v.menuId in ctx.soldOut -> Usability.No(SOLD_OUT)
                v.menuId !in ctx.cartMenuIds -> Usability.No("장바구니에 '${v.menuName}'을(를) 담아 주세요")
                else -> Usability.Ok
            }
            is Voucher.Amount -> when {
                v.cafeId != null && v.cafeId != ctx.cafeId -> Usability.No("${v.cafeName?.takeIf { it.isNotBlank() } ?: "다른 매장"}에서만 쓸 수 있어요")
                v.minOrderAmount != null && v.minOrderAmount > 0 && ctx.subtotal < v.minOrderAmount ->
                    Usability.No("${formatWon(v.minOrderAmount)} 이상 주문할 때 쓸 수 있어요")
                else -> Usability.Ok
            }
        }
    }

    /** 쿠폰 한 줄 설명 (목록·결제 화면 공통): 사용 조건 + 유효기간 */
    fun detail(v: Voucher, zone: ZoneId = ZoneId.of("Asia/Seoul")): String {
        val until = "${dateDots(v.expiresAt, zone)}까지"
        return when (v) {
            is Voucher.MenuExchange -> "${v.cafeName} · ${v.menuName} 1개 · $until"
            is Voucher.Amount -> {
                val scope = if (v.cafeId != null) (v.cafeName ?: "지정 매장") else "모든 매장"
                val min = v.minOrderAmount?.takeIf { it > 0 }?.let { " · ${formatWon(it)} 이상 주문" } ?: ""
                "$scope$min · $until"
            }
        }
    }

    /** 만료까지 남은 일수 (표시용). 오늘 만료면 0, 지났으면 음수. */
    fun daysLeft(expiresAt: Instant, now: Instant): Int {
        val ms = expiresAt.toEpochMilli() - now.toEpochMilli()
        return Math.ceil(ms / 86_400_000.0).toInt()
    }

    /** "2027.06.30" */
    fun dateDots(t: Instant, zone: ZoneId = ZoneId.of("Asia/Seoul")): String {
        val z = t.atZone(zone)
        return String.format(Locale.US, "%04d.%02d.%02d", z.year, z.monthValue, z.dayOfMonth)
    }
}

/** 결제 화면의 쿠폰 선택 결과 */
data class VoucherSelection(val ids: List<String> = emptyList()) {
    /**
     * 쿠폰을 눌렀다. 이미 고른 것이면 해제, 아니면 선택(같은 종류는 [CouponRules.MAX_VOUCHERS_PER_KIND] 장까지 — 넘으면 오래된 선택부터 바꾼다).
     * 쓸 수 없는 쿠폰([blocked] 가 true)은 선택되지 않는다. 해제는 항상 가능.
     * @param kindOf 선택된 id → 종류 (내 쿠폰 목록에서 찾는다. 모르는 id 는 null)
     */
    fun toggle(voucher: Voucher, blocked: Boolean, kindOf: (String) -> VoucherKind?): VoucherSelection {
        if (voucher.id in ids) return VoucherSelection(ids - voucher.id)
        if (blocked) return this
        val sameKind = ids.filter { kindOf(it) == voucher.kind }
        val keep = ids.filter { kindOf(it) != voucher.kind }
        return VoucherSelection(keep + (sameKind + voucher.id).takeLast(CouponRules.MAX_VOUCHERS_PER_KIND))
    }

    data class Pruned(val selection: VoucherSelection, val dropped: List<String>) {
        val notice: String? get() = if (dropped.isEmpty()) null else CouponRules.STALE_SELECTION_NOTICE
    }

    /** 장바구니가 바뀌어 이미 고른 쿠폰을 못 쓰게 되면 선택에서 뺀다 (+ 안내) */
    fun pruneUnusable(mine: List<Voucher>, ctx: CouponContext, now: Instant): Pruned {
        val stale = ids.filter { id ->
            val v = mine.firstOrNull { it.id == id }
            v != null && !CouponRules.usability(v, ctx, now).ok
        }
        return Pruned(VoucherSelection(ids - stale.toSet()), stale)
    }
}
