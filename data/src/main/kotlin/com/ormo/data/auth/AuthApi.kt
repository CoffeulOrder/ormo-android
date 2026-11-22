package com.ormo.data.auth

import com.ormo.data.remote.ApiClient
import com.ormo.data.remote.AppError
import com.ormo.data.remote.id
import com.ormo.data.remote.intValue
import com.ormo.data.remote.str
import com.ormo.domain.auth.SendCodeResult
import com.ormo.domain.auth.SignUpRequest
import com.ormo.domain.auth.UserProfile
import com.ormo.domain.auth.VerifyPurpose
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 로그인(또는 가입 직후) 응답. `schoolId` 는 서버가 이메일 도메인으로 정해 줄 때만 온다. */
data class LoginSession(
    val accessToken: String,
    val refreshToken: String,
    val schoolId: String? = null,
    val name: String? = null,
)

/** 저장된 리프레시 토큰으로 세션 복구. 서버가 토큰을 회전시키면 `refreshToken` 이 온다. */
data class RefreshResult(val accessToken: String, val refreshToken: String? = null, val name: String? = null)

/**
 * 인증 API (RN `api/auth-api.ts` + 새 디자인 명세 반영). 구현은 실패를 [AppError] 로 던진다
 * (표시용 실패 종류로의 변환은 [AuthErrors]).
 *
 *   POST /auth/email/send-code    { email, purpose }                       -> { expiresInSec, resendAfterSec }
 *   POST /auth/email/verify-code  { email, purpose, code }                 -> { verificationToken }
 *   POST /auth/signup             { email, name, password, verificationToken, consents:{terms,privacy} }
 *                                                                          -> { accessToken?, refreshToken?, schoolId?, name? }
 *   POST /auth/login              { email, password, schoolId?, autoLogin } -> { accessToken, refreshToken, schoolId?, name? }
 *   POST /auth/refresh            { refreshToken }                          -> { accessToken, refreshToken?, name? }
 *   POST /auth/logout             { refreshToken }                          (본문 무시)
 *   POST /auth/password/reset     { email, verificationToken, newPassword }
 *   POST /me/password             { currentPassword, newPassword }          (성공하면 서버가 모든 기기 세션 폐기 — 디자인 5.2.1)
 *   GET  /me                      -> { name, email? }
 *
 * ⚠️ 경로/형태는 협의된 스펙이 없어 앱이 가정한 것이다 (RN 앱과 같은 가정 + 새 디자인: 닉네임 없음, 이름, 현재 비밀번호 방식).
 * 서버는 인증 토큰이 해당 이메일·용도로 발급된 것인지, 이메일 도메인·`+` 별칭 규칙을 반드시 다시 검증해야 한다.
 */
interface AuthApi {
    suspend fun sendEmailCode(email: String, purpose: VerifyPurpose): SendCodeResult
    suspend fun verifyEmailCode(email: String, purpose: VerifyPurpose, code: String): String

    /** 가입 응답에 토큰이 있으면 돌려준다(가입 후 바로 로그인). 없으면 null → 호출한 쪽이 로그인한다. */
    suspend fun signUp(req: SignUpRequest): LoginSession?
    suspend fun login(email: String, password: String, schoolId: String?, autoLogin: Boolean): LoginSession
    suspend fun refresh(refreshToken: String): RefreshResult
    suspend fun logout(refreshToken: String)
    suspend fun resetPassword(email: String, verificationToken: String, newPassword: String)
    suspend fun changePassword(currentPassword: String, newPassword: String)
    suspend fun me(): UserProfile

    /** 회원 탈퇴 (5.3.1: 비밀번호 확인 후). 진행 중 주문이 있으면 서버가 거절한다 (5.3.2). */
    suspend fun withdraw(password: String)
}

class RemoteAuthApi(private val client: ApiClient) : AuthApi {

    override suspend fun sendEmailCode(email: String, purpose: VerifyPurpose): SendCodeResult {
        val raw = client.post(
            "/auth/email/send-code",
            buildJsonObject {
                put("email", email)
                put("purpose", purpose.name)
            },
        )
        val o = raw as? JsonObject
        return SendCodeResult(
            expiresInSec = o?.get("expiresInSec").intValue()?.takeIf { it > 0 } ?: DEFAULT_CODE_TTL_SEC,
            resendAfterSec = o?.get("resendAfterSec").intValue()?.takeIf { it > 0 } ?: DEFAULT_RESEND_SEC,
        )
    }

