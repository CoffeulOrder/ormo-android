package com.ormo.core

import java.util.Locale

/** 금액 표시: 12000 → "12,000원". 로케일과 무관하게 항상 쉼표 구분. */
fun formatWon(amount: Int): String = String.format(Locale.US, "%,d원", amount)

/** 카운트다운 표시 "m:ss" (14:59, 4:59, 0:59). 음수는 0:00. */
fun formatCountdown(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/**
 * 끝나는 시각 기준 남은 초 (올림). 시계는 호출하는 쪽이 넘긴다 (테스트 가능하게).
 * 로그인 잠금(0.1.2)·인증코드 유효 시간·재발송 대기(0.2.2)에 쓴다. 값 자체는 전부 서버 설정이 우선.
 */
class Countdown(private val endAtMillis: Long) {
    fun remainingSeconds(nowMillis: Long): Int {
        val ms = endAtMillis - nowMillis
        return if (ms <= 0) 0 else ((ms + 999) / 1000).toInt()
    }

    fun isFinished(nowMillis: Long): Boolean = nowMillis >= endAtMillis
}

/**
 * 베타 범위 플래그. 꺼진 기능은 메뉴 숨김 + 화면 진입 차단 + 서버 호출 안 함 세 가지를 모두 지킨다.
 * (새 디자인에는 쿠폰·이벤트·내 메뉴·추천인 화면이 없어 UI 는 보류 — 규칙·Repository 만 준비)
 */
data class FeatureFlags(
    val coupons: Boolean = false,
    val events: Boolean = false,
    val myMenu: Boolean = false,
    val referral: Boolean = false,
)

/** 로그아웃/세션 만료 때 초기화해야 하는 개인 데이터 저장소. */
interface Resettable {
    fun reset()
}

/** 로그아웃/세션 만료 때 호출되는 한 곳. 새 개인 데이터 저장소를 만들면 여기에 등록해야 한다. */
class SessionResetter(private val targets: Collection<Resettable>) {
    fun resetAll() = targets.forEach { it.reset() }
}
