package com.ormo.customer.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.ormo.customer.ui.components.BottomCta
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.FormScreen
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoField
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.OrmoSnackbarState
import com.ormo.customer.ui.components.SnackbarDurations
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space
import com.ormo.domain.auth.ResetStep
import com.ormo.domain.rules.EmailRules

/** 비밀번호 재설정 (디자인 0.3.1~0.3.3). 완료 화면의 [로그인하기] → 로그인 화면(이메일 채움 + 성공 토스트 '비밀번호를 변경했어요' 2초). */
@Composable
fun ResetScreen(
    vm: ResetViewModel,
    onClose: () -> Unit,
    onLogin: (email: String?) -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val s = ui.s
    val snackbar = remember { OrmoSnackbarState() }
    val verification by vm.verification.state.collectAsState()
    val sendingCode by vm.verification.sending.collectAsState()
    val codeNotice by vm.verification.notice.collectAsState()

    LaunchedEffect(Unit) {
        vm.eventFlow.collect { e ->
            when (e) {
                is ResetEvent.Snackbar -> snackbar.show(e.message, SnackbarDurations.PARTIAL_FAILURE)
            }
        }
    }
    // 완료 화면에서는 뒤로가기도 로그인으로
    BackHandler { if (s.step == ResetStep.DONE) onLogin(vm.emailForLogin()) else if (!vm.back()) onClose() }

    when (s.step) {
        ResetStep.EMAIL -> ResetEmailStep(ui, snackbar, vm, onClose)
        ResetStep.CODE -> CodeStep(
            email = s.email.orEmpty(),
            verification = verification,
            sending = sendingCode,
            notice = codeNotice,
            onNoticeShown = vm.verification::clearNotice,
            onCodeChanged = vm::onCodeChanged,
            onResend = vm::resendCode,
            onBack = { vm.back() },
        )
        ResetStep.PASSWORD -> NewPasswordStep(
            form = ui.password,
            loading = ui.submitting,
            snackbar = snackbar,
            onPasswordChanged = vm::onPasswordChanged,
            onConfirmChanged = vm::onConfirmChanged,
            onAdvance = vm::advancePassword,
            onSubmit = vm::submit,
            onBack = { vm.back() },
            stageTitleFirst = "새 비밀번호를 입력해주세요",
        )
        ResetStep.DONE -> {
            val c = Ormo.colors
            Column(Modifier.fillMaxSize().background(c.bgDefault)) {
                Column(
                    // Figma 0.3.3: 심볼 상자 위가 y=233, 제목 글자 y=411, 안내 y=455
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.pageMargin).padding(top = 233.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    OrmoMark()
                    Text("비밀번호를 변경했어요", style = OrmoType.displayXsSemibold, color = c.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 44.dp).semantics { heading() })
                    Text("새 비밀번호로 로그인해주세요", style = OrmoType.bodyMdRegular, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 13.dp))
                }
                BottomCta { OrmoButton("로그인하기", { onLogin(vm.emailForLogin()) }, Modifier.fillMaxWidth(), size = ButtonSize.XL) }
            }
        }
    }
}

@Composable
private fun ResetEmailStep(ui: ResetUi, snackbar: OrmoSnackbarState, vm: ResetViewModel, onClose: () -> Unit) {
    val s = ui.s
    var showDomainSheet by remember { mutableStateOf(false) }
    val formatError = s.emailId.isNotEmpty() && !EmailRules.isValidId(s.emailId)
    val error = when {
        formatError -> "이메일 형식을 확인해주세요"
        ui.emailProblem == EmailProblem.DAILY_LIMIT -> codeNoticeText(CodeNotice.DAILY_LIMIT)
        ui.emailProblem == EmailProblem.TOO_SOON -> codeNoticeText(CodeNotice.TOO_SOON)
        else -> null
    }
    val domains = ui.domains
    FormScreen(
        firstStep = true,
        title = "가입한 학교 이메일을\n입력해주세요",
        onBack = onClose,
        snackbar = snackbar,
        bottom = { OrmoButton("인증코드 받기", vm::requestCode, Modifier.fillMaxWidth(), size = ButtonSize.XL, enabled = s.canRequestCode, loading = ui.sending) },
    ) {
        if (ui.domainsFailed) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s12)) {
                Text("학교 메일 정보를 불러오지 못했어요", style = OrmoType.bodyMdMedium, color = Ormo.colors.textDanger)
                OrmoButton("다시 시도", vm::loadDomains, kind = ButtonKind.WeakNeutral, size = ButtonSize.M)
            }
        } else {
            OrmoField(
                value = s.emailId,
                onValueChange = vm::onEmailChanged,
                label = "이메일",
                error = error,
                suffix = s.domain?.let { "@$it" },
                trailing = if (domains?.showsPicker == true) {
                    { Icon(OrmoIcons.ChevronDown, contentDescription = "학교 메일 선택", tint = Ormo.colors.iconPrimary, modifier = Modifier.size(Size.iconLg)) }
                } else {
                    null
                },
                onSuffixClick = if (domains?.showsPicker == true) ({ showDomainSheet = true }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.requestCode() }),
                autoFocus = true,
            )
        }
    }
    if (showDomainSheet && domains != null) {
        DomainSheet(domains.items.map { it.domain }, selected = s.domain, onSelect = { vm.onDomainSelected(it); showDomainSheet = false }, onDismiss = { showDomainSheet = false })
    }
}
