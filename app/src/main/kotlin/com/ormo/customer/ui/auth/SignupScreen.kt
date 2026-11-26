package com.ormo.customer.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.FormScreen
import com.ormo.customer.ui.components.OrmoBottomSheet
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoDialog
import com.ormo.customer.ui.components.OrmoField
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.OrmoSnackbarState
import com.ormo.customer.ui.components.SnackbarDurations
import com.ormo.customer.ui.components.TermsRow
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space
import com.ormo.domain.auth.ConsentId
import com.ormo.domain.auth.SignupNotice
import com.ormo.domain.auth.SignupStep
import com.ormo.domain.rules.NameRules

/**
 * 회원가입 (디자인 0.2): 한 화면에 하나씩 이메일 → 코드 → 이름 → 비밀번호 → 약관(시트) → 완료.
 * 뒤로가기는 한 단계씩, 이메일 단계에서만 화면을 닫는다. 완료 화면에서는 뒤로가기를 막는다.
 */
@Composable
fun SignupScreen(
    vm: SignupViewModel,
    onClose: () -> Unit,
    onFinished: (loggedIn: Boolean) -> Unit,
    onLoginWithEmail: (email: String?) -> Unit,
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
                is SignupEvent.Snackbar -> snackbar.show(e.message, SnackbarDurations.PARTIAL_FAILURE)
                is SignupEvent.Finished -> onFinished(e.loggedIn)
            }
        }
    }
    BackHandler { if (!vm.back()) onClose() }

    when (s.step) {
        SignupStep.EMAIL -> EmailStep(
            ui = ui,
            snackbar = snackbar,
            onEmailChanged = vm::onEmailChanged,
            onDomainSelected = vm::onDomainSelected,
            onRequestCode = vm::requestCode,
            onRetryDomains = vm::loadDomains,
            onLogin = { onLoginWithEmail(vm.emailForLogin()) },
            onBack = onClose,
        )
        SignupStep.CODE -> CodeStep(
            email = s.email.orEmpty(),
            verification = verification,
            sending = sendingCode,
            notice = codeNotice,
            onNoticeShown = vm.verification::clearNotice,
            onCodeChanged = vm::onCodeChanged,
            onResend = vm::resendCode,
            onBack = { vm.back() },
        )
        SignupStep.NAME -> NameStep(
            value = ui.nameInput,
            error = ui.nameError,
            canConfirm = ui.canConfirmName,
            onChange = vm::onNameChanged,
            onConfirm = vm::confirmName,
            onBack = { vm.back() },
        )
        SignupStep.PASSWORD, SignupStep.CONSENT -> {
            NewPasswordStep(
                form = ui.password,
                loading = false,
                snackbar = snackbar,
                onPasswordChanged = vm::onPasswordChanged,
                onConfirmChanged = vm::onConfirmChanged,
                onAdvance = vm::advancePassword,
                onSubmit = vm::confirmPassword,
                onBack = { vm.back() },
                name = s.name,
            )
            if (s.step == SignupStep.CONSENT) ConsentSheet(ui, vm)
        }
        SignupStep.DONE -> DoneStep(name = s.name)
    }
}

