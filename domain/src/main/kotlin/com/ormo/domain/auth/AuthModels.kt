package com.ormo.domain.auth

/** 이메일 인증을 쓰는 곳. 서버가 용도별로 코드를 따로 발급·검증한다. (비밀번호 변경은 현재 비밀번호 방식이라 인증 없음 — 디자인 5.2.1) */
enum class VerifyPurpose { SIGNUP, PASSWORD_RESET }

/** 인증코드 발송 결과. 값은 서버 응답이 우선 (기본: 유효 300초 / 재발송 대기 60초 — 디자인 8.1). */
data class SendCodeResult(val expiresInSec: Int, val resendAfterSec: Int)

data class SignUpRequest(
    val email: String,
    val name: String,
    val password: String,
    val verificationToken: String,
    val termsAgreed: Boolean,
    val privacyAgreed: Boolean,
)

/** 로그인 실패 종류 (디자인 0.1.1~0.1.3) */
sealed interface LoginFailure {
    /** 이메일이나 비밀번호가 틀림 — 어느 쪽인지, 가입된 이메일인지 알려주지 않는다 */
    data object InvalidCredentials : LoginFailure

    /** 연속 실패 횟수 초과로 잠김 (기본 5번/15분). `retryAfterSec` 만큼 로그인 불가 */
    data class Locked(val retryAfterSec: Int) : LoginFailure

    /** 정지·탈퇴 계정 (0.1.3 "이용이 제한된 계정이에요") */
    data object Restricted : LoginFailure

    data object Network : LoginFailure
    data object Other : LoginFailure
}

/** 인증코드 확인 실패 (디자인 0.2.2) */
enum class CodeError { MISMATCH, EXPIRED, TOO_MANY_ATTEMPTS }

/** 인증코드 발송 실패 (디자인 0.2.1) */
sealed interface SendCodeFailure {
    /** 이미 가입된 이메일 ("이미 가입된 이메일이에요" + [로그인하기]) — 가입 용도에서만 */
    data object AlreadyRegistered : SendCodeFailure

    /** 하루 발송 횟수 초과 */
    data object DailyLimit : SendCodeFailure

    /** 재발송 대기 중 */
    data class TooSoon(val retryAfterSec: Int?) : SendCodeFailure

    data object Network : SendCodeFailure
    data object Other : SendCodeFailure
}

/** 가입 요청 거절 사유 (디자인 0.2.x) */
enum class SignupRejection { EMAIL_TAKEN, VERIFICATION_INVALID, PASSWORD_INVALID, CONSENT_REQUIRED, NETWORK, OTHER }

/** 비밀번호 재설정·변경에서 서버가 돌려주는 거절 (디자인 0.3.2, 5.2.1) */
enum class PasswordFailure {
    /** "기존 비밀번호와 다른 비밀번호를 입력해 주세요" (서버 확인) */
    SAME_AS_OLD,

    /** "현재 비밀번호가 맞지 않아요" (변경 화면) */
    WRONG_CURRENT,

    /** 규칙 위반 (서버가 앱보다 엄격할 때) */
    INVALID_FORMAT,

    /** 인증 토큰 만료·무효 (재설정) */
    VERIFICATION_INVALID,
    NETWORK,
    OTHER,
}

/** 학교 메일 도메인 한 개. 도메인 목록은 서버가 준다 (D-23). 하나뿐이면 UI 는 선택 시트·▾ 없이 글자로만 표시. */
data class SchoolDomain(val domain: String, val schoolId: String, val schoolName: String)

/** 내 정보 (디자인 5.1.1: 이름만 표시, 닉네임 없음) */
data class UserProfile(val name: String, val email: String?)

/** 인증코드 확인 실패: 코드 문제(틀림·만료·횟수 초과)이거나 확인 자체가 안 된 경우 */
sealed interface VerifyCodeFailure {
    data class Code(val error: CodeError) : VerifyCodeFailure
    data object Network : VerifyCodeFailure
    data object Other : VerifyCodeFailure
}

/**
 * 학교 메일 도메인 목록 (디자인 0.1.4). 도메인이 하나뿐이면 선택 시트·▾ 없이 글자로만 표시한다.
 * 도메인으로 학교(메뉴·매장 데이터의 단위)가 정해진다.
 */
class EmailDomains(val items: List<SchoolDomain>) {
    val showsPicker: Boolean get() = items.size > 1

    /** 처음 선택해 둘 도메인 (목록의 첫 번째) */
    val default: SchoolDomain? get() = items.firstOrNull()

    val domains: List<String> get() = items.map { it.domain }

    fun find(domain: String?): SchoolDomain? =
        domain?.let { d -> items.firstOrNull { it.domain.equals(d, ignoreCase = true) } }

    fun schoolIdOf(domain: String?): String? = find(domain)?.schoolId
}
