package com.ormo.domain.profile

/** 저장되는 알림 설정 (5.5.1). 마케팅 알림은 베타에 없다. */
data class NotificationSettings(
    /** 주문 알림: 음료가 준비되거나 주문 상태가 변경되면(준비, 거절, 매장 취소, 환불) 푸시 */
    val orderPush: Boolean = true,
    /** 잠금 화면 실시간 현황 (Android 는 진행 중 알림) */
    val live: Boolean = true,
)

enum class NotificationSwitch { ORDER_PUSH, LIVE }

/** OS 알림이 꺼져 있을 때의 안내 (5.5.1) */
data class OsOffNotice(val title: String, val body: String)

/**
 * 알림 설정 화면 상태 (5.5.1). 순수 불변.
 * - 스위치는 **누르는 즉시 저장**, 저장 버튼 없음: 화면은 바로 바꿔 보이고([onToggle]) 저장이 실패하면 그 스위치만 되돌린다([onSaveFailed]).
 * - **OS 알림 권한이 꺼져 있으면** 스위치는 꺼진 채 비활성이고 안내 + [설정 열기]를 보인다. 화면에 들어올 때마다/설정에서 돌아올 때마다 [withOsPermission] 으로 다시 확인.
 *   (저장된 설정 값은 건드리지 않는다 — 권한을 다시 켜면 이전 선택이 그대로 돌아온다.)
 */
data class NotificationSettingsState(
    /** 서버에 저장이 확인된 값 */
    val confirmed: NotificationSettings = NotificationSettings(),
    /** 화면에 보이는 값 (저장 중인 것 포함) */
    val shown: NotificationSettings = confirmed,
    val osGranted: Boolean = true,
    /** 저장 중인 스위치 (같은 스위치를 연달아 누를 때 마지막 값이 이긴다) */
    val saving: Set<NotificationSwitch> = emptySet(),
    val failed: NotificationSwitch? = null,
) {
    /** OS 가 꺼져 있으면 스위치는 꺼진 채 비활성 */
    fun isOn(s: NotificationSwitch): Boolean = osGranted && value(shown, s)
    fun isEnabled(s: NotificationSwitch): Boolean = osGranted

    val osOffNotice: OsOffNotice?
        get() = if (osGranted) null else OsOffNotice("ORMO 알림이 꺼져 있어요", "주문 알림을 받으려면 휴대폰 설정에서 알림을 켜주세요")

    /** 누르는 즉시 반영. OS 가 꺼져 있으면 무시. 반환된 새 값을 서버에 저장한다. */
    fun onToggle(s: NotificationSwitch, on: Boolean): NotificationSettingsState =
        if (!osGranted) this else copy(shown = with(shown, s, on), saving = saving + s, failed = null)

    /** 저장 성공: 확인된 값 갱신. 그 사이 같은 스위치를 또 눌렀다면(값이 다르면) 아직 저장 중으로 둔다. */
    fun onSaved(s: NotificationSwitch, saved: Boolean): NotificationSettingsState {
        val next = copy(confirmed = with(confirmed, s, saved))
        return if (value(shown, s) == saved) next.copy(saving = saving - s) else next
    }

    /** 저장 실패: 그 스위치를 확인된 값으로 되돌리고 알린다 (스낵바) */
    fun onSaveFailed(s: NotificationSwitch): NotificationSettingsState =
        copy(shown = with(shown, s, value(confirmed, s)), saving = saving - s, failed = s)

    fun withOsPermission(granted: Boolean): NotificationSettingsState = copy(osGranted = granted)

    fun withLoaded(settings: NotificationSettings): NotificationSettingsState = copy(confirmed = settings, shown = settings, saving = emptySet())

    private fun value(set: NotificationSettings, s: NotificationSwitch) = when (s) {
        NotificationSwitch.ORDER_PUSH -> set.orderPush
        NotificationSwitch.LIVE -> set.live
    }

    private fun with(set: NotificationSettings, s: NotificationSwitch, v: Boolean) = when (s) {
        NotificationSwitch.ORDER_PUSH -> set.copy(orderPush = v)
        NotificationSwitch.LIVE -> set.copy(live = v)
    }
}

/** 설정이 실제 동작에 미치는 영향 (5.5.1 개발 메모) */
object NotificationBehavior {
    /** 주문 알림을 끄면 푸시 없이 앱 안 주문 현황 바로만 안내 */
    fun pushAllowed(settings: NotificationSettings, osGranted: Boolean): Boolean = osGranted && settings.orderPush

    /** 실시간 현황(진행 중 알림)을 끄면 시작하지 않고, 픽업 대기는 푸시로 알린다 */
    fun ongoingAllowed(settings: NotificationSettings, osGranted: Boolean): Boolean = osGranted && settings.live
}
