package com.ormo.data.auth

import com.ormo.data.remote.AppError
import com.ormo.data.remote.intValue
import com.ormo.domain.auth.CodeError
import com.ormo.domain.auth.LoginFailure
import com.ormo.domain.auth.PasswordFailure
import com.ormo.domain.auth.SendCodeFailure
import com.ormo.domain.auth.SignupRejection
import com.ormo.domain.auth.VerifyCodeFailure
import com.ormo.domain.profile.WithdrawFailure
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * 서버 오류 → 화면에 보여줄 실패 종류. **BE 오류 코드 매핑은 전부 여기 한 곳**.
 * 실제 BE 의 코드(AUTH_00x · EV00x · U00x · C00x)를 기준으로 하고, 앱이 예전에 가정했던 이름도 받아 둔다.
 *
 * 코드를 모르는 오류는 상태 코드로 판단하고, 그래도 모르면 Other (일반 실패 문구).
 */
object AuthErrors {
    /** 잠김 시간을 서버가 안 알려줄 때의 기본값 (디자인 8.1: 15분) — TODO(BE): 응답 필드 확정 */
    const val DEFAULT_LOCK_SEC = 15 * 60

    private val INVALID_CREDENTIALS = setOf("INVALID_CREDENTIALS", "AUTH_001")
    private val LOCKED = setOf("ACCOUNT_LOCKED", "AUTH_002", "TOO_MANY_LOGIN_ATTEMPTS")
    private val RESTRICTED = setOf("ACCOUNT_RESTRICTED", "ACCOUNT_SUSPENDED", "ACCOUNT_WITHDRAWN", "AUTH_005")

    private val EMAIL_TAKEN = setOf("EMAIL_TAKEN", "U001", "EV006")
    private val DAILY_LIMIT = setOf("DAILY_LIMIT_EXCEEDED", "EV003")
    private val RESEND_TOO_SOON = setOf("TOO_MANY_REQUESTS", "RESEND_TOO_SOON", "EV002")

    private val CODE_INVALID = setOf("CODE_INVALID", "EV004")
    private val CODE_EXPIRED = setOf("CODE_EXPIRED", "EV005")
    // BE: 확인 5회 실패하면 코드가 폐기되어 EV005(410) 로 온다 — 만료와 구분하는 것은 `VerificationState` 가 한다. EV007 은 메일 발송 실패(503)다.
    private val CODE_TOO_MANY = setOf("TOO_MANY_ATTEMPTS")

    private val VERIFICATION_INVALID = setOf("VERIFICATION_INVALID", "EV008")
    private val PASSWORD_INVALID = setOf("PASSWORD_INVALID", "U002")
    private val CONSENT_REQUIRED = setOf("CONSENT_REQUIRED", "U003")
    private val PASSWORD_SAME = setOf("PASSWORD_SAME_AS_OLD", "PASSWORD_SAME", "SAME_PASSWORD")
    private val ACTIVE_ORDERS = setOf("ACTIVE_ORDER_EXISTS", "ORDER_IN_PROGRESS", "HAS_ACTIVE_ORDERS", "U005")
    private val CURRENT_PASSWORD_WRONG = setOf("CURRENT_PASSWORD_MISMATCH", "WRONG_CURRENT_PASSWORD", "U004")

    fun login(e: Throwable, now: Instant = Instant.now()): LoginFailure {
        val api = e as? AppError.Api ?: return if (e is AppError.Network) LoginFailure.Network else LoginFailure.Other
        val code = api.code
        return when {
            api.status == 423 || code in LOCKED ->
                LoginFailure.Locked(retryAfterSec(api.body, "retryAfterSec") ?: secondsUntil(api.body, "lockedUntil", now) ?: DEFAULT_LOCK_SEC)
            code in RESTRICTED || (api.status == 403 && code == null) -> LoginFailure.Restricted
            code in INVALID_CREDENTIALS || api.status == 401 -> LoginFailure.InvalidCredentials
            api.status == 429 -> LoginFailure.Locked(retryAfterSec(api.body, "retryAfterSec") ?: DEFAULT_LOCK_SEC)
            else -> LoginFailure.Other
        }
    }