@Composable
private fun EmailStep(
    ui: SignupUi,
    snackbar: OrmoSnackbarState,
    onEmailChanged: (String) -> Unit,
    onDomainSelected: (String) -> Unit,
    onRequestCode: () -> Unit,
    onRetryDomains: () -> Unit,
    onLogin: () -> Unit,
    onBack: () -> Unit,
) {
    val s = ui.s
    var showDomainSheet by remember { mutableStateOf(false) }
    val taken = s.notice == SignupNotice.EMAIL_TAKEN
    val formatError = s.emailId.isNotEmpty() && !com.ormo.domain.rules.EmailRules.isValidId(s.emailId)
    val error = when {
        taken -> "이미 가입된 이메일이에요"
        formatError -> "이메일 형식을 확인해주세요"
        ui.emailProblem == EmailProblem.DAILY_LIMIT -> codeNoticeText(CodeNotice.DAILY_LIMIT)
        ui.emailProblem == EmailProblem.TOO_SOON -> codeNoticeText(CodeNotice.TOO_SOON)
        s.notice == SignupNotice.VERIFICATION_EXPIRED -> "인증이 만료됐어요. 다시 인증해주세요"
        else -> null
    }
    val domains = ui.domains
    FormScreen(
        firstStep = true,
        title = "학교 이메일을 입력해주세요",
        onBack = onBack,
        snackbar = snackbar,
        bottom = {
            if (taken) {
                OrmoButton("로그인하기", onLogin, Modifier.fillMaxWidth(), size = ButtonSize.XL)
            } else {
                OrmoButton("인증코드 받기", onRequestCode, Modifier.fillMaxWidth(), size = ButtonSize.XL, enabled = s.canRequestCode, loading = ui.sending)
            }
        },
    ) {
        if (ui.domainsFailed) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s12)) {
                Text("학교 메일 정보를 불러오지 못했어요", style = OrmoType.bodyMdMedium, color = Ormo.colors.textDanger)
                OrmoButton("다시 시도", onRetryDomains, kind = ButtonKind.WeakNeutral, size = ButtonSize.M)
            }
        } else {
            OrmoField(
                value = s.emailId,
                onValueChange = onEmailChanged,
                label = "이메일",
                helper = "학교 메일로 재학생인지 확인해요",
                error = error,
                suffix = s.domain?.let { "@$it" },
                trailing = if (domains?.showsPicker == true) {
                    { Icon(OrmoIcons.ChevronDown, contentDescription = "학교 메일 선택", tint = Ormo.colors.iconPrimary, modifier = Modifier.size(Size.iconLg)) }
                } else {
                    null
                },
                onSuffixClick = if (domains?.showsPicker == true) ({ showDomainSheet = true }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRequestCode() }),
                autoFocus = true,
            )
        }
    }
    if (showDomainSheet && domains != null) {
        DomainSheet(domains.items.map { it.domain }, selected = s.domain, onSelect = { onDomainSelected(it); showDomainSheet = false }, onDismiss = { showDomainSheet = false })
    }
}

@Composable
internal fun DomainSheet(domains: List<String>, selected: String?, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    OrmoBottomSheet(onDismissRequest = onDismiss, title = "학교 메일 주소를 선택해주세요", titleBottomPadding = 0.dp) {
        Text("학교에서 발급한 메일 주소만 사용할 수 있어요", style = OrmoType.bodyMdRegular, color = Ormo.colors.textTertiary) // Figma: Text/Tertiary
        Column(Modifier.padding(top = 31.dp, bottom = Space.s16)) { // Figma: 안내 글자 아래 행 시작, 행 높이 48
            domains.forEach { d ->
                androidx.compose.foundation.layout.Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = d == selected, role = androidx.compose.ui.semantics.Role.RadioButton) { onSelect(d) }
                        .padding(vertical = Space.s12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("@$d", style = OrmoType.bodyMdMedium, color = Ormo.colors.textPrimary, modifier = Modifier.weight(1f))
                    if (d == selected) Icon(OrmoIcons.Check, contentDescription = null, tint = Ormo.colors.iconBrand, modifier = Modifier.size(Size.iconLg))
                }
            }
        }
    }
}

@Composable
private fun NameStep(
    value: String,
    error: NameRules.Error?,
    canConfirm: Boolean,
    onChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    FormScreen(
        title = "이름을 입력해주세요",
        onBack = onBack,
        bottom = { OrmoButton("확인", onConfirm, Modifier.fillMaxWidth(), size = ButtonSize.XL, enabled = canConfirm) },
    ) {
        OrmoField(
            value = value,
            onValueChange = onChange,
            label = "이름",
            // TODO(spec 0.2.3): 허용 글자와 오류 문구는 개발과 확정 필요 — 지금은 이모지·기호를 막는 문구
            error = when (error) {
                NameRules.Error.INVALID_CHARS -> "이름은 한글과 영문만 입력할 수 있어요"
                NameRules.Error.TOO_LONG -> "이름은 20자까지 입력할 수 있어요"
                else -> null
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { if (canConfirm) onConfirm() }),
            autoFocus = true,
        )
    }
}

