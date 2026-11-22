package com.ormo.domain.promo

import java.time.Instant
import java.time.ZoneId

/*
 * 이벤트 배너(팝업) 규칙 — **베타에서는 꺼져 있다** (FeatureFlags.events). 이식 계획 §4.9, RN `use-banners.tsx`·`banner-store.ts`.
 */

sealed interface BannerLink {
    /** 앱 안 화면 — [BannerRules.ALLOWED_ROUTES] 허용 목록의 경로만 */
    data class Route(val path: String) : BannerLink

    /** 외부 페이지 — https 만, 인앱 브라우저로 연다 */
    data class Url(val url: String) : BannerLink
}

data class Banner(
    val id: String,
    /** 접근성 라벨 용 (이미지에 글이 들어 있으므로 화면에는 그리지 않는다) */
    val title: String,
    val imageUrl: String,
    /** 클수록 먼저. 한 번에 팝업은 1개만 뜬다. */
    val priority: Int,
    val link: BannerLink? = null,
)

enum class BannerEvent(val wire: String) { IMPRESSION("impression"), OPEN("open"), CLOSE("close"), HIDE_TODAY("hide_today") }

object BannerRules {
    /** 배너가 열 수 있는 앱 안 경로 (허용 목록). 친구 초대 화면이 생기면 '/invite' 를 추가한다. */
    val ALLOWED_ROUTES = setOf("/menu", "/branch", "/coupons", "/notifications", "/payment-methods", "/events", "/orders")

    /** 잘못되거나 허용되지 않은 링크는 에러가 아니라 '링크 없음' (팝업은 보여주되 이동은 막는다) */
    fun parseLink(type: String?, value: String?): BannerLink? = when {
        type == "route" && value != null && value in ALLOWED_ROUTES -> BannerLink.Route(value)
        type == "url" && value != null && value.startsWith("https://") -> BannerLink.Url(value)
        else -> null
    }

    /** 서버 목록을 우선순위 내림차순으로 (같으면 받은 순서 유지) */
    fun byPriority(banners: List<Banner>): List<Banner> = banners.sortedByDescending { it.priority }

    // ---- "오늘 하루 보지 않기" ----
    /** 자정까지는 최대 24시간(+여유 1시간). 이보다 먼 미래로 저장된 값(기기 시계 조작)은 무시한다 */
    const val MAX_HIDE_MS = 25L * 60 * 60 * 1000

    /** 기기 로컬 시간 기준 다음 자정 */
    fun nextMidnight(now: Instant, zone: ZoneId): Instant = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()

    /** 저장된 '숨김 끝나는 시각(epoch ms)' 문자열로 오늘 숨김인지. 깨진 값·너무 먼 미래 값은 숨기지 않는다. */
    fun isHidden(stored: String?, now: Instant): Boolean {
        val until = stored?.toLongOrNull() ?: return false
        if (until - now.toEpochMilli() > MAX_HIDE_MS) return false
        return now.toEpochMilli() < until
    }

    /** 저장소 키: 배너 id 별로 독립 (userKey = 계정) */
    fun hideKey(userKey: String, bannerId: String) = "popup:$userKey:$bannerId:hideUntil"
}

/** 이벤트 목록 (햄버거 메뉴 → 이벤트). 진행 여부는 서버가 걸러서 주므로 앱은 기기 시계로 판단하지 않는다. */
data class AppEvent(
    val id: String,
    val title: String,
    val summary: String? = null,
    /** 상세 설명 (줄바꿈 포함 텍스트) */
    val body: String? = null,
    val imageUrl: String? = null,
    val startsAt: Instant,
    val endsAt: Instant,
)
