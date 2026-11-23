package com.ormo.customer.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.ormo.core.formatCountdown
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.FormScreen
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoCodeInput
import com.ormo.customer.ui.components.OrmoField
import com.ormo.customer.ui.components.OrmoPasswordField
import com.ormo.customer.ui.components.OrmoSnackbarState
import com.ormo.customer.ui.components.PasswordRuleRow
import com.ormo.customer.ui.components.SnackbarDurations
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space
import com.ormo.domain.auth.PasswordFailure
import com.ormo.domain.auth.PasswordFormState
import com.ormo.domain.auth.PasswordStage
import com.ormo.domain.auth.VerificationMessage
import com.ormo.domain.auth.VerificationPhase
import com.ormo.domain.auth.VerificationState

// 가입(0.2)과 비밀번호 재설정(0.3)이 함께 쓰는 단계 화면들

fun codeNoticeText(n: CodeNotice): String = when (n) {
    CodeNotice.NETWORK -> "인터넷 연결을 확인해주세요"
    CodeNotice.OTHER -> "잠시 후 다시 시도해주세요"
    CodeNotice.DAILY_LIMIT -> "오늘은 인증코드를 더 받을 수 없어요" // TODO(spec 0.2.1): 하루 한도 초과 문구 미정
    CodeNotice.TOO_SOON -> "잠시 후에 다시 받을 수 있어요"
}

/**
 * 인증코드 입력 (디자인 0.2.2). 6자리를 모두 넣으면 버튼 없이 확인한다.
 * 남은 시간(기본 5:00)은 코드 유효 시간, 재발송 '0:59 후에 다시 받을 수 있어요'(회색, 1초씩 감소) → 0:00 에 '메일이 오지 않나요?' 링크.
 * 오답 1~4번째 '인증코드가 일치하지 않아요', 5번째 '코드를 5번 잘못 입력해 사용이 제한됐어요'(입력 끔), 시간 지남 '입력 시간이 만료됐어요'(빨간색, 입력 끔)
 * → [인증코드 다시 받기].
 */
