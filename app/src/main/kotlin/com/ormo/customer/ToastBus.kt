package com.ormo.customer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 화면을 닫고 돌아간 곳에서 띄울 한 번짜리 안내 (예: 옵션 화면에서 담고 메뉴 홈으로 돌아오면 '장바구니에 메뉴를 추가했어요').
 * 돌아간 화면은 그 순간 다시 그려지므로, 보낸 쪽이 먼저 넣어 두고 받는 쪽이 보여준 뒤 [consume] 한다.
 */
class ToastBus {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    fun post(message: String) { _pending.value = message }

    fun consume(message: String) { if (_pending.value == message) _pending.value = null }
}
