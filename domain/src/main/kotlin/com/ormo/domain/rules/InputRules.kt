package com.ormo.domain.rules

/**
 * 입력 규칙. 앱 검증은 입력 편의용이고 서버가 같은 규칙으로 다시 검증한다.
 * 출처: 디자인 명세 0.2.x / 0.3.2 / 5.2.1 (새 Figma) + RN `constants/auth.ts`.
 * 닉네임은 새 디자인에서 없어졌다 (이름만).
 */
object PasswordRules {
    const val MIN_LENGTH = 8

    /** TODO(BE): BE 는 "8자 이상, 72바이트 이하" (bcrypt 한계). 확정되면 확인. */
    const val MAX_BYTES = 72

    /** 입력 중 실시간으로 보여 주는 규칙 행 상태 (디자인 0.2.4: 규칙 두 개) */
    data class Check(
        val longEnough: Boolean,
        val hasLetterAndDigit: Boolean,
        val withinMaxBytes: Boolean,
    ) {
        val isValid: Boolean get() = longEnough && hasLetterAndDigit && withinMaxBytes
    }

    fun check(pw: String): Check = Check(
        longEnough = pw.length >= MIN_LENGTH,
        hasLetterAndDigit = pw.any { it in 'A'..'Z' || it in 'a'..'z' } && pw.any { it in '0'..'9' },
        withinMaxBytes = pw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES,
    )

    fun confirmMatches(password: String, confirm: String): Boolean = password == confirm
}

object EmailRules {
    private val ID_RE = Regex("^[A-Za-z0-9._-]{1,64}$")

    /**
     * 학교 이메일 아이디 정리: `@` 앞만 남기고 공백·`+` 를 지운다.
     * (`id+1@…` 같은 별칭으로 여러 계정을 만들 수 없게. 서버가 다시 정규화·중복 판정을 해야 한다.)
     */
    fun sanitizeId(raw: String): String = raw.split('@')[0].replace(Regex("[+\\s]"), "")

    /** 형식 검사: 영문·숫자·`.`·`_`·`-` 만 (띄어쓰기·한글·특수문자는 "이메일 형식을 확인해주세요") */
    fun isValidId(id: String): Boolean = ID_RE.matches(id)

    data class Parts(val id: String, val domain: String?)

    /**
     * 이메일 전체를 붙여 넣었을 때 아이디와 도메인을 나눈다 (디자인 0.1.1).
     * 도메인이 허용 목록에 없으면 `domain = null` (아이디만 채운다).
     */
    fun splitPasted(text: String, allowedDomains: Collection<String>): Parts {
        val trimmed = text.trim()
        val id = sanitizeId(trimmed)
        if ('@' !in trimmed) return Parts(id, null)
        val domain = trimmed.substringAfter('@').trim().lowercase()
        val matched = allowedDomains.firstOrNull { it.lowercase() == domain }
        return Parts(id, matched)
    }
}

object VerificationCodeRules {
    const val LENGTH = 6

    /** 숫자만 남기고 6자리까지 */
    fun sanitize(raw: String): String = raw.filter { it in '0'..'9' }.take(LENGTH)

    fun isComplete(code: String): Boolean = code.length == LENGTH && code.all { it in '0'..'9' }
}

object NameRules {
    const val MAX_LENGTH = 20

    enum class Error { EMPTY, TOO_LONG, INVALID_CHARS }

    /** 앞뒤 공백 제거, 가운데 띄어쓰기는 유지 (디자인 0.2.3) */
    fun normalize(raw: String): String = raw.trim()

    /**
     * 1~20자, 공백만은 불가, 이모지·기호는 불가.
     * TODO(spec 0.2.3): "허용 글자와 문구는 개발과 확정 필요" — 지금은 글자(한글·영문 등)·숫자·띄어쓰기만 허용.
     * 한글 조합 중에는 호출하지 말 것 (UI 가 조합 완료 후에만 검사).
     */
    fun validate(raw: String): Error? {
        val name = normalize(raw)
        if (name.isEmpty()) return Error.EMPTY
        if (name.codePointCount(0, name.length) > MAX_LENGTH) return Error.TOO_LONG
        val ok = name.codePoints().allMatch { cp -> cp == ' '.code || Character.isLetterOrDigit(cp) }
        return if (ok) null else Error.INVALID_CHARS
    }
}

/** 회원가입 추천인 코드 (FeatureFlags.referral 이 켜졌을 때만 입력칸을 보인다). 영문/숫자 4~12자, 비워 두면 보내지 않는다. */
object ReferralRules {
    private val RE = Regex("^[A-Za-z0-9]{4,12}$")

    /** 입력 중에는 영문/숫자만 남긴다 */
    fun sanitize(raw: String): String = raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }

    fun isValid(code: String): Boolean = RE.matches(code.trim())

    /** 칸을 채웠는데 형식이 틀렸을 때만 오류 ('영문/숫자 4~12자로 입력해 주세요') */
    fun isBad(code: String): Boolean = code.trim().isNotEmpty() && !isValid(code)

    const val BAD_FORMAT = "영문/숫자 4~12자로 입력해 주세요"

    /** 요청에 실을 값: 플래그가 꺼져 있거나 비어 있으면 null */
    fun requestValue(code: String, enabled: Boolean): String? = if (enabled) code.trim().takeIf { it.isNotEmpty() } else null
}