@Composable
fun CodeStep(
    email: String,
    verification: VerificationState?,
    sending: Boolean,
    notice: CodeNotice?,
    onNoticeShown: () -> Unit,
    onCodeChanged: (String) -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Ormo.colors
    val snackbar = androidx.compose.runtime.remember { OrmoSnackbarState() }
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.show(codeNoticeText(notice), SnackbarDurations.PARTIAL_FAILURE)
            onNoticeShown()
        }
    }
    val now = rememberNowMs(active = verification != null)
    val v = verification
    val enabled = v?.inputEnabled(now) == true
    val message = v?.displayMessage(now)
    val showResendButton = v != null && (v.phase == VerificationPhase.LOCKED || v.isExpired(now))

    FormScreen(
        firstStep = true,
        titleGap = 20.dp,
        title = "메일로 받은\n인증코드 6자리를 입력해주세요",
        onBack = onBack,
        snackbar = snackbar,
        bottom = if (showResendButton) {
            { OrmoButton("인증코드 다시 받기", onResend, Modifier.fillMaxWidth(), size = ButtonSize.XL, loading = sending) }
        } else if (v != null) {
            {
                // Figma 0.2.2: 키보드 위 가운데에 밑줄 글씨 '0:59 후 다시 받을 수 있어요' → 0:00 에 '메일이 오지 않나요?'
                val resendSec = v.resendRemainingSeconds(now)
                Box(Modifier.fillMaxWidth().padding(bottom = Space.s8), contentAlignment = Alignment.Center) {
                    if (resendSec > 0) {
                        Text("${formatCountdown(resendSec)} 후에 다시 받을 수 있어요", style = OrmoType.bodyMdRegular, color = c.textSecondary, textDecoration = TextDecoration.Underline)
                    } else {
                        Text(
                            "메일이 오지 않나요?",
                            style = OrmoType.bodyMdSemibold,
                            color = c.textBrand,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable(role = Role.Button, onClick = onResend).padding(vertical = Space.s4),
                        )
                    }
                }
            }
        } else {
            null
        },
    ) {
        // 제목 아래 남은 시간 (이메일 주소 줄은 Figma 에 없음)
        if (v != null && !v.isExpired(now) && v.phase != VerificationPhase.LOCKED) {
            Text("남은 시간 ${formatCountdown(v.remainingSeconds(now))}", style = OrmoType.bodyMdRegular, color = c.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
        } else {
            Text(email, style = OrmoType.bodyMdRegular, color = c.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
        }
        OrmoCodeInput(
            value = v?.code.orEmpty(),
            onValueChange = onCodeChanged,
            enabled = enabled,
            invalid = message == VerificationMessage.MISMATCH || message == VerificationMessage.EXPIRED,
        )
        when (message) {
            VerificationMessage.MISMATCH -> Text("인증코드가 일치하지 않아요", style = OrmoType.bodySmMedium, color = c.textDanger)
            VerificationMessage.TOO_MANY_WRONG -> Text("코드를 5번 잘못 입력해 사용이 제한됐어요", style = OrmoType.bodySmMedium, color = c.textDanger)
            VerificationMessage.EXPIRED -> Text("입력 시간이 만료됐어요", style = OrmoType.bodySmMedium, color = c.textDanger)
            null -> Unit
        }
    }
}

/**
 * 새 비밀번호 (디자인 0.2.4 가입 / 0.3.2 재설정). ① 비밀번호 입력 → 규칙 두 개(8자 이상, 영문과 숫자 포함)를 입력 중 실시간으로 표시,
 * 모두 충족하면 [확인] 활성 → ② **확인 칸이 위로 쌓임** → 일치하면 [확인]/키보드 [완료]. 불일치는 빨간 칸 + '비밀번호가 일치하지 않아요', [확인] 꺼짐.
 * 서버가 현재 비밀번호와 같다고 거절하면 '기존 비밀번호와 다른 비밀번호를 입력해 주세요' (재설정).
 */
@Composable
fun NewPasswordStep(
    form: PasswordFormState,
    loading: Boolean,
    snackbar: OrmoSnackbarState?,
    onPasswordChanged: (String) -> Unit,
    onConfirmChanged: (String) -> Unit,
    onAdvance: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    stageTitleFirst: String = "비밀번호를 입력해주세요",
    /** 가입: 앞에서 입력한 이름이 비밀번호 칸 아래에 쌓여 보인다 (Figma 0.2.4) */
    name: String? = null,
) {
    val check = form.check
    val confirmStage = form.stage == PasswordStage.CONFIRM
    val serverError = when (form.serverFailure) {
        PasswordFailure.SAME_AS_OLD -> "기존 비밀번호와 다른 비밀번호를 입력해 주세요"
        PasswordFailure.INVALID_FORMAT -> "비밀번호 규칙을 다시 확인해주세요"
        else -> null
    }
    FormScreen(
        title = if (confirmStage) "비밀번호를 한 번 더 입력해주세요" else stageTitleFirst,
        onBack = onBack,
        snackbar = snackbar,
        bottom = {
            OrmoButton(
                "확인",
                if (confirmStage) onSubmit else onAdvance,
                Modifier.fillMaxWidth(),
                size = ButtonSize.XL,
                enabled = if (confirmStage) form.canSubmit else form.canAdvance,
                loading = loading,
            )
        },
    ) {
        if (confirmStage) {
            // 확인 칸이 위로 쌓인다
            OrmoPasswordField(
                value = form.confirm,
                onValueChange = onConfirmChanged,
                label = "비밀번호 확인",
                error = if (form.mismatch) "비밀번호가 일치하지 않아요" else serverError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (form.canSubmit) onSubmit() }),
                autoFocus = true,
            )
        }
        // Figma: 비밀번호 칸과 규칙 줄은 한 묶음(간격 8)
        Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            OrmoPasswordField(
                value = form.password,
                onValueChange = onPasswordChanged,
                label = "비밀번호",
                enabled = !confirmStage,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (form.canAdvance) onAdvance() }),
                autoFocus = !confirmStage,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s12)) { // Figma: 규칙 두 개를 한 줄에
                PasswordRuleRow("8자 이상", check.longEnough)
                PasswordRuleRow("영문과 숫자 포함", check.hasLetterAndDigit)
            }
        }
        if (!name.isNullOrEmpty()) {
            OrmoField(value = name, onValueChange = {}, label = "이름", enabled = false, clearable = false)
        }
    }
}
