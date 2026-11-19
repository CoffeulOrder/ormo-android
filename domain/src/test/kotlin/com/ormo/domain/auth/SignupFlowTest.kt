package com.ormo.domain.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignupFlowTest {
    private val all = ConsentState().toggleAll()

    private fun atConsent(): SignupState = SignupState()
        .onEmailChanged("kim", "eulji.ac.kr").codeSent()
        .codeVerified("tok").nameConfirmed("가을").passwordConfirmed("abc12345")

    @Test fun `정상 흐름 이메일 코드 이름 비밀번호 약관 완료`() {
        var s = SignupState().onEmailChanged("kim", "eulji.ac.kr")
        assertTrue(s.canRequestCode)
        assertEquals("kim@eulji.ac.kr", s.email)

        s = s.codeSent()
        assertEquals(SignupStep.CODE, s.step)
        s = s.codeVerified("tok")
        assertEquals(SignupStep.NAME, s.step)
        assertEquals("tok", s.verificationToken)
        s = s.nameConfirmed("  가을  ")
        assertEquals(SignupStep.PASSWORD, s.step)
        assertEquals("가을", s.name)
        s = s.passwordConfirmed("abc12345")
        assertEquals(SignupStep.CONSENT, s.step)

        val req = s.toRequest(all)!!
        assertEquals(
            SignUpRequest("kim@eulji.ac.kr", "가을", "abc12345", "tok", termsAgreed = true, privacyAgreed = true),
            req,
        )

        val done = s.succeeded()
        assertEquals(SignupStep.DONE, done.step)
        assertEquals("", done.password) // 비밀번호는 메모리에서 지운다
        assertNull(done.verificationToken)
    }

    @Test fun `이메일 형식이 틀리거나 도메인이 없으면 코드 요청 불가`() {
        assertFalse(SignupState().onEmailChanged("김철수", "eulji.ac.kr").canRequestCode)
        assertFalse(SignupState().onEmailChanged("a!b", "eulji.ac.kr").canRequestCode)
        assertFalse(SignupState().onEmailChanged("kim", null).canRequestCode)
        assertFalse(SignupState().onEmailChanged("", "eulji.ac.kr").canRequestCode)
        assertEquals(SignupStep.EMAIL, SignupState().onEmailChanged("김철수", "x").codeSent().step)
    }

    @Test fun `이메일 아이디의 플러스와 공백은 지워진다`() {
        assertEquals("kim1", SignupState().onEmailChanged("kim+1 ", "eulji.ac.kr").emailId)
    }

    @Test fun `단계를 건너뛰는 호출은 무시된다`() {
        val s = SignupState().onEmailChanged("kim", "eulji.ac.kr")
        assertEquals(SignupStep.EMAIL, s.codeVerified("tok").step)
        assertEquals(SignupStep.EMAIL, s.nameConfirmed("가을").step)
        assertEquals(SignupStep.EMAIL, s.passwordConfirmed("abc12345").step)
        assertNull(s.toRequest(all))
    }

    @Test fun `이름 규칙에 맞지 않으면 다음으로 넘어가지 않는다`() {
        val s = SignupState().onEmailChanged("kim", "eulji.ac.kr").codeSent().codeVerified("tok")
        assertEquals(SignupStep.NAME, s.nameConfirmed("").step)
        assertEquals(SignupStep.NAME, s.nameConfirmed("   ").step)
        assertEquals(SignupStep.NAME, s.nameConfirmed("가을😀").step)
        assertEquals(SignupStep.NAME, s.nameConfirmed("가".repeat(21)).step)
    }

    @Test fun `필수 약관에 모두 동의해야 요청이 만들어진다`() {
        val s = atConsent()
        assertNull(s.toRequest(ConsentState()))
        assertNull(s.toRequest(ConsentState().toggle(ConsentId.TERMS)))
        assertNotNull(s.toRequest(all))
    }

    @Test fun `뒤로가기`() {
        val consent = atConsent()
        assertEquals(SignupStep.PASSWORD, consent.back().step)
        assertEquals(SignupStep.NAME, consent.back().back().step)
        val fromName = consent.back().back().back()
        assertEquals(SignupStep.EMAIL, fromName.step)
        assertNull(fromName.verificationToken) // 인증이 끝난 뒤 이메일로 돌아가면 다시 인증
        assertEquals(SignupStep.EMAIL, SignupState().onEmailChanged("kim", "eulji.ac.kr").codeSent().back().step)
        assertEquals(SignupStep.EMAIL, SignupState().back().step)
    }

    @Test fun `뒤로 갔다 와도 앞에서 모은 값은 유지된다`() {
        val s = atConsent().back().back()
        assertEquals("가을", s.name)
        assertEquals("kim@eulji.ac.kr", s.email)
        assertEquals("tok", s.verificationToken)
    }

    @Test fun `이메일 중복이면 처음으로 돌아가 안내한다`() {
        val s = atConsent().rejected(SignupRejection.EMAIL_TAKEN)
        assertEquals(SignupStep.EMAIL, s.step)
        assertEquals(SignupNotice.EMAIL_TAKEN, s.notice)
        assertNull(s.verificationToken)
        assertEquals("", s.password)
        assertEquals("kim@eulji.ac.kr", s.email) // 이메일은 유지 ([로그인하기] 에서 채움)
    }

    @Test fun `인증이 무효가 되면 인증부터 다시`() {
        val s = atConsent().rejected(SignupRejection.VERIFICATION_INVALID)
        assertEquals(SignupStep.EMAIL, s.step)
        assertEquals(SignupNotice.VERIFICATION_EXPIRED, s.notice)
        assertNull(s.verificationToken)
    }

    @Test fun `비밀번호 규칙 위반은 비밀번호 단계로`() {
        assertEquals(SignupStep.PASSWORD, atConsent().rejected(SignupRejection.PASSWORD_INVALID).step)
    }

    @Test fun `네트워크 오류와 기타 거절은 제자리`() {
        val s = atConsent()
        assertEquals(s, s.rejected(SignupRejection.NETWORK))
        assertEquals(s, s.rejected(SignupRejection.OTHER))
        assertEquals(s, s.rejected(SignupRejection.CONSENT_REQUIRED))
    }

    @Test fun `이메일을 고치면 안내가 사라진다`() {
        val s = atConsent().rejected(SignupRejection.EMAIL_TAKEN).onEmailChanged("lee", "eulji.ac.kr")
        assertNull(s.notice)
    }
}
