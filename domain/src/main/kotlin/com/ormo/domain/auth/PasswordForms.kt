package com.ormo.domain.auth

import com.ormo.domain.rules.PasswordRules

enum class PasswordStage { PASSWORD, CONFIRM }

/**
 * 새 비밀번호 입력 (가입 0.2.4, 재설정 0.3.2).
 * ① 비밀번호 입력 → 규칙(8자 이상, 영문과 숫자 포함)을 모두 충족하면 [확인] 활성 → ② 확인 칸이 위로 쌓임 →
 * 일치하면 [확인]/키보드 [완료]. 규칙은 입력 중 실시간으로 표시한다.
 */
data class PasswordFormState(
    val password: String = "",
    val confirm: String = "",
    val stage: PasswordStage = PasswordStage.PASSWORD,
    /** 서버가 돌려준 거절 ("기존 비밀번호와 다른 비밀번호를 입력해 주세요" 등). 입력이 바뀌면 지운다. */
    val serverFailure: PasswordFailure? = null,
) {
    val check: PasswordRules.Check get() = PasswordRules.check(password)

    /** 첫 [확인] 버튼 활성 조건 */
    val canAdvance: Boolean get() = stage == PasswordStage.PASSWORD && check.isValid

    /**
     * "비밀번호가 일치하지 않아요" 표시: 확인 칸에 비밀번호만큼(이상) 입력했는데 다를 때.
     * TODO(spec 0.2.4): 불일치를 언제부터 보여줄지(입력 중 / 길이 도달 / [확인] 시점)는 디자인 확인 필요.
     */
    val mismatch: Boolean
        get() = stage == PasswordStage.CONFIRM && confirm.length >= password.length && confirm.isNotEmpty() && confirm != password

    val canSubmit: Boolean get() = stage == PasswordStage.CONFIRM && check.isValid && confirm == password

    fun onPasswordChanged(value: String): PasswordFormState = copy(password = value, serverFailure = null)

    fun advance(): PasswordFormState = if (canAdvance) copy(stage = PasswordStage.CONFIRM) else this

    fun onConfirmChanged(value: String): PasswordFormState = copy(confirm = value, serverFailure = null)

    fun onServerFailure(failure: PasswordFailure): PasswordFormState = copy(serverFailure = failure)
}

/** 비밀번호 변경 (5.2.1): 현재 비밀번호 + 새 비밀번호 + 확인. 성공하면 모든 기기에서 로그아웃된다. */
data class ChangePasswordFormState(
    val current: String = "",
    val new: String = "",
    val confirm: String = "",
    val serverFailure: PasswordFailure? = null,
) {
    val check: PasswordRules.Check get() = PasswordRules.check(new)

    val mismatch: Boolean get() = confirm.isNotEmpty() && confirm != new

    val canSubmit: Boolean get() = current.isNotEmpty() && check.isValid && confirm == new

    fun onCurrentChanged(v: String) = copy(current = v, serverFailure = null)
    fun onNewChanged(v: String) = copy(new = v, serverFailure = null)
    fun onConfirmChanged(v: String) = copy(confirm = v, serverFailure = null)
    fun onServerFailure(f: PasswordFailure) = copy(serverFailure = f)
}
