package com.ormo.customer.ui.auth

import com.ormo.core.Outcome
import com.ormo.core.SessionResetter
import com.ormo.customer.fake.FakeBackend
import com.ormo.data.auth.AuthRepository
import com.ormo.data.local.InMemorySessionStore
import com.ormo.data.remote.SessionManager
import com.ormo.domain.auth.LoginFailure
import com.ormo.domain.auth.SendCodeResult
import com.ormo.domain.auth.VerificationMessage
import com.ormo.domain.auth.VerificationPhase
import com.ormo.domain.auth.VerifyPurpose
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 화면 뒤의 흐름 검증: 앱 안의 가짜 서버([FakeBackend])를 실제 `AuthRepository`·[VerificationController] 에 연결해
 * 로그인 잠금, 인증코드 5번 오답, 재발송이 화면이 쓰는 상태 그대로 나오는지 본다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthFlowTest {
    private fun TestScope.auth(): AuthRepository =
        AuthRepository(FakeBackend(latencyMs = 0).auth, SessionManager(), InMemorySessionStore(), SessionResetter(emptyList()), backgroundScope)

    // ---- 로그인 ----
    @Test fun `틀린 비밀번호 4번은 인증 실패 - 5번째에 15분 잠금`() = runTest {
        val a = auth()
        repeat(4) { assertEquals(Outcome.Fail(LoginFailure.InvalidCredentials), a.signIn("kim@eulji.ac.kr", "wrong$it", "s1")) }
        assertEquals(Outcome.Fail(LoginFailure.Locked(900)), a.signIn("kim@eulji.ac.kr", "wrong5", "s1"))
    }

    @Test fun `이용 제한 계정`() = runTest {
        assertEquals(Outcome.Fail(LoginFailure.Restricted), auth().signIn("banned@eulji.ac.kr", "abc12345", "s1"))
    }

    @Test fun `올바른 비밀번호로 로그인하면 이름이 보인다`() = runTest {
        val a = auth()
        assertEquals(Outcome.Ok(Unit), a.signIn("kim@eulji.ac.kr", "abc12345", "s1"))
        runCurrent()
        assertEquals("가을", a.profile.value?.name)
    }

    // ---- 인증코드 ----
    private fun TestScope.controller(a: AuthRepository, onVerified: (String) -> Unit = {}, now: () -> Long = { testScheduler.currentTime }) =
        VerificationController(backgroundScope, a, VerifyPurpose.SIGNUP, onVerified, clock = now)

    @Test fun `틀린 코드는 일치하지 않음 문구를 띄우고 입력한 코드는 그대로 둔다`() = runTest {
        val c = controller(auth())
        c.started(SendCodeResult(300, 60))
        c.onCodeChanged("111111", "new@eulji.ac.kr")
        runCurrent()
        val v = assertNotNull(c.state.value)
        assertEquals(VerificationMessage.MISMATCH, v.message)
        assertEquals("111111", v.code)
        assertEquals(1, v.wrongAttempts)
    }

    @Test fun `5번 틀리면 입력이 꺼지고 다시 받기만 가능`() = runTest {
        val c = controller(auth())
        c.started(SendCodeResult(300, 60))
        repeat(5) { c.onCodeChanged("111111", "new@eulji.ac.kr"); runCurrent() }
        val v = assertNotNull(c.state.value)
        assertEquals(VerificationPhase.LOCKED, v.phase)
        assertTrue(!v.inputEnabled(testScheduler.currentTime))
        assertTrue(v.canResend(testScheduler.currentTime))
        // 잠긴 뒤 입력은 무시된다
        c.onCodeChanged("123456", "new@eulji.ac.kr"); runCurrent()
        assertEquals(VerificationPhase.LOCKED, c.state.value!!.phase)
    }

    @Test fun `맞는 코드는 0점7초 뒤에 다음 화면으로`() = runTest {
        var token: String? = null
        val c = controller(auth(), onVerified = { token = it })
        c.started(SendCodeResult(300, 60))
        c.onCodeChanged("123456", "new@eulji.ac.kr")
        runCurrent()
        assertEquals(VerificationPhase.VERIFIED, c.state.value!!.phase)
        assertNull(token) // 아직 이동 안 함
        advanceTimeBy(699); runCurrent()
        assertNull(token)
        advanceTimeBy(2); runCurrent()
        assertEquals("verified-new@eulji.ac.kr", token)
    }

    @Test fun `재발송은 대기가 끝나야 하고 새 코드로 처음부터`() = runTest {
        var clock = 0L
        val c = controller(auth(), now = { clock })
        c.started(SendCodeResult(300, 60))
        c.resend("new@eulji.ac.kr") // 대기 중이라 무시
        runCurrent()
        assertEquals(60, c.state.value!!.resendRemainingSeconds(0))
        clock = 61_000
        c.resend("new@eulji.ac.kr")
        runCurrent()
        val v = c.state.value!!
        assertEquals(300, v.remainingSeconds(clock))
        assertEquals(60, v.resendRemainingSeconds(clock))
        assertEquals("", v.code)
    }

    @Test fun `하루 한도를 넘기면 다시 받기가 안내와 함께 거절된다`() = runTest {
        var clock = 0L
        val c = controller(auth(), now = { clock })
        c.started(SendCodeResult(300, 60))
        // 가짜 서버는 같은 이메일로 6번째 발송부터 한도 초과
        repeat(5) { clock += 61_000; c.resend("new@eulji.ac.kr"); runCurrent() }
        clock += 61_000
        c.resend("new@eulji.ac.kr")
        runCurrent()
        assertEquals(CodeNotice.DAILY_LIMIT, c.notice.value)
        assertIs<com.ormo.domain.auth.VerificationState>(c.state.value)
    }
}
