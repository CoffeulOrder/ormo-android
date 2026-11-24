package com.ormo.customer.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.FormScreen
import com.ormo.customer.ui.components.OrmoBottomSheet
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoDialog
import com.ormo.customer.ui.components.OrmoField
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.OrmoPasswordField
import com.ormo.customer.ui.components.OrmoSnackbarState
import com.ormo.customer.ui.components.SnackbarDurations
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space
import com.ormo.data.auth.AuthNotice
import com.ormo.domain.auth.LoginMessage

/**
 * 로그인 (디자인 0.1.1~0.1.4). 두 칸이 모두 차면 [로그인] 활성 → 누르면 로딩. 실패하면 비밀번호 칸만 비우고(이메일 유지),
 * 두 칸 모두 빨간 테두리 + 비밀번호 칸 아래에 문구 한 줄 (어느 칸이 틀렸는지 알려주지 않음).
 * 잠김(0.1.2): 버튼이 "14:59 후 로그인" 으로 바뀌고 1초마다 줄어 0:00 에 다시 켜진다.
 */
@Composable
fun LoginScreen(
    vm: LoginViewModel,
    notice: AuthNotice?,
    onNoticeShown: () -> Unit,
    onLoggedIn: () -> Unit,
    onForgotPassword: () -> Unit,
    onSignup: () -> Unit,
    onTitleLongPress: (() -> Unit)? = null,
) {
    val ui by vm.ui.collectAsState()
    val form = ui.form
    val snackbar = remember { OrmoSnackbarState() }
    var showDomainSheet by remember { mutableStateOf(false) }

    val locked = form.lockedUntilMs != null
    val now = rememberNowMs(active = locked)

    LaunchedEffect(Unit) {
        vm.eventFlow.collect { e ->
            when (e) {
                LoginEvent.LoggedIn -> onLoggedIn()
                is LoginEvent.Snackbar -> snackbar.show(e.message, SnackbarDurations.PARTIAL_FAILURE, kind = com.ormo.customer.ui.components.SnackbarKind.Error, atTop = true)
            }
        }
    }
    LaunchedEffect(notice) {
        when (notice) {
            AuthNotice.PASSWORD_CHANGED -> snackbar.show("비밀번호를 변경했어요", SnackbarDurations.PASSWORD_CHANGED)
            AuthNotice.WITHDRAWN -> snackbar.show("탈퇴가 완료됐어요. 그동안 ORMO를 이용해 주셔서 감사해요", 3_000)
            else -> Unit
        }
        if (notice != null) onNoticeShown()
    }

    val lockLabel = form.lockCountdownLabel(now)
    val canSubmit = form.canSubmit(now)
    // Figma 0.1.2: 잠긴 동안에도 두 칸이 빨갛고 오류 문구가 남는다
    val invalid = form.message == LoginMessage.INVALID_CREDENTIALS || lockLabel != null
    val domains = ui.domains

    FormScreen(
        firstStep = true,
        bottomPadding = 24.dp,
        title = "학교 이메일로 \n로그인해주세요",
        onBack = null,
        snackbar = snackbar,
        scrollToEndKey = form.message,
        bottom = {
            OrmoButton(
                text = if (lockLabel != null) "$lockLabel 후 로그인" else "로그인",
                onClick = vm::submit,
                modifier = Modifier.fillMaxWidth(),
                size = ButtonSize.XL,
                enabled = canSubmit || form.submitting,
                loading = form.submitting,
            )
            // 하단 보조 행동 (Ghost 텍스트): '비밀번호 찾기 | 회원가입'. 잠겼으면 [비밀번호 재설정하기] (0.1.2)
            // Figma: 글자 사이 간격이 좁은 한 줄 (버튼 여백 없이), 눌리는 영역은 높이 48
            Row(Modifier.fillMaxWidth().offset(y = (-4).dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                LinkText(if (lockLabel != null) "비밀번호 재설정하기" else "비밀번호 찾기", onForgotPassword)
                Text("|", style = OrmoType.bodyMdMedium, color = Ormo.colors.textTertiary)
                LinkText("회원가입", onSignup)
            }
        },
    ) {
        if (ui.domainsFailed) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s12)) {
                Text("학교 메일 정보를 불러오지 못했어요", style = OrmoType.bodyMdMedium, color = Ormo.colors.textDanger)
                OrmoButton("다시 시도", vm::loadDomains, kind = ButtonKind.WeakNeutral, size = ButtonSize.M)
            }
        } else {
            OrmoField(
                value = form.emailId,
                onValueChange = vm::onEmailChanged,
                label = "이메일",
                suffix = form.domain?.let { "@$it" },
                trailing = if (domains?.showsPicker == true) {
                    { Icon(OrmoIcons.ChevronDown, contentDescription = "학교 메일 선택", tint = Ormo.colors.iconSecondary, modifier = Modifier.size(Size.iconLg)) }
                } else {
                    null
                },
                onSuffixClick = if (domains?.showsPicker == true) ({ showDomainSheet = true }) else null,
                invalid = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            OrmoPasswordField(
                value = form.password,
                onValueChange = vm::onPasswordChanged,
                label = "비밀번호",
                placeholder = "비밀번호를 입력해주세요",
                error = if (invalid) "이메일이나 비밀번호를 다시 확인해주세요" else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.submit() }),
            )
        }
    }

    if (showDomainSheet && domains != null) {
        OrmoBottomSheet(
            onDismissRequest = { showDomainSheet = false },
            title = "학교 메일 주소를 선택해주세요",
            titleBottomPadding = 0.dp,
        ) {
            Text("학교에서 발급한 메일 주소만 사용할 수 있어요", style = OrmoType.bodyMdRegular, color = Ormo.colors.textSecondary)
            Column(Modifier.padding(top = 31.dp, bottom = Space.s16)) { // Figma: 안내 글자 아래 행 시작, 행 높이 48
                domains.items.forEach { d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = d.domain == form.domain, role = Role.RadioButton) {
                                vm.onDomainSelected(d.domain)
                                showDomainSheet = false
                            }
                            .padding(vertical = Space.s12),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("@${d.domain}", style = OrmoType.bodyMdMedium, color = Ormo.colors.textPrimary, modifier = Modifier.weight(1f))
                        if (d.domain == form.domain) Icon(OrmoIcons.Check, contentDescription = null, tint = Ormo.colors.iconBrand, modifier = Modifier.size(Size.iconLg))
                    }
                }
            }
        }
    }

    if (ui.showRestricted) {
        OrmoDialog(
            title = "이용이 제한된 계정이에요",
            body = "궁금한 점은 고객센터로 문의해주세요",
            confirmLabel = "문의하기",
            // TODO(spec 0.1.3): 문의 채널(카카오톡 채널, 메일) 미정 — 정해지면 여기서 연결. 지금은 닫기만 한다.
            onConfirm = vm::dismissRestricted,
            dismissLabel = "닫기",
            onDismiss = vm::dismissRestricted,
        )
    }
}

/** 로그인 아래 작은 링크 글자 (Figma: 14 Medium, 버튼 여백 없음) */
@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = OrmoType.bodyMdMedium, color = Ormo.colors.textSecondary)
    }
}
