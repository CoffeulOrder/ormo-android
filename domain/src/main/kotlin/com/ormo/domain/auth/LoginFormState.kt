package com.ormo.domain.auth

import com.ormo.core.formatCountdown
import com.ormo.domain.rules.EmailRules

enum class LoginMessage {
    /** 비밀번호 칸 아래 "이메일이나 비밀번호를 다시 확인해주세요" (두 칸 모두 빨간 테두리) */
    INVALID_CREDENTIALS,

    /** 스낵바 "비밀번호 입력 횟수를 초과했어요" */
    LOCKED,

    /** 다이얼로그 "이용이 제한된 계정이에요" */
    RESTRICTED,

    /** 6.1.1 네트워크 오류 */
    NETWORK,
    OTHER,
}

/**
 * 로그인 화면 상태 (디자인 0.1.1~0.1.3).
 * - 두 칸이 모두 차면 [로그인] 활성. 한 번 로그인하면 다음부터 자동 로그인(체크박스 없음).
 * - 실패 시 비밀번호 칸만 비우고 이메일은 유지. 어느 칸이 틀렸는지 알려주지 않는다.
 * - 잠김: 버튼이 "14:59 후 로그인" 으로 바뀌고 1초마다 줄어들며, 0:00 에 다시 활성. 횟수·시간은 서버 설정(기본 5번/15분).
 */
data class LoginFormState(
    val emailId: String = "",
    val domain: String? = null,
    val password: String = "",
    val submitting: Boolean = false,
    val message: LoginMessage? = null,
    val lockedUntilMs: Long? = null,
) {
    val email: String? get() = domain?.let { "$emailId@$it" }

    fun isLocked(nowMs: Long): Boolean = lockedUntilMs != null && nowMs < lockedUntilMs

    /** 잠김 중 버튼에 표시할 "14:59" (잠김이 아니면 null → 평소 문구 "로그인") */
    fun lockCountdownLabel(nowMs: Long): String? {
        val until = lockedUntilMs ?: return null
        if (nowMs >= until) return null
        return formatCountdown(((until - nowMs + 999) / 1000).toInt())
    }

    fun canSubmit(nowMs: Long): Boolean =
        domain != null && emailId.isNotEmpty() && password.isNotEmpty() && !submitting && !isLocked(nowMs)

    fun onEmailChanged(raw: String): LoginFormState = copy(emailId = EmailRules.sanitizeId(raw), message = null)

    fun onDomainSelected(domain: String): LoginFormState = copy(domain = domain)

    /** 이메일 전체를 붙여 넣으면 아이디와 도메인을 나눈다 (도메인이 허용 목록에 있을 때만 바꾼다) */
    fun onEmailPasted(text: String, allowedDomains: Collection<String>): LoginFormState {
        val parts = EmailRules.splitPasted(text, allowedDomains)
        return copy(emailId = parts.id, domain = parts.domain ?: domain, message = null)
    }

    fun onPasswordChanged(raw: String): LoginFormState = copy(password = raw, message = null)

    fun onSubmitStarted(): LoginFormState = copy(submitting = true, message = null)

    fun onFailure(failure: LoginFailure, nowMs: Long): LoginFormState = when (failure) {
        LoginFailure.InvalidCredentials ->
            copy(submitting = false, password = "", message = LoginMessage.INVALID_CREDENTIALS)
        is LoginFailure.Locked ->
            copy(
                submitting = false,
                password = "",
                message = LoginMessage.LOCKED,
                lockedUntilMs = nowMs + failure.retryAfterSec * 1000L,
            )
        LoginFailure.Restricted -> copy(submitting = false, message = LoginMessage.RESTRICTED)
        LoginFailure.Network -> copy(submitting = false, message = LoginMessage.NETWORK)
        LoginFailure.Other -> copy(submitting = false, message = LoginMessage.OTHER)
    }
}
