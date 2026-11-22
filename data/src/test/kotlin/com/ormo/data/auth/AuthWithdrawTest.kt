package com.ormo.data.auth

import com.ormo.core.Outcome
import com.ormo.core.SessionResetter
import com.ormo.data.local.InMemorySessionStore
import com.ormo.data.local.PersistedSession
import com.ormo.data.remote.AppError
import com.ormo.data.remote.SessionManager
import com.ormo.domain.profile.WithdrawFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AuthWithdrawTest {
    private val api = FakeAuthApi()
    private val sessions = SessionManager()
    private val store = InMemorySessionStore()
    private val resettable = CountingResettable()
    private val cleaned = mutableListOf<String?>()

    private fun TestScope.repo() = AuthRepository(
        api, sessions, store, SessionResetter(listOf(resettable)), backgroundScope,
        onWithdrawn = { cleaned += it },
    )

    private suspend fun TestScope.signedIn(): AuthRepository {
        val r = repo()
        r.signIn("kim@eulji.ac.kr", "pw", "s1")
        runCurrent()
        return r
    }

    @Test fun `탈퇴하면 기기 데이터를 지우고 로그아웃되며 안내가 뜬다 - 서버 로그아웃 요청은 없다`() = runTest {
        val r = signedIn()
        var seen: String? = null
        api.withdrawImpl = { seen = it }

        assertEquals(Outcome.Ok(Unit), r.withdraw("pw12345"))
        runCurrent()

        assertEquals("pw12345", seen)
        assertEquals(listOf<String?>("kim@eulji.ac.kr"), cleaned)
        assertEquals(AuthStatus.SIGNED_OUT, r.status.value)
        assertEquals(AuthNotice.WITHDRAWN, r.notice.value)
        assertNull(store.load())
        assertEquals(1, resettable.resets)
        assertEquals(0, api.logoutCalls.size)
        assertNull(sessions.accessToken)
    }

    @Test fun `비밀번호가 틀리면 로그인 상태를 유지한다`() = runTest {
        val r = signedIn()
        api.withdrawImpl = { throw AppError.Api(400, "CURRENT_PASSWORD_MISMATCH") }
        assertEquals(Outcome.Fail(WithdrawFailure.WRONG_PASSWORD), r.withdraw("bad"))
        assertEquals(AuthStatus.SIGNED_IN, r.status.value)
        assertEquals(0, resettable.resets)
        assertEquals(emptyList<String?>(), cleaned)
    }

    @Test fun `진행 중 주문이 있으면 서버가 거절한다`() = runTest {
        val r = signedIn()
        api.withdrawImpl = { throw AppError.Api(409, "ACTIVE_ORDER_EXISTS") }
        assertEquals(Outcome.Fail(WithdrawFailure.ACTIVE_ORDERS), r.withdraw("pw"))
        assertEquals(AuthStatus.SIGNED_IN, r.status.value)
    }

    @Test fun `네트워크 오류와 기타 오류`() = runTest {
        val r = signedIn()
        api.withdrawImpl = { throw AppError.Network() }
        assertEquals(Outcome.Fail(WithdrawFailure.NETWORK), r.withdraw("pw"))
        api.withdrawImpl = { throw AppError.Api(500) }
        assertEquals(Outcome.Fail(WithdrawFailure.OTHER), r.withdraw("pw"))
        assertEquals(AuthStatus.SIGNED_IN, r.status.value)
    }

    @Test fun `기기 데이터 정리가 실패해도 탈퇴는 완료된다`() = runTest {
        val r = AuthRepository(api, sessions, store, SessionResetter(listOf(resettable)), backgroundScope, onWithdrawn = { error("disk") })
        r.signIn("kim@eulji.ac.kr", "pw", "s1")
        runCurrent()
        assertEquals(Outcome.Ok(Unit), r.withdraw("pw"))
        assertEquals(AuthStatus.SIGNED_OUT, r.status.value)
    }

    @Test fun `일반 로그아웃은 여전히 서버에 폐기를 요청한다`() = runTest {
        val r = signedIn()
        r.signOut()
        runCurrent()
        assertEquals(1, api.logoutCalls.size)
        assertNull(r.notice.value)
    }
}
