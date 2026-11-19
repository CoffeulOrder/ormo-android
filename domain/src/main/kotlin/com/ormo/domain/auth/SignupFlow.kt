package com.ormo.domain.auth

import com.ormo.domain.rules.EmailRules
import com.ormo.domain.rules.NameRules

enum class SignupStep { EMAIL, CODE, NAME, PASSWORD, CONSENT, DONE }

/** 가입 화면으로 돌아왔을 때 보여줄 안내 */
enum class SignupNotice {
    /** "이미 가입된 이메일이에요" + [로그인하기] */
    EMAIL_TAKEN,

    /** 인증이 만료·무효 → 이메일 인증부터 다시 */
    VERIFICATION_EXPIRED,
}

/**
 * 회원가입 진행 상태 (디자인 0.2): 한 화면에 하나씩 이메일 → 코드 → 이름 → 비밀번호 → 약관 → 완료.
 * 화면이 바뀌어도 앞 단계에서 모은 값은 유지된다. 인증 토큰은 **한 번만** 쓰이므로, 가입이 거절되면
 * 토큰이 소모됐을 수 있는 경우(이메일 중복·토큰 무효)에는 처음 단계로 돌아가 인증부터 다시 하게 한다.
 * (약관·비밀번호 검증 실패로 막혀도 토큰이 소모되지 않아야 하는 것은 서버 책임 — 그래서 그 경우는 제자리.)
 *
 * 비밀번호는 메모리에만 두고, 완료(DONE) 때 지운다.
 */
data class SignupState(
    val step: SignupStep = SignupStep.EMAIL,
    val emailId: String = "",
    val domain: String? = null,
    val verificationToken: String? = null,
    val name: String = "",
    val password: String = "",
    val notice: SignupNotice? = null,
) {
    val email: String? get() = domain?.let { "$emailId@$it" }

    /** 이메일 형식이 맞으면 [인증코드 받기] 활성 */
    val canRequestCode: Boolean get() = step == SignupStep.EMAIL && domain != null && EmailRules.isValidId(emailId)

    fun onEmailChanged(rawId: String, domain: String?): SignupState =
        copy(emailId = EmailRules.sanitizeId(rawId), domain = domain, notice = null)

    /** 코드가 발송되었다 → 코드 입력 화면 */
    fun codeSent(): SignupState = if (canRequestCode) copy(step = SignupStep.CODE, notice = null) else this

    fun codeVerified(token: String): SignupState =
        if (step == SignupStep.CODE) copy(step = SignupStep.NAME, verificationToken = token) else this

    /** 이름 [확인]. 규칙에 맞지 않으면 그대로. 앞뒤 공백은 제거해서 저장한다. */
    fun nameConfirmed(raw: String): SignupState {
        if (step != SignupStep.NAME || NameRules.validate(raw) != null) return this
        return copy(step = SignupStep.PASSWORD, name = NameRules.normalize(raw))
    }

    fun passwordConfirmed(pw: String): SignupState =
        if (step == SignupStep.PASSWORD) copy(step = SignupStep.CONSENT, password = pw) else this

    /** 뒤로가기. 인증이 끝난 뒤의 이름 화면에서는 이메일로 (인증코드 화면으로 돌아가도 의미가 없으므로). */
    fun back(): SignupState = when (step) {
        SignupStep.CODE -> copy(step = SignupStep.EMAIL)
        SignupStep.NAME -> copy(step = SignupStep.EMAIL, verificationToken = null)
        SignupStep.PASSWORD -> copy(step = SignupStep.NAME)
        SignupStep.CONSENT -> copy(step = SignupStep.PASSWORD)
        else -> this
    }

    fun toRequest(consent: ConsentState): SignUpRequest? {
        val email = email ?: return null
        val token = verificationToken ?: return null
        if (step != SignupStep.CONSENT || !consent.canSubmit) return null
        return SignUpRequest(
            email = email,
            name = name,
            password = password,
            verificationToken = token,
            termsAgreed = consent.isChecked(ConsentId.TERMS),
            privacyAgreed = consent.isChecked(ConsentId.PRIVACY),
        )
    }

    /** 가입이 거절됐다 */
    fun rejected(reason: SignupRejection): SignupState = when (reason) {
        SignupRejection.EMAIL_TAKEN ->
            copy(step = SignupStep.EMAIL, verificationToken = null, password = "", notice = SignupNotice.EMAIL_TAKEN)
        SignupRejection.VERIFICATION_INVALID ->
            copy(step = SignupStep.EMAIL, verificationToken = null, password = "", notice = SignupNotice.VERIFICATION_EXPIRED)
        SignupRejection.PASSWORD_INVALID -> copy(step = SignupStep.PASSWORD)
        SignupRejection.CONSENT_REQUIRED, SignupRejection.NETWORK, SignupRejection.OTHER -> this
    }

    /** 가입 완료 (가입 후 바로 로그인 — 0.2.6) */
    fun succeeded(): SignupState = copy(step = SignupStep.DONE, password = "", verificationToken = null)
}
