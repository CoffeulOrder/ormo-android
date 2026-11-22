package com.ormo.domain.auth

import com.ormo.domain.rules.EmailRules

enum class ResetStep { EMAIL, CODE, PASSWORD, DONE }

/**
 * 비밀번호 재설정 진행 상태 (디자인 0.3): 이메일(0.3.1) → 인증코드(0.2.2 와 같은 화면) → 새 비밀번호(0.3.2) → 완료(0.3.3).
 * 가입 흐름([SignupState])과 같은 방식이며, 이름·약관 단계가 없고 **미가입 이메일이어도 같은 안내**를 보여준다
 * (가입 여부를 알려주지 않음 — 그래서 "이미 가입된 이메일" 같은 오류 상태가 없다).
 */
data class ResetState(
    val step: ResetStep = ResetStep.EMAIL,
    val emailId: String = "",
    val domain: String? = null,
    val verificationToken: String? = null,
) {
    val email: String? get() = domain?.let { "$emailId@$it" }

    val canRequestCode: Boolean get() = step == ResetStep.EMAIL && domain != null && EmailRules.isValidId(emailId)

    fun onEmailChanged(rawId: String, domain: String?): ResetState =
        copy(emailId = EmailRules.sanitizeId(rawId), domain = domain)

    fun codeSent(): ResetState = if (canRequestCode) copy(step = ResetStep.CODE) else this

    fun codeVerified(token: String): ResetState =
        if (step == ResetStep.CODE) copy(step = ResetStep.PASSWORD, verificationToken = token) else this

    /** 뒤로가기. 인증이 끝난 뒤의 비밀번호 화면에서는 이메일로 (인증 토큰은 한 번만 쓰이므로 다시 인증). */
    fun back(): ResetState = when (step) {
        ResetStep.CODE -> copy(step = ResetStep.EMAIL)
        ResetStep.PASSWORD -> copy(step = ResetStep.EMAIL, verificationToken = null)
        else -> this
    }

    /** 서버가 인증 토큰을 거절 → 인증부터 다시 */
    fun verificationExpired(): ResetState = copy(step = ResetStep.EMAIL, verificationToken = null)

    fun succeeded(): ResetState = copy(step = ResetStep.DONE, verificationToken = null)
}
