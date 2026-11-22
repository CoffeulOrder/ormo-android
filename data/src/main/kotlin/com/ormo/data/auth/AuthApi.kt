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
import kotlinx.serialization.json.buildJsonArray
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
 * 인증 API. **실제 BE(`Coffeul/Backend`, 2026-11 기준) 계약**을 따른다 — 기준 URL 은 `…/api/v1` 까지 포함한다.
 * 구현은 실패를 [AppError] 로 던진다 (표시용 실패 종류로의 변환은 [AuthErrors]).
 *
 *   POST   /verifications/email          { email, purpose }            -> { email, expiresInSeconds, resendAvailableInSeconds }   (SM-1)
 *   POST   /verifications/email/confirm  { email, purpose, code }      -> { verificationToken, expiresInSeconds }                  (SM-2)
 *   POST   /members                      { verificationToken, name, password, agreements:[{type,version,agreed}] }
 *                                                                      -> { memberId, name, email, school:{schoolId,name,campus}, accessToken, refreshToken, expiresIn }  (SM-3)
 *   POST   /auth/login                   { email, password }           -> { accessToken, refreshToken, expiresIn, member:{memberId,name,schoolId} }  (MS-1)
 *   POST   /auth/refresh                 { refreshToken }              -> { accessToken, refreshToken, expiresIn }                 (MS-3, 회전)
 *   POST   /auth/logout                  { refreshToken }              (Authorization 필요)                                        (MS-4)
 *   POST   /members/password-reset       { verificationToken, newPassword }  (이메일은 토큰에 묶임)                                 (SM-6)
 *   PATCH  /members/me/password          { currentPassword, newPassword }    (성공하면 서버가 모든 기기 세션 폐기)                    (SM-5)
 *   GET    /members/me                   -> { memberId, name, email, school:{schoolId,name,campus}, createdAt }                    (SM-4)
 *   DELETE /members/me                   { password }                  (진행 중 주문 있으면 409 U005)                              (SM-7)
 *
 * 로그인은 이메일·비밀번호만 보낸다 — 학교는 서버가 이메일 도메인으로 정하고, 자동 로그인 여부는 앱(저장소)의 몫이다.
 * 가입은 이메일을 보내지 않는다 — 인증 토큰에 묶인 이메일을 서버가 쓴다 (바꿔치기 방지).
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
            "/verifications/email",
            buildJsonObject {
                put("email", email)
                put("purpose", purpose.name)
            },
        )
        val o = raw as? JsonObject
        return SendCodeResult(
            expiresInSec = o?.get("expiresInSeconds").intValue()?.takeIf { it > 0 } ?: DEFAULT_CODE_TTL_SEC,
            resendAfterSec = o?.get("resendAvailableInSeconds").intValue()?.takeIf { it > 0 } ?: DEFAULT_RESEND_SEC,
        )
    }

    override suspend fun verifyEmailCode(email: String, purpose: VerifyPurpose, code: String): String {
        val raw = client.post(
            "/verifications/email/confirm",
            buildJsonObject {
                put("email", email)
                put("purpose", purpose.name)
                put("code", code)
            },
        )
        return (raw as? JsonObject)?.get("verificationToken").str() ?: throw AppError.Schema("confirm")
    }

    override suspend fun signUp(req: SignUpRequest): LoginSession? {
        val raw = client.post(
            "/members",
            buildJsonObject {
                put("verificationToken", req.verificationToken)
                put("name", req.name)
                put("password", req.password)
                // 추천인 코드: BE 에 아직 필드가 없다 (베타 꺼짐). 값이 있을 때만 싣는다.
                if (!req.referralCode.isNullOrBlank()) put("referralCode", req.referralCode)
                put(
                    "agreements",
                    buildJsonArray {
                        add(agreement("SERVICE", req.termsAgreed))
                        add(agreement("PRIVACY", req.privacyAgreed))
                    },
                )
            },
        )
        return AuthParsers.registered(raw)
    }

    private fun agreement(type: String, agreed: Boolean) = buildJsonObject {
        put("type", type)
        put("version", TERMS_VERSION)
        put("agreed", agreed)
    }

    override suspend fun login(email: String, password: String, schoolId: String?, autoLogin: Boolean): LoginSession {
        val raw = client.post(
            "/auth/login",
            buildJsonObject {
                put("email", email)
                put("password", password)
            },
        )
        return AuthParsers.loggedIn(raw) ?: throw AppError.Schema("login")
    }

    override suspend fun refresh(refreshToken: String): RefreshResult {
        val raw = client.post("/auth/refresh", buildJsonObject { put("refreshToken", refreshToken) })
        return AuthParsers.refreshResult(raw)
    }

    override suspend fun logout(refreshToken: String) {
        client.post("/auth/logout", buildJsonObject { put("refreshToken", refreshToken) })
    }

    /** 이메일은 보내지 않는다 — 인증 토큰(PASSWORD_RESET)에 묶여 있다 */
    override suspend fun resetPassword(email: String, verificationToken: String, newPassword: String) {
        client.post(
            "/members/password-reset",
            buildJsonObject {
                put("verificationToken", verificationToken)
                put("newPassword", newPassword)
            },
        )
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        client.patch(
            "/members/me/password",
            buildJsonObject {
                put("currentPassword", currentPassword)
                put("newPassword", newPassword)
            },
        )
    }

    override suspend fun me(): UserProfile = AuthParsers.profile(client.get("/members/me"))

    override suspend fun withdraw(password: String) {
        client.delete("/members/me", buildJsonObject { put("password", password) })
    }

    companion object {
        /** 서버 응답이 없을 때의 기본값 (디자인 8.1: 화면 예시 5:00, 재발송 1분) */
        const val DEFAULT_CODE_TTL_SEC = 300
        const val DEFAULT_RESEND_SEC = 60

        /**
         * 동의한 약관의 버전. BE 는 길이(20자 이하)만 검사하고 저장한다.
         * TODO(BE·법무): 약관 버전 표기 확정 — 약관 본문이 바뀌면 같이 올린다.
         */
        const val TERMS_VERSION = "1.0"
    }
}

