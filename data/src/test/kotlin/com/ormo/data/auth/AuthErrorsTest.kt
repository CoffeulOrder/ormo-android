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
