package com.ormo.customer.fake

import com.ormo.data.auth.AuthApi
import com.ormo.data.auth.LoginSession
import com.ormo.data.auth.RefreshResult
import com.ormo.data.auth.SchoolDirectoryApi
import com.ormo.data.remote.AppError
import com.ormo.domain.auth.EmailDomains
import com.ormo.domain.auth.SchoolDomain
import com.ormo.domain.auth.SendCodeResult
import com.ormo.domain.auth.SignUpRequest
import com.ormo.domain.auth.UserProfile
import com.ormo.domain.auth.VerifyPurpose
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 개발용 가짜 서버. BE 가 준비되기 전에 화면 흐름을 끝까지 눌러 보려고 앱 안에 둔다 (메모리에만 있고 앱을 다시 켜면 초기화).
 * `BuildConfig.USE_FAKE_AUTH` 가 true 일 때만 쓴다 (실제 BE 와 인증 계약이 맞춰져 있어 `-Pormo.realAuth=true` 로 끌 수 있다). 나머지 BE 가 완성되면 이 패키지를 지운다 (BE_GAP_M8.md).
 *
 * 시험용 계정·규칙
 * - 로그인: `kim@eulji.ac.kr` / `abc12345` (이름 "가을"). 5번 연속 틀리면 15분 잠금. `banned@…` 는 이용 제한 계정.
 * - 가입: `taken…` 으로 시작하는 아이디는 이미 가입된 이메일. 인증코드는 항상 **123456**. 같은 이메일로 6번째 발송은 하루 한도 초과.
 * - 비밀번호 재설정: 어떤 이메일이든 같은 응답. 현재 비밀번호와 같으면 거절.
 */
class FakeBackend(private val latencyMs: Long = 500) {
    private class User(var name: String, var password: String)

    private val users = mutableMapOf("kim@eulji.ac.kr" to User("가을", "abc12345"))
    private val failCounts = mutableMapOf<String, Int>()
    private val sendCounts = mutableMapOf<String, Int>()
    private val wrongCodes = mutableMapOf<String, Int>()
    private var currentEmail: String? = null

    val auth: AuthApi = object : AuthApi {
        override suspend fun sendEmailCode(email: String, purpose: VerifyPurpose): SendCodeResult {
            delay(latencyMs)
            val id = email.substringBefore('@')
            if (purpose == VerifyPurpose.SIGNUP && (id.startsWith("taken") || users.containsKey(email))) throw AppError.Api(409, "EMAIL_TAKEN")
            val n = (sendCounts[email] ?: 0) + 1
            sendCounts[email] = n
            if (n > 5) throw AppError.Api(429, "DAILY_LIMIT_EXCEEDED")
            wrongCodes.remove(email) // 새 코드는 처음부터
            return SendCodeResult(expiresInSec = 300, resendAfterSec = 60)
        }

        override suspend fun verifyEmailCode(email: String, purpose: VerifyPurpose, code: String): String {
            delay(latencyMs)
            // 실서버처럼: 1~4번째 오답은 EV004, 5번째는 코드를 폐기하고 만료와 같은 410 EV005
            if ((wrongCodes[email] ?: 0) >= 5) throw AppError.Api(410, "EV005")
            if (code != "123456") {
                val wrong = (wrongCodes[email] ?: 0) + 1
                wrongCodes[email] = wrong
                throw if (wrong >= 5) AppError.Api(410, "EV005") else AppError.Api(400, "EV004")
            }
            return "verified-$email"
        }

        override suspend fun signUp(req: SignUpRequest): LoginSession? {
            delay(latencyMs)
            if (users.containsKey(req.email)) throw AppError.Api(409, "EMAIL_TAKEN")
            users[req.email] = User(req.name, req.password)
            currentEmail = req.email
            return LoginSession("access-${req.email}", "r-${req.email}", schoolId = "s1", name = req.name)
        }

        override suspend fun login(email: String, password: String, schoolId: String?, autoLogin: Boolean): LoginSession {
            delay(latencyMs)
            if (email.startsWith("banned")) throw AppError.Api(403, "ACCOUNT_RESTRICTED")
            val user = users[email]
            if (user == null || user.password != password) {
                val n = (failCounts[email] ?: 0) + 1
                failCounts[email] = n
                if (n >= 5) throw AppError.Api(423, "ACCOUNT_LOCKED", buildJsonObject { put("retryAfterSec", 900) })
                throw AppError.Api(401, "INVALID_CREDENTIALS")
            }
            failCounts.remove(email)
            currentEmail = email
            return LoginSession("access-$email", "r-$email", schoolId = "s1", name = user.name)
        }

        override suspend fun refresh(refreshToken: String): RefreshResult {
            delay(latencyMs)
            val email = refreshToken.removePrefix("r-")
            if (!refreshToken.startsWith("r-")) throw AppError.Api(401, "AUTH_003")
            currentEmail = email
            return RefreshResult("access-$email", null, users[email]?.name ?: "가을")
        }

        override suspend fun logout(refreshToken: String) { delay(latencyMs) }

        override suspend fun resetPassword(email: String, verificationToken: String, newPassword: String) {
            delay(latencyMs)
            val user = users[email]
            if (user != null && user.password == newPassword) throw AppError.Api(400, "PASSWORD_SAME_AS_OLD")
            user?.password = newPassword
            failCounts.remove(email) // 재설정하면 잠금 해제 (0.1.2)
        }

        override suspend fun changePassword(currentPassword: String, newPassword: String) {
            delay(latencyMs)
            val user = users[currentEmail] ?: throw AppError.Api(401, "AUTH_003")
            if (user.password != currentPassword) throw AppError.Api(400, "CURRENT_PASSWORD_MISMATCH")
            if (currentPassword == newPassword) throw AppError.Api(400, "PASSWORD_SAME_AS_OLD")
            user.password = newPassword
        }

        override suspend fun me(): UserProfile {
            delay(latencyMs)
            val email = currentEmail
            return UserProfile(users[email]?.name ?: "가을", email)
        }

        override suspend fun withdraw(password: String) {
            delay(latencyMs)
            val user = users[currentEmail] ?: throw AppError.Api(401, "AUTH_003")
            if (user.password != password) throw AppError.Api(400, "CURRENT_PASSWORD_MISMATCH")
            users.remove(currentEmail)
        }
    }

    val schools: SchoolDirectoryApi = object : SchoolDirectoryApi {
        override suspend fun fetchEmailDomains(): EmailDomains {
            delay(latencyMs)
            return EmailDomains(
                listOf(
                    SchoolDomain("eulji.ac.kr", "s1", "을지대학교"),
                    SchoolDomain("student.eulji.ac.kr", "s1", "을지대학교"),
                ),
            )
        }
    }
}