/** 응답 검증 (파서). 형태가 틀리면 [AppError.Schema]. */
internal object AuthParsers {
    /** 로그인 응답: 토큰은 최상위, 이름·학교는 `member` 안 */
    fun loggedIn(raw: JsonElement?): LoginSession? {
        val o = raw as? JsonObject ?: return null
        val member = o["member"] as? JsonObject
        return session(o, schoolId = member?.get("schoolId").id(), name = member?.get("name").str())
    }

    /** 가입 응답: 토큰은 최상위, 이름은 최상위, 학교는 `school` 안 */
    fun registered(raw: JsonElement?): LoginSession? {
        val o = raw as? JsonObject ?: return null
        val school = o["school"] as? JsonObject
        return session(o, schoolId = school?.get("schoolId").id(), name = o["name"].str())
    }

    private fun session(o: JsonObject, schoolId: String?, name: String?): LoginSession? {
        val access = o["accessToken"].str() ?: return null
        val refresh = o["refreshToken"].str() ?: return null
        return LoginSession(accessToken = access, refreshToken = refresh, schoolId = schoolId, name = name)
    }

    fun refreshResult(raw: JsonElement?): RefreshResult {
        val o = raw as? JsonObject ?: throw AppError.Schema("refresh")
        val access = o["accessToken"].str() ?: throw AppError.Schema("refresh")
        return RefreshResult(access, o["refreshToken"].str(), o["name"].str())
    }

    fun profile(raw: JsonElement?): UserProfile {
        val o = raw as? JsonObject ?: throw AppError.Schema("me")
        val name = o["name"].str() ?: throw AppError.Schema("me.name")
        val schoolName = (o["school"] as? JsonObject)?.get("name").str()?.takeIf { it.isNotBlank() }
        return UserProfile(name, o["email"].str(), schoolName)
    }
}
