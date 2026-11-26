package com.ormo.customer.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 위치 관련 기기 설정 (서버로 보내지 않는다).
 * - [useLocation]: 5.5.1 '내 위치 사용' 스위치. 켜면 매장 목록에 거리·도보 시간을 보인다. 기본 켜짐. 휴대폰 위치 권한이 꺼져 있으면 이 값과 상관없이 꺼진 것으로 본다.
 * - [consented]: 1.1.1 위치 권한 안내 시트에서 '[선택] 위치기반서비스 이용약관'에 동의했는가 (OS 권한 창은 동의한 뒤에만 띄운다).
 * - [asked]: 위치 권한 안내 시트를 한 번이라도 보여 줬는가 — **한 번 답하면 다시 자동으로 띄우지 않는다** (Figma 1.1.1).
 */
class PrefsLocationPreference(context: Context) {
    private val prefs = context.getSharedPreferences("ormo_location_pref", Context.MODE_PRIVATE)
    private val _useLocation = MutableStateFlow(prefs.getBoolean(KEY_USE, true))
    val useLocation: StateFlow<Boolean> = _useLocation.asStateFlow()
    private val _consented = MutableStateFlow(prefs.getBoolean(KEY_CONSENT, false))
    val consented: StateFlow<Boolean> = _consented.asStateFlow()
    private val _asked = MutableStateFlow(prefs.getBoolean(KEY_ASKED, false))
    val asked: StateFlow<Boolean> = _asked.asStateFlow()

    fun set(on: Boolean) {
        prefs.edit().putBoolean(KEY_USE, on).apply()
        _useLocation.value = on
    }

    fun setConsented(value: Boolean) {
        prefs.edit().putBoolean(KEY_CONSENT, value).apply()
        _consented.value = value
    }

    fun markAsked() {
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        _asked.value = true
    }

    private companion object {
        const val KEY_USE = "use_location"
        const val KEY_CONSENT = "location_terms_consented"
        const val KEY_ASKED = "location_prompt_asked"
    }
}
