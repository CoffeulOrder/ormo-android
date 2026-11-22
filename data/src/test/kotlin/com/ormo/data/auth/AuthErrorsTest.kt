package com.ormo.data.auth

import com.ormo.data.remote.AppError
import com.ormo.domain.auth.CodeError
import com.ormo.domain.auth.LoginFailure
import com.ormo.domain.auth.PasswordFailure
import com.ormo.domain.auth.SendCodeFailure
import com.ormo.domain.auth.SignupRejection
import com.ormo.domain.auth.VerifyCodeFailure
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthErrorsTest {
    private fun api(status: Int, code: String? = null, body: String? = null) =
        AppError.Api(status, code, body?.let { Json.parseToJsonElement(it) })

    private val network = AppError.Network(IOException())

    @Test fun `로그인 - 이메일이나 비밀번호가 틀림`() {
        assertEquals(LoginFailure.InvalidCredentials, AuthErrors.login(api(401, "INVALID_CREDENTIALS")))
        assertEquals(LoginFailure.InvalidCredentials, AuthErrors.login(api(401, "AUTH_001")))
        assertEquals(LoginFailure.InvalidCredentials, AuthErrors.login(api(401))) // 코드를 몰라도 401 이면
    }

    @Test fun `로그인 - 잠김과 남은 시간`() {
        assertEquals(LoginFailure.Locked(600), AuthErrors.login(api(423, "AUTH_002", """{"retryAfterSec":600}""")))
        assertEquals(LoginFailure.Locked(120), AuthErrors.login(api(423, null, """{"data":{"retryAfterSec":120}}""")))
        assertEquals(LoginFailure.Locked(AuthErrors.DEFAULT_LOCK_SEC), AuthErrors.login(api(423, "AUTH_002")))
        assertEquals(LoginFailure.Locked(AuthErrors.DEFAULT_LOCK_SEC), AuthErrors.login(api(423, null, """{"retryAfterSec":0}""")))
        assertEquals(LoginFailure.Locked(30), AuthErrors.login(api(429, null, """{"retryAfterSec":30}""")))
    }

    @Test fun `로그인 - 이용 제한 계정`() {
        assertEquals(LoginFailure.Restricted, AuthErrors.login(api(403, "AUTH_005")))
        assertEquals(LoginFailure.Restricted, AuthErrors.login(api(403, "ACCOUNT_WITHDRAWN")))
        assertEquals(LoginFailure.Restricted, AuthErrors.login(api(403)))
    }

    @Test fun `로그인 - 네트워크와 기타`() {
        assertEquals(LoginFailure.Network, AuthErrors.login(network))
        assertEquals(LoginFailure.Other, AuthErrors.login(api(500)))
        assertEquals(LoginFailure.Other, AuthErrors.login(AppError.Schema("login")))
        assertEquals(LoginFailure.Other, AuthErrors.login(IllegalStateException()))
    }

    @Test fun `인증코드 발송 오류`() {
        assertEquals(SendCodeFailure.AlreadyRegistered, AuthErrors.sendCode(api(409, "EMAIL_TAKEN")))
        assertEquals(SendCodeFailure.AlreadyRegistered, AuthErrors.sendCode(api(409)))
        assertEquals(SendCodeFailure.AlreadyRegistered, AuthErrors.sendCode(api(400, "EV006")))
        assertEquals(SendCodeFailure.DailyLimit, AuthErrors.sendCode(api(429, "EV003")))
        assertEquals(SendCodeFailure.TooSoon(30), AuthErrors.sendCode(api(429, "TOO_MANY_REQUESTS", """{"resendAfterSec":30}""")))
        assertEquals(SendCodeFailure.TooSoon(null), AuthErrors.sendCode(api(429)))
        assertEquals(SendCodeFailure.Network, AuthErrors.sendCode(network))
        assertEquals(SendCodeFailure.Other, AuthErrors.sendCode(api(500)))
    }

    @Test fun `인증코드 확인 오류`() {
        fun code(e: Throwable) = (AuthErrors.verifyCode(e) as VerifyCodeFailure.Code).error
        assertEquals(CodeError.MISMATCH, code(api(400, "CODE_INVALID")))
        assertEquals(CodeError.MISMATCH, code(api(400, "EV004")))
        assertEquals(CodeError.EXPIRED, code(api(410, "CODE_EXPIRED")))
        assertEquals(CodeError.EXPIRED, code(api(400, "EV005")))
        assertEquals(CodeError.TOO_MANY_ATTEMPTS, code(api(429, "TOO_MANY_ATTEMPTS")))
        assertEquals(CodeError.TOO_MANY_ATTEMPTS, code(api(400, "TOO_MANY_ATTEMPTS"))) // 코드가 상태보다 우선
        assertEquals(VerifyCodeFailure.Network, AuthErrors.verifyCode(network))
        assertEquals(VerifyCodeFailure.Other, AuthErrors.verifyCode(api(500)))
    }

    @Test fun `가입 거절 사유`() {
        assertEquals(SignupRejection.EMAIL_TAKEN, AuthErrors.signUp(api(409, "EMAIL_TAKEN")))
        assertEquals(SignupRejection.EMAIL_TAKEN, AuthErrors.signUp(api(409)))
        assertEquals(SignupRejection.EMAIL_TAKEN, AuthErrors.signUp(api(409, "U001")))
        assertEquals(SignupRejection.VERIFICATION_INVALID, AuthErrors.signUp(api(400, "VERIFICATION_INVALID")))
        assertEquals(SignupRejection.VERIFICATION_INVALID, AuthErrors.signUp(api(400, "EV008")))
        assertEquals(SignupRejection.PASSWORD_INVALID, AuthErrors.signUp(api(400, "U002")))
        assertEquals(SignupRejection.CONSENT_REQUIRED, AuthErrors.signUp(api(400, "U003")))
        assertEquals(SignupRejection.NETWORK, AuthErrors.signUp(network))
        assertEquals(SignupRejection.OTHER, AuthErrors.signUp(api(500)))
    }

    @Test fun `비밀번호 재설정과 변경 거절`() {
        assertEquals(PasswordFailure.SAME_AS_OLD, AuthErrors.password(api(400, "PASSWORD_SAME_AS_OLD")))
        assertEquals(PasswordFailure.WRONG_CURRENT, AuthErrors.password(api(400, "CURRENT_PASSWORD_MISMATCH")))
        assertEquals(PasswordFailure.WRONG_CURRENT, AuthErrors.password(api(401, "AUTH_001")))
        assertEquals(PasswordFailure.INVALID_FORMAT, AuthErrors.password(api(400, "PASSWORD_INVALID")))
        assertEquals(PasswordFailure.VERIFICATION_INVALID, AuthErrors.password(api(400, "EV008")))
        assertEquals(PasswordFailure.NETWORK, AuthErrors.password(network))
        assertEquals(PasswordFailure.OTHER, AuthErrors.password(api(500)))
    }
}

