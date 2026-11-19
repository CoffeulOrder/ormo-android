package com.ormo.domain.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginFormStateTest {
    private val domains = listOf("eulji.ac.kr", "student.eulji.ac.kr")
    private val filled = LoginFormState(emailId = "kim", domain = "eulji.ac.kr", password = "abc12345")

    @Test fun `두 칸이 모두 차야 로그인 버튼이 켜진다`() {
        assertFalse(LoginFormState().canSubmit(0))
        assertFalse(LoginFormState(emailId = "kim", domain = "eulji.ac.kr").canSubmit(0))
        assertFalse(LoginFormState(password = "x", domain = "eulji.ac.kr").canSubmit(0))
        assertFalse(LoginFormState(emailId = "kim", password = "x").canSubmit(0)) // 도메인 없음
        assertTrue(filled.canSubmit(0))
    }

    @Test fun `전송 중에는 다시 누를 수 없다`() {
        assertFalse(filled.onSubmitStarted().canSubmit(0))
    }

    @Test fun `이메일 전체를 붙여 넣으면 아이디와 도메인을 나눈다`() {
        val s = LoginFormState().onEmailPasted("Kim@student.eulji.ac.kr", domains)
        assertEquals("Kim", s.emailId)
        assertEquals("student.eulji.ac.kr", s.domain)
    }

    @Test fun `허용되지 않은 도메인을 붙여 넣으면 기존 도메인을 유지한다`() {
        val s = filled.onEmailPasted("kim@gmail.com", domains)
        assertEquals("kim", s.emailId)
        assertEquals("eulji.ac.kr", s.domain)
    }

    @Test fun `아이디 입력의 플러스와 공백은 지워진다`() {
        assertEquals("kim1", LoginFormState().onEmailChanged("kim+1 ").emailId)
    }

    @Test fun `로그인 실패는 비밀번호만 비우고 이메일은 유지한다`() {
        val s = filled.onSubmitStarted().onFailure(LoginFailure.InvalidCredentials, 0)
        assertEquals("kim", s.emailId)
        assertEquals("", s.password)
        assertEquals(LoginMessage.INVALID_CREDENTIALS, s.message)
        assertFalse(s.submitting)
    }

    @Test fun `입력을 다시 시작하면 오류 문구가 사라진다`() {
        val s = filled.onFailure(LoginFailure.InvalidCredentials, 0)
        assertNull(s.onPasswordChanged("a").message)
    }

    @Test fun `잠기면 카운트다운이 1초마다 줄고 끝나면 다시 켜진다`() {
        val locked = filled.onSubmitStarted().onFailure(LoginFailure.Locked(retryAfterSec = 900), nowMs = 1_000)
        assertEquals(LoginMessage.LOCKED, locked.message)
        assertTrue(locked.isLocked(1_000))
        assertEquals("15:00", locked.lockCountdownLabel(1_000))
        assertEquals("14:59", locked.lockCountdownLabel(2_000))
        assertEquals("0:01", locked.lockCountdownLabel(900_000))

        val typed = locked.onPasswordChanged("abc12345") // 잠긴 동안 다시 입력해도
        assertFalse(typed.canSubmit(900_000)) // 버튼은 꺼져 있다

        assertFalse(typed.isLocked(901_000))
        assertNull(typed.lockCountdownLabel(901_000))
        assertTrue(typed.canSubmit(901_000)) // 0:00 에 재활성
    }

    @Test fun `잠김이 아니면 카운트다운 문구가 없다`() {
        assertNull(filled.lockCountdownLabel(0))
        assertFalse(filled.isLocked(0))
    }

    @Test fun `이용 제한 계정은 다이얼로그용 문구이고 입력은 유지`() {
        val s = filled.onFailure(LoginFailure.Restricted, 0)
        assertEquals(LoginMessage.RESTRICTED, s.message)
        assertEquals("abc12345", s.password)
    }

    @Test fun `네트워크 오류와 기타 오류`() {
        assertEquals(LoginMessage.NETWORK, filled.onFailure(LoginFailure.Network, 0).message)
        assertEquals(LoginMessage.OTHER, filled.onFailure(LoginFailure.Other, 0).message)
    }
}
