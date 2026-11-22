package com.ormo.data.profile

import com.ormo.domain.profile.NotificationSettings
import com.ormo.domain.profile.NotificationSettingsState
import com.ormo.domain.profile.NotificationSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 알림 설정 서버 API (5.5.1). ⚠️ 계약 미확정 — Fake 로 먼저. 실패는 예외로 던진다.
 * 설정은 서버가 푸시를 보낼지 정하는 데 쓰이므로 서버에 저장한다.
 */
interface NotificationSettingsApi {
    suspend fun fetch(): NotificationSettings
    suspend fun save(settings: NotificationSettings)
}

/**
 * 알림 설정 저장소: 스위치를 **누르는 즉시 저장**하고(저장 버튼 없음) 실패하면 그 스위치만 되돌린다.
 * 저장은 한 번에 하나씩 하고, 저장 시점의 화면 값을 보내므로 연달아 눌러도 마지막 값이 이긴다.
 */
class NotificationSettingsRepository(
    private val api: NotificationSettingsApi,
    osGranted: Boolean = true,
) {
    private val _state = MutableStateFlow(NotificationSettingsState(osGranted = osGranted))
    val state: StateFlow<NotificationSettingsState> = _state

    private val saveLock = Mutex()
    private val lock = Any()

    private fun update(f: (NotificationSettingsState) -> NotificationSettingsState): NotificationSettingsState =
        synchronized(lock) { f(_state.value).also { _state.value = it } }

    /** 화면에 들어올 때. 실패하면 기본값으로 둔다 (켜짐). 반환값: 불러왔는가 */
    suspend fun load(): Boolean = try {
        val s = api.fetch()
        update { it.withLoaded(s) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** 화면에 들어올 때마다/설정 앱에서 돌아올 때마다 OS 권한을 다시 확인해 넣는다 */
    fun setOsPermission(granted: Boolean) {
        update { it.withOsPermission(granted) }
    }

    /** 스위치를 눌렀다. 화면에는 바로 반영되고 저장은 뒤에서 진행. 저장이 끝나면 반환. */
    suspend fun toggle(s: NotificationSwitch, on: Boolean) {
        val after = update { it.onToggle(s, on) }
        if (!after.osGranted) return
        saveLock.withLock {
            val settings = synchronized(lock) { _state.value.shown }
            try {
                api.save(settings)
                update { it.onSaved(s, valueOf(settings, s)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.onSaveFailed(s) }
            }
        }
    }

    private fun valueOf(set: NotificationSettings, s: NotificationSwitch) = when (s) {
        NotificationSwitch.ORDER_PUSH -> set.orderPush
        NotificationSwitch.LIVE -> set.live
    }
}