    override suspend fun verifyEmailCode(email: String, purpose: VerifyPurpose, code: String): String {
        val raw = client.post(
            "/auth/email/verify-code",
            buildJsonObject {
                put("email", email)
                put("purpose", purpose.name)
                put("code", code)
            },
        )
        return (raw as? JsonObject)?.get("verificationToken").str() ?: throw AppError.Schema("verify-code")
    }

    override suspend fun signUp(req: SignUpRequest): LoginSession? {
        val raw = client.post(
            "/auth/signup",
            buildJsonObject {
                put("email", req.email)
                put("name", req.name)
                put("password", req.password)
                put("verificationToken", req.verificationToken)
                if (!req.referralCode.isNullOrBlank()) put("referralCode", req.referralCode)
                put(
                    "consents",
                    buildJsonObject {
                        put("terms", req.termsAgreed)
                        put("privacy", req.privacyAgreed)
                    },
                )
            },
        )
        return AuthParsers.optionalLoginSession(raw)
    }

    override suspend fun login(email: String, password: String, schoolId: String?, autoLogin: Boolean): LoginSession {
        val raw = client.post(
            "/auth/login",
            buildJsonObject {
                put("email", email)
                put("password", password)
                if (schoolId != null) put("schoolId", schoolId)
                put("autoLogin", autoLogin)
            },
        )
        return AuthParsers.optionalLoginSession(raw) ?: throw AppError.Schema("login")
    }

    override suspend fun refresh(refreshToken: String): RefreshResult {
        val raw = client.post("/auth/refresh", buildJsonObject { put("refreshToken", refreshToken) })
        return AuthParsers.refreshResult(raw)
    }

    override suspend fun logout(refreshToken: String) {
        client.post("/auth/logout", buildJsonObject { put("refreshToken", refreshToken) })
    }

    override suspend fun resetPassword(email: String, verificationToken: String, newPassword: String) {
        client.post(
            "/auth/password/reset",
            buildJsonObject {
                put("email", email)
                put("verificationToken", verificationToken)
                put("newPassword", newPassword)
            },
        )
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        client.post(
            "/me/password",
            buildJsonObject {
                put("currentPassword", currentPassword)
                put("newPassword", newPassword)
            },
        )
    }

    override suspend fun me(): UserProfile = AuthParsers.profile(client.get("/me"))

    /** ⚠️ 경로/본문은 앱이 가정한 것 (BE 확정 전): `POST /me/withdraw { password }` */
    override suspend fun withdraw(password: String) {
        client.post("/me/withdraw", buildJsonObject { put("password", password) })
    }

    companion object {
        /** 서버 응답이 없을 때의 기본값 (디자인 8.1: 화면 예시 5:00, 재발송 1분) */
        const val DEFAULT_CODE_TTL_SEC = 300
        const val DEFAULT_RESEND_SEC = 60
    }
}

/** 응답 검증 (파서). 형태가 틀리면 [AppError.Schema]. */
internal object AuthParsers {
    fun optionalLoginSession(raw: JsonElement?): LoginSession? {
        val o = raw as? JsonObject ?: return null
        val access = o["accessToken"].str() ?: return null
        val refresh = o["refreshToken"].str() ?: return null
        return LoginSession(
            accessToken = access,
            refreshToken = refresh,
            schoolId = o["schoolId"].id(),
            name = o["name"].str(),
        )
    }

    fun refreshResult(raw: JsonElement?): RefreshResult {
        val o = raw as? JsonObject ?: throw AppError.Schema("refresh")
        val access = o["accessToken"].str() ?: throw AppError.Schema("refresh")
        return RefreshResult(access, o["refreshToken"].str(), o["name"].str())
    }

    fun profile(raw: JsonElement?): UserProfile {
        val o = raw as? JsonObject ?: throw AppError.Schema("me")
        val name = o["name"].str() ?: throw AppError.Schema("me.name")
        return UserProfile(name, o["email"].str())
    }
}
