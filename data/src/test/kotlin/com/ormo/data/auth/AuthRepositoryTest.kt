package com.ormo.data.auth

import com.ormo.core.Outcome
import com.ormo.core.Resettable
import com.ormo.core.SessionResetter
import com.ormo.data.local.InMemorySessionStore
import com.ormo.data.local.PersistedSession
import com.ormo.data.local.SessionStore
import com.ormo.data.remote.AppError
import com.ormo.data.remote.RefreshedTokens
import com.ormo.data.remote.SessionManager
import com.ormo.domain.auth.CodeError
import com.ormo.domain.auth.LoginFailure
import com.ormo.domain.auth.PasswordFailure
import com.ormo.domain.auth.SendCodeFailure
import com.ormo.domain.auth.SendCodeResult
import com.ormo.domain.auth.SignUpRequest
import com.ormo.domain.auth.SignupRejection
import com.ormo.domain.auth.UserProfile
import com.ormo.domain.auth.VerifyCodeFailure
import com.ormo.domain.auth.VerifyPurpose
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FakeAuthApi : AuthApi {
    var refreshImpl: suspend (String) -> RefreshResult = { RefreshResult("a1", "r2") }
    var loginImpl: suspend (String, String, String?, Boolean) -> LoginSession = { _, _, _, _ -> LoginSession("a1", "r1") }
    var signUpImpl: suspend (SignUpRequest) -> LoginSession? = { null }
    var logoutImpl: suspend (String) -> Unit = {}
    var changePasswordImpl: suspend (String, String) -> Unit = { _, _ -> }
    var resetPasswordImpl: suspend (String, String, String) -> Unit = { _, _, _ -> }
    var sendCodeImpl: suspend (String, VerifyPurpose) -> SendCodeResult = { _, _ -> SendCodeResult(300, 60) }
    var verifyImpl: suspend (String, VerifyPurpose, String) -> String = { _, _, _ -> "tok" }
    var withdrawImpl: suspend (String) -> Unit = {}
    var meImpl: suspend () -> UserProfile = { UserProfile("가을", "kim@eulji.ac.kr") }

    val refreshCalls = mutableListOf<String>()
    val loginCalls = mutableListOf<List<Any?>>()
    val logoutCalls = mutableListOf<String>()
    var meCalls = 0

    override suspend fun sendEmailCode(email: String, purpose: VerifyPurpose) = sendCodeImpl(email, purpose)
    override suspend fun verifyEmailCode(email: String, purpose: VerifyPurpose, code: String) = verifyImpl(email, purpose, code)
    override suspend fun signUp(req: SignUpRequest) = signUpImpl(req)
    override suspend fun login(email: String, password: String, schoolId: String?, autoLogin: Boolean): LoginSession {
        loginCalls += listOf(email, password, schoolId, autoLogin)
        return loginImpl(email, password, schoolId, autoLogin)
    }
    override suspend fun refresh(refreshToken: String): RefreshResult {
        refreshCalls += refreshToken
        return refreshImpl(refreshToken)
    }
    override suspend fun logout(refreshToken: String) {
        logoutCalls += refreshToken
        logoutImpl(refreshToken)
    }
    override suspend fun resetPassword(email: String, verificationToken: String, newPassword: String) =
        resetPasswordImpl(email, verificationToken, newPassword)
    override suspend fun changePassword(currentPassword: String, newPassword: String) = changePasswordImpl(currentPassword, newPassword)
    override suspend fun me(): UserProfile {
        meCalls++
        return meImpl()
    }
    override suspend fun withdraw(password: String) = withdrawImpl(password)
}

