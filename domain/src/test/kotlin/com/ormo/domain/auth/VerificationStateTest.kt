package com.ormo.domain.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerificationStateTest {
    private val start = VerificationState.started(SendCodeResult(expiresInSec = 300, resendAfterSec = 60), nowMs = 0)

    @Test fun `시작 직후 상태`() {
        assertEquals(300, start.remainingSeconds(0))
        assertEquals(60, start.resendRemainingSeconds(0))
        assertTrue(start.inputEnabled(0))
        assertFalse(start.canResend(0))
        assertNull(start.displayMessage(0))
        assertEquals("", start.code)
    }

    @Test fun `숫자만 6자리까지 입력되고 6자리가 차면 자동 확인`() {
        var s = start.onCodeChanged("12a3 4", 0)
        assertEquals("1234", s.code)
        assertFalse(s.shouldSubmit(0))
        s = s.onCodeChanged("1234567", 0)
        assertEquals("123456", s.code)
        assertTrue(s.shouldSubmit(0))
    }

    @Test fun `남은 시간은 올림으로 센다`() {
        assertEquals(1, start.remainingSeconds(299_500))
        assertEquals(0, start.remainingSeconds(300_000))
        assertEquals(1, start.resendRemainingSeconds(59_001))
    }

    @Test fun `재발송 대기가 끝나야 다시 받을 수 있다`() {
        assertFalse(start.canResend(59_999))
        assertTrue(start.canResend(60_000))
    }

    @Test fun `시간이 지나면 입력이 꺼지고 만료 문구가 나오며 바로 다시 받을 수 있다`() {
        val s = VerificationState.started(SendCodeResult(300, 600), 0).onCodeChanged("123", 0)
        assertTrue(s.isExpired(300_000))
        assertFalse(s.inputEnabled(300_000))
        assertEquals(VerificationMessage.EXPIRED, s.displayMessage(300_000))
        assertEquals("123", s.onCodeChanged("123456", 300_000).code) // 입력 무시
        assertTrue(s.canResend(300_000)) // 재발송 대기(600초)가 남았어도 만료되면 가능
        assertFalse(s.copy(code = "123456").shouldSubmit(300_000))
    }

    @Test fun `틀린 코드 1~4번째는 오류 문구와 함께 칸을 비운다`() {
        var s = start
        repeat(4) { i ->
            s = s.onCodeChanged("111111", 0).onSubmitStarted().onFailed(CodeError.MISMATCH, 0)
            assertEquals(i + 1, s.wrongAttempts)
            assertEquals(VerificationPhase.INPUT, s.phase)
            assertEquals(VerificationMessage.MISMATCH, s.message)
            assertEquals("", s.code)
            assertTrue(s.inputEnabled(0))
        }
    }

    @Test fun `5번째 오류면 사용이 제한되고 입력이 꺼진다`() {
        var s = start
        repeat(5) { s = s.onCodeChanged("111111", 0).onSubmitStarted().onFailed(CodeError.MISMATCH, 0) }
        assertEquals(VerificationPhase.LOCKED, s.phase)
        assertEquals(VerificationMessage.TOO_MANY_WRONG, s.displayMessage(0))
        assertFalse(s.inputEnabled(0))
        assertTrue(s.canResend(0)) // [인증코드 다시 받기]
    }

    @Test fun `서버가 횟수 초과를 알리면 바로 제한된다`() {
        val s = start.onCodeChanged("111111", 0).onSubmitStarted().onFailed(CodeError.TOO_MANY_ATTEMPTS, 0)
        assertEquals(VerificationPhase.LOCKED, s.phase)
    }

    @Test fun `서버가 만료를 알리면 만료 상태가 된다`() {
        val s = start.onCodeChanged("111111", 10_000).onSubmitStarted().onFailed(CodeError.EXPIRED, 10_000)
        assertTrue(s.isExpired(10_000))
        assertEquals(VerificationMessage.EXPIRED, s.displayMessage(10_000))
        assertTrue(s.canResend(10_000))
    }

    @Test fun `확인 중에는 입력과 중복 확인이 막힌다`() {
        val s = start.onCodeChanged("123456", 0).onSubmitStarted()
        assertEquals(VerificationPhase.VERIFYING, s.phase)
        assertFalse(s.shouldSubmit(0))
        assertEquals("123456", s.onCodeChanged("000000", 0).code)
    }

    @Test fun `확인에 성공하면 VERIFIED`() {
        val s = start.onCodeChanged("123456", 0).onSubmitStarted().onVerified()
        assertEquals(VerificationPhase.VERIFIED, s.phase)
        assertFalse(s.inputEnabled(0))
    }

    @Test fun `네트워크 오류로 확인이 안 되면 칸을 비우고 다시 입력할 수 있다`() {
        val s = start.onCodeChanged("123456", 0).onSubmitStarted().onSubmitAborted()
        assertEquals(VerificationPhase.INPUT, s.phase)
        assertEquals("", s.code)
        assertFalse(s.shouldSubmit(0)) // 자동 재전송 루프 방지
    }

    @Test fun `입력을 시작하면 이전 오류 문구가 사라진다`() {
        val s = start.onCodeChanged("111111", 0).onSubmitStarted().onFailed(CodeError.MISMATCH, 0)
        assertEquals(VerificationMessage.MISMATCH, s.displayMessage(0))
        assertNull(s.onCodeChanged("1", 0).displayMessage(0))
    }

    @Test fun `다시 받으면 처음 상태로 시간이 재시작된다`() {
        var s = start
        repeat(5) { s = s.onCodeChanged("111111", 0).onSubmitStarted().onFailed(CodeError.MISMATCH, 0) }
        val resent = s.onResent(SendCodeResult(300, 60), nowMs = 100_000)
        assertEquals("", resent.code)
        assertEquals(0, resent.wrongAttempts)
        assertEquals(VerificationPhase.INPUT, resent.phase)
        assertNull(resent.message)
        assertEquals(300, resent.remainingSeconds(100_000))
        assertEquals(60, resent.resendRemainingSeconds(100_000))
        assertTrue(resent.inputEnabled(100_000))
    }
}
