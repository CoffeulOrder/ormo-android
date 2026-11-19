package com.ormo.domain.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputRulesTest {

    // ---- 비밀번호 ----

    @Test fun `8자 이상 영문과 숫자 포함이면 통과`() {
        assertTrue(PasswordRules.check("abc12345").isValid)
    }

    @Test fun `숫자가 없으면 규칙 두 번째 행이 미충족`() {
        val c = PasswordRules.check("abcdefgh")
        assertTrue(c.longEnough)
        assertFalse(c.hasLetterAndDigit)
        assertFalse(c.isValid)
    }

    @Test fun `7자는 길이 미충족`() {
        val c = PasswordRules.check("abcde12")
        assertFalse(c.longEnough)
        assertTrue(c.hasLetterAndDigit)
    }

    @Test fun `한글은 영문으로 치지 않는다`() {
        assertFalse(PasswordRules.check("비밀번호12345678").hasLetterAndDigit)
    }

    @Test fun `72바이트까지 허용`() {
        assertTrue(PasswordRules.check("a1" + "a".repeat(70)).isValid) // 72 bytes
        assertFalse(PasswordRules.check("a1" + "a".repeat(71)).isValid) // 73 bytes
        assertFalse(PasswordRules.check("a1" + "가".repeat(24)).withinMaxBytes) // 2 + 72 bytes
    }

    @Test fun `비밀번호 확인 일치`() {
        assertTrue(PasswordRules.confirmMatches("abc12345", "abc12345"))
        assertFalse(PasswordRules.confirmMatches("abc12345", "abc12346"))
    }

    // ---- 이메일 ----

    @Test fun `아이디 정리는 골뱅이 앞만 남기고 공백과 더하기를 지운다`() {
        assertEquals("kim1", EmailRules.sanitizeId("kim+1 @eulji.ac.kr"))
        assertEquals("kim", EmailRules.sanitizeId("kim"))
    }

    @Test fun `아이디 형식`() {
        assertTrue(EmailRules.isValidId("kim.lee_1-x"))
        assertFalse(EmailRules.isValidId("김철수"))
        assertFalse(EmailRules.isValidId("a b"))
        assertFalse(EmailRules.isValidId("a+b"))
        assertFalse(EmailRules.isValidId(""))
    }

    @Test fun `이메일 전체를 붙여 넣으면 아이디와 도메인을 나눈다`() {
        val domains = listOf("eulji.ac.kr", "student.eulji.ac.kr")
        assertEquals(EmailRules.Parts("Kim", "eulji.ac.kr"), EmailRules.splitPasted(" Kim@Eulji.ac.kr ", domains))
        assertEquals(EmailRules.Parts("kim", "student.eulji.ac.kr"), EmailRules.splitPasted("kim@student.eulji.ac.kr", domains))
    }

    @Test fun `허용 목록에 없는 도메인이나 골뱅이 없는 입력은 도메인 없음`() {
        val domains = listOf("eulji.ac.kr")
        assertEquals(EmailRules.Parts("kim", null), EmailRules.splitPasted("kim@gmail.com", domains))
        assertEquals(EmailRules.Parts("kim", null), EmailRules.splitPasted("kim", domains))
    }

    // ---- 인증코드 ----

    @Test fun `인증코드는 숫자만 6자리`() {
        assertEquals("123456", VerificationCodeRules.sanitize("12a3 45678"))
        assertTrue(VerificationCodeRules.isComplete("123456"))
        assertFalse(VerificationCodeRules.isComplete("12345"))
        assertFalse(VerificationCodeRules.isComplete("12345a"))
    }

    // ---- 이름 ----

    @Test fun `이름 정상 입력`() {
        assertNull(NameRules.validate("가을"))
        assertNull(NameRules.validate("가 을"))
        assertNull(NameRules.validate("Autumn"))
        assertNull(NameRules.validate("가".repeat(20)))
    }

    @Test fun `이름 앞뒤 공백 제거 가운데 띄어쓰기 유지`() {
        assertEquals("가 을", NameRules.normalize("  가 을  "))
    }

    @Test fun `이름이 비었거나 공백뿐이면 EMPTY`() {
        assertEquals(NameRules.Error.EMPTY, NameRules.validate(""))
        assertEquals(NameRules.Error.EMPTY, NameRules.validate("   "))
    }

    @Test fun `이름이 21자면 TOO_LONG`() {
        assertEquals(NameRules.Error.TOO_LONG, NameRules.validate("가".repeat(21)))
    }

    @Test fun `이모지와 기호는 INVALID_CHARS`() {
        assertEquals(NameRules.Error.INVALID_CHARS, NameRules.validate("가을😀"))
        assertEquals(NameRules.Error.INVALID_CHARS, NameRules.validate("a!b"))
    }

    @Test fun `이모지는 한 글자로 센다`() {
        // 20자 + 이모지 1개 = 21 code points → TOO_LONG (UTF-16 길이로 세면 22)
        assertEquals(NameRules.Error.TOO_LONG, NameRules.validate("가".repeat(20) + "😀"))
    }
}
