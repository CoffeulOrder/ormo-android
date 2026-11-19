package com.ormo.domain.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordFormStateTest {

    @Test fun `규칙을 충족하면 첫 확인 버튼이 켜진다`() {
        assertFalse(PasswordFormState("abc").canAdvance)
        assertFalse(PasswordFormState("abcdefgh").canAdvance)
        assertTrue(PasswordFormState("abc12345").canAdvance)
    }

    @Test fun `확인 단계로는 규칙 충족 시에만 넘어간다`() {
        assertEquals(PasswordStage.PASSWORD, PasswordFormState("abc").advance().stage)
        assertEquals(PasswordStage.CONFIRM, PasswordFormState("abc12345").advance().stage)
    }

    @Test fun `확인 칸이 일치해야 제출할 수 있다`() {
        val s = PasswordFormState("abc12345").advance()
        assertFalse(s.canSubmit)
        assertFalse(s.onConfirmChanged("abc1234").canSubmit)
        assertTrue(s.onConfirmChanged("abc12345").canSubmit)
    }

    @Test fun `불일치 문구는 확인 칸이 비밀번호 길이만큼 찼는데 다를 때`() {
        val s = PasswordFormState("abc12345").advance()
        assertFalse(s.mismatch)
        assertFalse(s.onConfirmChanged("abc1234").mismatch) // 아직 입력 중
        assertTrue(s.onConfirmChanged("abc12346").mismatch)
        assertFalse(s.onConfirmChanged("abc12345").mismatch)
    }

    @Test fun `서버 거절은 입력을 바꾸면 사라진다`() {
        val s = PasswordFormState("abc12345").advance().onConfirmChanged("abc12345")
            .onServerFailure(PasswordFailure.SAME_AS_OLD)
        assertEquals(PasswordFailure.SAME_AS_OLD, s.serverFailure)
        assertNull(s.onPasswordChanged("abc12346").serverFailure)
        assertNull(s.onConfirmChanged("x").serverFailure)
    }

    @Test fun `비밀번호를 고치면 일치 여부를 다시 본다`() {
        val s = PasswordFormState("abc12345").advance().onConfirmChanged("abc12345")
        assertTrue(s.canSubmit)
        assertFalse(s.onPasswordChanged("abc12346").canSubmit)
    }
}

class ChangePasswordFormStateTest {
    @Test fun `현재 비밀번호와 새 비밀번호 규칙과 확인이 모두 맞아야 제출`() {
        val s = ChangePasswordFormState()
        assertFalse(s.canSubmit)
        val ok = s.onCurrentChanged("old12345").onNewChanged("new12345").onConfirmChanged("new12345")
        assertTrue(ok.canSubmit)
        assertFalse(ok.onCurrentChanged("").canSubmit)
        assertFalse(ok.onNewChanged("short1").canSubmit)
        assertFalse(ok.onConfirmChanged("new1234").canSubmit)
    }

    @Test fun `불일치와 서버 거절`() {
        val s = ChangePasswordFormState(current = "old12345", new = "new12345", confirm = "new1234")
        assertTrue(s.mismatch)
        val failed = s.onServerFailure(PasswordFailure.WRONG_CURRENT)
        assertEquals(PasswordFailure.WRONG_CURRENT, failed.serverFailure)
        assertNull(failed.onCurrentChanged("x").serverFailure)
    }
}

class ConsentStateTest {
    @Test fun `처음에는 모두 비어 있고 제출할 수 없다`() {
        val s = ConsentState()
        assertFalse(s.allChecked)
        assertFalse(s.canSubmit)
        ConsentId.entries.forEach { assertFalse(s.isChecked(it)) }
    }

    @Test fun `전체 동의로 모두 체크하고 다시 누르면 모두 해제`() {
        val all = ConsentState().toggleAll()
        assertTrue(all.allChecked)
        assertTrue(all.canSubmit)
        assertEquals(ConsentState(), all.toggleAll())
    }

    @Test fun `필수 항목을 모두 체크해야 제출할 수 있다`() {
        val one = ConsentState().toggle(ConsentId.TERMS)
        assertFalse(one.canSubmit)
        assertTrue(one.toggle(ConsentId.PRIVACY).canSubmit)
    }

    @Test fun `하나라도 해제하면 전체 동의도 해제된다`() {
        val s = ConsentState().toggleAll().toggle(ConsentId.PRIVACY)
        assertFalse(s.allChecked)
        assertFalse(s.canSubmit)
        assertTrue(s.isChecked(ConsentId.TERMS))
        // 일부만 체크된 상태에서 전체 동의를 누르면 모두 체크
        assertTrue(s.toggleAll().allChecked)
    }
}

class EmailDomainsTest {
    private val eulji = SchoolDomain("eulji.ac.kr", "s1", "을지대학교")
    private val student = SchoolDomain("student.eulji.ac.kr", "s1", "을지대학교")

    @Test fun `도메인이 하나뿐이면 선택 시트를 쓰지 않는다`() {
        val d = EmailDomains(listOf(eulji))
        assertFalse(d.showsPicker)
        assertEquals(eulji, d.default)
    }

    @Test fun `여러 개면 선택 시트를 쓰고 첫 번째가 기본값`() {
        val d = EmailDomains(listOf(eulji, student))
        assertTrue(d.showsPicker)
        assertEquals(eulji, d.default)
        assertEquals(listOf("eulji.ac.kr", "student.eulji.ac.kr"), d.domains)
    }

    @Test fun `도메인으로 학교를 찾는다 (대소문자 무시)`() {
        val d = EmailDomains(listOf(eulji, student))
        assertEquals("s1", d.schoolIdOf("Student.Eulji.ac.kr"))
        assertNull(d.schoolIdOf("gmail.com"))
        assertNull(d.schoolIdOf(null))
    }

    @Test fun `비어 있으면 기본값이 없다`() {
        assertNull(EmailDomains(emptyList()).default)
    }
}
