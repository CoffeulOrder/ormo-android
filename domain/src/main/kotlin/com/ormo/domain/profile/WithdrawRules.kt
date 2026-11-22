package com.ormo.domain.profile

/** 탈퇴 가능 여부 (5.3.1 / 5.3.2) */
sealed interface WithdrawGate {
    data object Allowed : WithdrawGate

    /** 진행 중인 주문이 있으면 탈퇴 차단 — '진행 중인 주문이 2건 있어요' / '주문이 끝난 뒤에 탈퇴할 수 있어요', [닫기] [주문 보기] */
    data class Blocked(val activeOrderCount: Int) : WithdrawGate {
        val title: String get() = "진행 중인 주문이 ${activeOrderCount}건 있어요"
        val body: String get() = "주문이 끝난 뒤에 탈퇴할 수 있어요"
    }
}

enum class WithdrawFailure {
    /** 비밀번호가 맞지 않음 — 탈퇴 전 비밀번호 확인 (5.3.1) */
    WRONG_PASSWORD,

    /** 화면에서는 통과했는데 서버가 진행 중 주문을 발견 (경쟁) → 5.3.2 */
    ACTIVE_ORDERS,
    NETWORK,
    OTHER,
}

/**
 * 탈퇴 안내 + 비밀번호 확인 화면 (5.3.1). 순수 불변.
 * 이름과 이메일은 바로 삭제하고 주문·결제 기록은 전자상거래법에 따라 5년 보관한다는 안내를 보여주고, 비밀번호를 확인한 뒤 앱 안에서 끝까지 처리한다.
 */
data class WithdrawFormState(
    val password: String = "",
    val submitting: Boolean = false,
    val failure: WithdrawFailure? = null,
) {
    val canSubmit: Boolean get() = password.isNotEmpty() && !submitting

    fun onPasswordChanged(value: String) = copy(password = value, failure = null)
    fun onSubmitStarted() = copy(submitting = true, failure = null)

    /** 실패하면 비밀번호 칸은 비운다 (틀린 값을 그대로 두지 않음). 네트워크·기타 오류는 입력을 유지해 다시 누르기만 하면 되게. */
    fun onFailure(f: WithdrawFailure) = copy(
        submitting = false,
        failure = f,
        password = if (f == WithdrawFailure.WRONG_PASSWORD) "" else password,
    )
}

object WithdrawRules {
    const val TITLE = "탈퇴하기 전에 확인해주세요"
    const val RETENTION_NOTICE = "이름과 이메일은 바로 삭제돼요. 주문·결제 기록은 전자상거래법에 따라 5년 동안 보관돼요."
    const val DONE = "탈퇴가 완료됐어요. 그동안 ORMO를 이용해 주셔서 감사해요"

    fun gate(activeOrderCount: Int): WithdrawGate =
        if (activeOrderCount > 0) WithdrawGate.Blocked(activeOrderCount) else WithdrawGate.Allowed
}
