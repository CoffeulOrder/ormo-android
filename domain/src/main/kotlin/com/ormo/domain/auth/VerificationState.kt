package com.ormo.domain.auth

import com.ormo.core.Countdown
import com.ormo.domain.rules.VerificationCodeRules

enum class VerificationPhase { INPUT, VERIFYING, VERIFIED, LOCKED }

/** 인증코드 화면(0.2.2)에 띄울 문구 종류 */
enum class VerificationMessage {
    /** "인증코드가 일치하지 않아요" (1~4번째 오류) */
    MISMATCH,

    /** "코드를 5번 잘못 입력해 사용이 제한됐어요" — 입력 끔 */
    TOO_MANY_WRONG,

    /** "입력 시간이 만료됐어요" (빨간색) — 입력 끔 */
    EXPIRED,
}

/**
 * 인증코드 입력 화면 상태 (디자인 0.2.2, 0.3.1). 시각은 호출하는 쪽이 넘긴다 (테스트 가능, 1초 틱은 UI 가 돌린다).
 *
 * - 6자리를 모두 넣으면 버튼 없이 자동 확인 ([shouldSubmit]).
 * - 남은 시간 = 코드 유효 시간(서버 값, 기본 5:00), 재발송 대기(기본 1분)는 따로. 0:00 이 되면 '메일이 오지 않나요?' 링크.
 * - 오류 5번이면 그 코드는 사용 불가 → 입력 끔 + [인증코드 다시 받기]. 시간이 지나도 마찬가지.
 * - 다시 받기를 누르면 [onResent] 로 새로 시작 (칸 비움, 오류 횟수 0, 시간 재시작).
 */
data class VerificationState(
    val code: String = "",
    val expiresAtMs: Long,
    val resendAtMs: Long,
    val wrongAttempts: Int = 0,
    val phase: VerificationPhase = VerificationPhase.INPUT,
    val message: VerificationMessage? = null,
) {
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs

    /** 남은 유효 시간(초, 올림) */
    fun remainingSeconds(nowMs: Long): Int = Countdown(expiresAtMs).remainingSeconds(nowMs)

    /** 재발송까지 남은 시간(초, 올림) — "0:59 후에 다시 받을 수 있어요" */
    fun resendRemainingSeconds(nowMs: Long): Int = Countdown(resendAtMs).remainingSeconds(nowMs)

    /** 다시 받기가 가능한가: 대기가 끝났거나, 이미 못 쓰는 코드(만료·5번 오류)일 때 */
    fun canResend(nowMs: Long): Boolean =
        phase == VerificationPhase.LOCKED || isExpired(nowMs) || resendRemainingSeconds(nowMs) == 0

    fun inputEnabled(nowMs: Long): Boolean = phase == VerificationPhase.INPUT && !isExpired(nowMs)

    /**
     * 숫자만 6자리까지. 입력이 꺼져 있으면 무시. 입력을 시작하면 이전 오류 문구는 지운다.
     * 틀린 코드는 칸에 남아 있으므로(Figma: 숫자 6개 + 빨간 테두리) 그 위에 **새 숫자를 치면 처음부터 다시** 입력한다.
     */
    fun onCodeChanged(raw: String, nowMs: Long): VerificationState {
        if (!inputEnabled(nowMs)) return this
        val replacing = message == VerificationMessage.MISMATCH && code.length == VerificationCodeRules.LENGTH && raw.length > code.length
        val next = if (replacing) raw.takeLast(raw.length - code.length) else raw
        return copy(code = VerificationCodeRules.sanitize(next), message = null)
    }

    /** 6자리가 모두 차면 확인을 요청한다 */
    fun shouldSubmit(nowMs: Long): Boolean = inputEnabled(nowMs) && VerificationCodeRules.isComplete(code)

    fun onSubmitStarted(): VerificationState = copy(phase = VerificationPhase.VERIFYING, message = null)

    fun onVerified(): VerificationState = copy(phase = VerificationPhase.VERIFIED, message = null)

    fun onFailed(error: CodeError, nowMs: Long, remainingAttempts: Int? = null): VerificationState = when (error) {
        CodeError.MISMATCH -> {
            // 서버가 센 횟수가 더 많으면(응답을 놓친 요청 등) 서버를 따른다
            val wrong = maxOf(wrongAttempts + 1, if (remainingAttempts != null) MAX_WRONG - remainingAttempts else 0)
            if (wrong >= MAX_WRONG) {
                locked(wrong)
            } else {
                copy(wrongAttempts = wrong, phase = VerificationPhase.INPUT, message = VerificationMessage.MISMATCH)
            }
        }
        CodeError.TOO_MANY_ATTEMPTS -> locked(wrongAttempts)
        CodeError.EXPIRED ->
            // 서버는 5번째 오답에도 '만료'(410 EV005)로 답한다 → 시간이 남아 있는데 이미 4번 틀렸으면 5번 오답이다
            if (wrongAttempts >= MAX_WRONG - 1 && !isExpired(nowMs)) {
                locked(MAX_WRONG)
            } else {
                copy(expiresAtMs = nowMs, phase = VerificationPhase.INPUT, message = VerificationMessage.EXPIRED)
            }
    }

    private fun locked(wrong: Int) =
        copy(wrongAttempts = wrong, phase = VerificationPhase.LOCKED, message = VerificationMessage.TOO_MANY_WRONG)

    /** 네트워크 오류 등 확인 자체가 안 된 경우: 다시 입력할 수 있게 하되, 자동 재전송 루프를 막으려고 칸은 비운다 */
    fun onSubmitAborted(): VerificationState = copy(code = "", phase = VerificationPhase.INPUT)

    fun onResent(result: SendCodeResult, nowMs: Long): VerificationState = started(result, nowMs)

    /** 지금 보여줄 문구 (만료는 시간이 지나면서 저절로 생기므로 여기서 계산) */
    fun displayMessage(nowMs: Long): VerificationMessage? =
        message ?: if (phase == VerificationPhase.INPUT && isExpired(nowMs)) VerificationMessage.EXPIRED else null

    companion object {
        /** 5번째 오류에서 그 코드는 사용 불가 (디자인 8.1: 인증코드 오답 한도 5번) */
        const val MAX_WRONG = 5

        fun started(result: SendCodeResult, nowMs: Long) = VerificationState(
            expiresAtMs = nowMs + result.expiresInSec * 1000L,
            resendAtMs = nowMs + result.resendAfterSec * 1000L,
        )
    }
}