class CountingResettable : Resettable {
    var resets = 0
    override fun reset() { resets++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryTest {
    private val api = FakeAuthApi()
    private val sessions = SessionManager()
    private val store = InMemorySessionStore()
    private val resettable = CountingResettable()

    /** 만료 구독 같은 끝나지 않는 작업은 backgroundScope 에서 돌려야 runTest 가 끝난다 */
    private fun TestScope.repo(store: SessionStore = this@AuthRepositoryTest.store) =
        AuthRepository(api, sessions, store, SessionResetter(listOf(resettable)), backgroundScope)

    private val network = AppError.Network(IOException("offline"))

    // ---- 시작 시 복구 ----

    @Test fun `저장된 세션이 없으면 로그아웃 상태`() = runTest {
        val r = repo()
        r.restore()
        assertEquals(AuthStatus.SIGNED_OUT, r.status.value)
        assertEquals(0, api.refreshCalls.size)
    }

    @Test fun `저장된 세션으로 복구하고 회전된 토큰을 저장한다`() = runTest {
        store.save(PersistedSession("s1", "r1"))
        val r = repo()

        r.restore()
        runCurrent()

        assertEquals(AuthStatus.SIGNED_IN, r.status.value)
        assertEquals(listOf("r1"), api.refreshCalls)
        assertEquals("a1", sessions.accessToken)
        assertEquals("r2", sessions.refreshToken)
        assertEquals(PersistedSession("s1", "r2"), store.load())
        assertEquals("s1", r.schoolId.value)
    }

    @Test fun `서버가 토큰을 회전시키지 않으면 저장값을 그대로 쓴다`() = runTest {
        store.save(PersistedSession("s1", "r1"))
        api.refreshImpl = { RefreshResult("a1", null) }
        val r = repo()
        r.restore()
        assertEquals("r1", sessions.refreshToken)
        assertEquals(PersistedSession("s1", "r1"), store.load())
    }

    @Test fun `서버가 거절하면 저장된 세션을 지우고 로그아웃 상태`() = runTest {
        store.save(PersistedSession("s1", "r1"))
        api.refreshImpl = { throw AppError.Api(401, "AUTH_003") }
        val r = repo()
        r.restore()
        assertEquals(AuthStatus.SIGNED_OUT, r.status.value)
        assertNull(store.load())
        assertNull(sessions.accessToken)
    }

    @Test fun `네트워크 오류면 저장된 세션을 지우지 않고 오프라인 상태이며 재시도하면 복구된다`() = runTest {
        store.save(PersistedSession("s1", "r1"))
        api.refreshImpl = { throw network }
        val r = repo()
        r.restore()
        assertEquals(AuthStatus.OFFLINE, r.status.value)
        assertEquals(PersistedSession("s1", "r1"), store.load())

        api.refreshImpl = { RefreshResult("a1", "r2") }
        r.restore()
        assertEquals(AuthStatus.SIGNED_IN, r.status.value)
    }

    @Test fun `서버 장애(5xx)도 세션을 지우지 않는다`() = runTest {
        store.save(PersistedSession("s1", "r1"))
        api.refreshImpl = { throw AppError.Api(503) }
        val r = repo()
        r.restore()
        assertEquals(AuthStatus.OFFLINE, r.status.value)
        assertEquals(PersistedSession("s1", "r1"), store.load())
    }

    @Test fun `저장소를 읽을 수 없으면 저장된 세션 없음으로 본다`() = runTest {
        val broken = object : SessionStore {
            override suspend fun load(): PersistedSession? = throw IOException("keystore")
            override suspend fun save(session: PersistedSession) {}
            override suspend fun clear() {}
        }
        val r = repo(broken)
        r.restore()
        assertEquals(AuthStatus.SIGNED_OUT, r.status.value)
    }

    // ---- 로그인 ----

    @Test fun `로그인 성공 - 자동 로그인이면 토큰과 학교를 저장한다`() = runTest {
        api.loginImpl = { _, _, _, _ -> LoginSession("a1", "r1", schoolId = "s9", name = "가을") }
        val r = repo()

        val out = r.signIn("kim@eulji.ac.kr", "pw", schoolId = "s1")
        runCurrent()

        assertEquals(Outcome.Ok(Unit), out)
        assertEquals(AuthStatus.SIGNED_IN, r.status.value)
        assertEquals("a1", sessions.accessToken)
        assertEquals("r1", sessions.refreshToken)
        assertEquals(PersistedSession("s9", "r1"), store.load()) // 서버가 준 학교 id 가 우선
        assertEquals("s9", r.schoolId.value)
        assertEquals(listOf<Any?>("kim@eulji.ac.kr", "pw", "s1", true), api.loginCalls.single())
    }

    @Test fun `로그인 - 학교 id 를 서버가 안 주면 요청한 값을 쓴다`() = runTest {
        val r = repo()
        r.signIn("kim@eulji.ac.kr", "pw", schoolId = "s1")
        assertEquals("s1", r.schoolId.value)
        assertEquals(PersistedSession("s1", "r1"), store.load())
    }

    @Test fun `자동 로그인을 끄면 예전 저장분을 지우고 저장하지 않는다`() = runTest {
        store.save(PersistedSession("old", "old-r"))
        val r = repo()
        r.signIn("kim@eulji.ac.kr", "pw", "s1", autoLogin = false)
        assertEquals(AuthStatus.SIGNED_IN, r.status.value)
        assertNull(store.load())
        assertEquals("r1", sessions.refreshToken) // 메모리에는 있다
    }

    @Test fun `로그인 실패는 값으로 돌려주고 상태는 그대로`() = runTest {
        val r = repo()
        r.restore()
        api.loginImpl = { _, _, _, _ -> throw AppError.Api(401, "INVALID_CREDENTIALS") }
        assertEquals(Outcome.Fail(LoginFailure.InvalidCredentials), r.signIn("a@b.c", "pw", "s1"))

        api.loginImpl = { _, _, _, _ -> throw AppError.Api(423, "AUTH_002", kotlinx.serialization.json.Json.parseToJsonElement("""{"retryAfterSec":600}""")) }
        assertEquals(Outcome.Fail(LoginFailure.Locked(600)), r.signIn("a@b.c", "pw", "s1"))

        api.loginImpl = { _, _, _, _ -> throw network }
        assertEquals(Outcome.Fail(LoginFailure.Network), r.signIn("a@b.c", "pw", "s1"))

        assertEquals(AuthStatus.SIGNED_OUT, r.status.value)
        assertNull(store.load())
        assertNull(sessions.accessToken)
    }

    @Test fun `로그인하면 안내 문구는 지워진다`() = runTest {
        val r = repo()
        sessions.set("a", "r")
        sessions.refresh("a") { throw AppError.Api(401) }
        runCurrent()
        assertEquals(AuthNotice.SESSION_EXPIRED, r.notice.value)

        r.signIn("a@b.c", "pw", "s1")
        assertNull(r.notice.value)
    }

    @Test fun `로그인 후 내 정보를 받아 이름을 보여준다`() = runTest {
        val r = repo()
        r.signIn("kim@eulji.ac.kr", "pw", "s1")
        runCurrent()
        assertEquals(UserProfile("가을", "kim@eulji.ac.kr"), r.profile.value)
    }

    @Test fun `내 정보 조회가 실패해도 로그인에는 영향이 없다`() = runTest {
        api.meImpl = { throw network }
        val r = repo()
        r.signIn("kim@eulji.ac.kr", "pw", "s1")
        runCurrent()
        assertEquals(AuthStatus.SIGNED_IN, r.status.value)
        assertNull(r.profile.value)
    }

    @Test fun `로그인 응답에 이름이 있으면 바로 쓰고 이메일은 나중에 채운다`() = runTest {
        api.loginImpl = { _, _, _, _ -> LoginSession("a1", "r1", name = "가을") }
        val r = repo()
        r.signIn("kim@eulji.ac.kr", "pw", "s1")
        assertEquals(UserProfile("가을", null), r.profile.value)
        runCurrent()
        assertEquals(UserProfile("가을", "kim@eulji.ac.kr"), r.profile.value)
    }

    // ---- 가입 ----

    private val req = SignUpRequest("kim@eulji.ac.kr", "가을", "abc12345", "tok", termsAgreed = true, privacyAgreed = true)

    @Test fun `가입 응답에 토큰이 있으면 바로 로그인된다`() = runTest {
        api.signUpImpl = { LoginSession("a1", "r1", name = "가을") }
        val r = repo()
        assertEquals(Outcome.Ok(true), r.signUp(req, "s1"))
        assertEquals(AuthStatus.SIGNED_IN, r.status.value)
        assertEquals(0, api.loginCalls.size)
    }

    @Test fun `가입 응답에 토큰이 없으면 같은 이메일과 비밀번호로 로그인한다`() = runTest {
        val r = repo()
        assertEquals(Outcome.Ok(true), r.signUp(req, "s1"))
        assertEquals(listOf<Any?>("kim@eulji.ac.kr", "abc12345", "s1", true), api.loginCalls.single())
        assertEquals(AuthStatus.SIGNED_IN, r.status.value)
    }

    @Test fun `가입은 됐는데 자동 로그인이 안 되면 Ok(false)`() = runTest {
        api.loginImpl = { _, _, _, _ -> throw network }
        val r = repo()
        r.restore()
        assertEquals(Outcome.Ok(false), r.signUp(req, "s1"))
        assertEquals(AuthStatus.SIGNED_OUT, r.status.value)
    }

    @Test fun `가입이 거절되면 사유를 돌려주고 로그인하지 않는다`() = runTest {
        api.signUpImpl = { throw AppError.Api(409, "EMAIL_TAKEN") }
        val r = repo()
        r.restore()
        assertEquals(Outcome.Fail(SignupRejection.EMAIL_TAKEN), r.signUp(req, "s1"))
        assertEquals(0, api.loginCalls.size)
        assertEquals(AuthStatus.SIGNED_OUT, r.status.value)
    }

    // ---- 로그아웃 / 만료 ----

    @Test fun `로그아웃은 토큰과 저장 세션과 개인 데이터를 지우고 서버에 폐기를 요청한다`() = runTest {
        val r = repo()
        r.signIn("kim@eulji.ac.kr", "pw", "s1")
        runCurrent()

        r.signOut()
        runCurrent()

        assertEquals(AuthStatus.SIGNED_OUT, r.status.value)
        assertNull(sessions.accessToken)
        assertNull(sessions.refreshToken)
        assertNull(store.load())
        assertEquals(1, resettable.resets)
        assertNull(r.schoolId.value)
        assertNull(r.profile.value)
        assertNull(r.notice.value)
        assertEquals(listOf("r1"), api.logoutCalls)
    }

    @Test fun `서버 폐기 요청이 실패해도 로그아웃은 끝난다`() = runTest {
        api.logoutImpl = { throw network }
        val r = repo()
        r.signIn("kim@eulji.ac.kr", "pw", "s1")
        r.signOut()
        runCurrent()
        assertEquals(AuthStatus.SIGNED_OUT, r.status.value)
    }

    @Test fun `갱신이 거절되면 세션을 끝내고 만료 안내를 띄운다 (서버 폐기 요청은 보내지 않는다)`() = runTest {
        val r = repo()
        r.signIn("kim@eulji.ac.kr", "pw", "s1")
        runCurrent()

        // 요청 중 401 → 갱신 거절 (SessionManager 가 expired 를 내보낸다)
        assertNull(sessions.refresh(staleAccessToken = "a1") { throw AppError.Api(401, "AUTH_003") })
        runCurrent()

        assertEquals(AuthStatus.SIGNED_OUT, r.status.value)
        assertEquals(AuthNotice.SESSION_EXPIRED, r.notice.value)
        assertNull(store.load())
        assertEquals(1, resettable.resets)
        assertEquals(emptyList(), api.logoutCalls)
    }

    @Test fun `안내를 한 번 보여준 뒤 지울 수 있다`() = runTest {
        val r = repo()
        sessions.set("a", "r")
        sessions.refresh("a") { throw AppError.Api(401) }
        runCurrent()
        assertEquals(AuthNotice.SESSION_EXPIRED, r.notice.value)
        r.clearNotice()
        assertNull(r.notice.value)
    }

    // ---- 토큰 회전 ----

    @Test fun `요청 중 갱신으로 토큰이 회전되면 저장값도 바뀐다`() = runTest {
        val r = repo()
        r.signIn("kim@eulji.ac.kr", "pw", "s1")
        runCurrent()

        sessions.refresh(staleAccessToken = "a1") { RefreshedTokens("a2", "r9") }
        runCurrent()

        assertEquals(PersistedSession("s1", "r9"), store.load())
    }

    @Test fun `자동 로그인이 아니면 회전돼도 저장하지 않는다`() = runTest {
        val r = repo()
        r.signIn("kim@eulji.ac.kr", "pw", "s1", autoLogin = false)
        runCurrent()

        sessions.refresh(staleAccessToken = "a1") { RefreshedTokens("a2", "r9") }
        runCurrent()

        assertNull(store.load())
    }

    // ---- 비밀번호 ----

    @Test fun `비밀번호를 바꾸면 이 기기도 로그아웃되고 안내가 뜬다`() = runTest {
        val r = repo()
        r.signIn("kim@eulji.ac.kr", "pw", "s1")
        runCurrent()
        var seen: Pair<String, String>? = null
        api.changePasswordImpl = { cur, new -> seen = cur to new }

        assertEquals(Outcome.Ok(Unit), r.changePassword("old12345", "new12345"))
        runCurrent()

        assertEquals("old12345" to "new12345", seen)
        assertEquals(AuthStatus.SIGNED_OUT, r.status.value)
        assertEquals(AuthNotice.PASSWORD_CHANGED, r.notice.value)
        assertNull(store.load())
        assertEquals(1, resettable.resets)
    }

    @Test fun `비밀번호 변경 거절은 로그인 상태를 유지한다`() = runTest {
        val r = repo()
        r.signIn("kim@eulji.ac.kr", "pw", "s1")
        api.changePasswordImpl = { _, _ -> throw AppError.Api(400, "CURRENT_PASSWORD_MISMATCH") }
        assertEquals(Outcome.Fail(PasswordFailure.WRONG_CURRENT), r.changePassword("bad", "new12345"))
        assertEquals(AuthStatus.SIGNED_IN, r.status.value)
        assertEquals(0, resettable.resets)

        api.changePasswordImpl = { _, _ -> throw AppError.Api(400, "PASSWORD_SAME_AS_OLD") }
        assertEquals(Outcome.Fail(PasswordFailure.SAME_AS_OLD), r.changePassword("old12345", "old12345"))
    }

    @Test fun `비밀번호 재설정 성공은 안내만 띄운다`() = runTest {
        val r = repo()
        r.restore()
        assertEquals(Outcome.Ok(Unit), r.resetPassword("kim@eulji.ac.kr", "tok", "new12345"))
        assertEquals(AuthNotice.PASSWORD_CHANGED, r.notice.value)
        assertEquals(AuthStatus.SIGNED_OUT, r.status.value)
    }

    @Test fun `비밀번호 재설정 거절`() = runTest {
        api.resetPasswordImpl = { _, _, _ -> throw AppError.Api(400, "EV008") }
        val r = repo()
        assertEquals(Outcome.Fail(PasswordFailure.VERIFICATION_INVALID), r.resetPassword("a@b.c", "tok", "new12345"))
        assertNull(r.notice.value)
    }

    // ---- 이메일 인증 ----

    @Test fun `인증코드 발송과 확인`() = runTest {
        val r = repo()
        assertEquals(Outcome.Ok(SendCodeResult(300, 60)), r.sendEmailCode("a@b.c", VerifyPurpose.SIGNUP))
        assertEquals(Outcome.Ok("tok"), r.verifyEmailCode("a@b.c", VerifyPurpose.SIGNUP, "123456"))
    }

    @Test fun `인증코드 실패는 값으로 돌려준다`() = runTest {
        api.sendCodeImpl = { _, _ -> throw AppError.Api(409, "EMAIL_TAKEN") }
        api.verifyImpl = { _, _, _ -> throw AppError.Api(400, "CODE_INVALID") }
        val r = repo()
        assertEquals(Outcome.Fail(SendCodeFailure.AlreadyRegistered), r.sendEmailCode("a@b.c", VerifyPurpose.SIGNUP))
        assertEquals(
            Outcome.Fail(VerifyCodeFailure.Code(CodeError.MISMATCH)),
            r.verifyEmailCode("a@b.c", VerifyPurpose.SIGNUP, "000000"),
        )
        api.verifyImpl = { _, _, _ -> throw network }
        assertEquals(Outcome.Fail(VerifyCodeFailure.Network), r.verifyEmailCode("a@b.c", VerifyPurpose.SIGNUP, "000000"))
    }
}
