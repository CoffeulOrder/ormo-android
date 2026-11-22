package com.ormo.domain.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResetFlowTest {
    private fun atPassword() = ResetState().onEmailChanged("kim", "eulji.ac.kr").codeSent().codeVerified("tok")

    @Test fun `정상 흐름 이메일 코드 비밀번호 완료`() {
        var s = ResetState().onEmailChanged("kim", "eulji.ac.kr")
        assertTrue(s.canRequestCode)
        assertEquals("kim@eulji.ac.kr", s.email)
        s = s.codeSent()
        assertEquals(ResetStep.CODE, s.step)
        s = s.codeVerified("tok")
        assertEquals(ResetStep.PASSWORD, s.step)
        assertEquals("tok", s.verificationToken)
        val done = s.succeeded()
        assertEquals(ResetStep.DONE, done.step)
        assertNull(done.verificationToken)
    }

    @Test fun `이메일 형식이 틀리거나 도메인이 없으면 코드를 받을 수 없다`() {
        assertFalse(ResetState().onEmailChanged("김철수", "eulji.ac.kr").canRequestCode)
        assertFalse(ResetState().onEmailChanged("kim", null).canRequestCode)
        assertFalse(ResetState().onEmailChanged("", "eulji.ac.kr").canRequestCode)
        assertEquals(ResetStep.EMAIL, ResetState().onEmailChanged("김철수", "x").codeSent().step)
    }

    @Test fun `아이디의 플러스와 공백은 지워진다`() {
        assertEquals("kim1", ResetState().onEmailChanged("kim+1 ", "eulji.ac.kr").emailId)
    }

    @Test fun `단계를 건너뛰는 호출은 무시된다`() {
        val s = ResetState().onEmailChanged("kim", "eulji.ac.kr")
        assertEquals(ResetStep.EMAIL, s.codeVerified("tok").step)
        assertEquals(ResetStep.EMAIL, s.back().step)
    }

    @Test fun `뒤로가기 - 비밀번호 화면에서는 인증을 다시 한다`() {
        val back = atPassword().back()
        assertEquals(ResetStep.EMAIL, back.step)
        assertNull(back.verificationToken)
        assertEquals("kim@eulji.ac.kr", back.email)
        assertEquals(ResetStep.EMAIL, ResetState().onEmailChanged("kim", "eulji.ac.kr").codeSent().back().step)
    }

    @Test fun `인증 토큰이 거절되면 처음으로`() {
        val s = atPassword().verificationExpired()
        assertEquals(ResetStep.EMAIL, s.step)
        assertNull(s.verificationToken)
    }
}
