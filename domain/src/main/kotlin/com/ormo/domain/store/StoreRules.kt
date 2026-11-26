package com.ormo.domain.store

import java.time.LocalDateTime
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** 주문 가능 여부 + 안내 상자 문구 (디자인 2.1.2) */
sealed interface OrderAvailability {
    data object Available : OrderAvailability

    /** @property message 안내 상자 문구. 예: "14:30부터 다시 주문할 수 있어요" */
    data class Unavailable(val kind: UnavailableKind, val message: String) : OrderAvailability
}

enum class UnavailableKind { BREAK, CLOSED }

/** 매장 행의 상태 표시 (1.1.1). [detail] 은 '다시 여는 시각' 같은 보조 문구. */
data class StatusLabel(val text: String, val detail: String? = null)

/** 매장 변경 시 장바구니 경고 (1.1.2) */
sealed interface StoreChangeDecision {
    data object Proceed : StoreChangeDecision

    /** 안내 '매장을 변경하면 장바구니의 메뉴가 삭제돼요' — 비운 장바구니는 되돌릴 수 없다 */
    data class ConfirmClearCart(val itemCount: Int) : StoreChangeDecision {
        val title: String get() = "매장을 변경하면 장바구니의 메뉴가 삭제돼요"

        /** 개수를 안내에 포함 (1.1.2). TODO(spec 1.1.2): 정확한 문구는 Figma 확인 */
        val detail: String get() = "담아 둔 메뉴 ${itemCount}개가 사라져요"
    }
}

/** 앱을 켰을 때/로그인 직후 어디로 갈지 (디자인 0.1.x 이동, 1.1.1) */
sealed interface StoreEntry {
    /** 기억한 매장의 메뉴 홈으로 바로 */
    data class MenuHome(val storeId: String) : StoreEntry

    /** 매장 선택 화면. [hideBack] 은 가입 직후 첫 진입 (뒤로가기 숨김) */
    data class StoreSelect(val hideBack: Boolean) : StoreEntry
}

object StoreRules {
    /** 도보 속도 가정(m/분). TODO(spec 1.1.1): 도보 시간 계산식은 명세에 없음 — 보통 걸음 4.8km/h */
    const val WALK_METERS_PER_MIN = 80

    /** 메뉴가 없는 매장은 목록에서 제외한다 (1.1.1). 순서는 서버 그대로. */
    fun listable(stores: List<Store>): List<Store> = stores.filter { it.menuCount > 0 }

    /** 영업 종료 매장은 흐리게 (메뉴는 볼 수 있음) */
    fun dimmed(status: StoreStatus): Boolean = status is StoreStatus.Closed

    fun statusLabel(status: StoreStatus, now: LocalDateTime): StatusLabel = when (status) {
        StoreStatus.Open -> StatusLabel("영업 중")
        is StoreStatus.Break -> StatusLabel(
            "준비 중",
            status.reopenAt?.takeIf { it.isAfter(now) }?.let { "${dayPrefix(it, now)}${clock(it)}에 다시 열어요" },
        )
        is StoreStatus.Closed -> StatusLabel("영업 종료")
    }

    /** 2.1.2: 주문 불가 안내. 보는 동안 상태가 바뀌면 호출하는 쪽이 새 status 로 다시 부른다. */
    fun availability(status: StoreStatus, now: LocalDateTime): OrderAvailability = when (status) {
        StoreStatus.Open -> OrderAvailability.Available
        is StoreStatus.Break -> OrderAvailability.Unavailable(
            UnavailableKind.BREAK,
            status.reopenAt?.takeIf { it.isAfter(now) }
                ?.let { "${dayPrefix(it, now)}${clock(it)}부터 다시 주문할 수 있어요" } ?: REOPEN_SOON,
        )
        is StoreStatus.Closed -> OrderAvailability.Unavailable(
            UnavailableKind.CLOSED,
            status.nextOpenAt?.takeIf { it.isAfter(now) }
                ?.let { "${dayPrefix(it, now)}${clock(it)}부터 주문할 수 있어요" } ?: REOPEN_SOON,
        )
    }

    const val REOPEN_SOON = "잠시 후 다시 열어요"

    /** 1.1.2: 다른 매장의 메뉴가 장바구니에 있을 때만 경고 */
    fun decideStoreChange(cartStoreId: String?, cartItemCount: Int, targetStoreId: String): StoreChangeDecision =
        if (cartItemCount > 0 && cartStoreId != null && cartStoreId != targetStoreId) {
            StoreChangeDecision.ConfirmClearCart(cartItemCount)
        } else {
            StoreChangeDecision.Proceed
        }

    /**
     * 로그인 기록이 있으면 메뉴 홈, 최근 매장이 없으면 매장 선택 (0.1.x 이동).
     * 기억한 매장이 이제 목록에 없으면(운영 종료 등) 매장 선택으로 보낸다.
     * 기억한 매장이 메뉴 0개여도 메뉴 홈으로 간다 — '아직 메뉴가 없어요' 빈 상태는 이렇게 들어온 경우에만 보인다 (2.1.2).
     * @param allStores 메뉴 유무와 상관없는 전체 목록
     * @param firstEntryAfterSignup 가입 직후 첫 진입이면 매장 선택에서 뒤로가기를 숨긴다 (기억한 매장이 없는 게 보통)
     */
    fun resolveEntry(lastStoreId: String?, allStores: List<Store>, firstEntryAfterSignup: Boolean = false): StoreEntry {
        val remembered = lastStoreId?.takeIf { id -> allStores.any { it.id == id } }
        return if (remembered != null) StoreEntry.MenuHome(remembered) else StoreEntry.StoreSelect(hideBack = firstEntryAfterSignup)
    }

    /** 두 지점 사이 직선거리(m, 반올림) — 하버사인 */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return Math.round(r * 2 * atan2(sqrt(a), sqrt(1 - a))).toInt()
    }

    /**
     * "320m · 도보 4분" / "1.2km · 도보 15분". **사용자 위치가 없으면(권한 없음) 또는 매장 위치가 없으면 null** (1.1.1).
     */
    fun distanceLabel(store: Store, userLat: Double?, userLon: Double?): String? {
        val lat = store.latitude ?: return null
        val lon = store.longitude ?: return null
        if (userLat == null || userLon == null) return null
        val m = distanceMeters(userLat, userLon, lat, lon)
        val distance = if (m < 1000) "${m}m" else String.format(java.util.Locale.US, "%.1fkm", m / 1000.0)
        val minutes = max(1, ceil(m.toDouble() / WALK_METERS_PER_MIN).toInt())
        return "$distance · 도보 ${minutes}분"
    }

    private fun clock(t: LocalDateTime) = String.format(java.util.Locale.US, "%02d:%02d", t.hour, t.minute)

    private fun dayPrefix(target: LocalDateTime, now: LocalDateTime): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), target.toLocalDate())
        return when (days) {
            0L -> ""
            1L -> "내일 "
            else -> "${target.monthValue}월 ${target.dayOfMonth}일 "
        }
    }
}