/**
 * 약관 동의 시트 (디자인 0.2.5): '서비스 이용을 위해 약관에 동의해주세요'. [전체 동의]로 모두 체크, 하나라도 해제하면 전체 동의도 해제.
 * 처음엔 모두 비어 있고 [동의하고 가입하기]가 꺼져 있다. 각 행의 > 는 약관 전문. 시트를 아래로 밀면 닫히고 비밀번호 화면으로 돌아간다.
 */
@Composable
private fun ConsentSheet(ui: SignupUi, vm: SignupViewModel) {
    var legal by remember { mutableStateOf<ConsentId?>(null) }
    OrmoBottomSheet(
        onDismissRequest = vm::dismissConsent,
        title = "서비스 이용을 위해 약관에 동의해주세요",
        footer = {
            OrmoButton(
                "동의하고 가입하기",
                vm::submitSignup,
                Modifier.fillMaxWidth(),
                size = ButtonSize.XL,
                enabled = ui.consent.canSubmit,
                loading = ui.submitting,
            )
        },
    ) {
        TermsRow("전체 동의", ui.consent.allChecked, { vm.toggleAllConsent() }, strong = true)
        com.ormo.customer.ui.components.OrmoDivider()
        TermsRow("[필수] 서비스 이용약관", ui.consent.isChecked(ConsentId.TERMS), { vm.toggleConsent(ConsentId.TERMS) }, onOpen = { legal = ConsentId.TERMS })
        TermsRow("[필수] 개인정보 수집·이용", ui.consent.isChecked(ConsentId.PRIVACY), { vm.toggleConsent(ConsentId.PRIVACY) }, onOpen = { legal = ConsentId.PRIVACY })
    }
    legal?.let { id ->
        // TODO(spec 0.2.5): 약관 본문은 운영 중인 웹 페이지 주소로 받아 앱 안에서 열기 — 주소 확정 전이라 안내만 띄운다
        OrmoDialog(
            title = if (id == ConsentId.TERMS) "서비스 이용약관" else "개인정보 수집·이용",
            body = "약관 전문 페이지는 준비 중이에요",
            confirmLabel = "확인",
            onConfirm = { legal = null },
        )
    }
}

/**
 * 완료 (디자인 0.2.6): 심볼이 떨어지고 김과 조각이 퍼진 뒤(2.2초) 제목 '가을님, ORMO에 오신 걸 환영해요' 가 나타난다. 모션 종료(2.8초) 후 자동 이동.
 * 모션: Figma 에서 내보낸 Lottie `ormo_cup_confetti`. 알림 권한은 여기서 묻지 않는다 (첫 주문 뒤 4.5.1).
 */
@Composable
private fun DoneStep(name: String) {
    val c = Ormo.colors
    var celebrated by remember { mutableStateOf(false) }
    val titleAlpha by androidx.compose.animation.core.animateFloatAsState(if (celebrated) 1f else 0f, androidx.compose.animation.core.tween(300), label = "welcome-title")
    // Figma 0.2.6: 글자 두 줄은 화면 세로 중앙보다 36 위에 가운데 정렬
    Box(Modifier.fillMaxSize().background(c.bgDefault)) {
        com.ormo.customer.ui.auth.OrmoLottie(com.ormo.customer.R.raw.ormo_cup_confetti, align = Alignment.Center, recolor = if (Ormo.colors.isDark) listOf(com.ormo.customer.ui.auth.LottieRecolor("Confetti · Drop", androidx.compose.ui.graphics.Color.White)) else emptyList(), onFinished = { celebrated = true }) // 글자가 세로 중앙 기준이라 모션도 중앙 기준 (화면이 낮아도 서로 겹치지 않게)
        Text(
            "${name}님, \nORMO에 오신 걸 환영해요",
            style = OrmoType.displayXsSemibold,
            color = c.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).offset(y = (-36).dp).alpha(titleAlpha).semantics { heading() },
        )
    }
}