    fun sendCode(e: Throwable): SendCodeFailure {
        val api = e as? AppError.Api ?: return if (e is AppError.Network) SendCodeFailure.Network else SendCodeFailure.Other
        val code = api.code
        return when {
            code in EMAIL_TAKEN || api.status == 409 -> SendCodeFailure.AlreadyRegistered
            code in DAILY_LIMIT -> SendCodeFailure.DailyLimit
            code in RESEND_TOO_SOON || api.status == 429 -> SendCodeFailure.TooSoon(retryAfterSec(api.body, "resendAfterSec"))
            else -> SendCodeFailure.Other
        }
    }

    fun verifyCode(e: Throwable): VerifyCodeFailure {
        val api = e as? AppError.Api ?: return if (e is AppError.Network) VerifyCodeFailure.Network else VerifyCodeFailure.Other
        val code = api.code
        return when {
            code in CODE_TOO_MANY || api.status == 429 -> VerifyCodeFailure.Code(CodeError.TOO_MANY_ATTEMPTS)
            code in CODE_EXPIRED || api.status == 410 -> VerifyCodeFailure.Code(CodeError.EXPIRED)
            code in CODE_INVALID || api.status == 400 ->
                VerifyCodeFailure.Code(CodeError.MISMATCH, retryAfterSec(api.body, "remainingAttempts")) // 양의 정수 필드 읽기
            else -> VerifyCodeFailure.Other
        }
    }

    fun signUp(e: Throwable): SignupRejection {
        val api = e as? AppError.Api ?: return if (e is AppError.Network) SignupRejection.NETWORK else SignupRejection.OTHER
        val code = api.code
        return when {
            code in EMAIL_TAKEN || (api.status == 409 && code == null) -> SignupRejection.EMAIL_TAKEN
            code in VERIFICATION_INVALID -> SignupRejection.VERIFICATION_INVALID
            code in PASSWORD_INVALID -> SignupRejection.PASSWORD_INVALID
            code in CONSENT_REQUIRED -> SignupRejection.CONSENT_REQUIRED
            else -> SignupRejection.OTHER
        }
    }

    /** 비밀번호 재설정/변경 공통 */
    fun password(e: Throwable): PasswordFailure {
        val api = e as? AppError.Api ?: return if (e is AppError.Network) PasswordFailure.NETWORK else PasswordFailure.OTHER
        val code = api.code
        return when {
            code in PASSWORD_SAME -> PasswordFailure.SAME_AS_OLD
            code in CURRENT_PASSWORD_WRONG || code in INVALID_CREDENTIALS -> PasswordFailure.WRONG_CURRENT
            code in PASSWORD_INVALID -> PasswordFailure.INVALID_FORMAT
            code in VERIFICATION_INVALID -> PasswordFailure.VERIFICATION_INVALID
            else -> PasswordFailure.OTHER
        }
    }

    /** 회원 탈퇴 (5.3): 비밀번호 틀림 / 진행 중 주문 있음 */
    fun withdraw(e: Throwable): WithdrawFailure {
        val api = e as? AppError.Api ?: return if (e is AppError.Network) WithdrawFailure.NETWORK else WithdrawFailure.OTHER
        val code = api.code
        return when {
            code in ACTIVE_ORDERS -> WithdrawFailure.ACTIVE_ORDERS
            code in CURRENT_PASSWORD_WRONG || code in INVALID_CREDENTIALS -> WithdrawFailure.WRONG_PASSWORD
            else -> WithdrawFailure.OTHER
        }
    }

    /** BE 는 잠김을 `data.lockedUntil`(ISO-8601 시각)로 알려 준다 → 남은 초 (이미 지났거나 형식이 틀리면 null) */
    private fun secondsUntil(body: JsonElement?, field: String, now: Instant): Int? {
        val o = body as? JsonObject ?: return null
        val text = ((o["data"] as? JsonObject)?.get(field) ?: o[field]).let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?: return null
        val until = try { Instant.parse(text) } catch (_: java.time.format.DateTimeParseException) { return null }
        val sec = java.time.Duration.between(now, until).seconds
        return if (sec > 0) minOf(sec, Int.MAX_VALUE.toLong()).toInt() else null
    }

    /** 본문(또는 봉투의 data)에서 양의 정수 필드를 읽는다 */
    private fun retryAfterSec(body: JsonElement?, field: String): Int? {
        val o = body as? JsonObject ?: return null
        val direct = o[field].intValue()
        val nested = (o["data"] as? JsonObject)?.get(field).intValue()
        return (direct ?: nested)?.takeIf { it > 0 }
    }
}