/** 실제 BE 의 오류 코드(AUTH_00x · EV00x · U00x)와 응답 형태로 확인 */
class AuthErrorsRealBeTest {
    private fun api(status: Int, code: String? = null, body: String? = null) =
        AppError.Api(status, code, body?.let { Json.parseToJsonElement(it) })

    @Test fun `로그인 잠김 - BE 는 lockedUntil 시각을 준다`() {
        val now = java.time.Instant.parse("2026-11-23T01:00:00Z")
        val body = """{"status":423,"code":"AUTH_002","message":"잠겼어요","data":{"lockedUntil":"2026-11-23T01:15:00Z"}}"""
        assertEquals(LoginFailure.Locked(900), AuthErrors.login(api(423, "AUTH_002", body), now))
        // 이미 지난 시각·깨진 값은 기본값
        val past = """{"data":{"lockedUntil":"2026-11-23T00:00:00Z"}}"""
        assertEquals(LoginFailure.Locked(AuthErrors.DEFAULT_LOCK_SEC), AuthErrors.login(api(423, "AUTH_002", past), now))
        val broken = """{"data":{"lockedUntil":"곧"}}"""
        assertEquals(LoginFailure.Locked(AuthErrors.DEFAULT_LOCK_SEC), AuthErrors.login(api(423, "AUTH_002", broken), now))
    }

    @Test fun `인증코드 - EV004 틀림, EV005 만료(5회 실패 포함), 그 밖의 400 은 틀림`() {
        assertEquals(VerifyCodeFailure.Code(CodeError.MISMATCH), AuthErrors.verifyCode(api(400, "EV004")))
        assertEquals(VerifyCodeFailure.Code(CodeError.EXPIRED), AuthErrors.verifyCode(api(410, "EV005")))
        // EV007 은 메일 발송 실패(503)이지 '횟수 초과'가 아니다
        assertEquals(VerifyCodeFailure.Other, AuthErrors.verifyCode(api(503, "EV007")))
    }

    @Test fun `인증코드 발송 - EV006 가입됨, EV002 너무 빨리, EV003 하루 한도`() {
        assertEquals(SendCodeFailure.AlreadyRegistered, AuthErrors.sendCode(api(409, "EV006")))
        assertEquals(SendCodeFailure.TooSoon(null), AuthErrors.sendCode(api(429, "EV002")))
        assertEquals(SendCodeFailure.DailyLimit, AuthErrors.sendCode(api(429, "EV003")))
        // 지원하지 않는 학교 도메인·메일 발송 실패는 일반 실패
        assertEquals(SendCodeFailure.Other, AuthErrors.sendCode(api(400, "EV001")))
        assertEquals(SendCodeFailure.Other, AuthErrors.sendCode(api(503, "EV007")))
    }

    @Test fun `가입 - U001 U002 U003 EV008`() {
        assertEquals(SignupRejection.EMAIL_TAKEN, AuthErrors.signUp(api(409, "U001")))
        assertEquals(SignupRejection.PASSWORD_INVALID, AuthErrors.signUp(api(400, "U002")))
        assertEquals(SignupRejection.CONSENT_REQUIRED, AuthErrors.signUp(api(400, "U003")))
        assertEquals(SignupRejection.VERIFICATION_INVALID, AuthErrors.signUp(api(400, "EV008")))
    }

    @Test fun `비밀번호 변경 - U004 는 현재 비밀번호 틀림, U002 는 규칙 위반`() {
        assertEquals(PasswordFailure.WRONG_CURRENT, AuthErrors.password(api(401, "U004")))
        assertEquals(PasswordFailure.INVALID_FORMAT, AuthErrors.password(api(400, "U002")))
        assertEquals(PasswordFailure.VERIFICATION_INVALID, AuthErrors.password(api(400, "EV008")))
    }

    @Test fun `탈퇴 - U005 진행 중 주문, U004 비밀번호 틀림`() {
        val body = """{"status":409,"code":"U005","message":"진행 중인 주문이 있어 탈퇴할 수 없어요.","data":{"activeOrderCount":2}}"""
        assertEquals(com.ormo.domain.profile.WithdrawFailure.ACTIVE_ORDERS, AuthErrors.withdraw(api(409, "U005", body)))
        assertEquals(com.ormo.domain.profile.WithdrawFailure.WRONG_PASSWORD, AuthErrors.withdraw(api(401, "U004")))
    }
}
