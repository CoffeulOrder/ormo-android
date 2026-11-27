package com.ormo.customer.ui.auth

import com.ormo.core.Outcome
import com.ormo.data.auth.AuthRepository
import com.ormo.domain.auth.SendCodeFailure
import com.ormo.domain.auth.VerificationState
import com.ormo.domain.auth.VerifyCodeFailure
import com.ormo.domain.auth.VerifyPurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 인증코드 화면이 띄울 일회성 안내 */
enum class CodeNotice { NETWORK, OTHER, DAILY_LIMIT, TOO_SOON }

/**
 * 인증코드 화면(0.2.2) 하나를 돌리는 컨트롤러. 가입(0.2)과 비밀번호 재설정(0.3)이 같이 쓴다.
 * 순수 상태는 `:domain` 의 [VerificationState] 가 맡고, 여기서는 서버 호출과 시각만 이어 준다.
 *
 * - 6자리를 모두 넣으면 바로 확인 (버튼 없음). 맞으면 0.7초 뒤 [onVerified].
 * - 확인 요청이 겹치지 않도록 확인 중에는 입력이 막힌다 ([VerificationState.onSubmitStarted]).
 * - 오답 5번이면 입력이 꺼지고 [인증코드 다시 받기].
 */
class VerificationController(
    private val scope: CoroutineScope,
    private val auth: AuthRepository,
    private val purpose: VerifyPurpose,
    private val onVerified: (token: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<VerificationState?>(null)
    val state: StateFlow<VerificationState?> = _state

    private val _sending = MutableStateFlow(false)

    /** [인증코드 다시 받기] 요청 중 */
    val sending: StateFlow<Boolean> = _sending

    private val _notice = MutableStateFlow<CodeNotice?>(null)
    val notice: StateFlow<CodeNotice?> = _notice

    fun clearNotice() { _notice.value = null }

    /** 처음 코드를 받은 직후 (이메일 화면에서 발송 성공) */
    fun started(result: com.ormo.domain.auth.SendCodeResult) {
        _state.value = VerificationState.started(result, clock())
    }

    fun reset() { _state.value = null }

    fun onCodeChanged(raw: String, email: String) {
        val now = clock()
        val next = _state.value?.onCodeChanged(raw, now) ?: return
        _state.value = next
        if (next.shouldSubmit(now)) submit(email, next.code)
    }

    private fun submit(email: String, code: String) {
        _state.update { it?.onSubmitStarted() }
        scope.launch {
            when (val r = auth.verifyEmailCode(email, purpose, code)) {
                is Outcome.Ok -> {
                    _state.update { it?.onVerified() }
                    delay(VERIFIED_DELAY_MS) // 맞으면 0.7초 뒤 다음 화면 (0.2.2)
                    onVerified(r.value)
                }
                is Outcome.Fail -> when (val f = r.failure) {
                    is VerifyCodeFailure.Code -> _state.update { it?.onFailed(f.error, clock(), f.remainingAttempts) }
                    VerifyCodeFailure.Network -> { _state.update { it?.onSubmitAborted() }; _notice.value = CodeNotice.NETWORK }
                    VerifyCodeFailure.Other -> { _state.update { it?.onSubmitAborted() }; _notice.value = CodeNotice.OTHER }
                }
            }
        }
    }

    /** [인증코드 다시 받기] / '메일이 오지 않나요?': 새 코드 발송, 칸 비움, 시간 재시작. 다시 받기도 하루 발송 횟수에 포함된다. */
    fun resend(email: String) {
        val cur = _state.value ?: return
        if (_sending.value || !cur.canResend(clock())) return
        _sending.value = true
        scope.launch {
            when (val r = auth.sendEmailCode(email, purpose)) {
                is Outcome.Ok -> _state.update { it?.onResent(r.value, clock()) }
                is Outcome.Fail -> _notice.value = when (r.failure) {
                    SendCodeFailure.DailyLimit -> CodeNotice.DAILY_LIMIT
                    is SendCodeFailure.TooSoon -> CodeNotice.TOO_SOON
                    SendCodeFailure.Network -> CodeNotice.NETWORK
                    else -> CodeNotice.OTHER
                }
            }
            _sending.value = false
        }
    }

    private companion object {
        const val VERIFIED_DELAY_MS = 700L
    }
}
