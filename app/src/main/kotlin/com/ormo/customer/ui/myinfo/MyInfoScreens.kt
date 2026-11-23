package com.ormo.customer.ui.myinfo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.ormo.core.Outcome
import com.ormo.customer.AppContainer
import com.ormo.customer.ui.components.BottomCta
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.FormScreen
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoDialog
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.OrmoPasswordField
import com.ormo.customer.ui.components.OrmoSnackbarHost
import com.ormo.customer.ui.components.OrmoSnackbarState
import com.ormo.customer.ui.components.OrmoSwitch
import com.ormo.customer.ui.components.OrmoTopNav
import com.ormo.customer.ui.components.PasswordRuleRow
import com.ormo.customer.ui.components.SnackbarDurations
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space
import com.ormo.domain.auth.ChangePasswordFormState
import com.ormo.domain.auth.PasswordFailure
import com.ormo.domain.profile.MyInfoRow
import com.ormo.domain.profile.MyInfoRules
import com.ormo.domain.profile.NotificationSwitch
import com.ormo.domain.profile.WithdrawFailure
import com.ormo.domain.profile.WithdrawFormState
import com.ormo.domain.profile.WithdrawGate
import com.ormo.domain.profile.WithdrawRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** OS 알림이 켜져 있는가 (Android 13+ 에서는 POST_NOTIFICATIONS 허용 여부) */
fun osNotificationsGranted(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** 화면이 다시 보일 때마다(설정 앱에서 돌아올 때 포함) [block] 을 부른다 */
@Composable
private fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) block() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

// ───────────── 5.1.1 내 정보 ─────────────

class MyInfoViewModel(private val c: AppContainer) : ViewModel() {
    val profile = c.auth.profile
    private val _school = MutableStateFlow<String?>(null)
    val school: StateFlow<String?> = _school

    private val _gate = MutableStateFlow<WithdrawGate?>(null)
    val gate: StateFlow<WithdrawGate?> = _gate

    init {
        viewModelScope.launch { c.auth.refreshProfile() }
        viewModelScope.launch {
            _school.value = (c.stores.loadStores() as? Outcome.Ok)?.value?.all?.firstNotNullOfOrNull { it.schoolName }
        }
    }

    fun logout() { c.appScope.launch { c.auth.signOut() } }

    /** 탈퇴 전에 진행 중 주문을 확인한다 (5.3.2). 확인을 못 하면 막지 않고 진행 (서버가 최종 판단). */
    fun checkWithdraw(onAllowed: () -> Unit) {
        viewModelScope.launch {
            c.orders.refreshActive()
            when (val g = WithdrawRules.gate(c.orders.active.value.size)) {
                WithdrawGate.Allowed -> onAllowed()
                is WithdrawGate.Blocked -> _gate.value = g
            }
        }
    }

    fun dismissGate() { _gate.value = null }

    companion object {
        fun factory(c: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MyInfoViewModel(c) as T
        }
    }
}

/** 내 정보 (디자인 5.1.1 / Figma): 이름·이메일·학교 카드, 행 6개(주문 내역 · 비밀번호 바꾸기 · 알림 설정 · 약관 및 정책 · 로그아웃 · 탈퇴하기) */
@Composable
fun MyInfoScreen(
    vm: MyInfoViewModel,
    onBack: () -> Unit,
    onOrders: () -> Unit,
    onPassword: () -> Unit,
    onNotifications: () -> Unit,
    onLegal: () -> Unit,
    onWithdraw: () -> Unit,
    onCatalog: (() -> Unit)? = null,
) {
    val c = Ormo.colors
    val context = LocalContext.current
    val profile by vm.profile.collectAsState()
    val school by vm.school.collectAsState()
    val gate by vm.gate.collectAsState()
    var osGranted by remember { mutableStateOf(osNotificationsGranted(context)) }
    OnResume { osGranted = osNotificationsGranted(context) }
    var confirmLogout by remember { mutableStateOf(false) }
    val view = MyInfoRules.view(profile?.name, profile?.email, osGranted)

    Column(Modifier.fillMaxSize().background(c.bgDefault)) {
        OrmoTopNav(title = "내 정보", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(
                Modifier.padding(horizontal = Space.pageMargin, vertical = Space.s8).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.bgLower).padding(Space.s20),
                verticalArrangement = Arrangement.spacedBy(Space.s4),
            ) {
                Text(view.name.ifEmpty { " " }, style = OrmoType.bodyXlSemibold, color = c.textPrimary)
                view.email?.let { Text(it, style = OrmoType.bodyMdRegular, color = c.textSecondary) }
                (profile?.schoolName ?: school)?.let { Text(it, style = OrmoType.bodySmRegular, color = c.textSecondary) }
            }
            Column(Modifier.padding(top = Space.s12)) {
                view.rows.forEach { row ->
                    when (row) {
                        MyInfoRow.ORDER_HISTORY -> MyInfoItem("주문 내역", onClick = onOrders)
                        MyInfoRow.CHANGE_PASSWORD -> MyInfoItem("비밀번호 바꾸기", onClick = onPassword)
                        MyInfoRow.NOTIFICATIONS -> MyInfoItem("알림 설정", description = view.notificationRow.description, onClick = onNotifications)
                        MyInfoRow.TERMS_AND_POLICY -> MyInfoItem("약관 및 정책", onClick = onLegal)
                        MyInfoRow.LOGOUT -> MyInfoItem("로그아웃", onClick = { confirmLogout = true })
                        MyInfoRow.WITHDRAW -> MyInfoItem("탈퇴하기", muted = true, onClick = { vm.checkWithdraw(onWithdraw) })
                    }
                }
                if (onCatalog != null) MyInfoItem("컴포넌트 카탈로그 (개발용)", muted = true, onClick = onCatalog)
            }
        }
    }

    if (confirmLogout) {
        OrmoDialog(
            title = MyInfoRules.LOGOUT_CONFIRM.title,
            body = MyInfoRules.LOGOUT_CONFIRM.body,
            confirmLabel = "로그아웃",
            onConfirm = { confirmLogout = false; vm.logout() },
            dismissLabel = "취소",
            onDismiss = { confirmLogout = false },
        )
    }
    // 5.3.2 탈퇴 불가: 진행 중 주문이 있다
    (gate as? WithdrawGate.Blocked)?.let { g ->
        OrmoDialog(
            title = g.title,
            body = g.body,
            confirmLabel = "주문 보기",
            onConfirm = { vm.dismissGate(); onOrders() },
            dismissLabel = "닫기",
            onDismiss = vm::dismissGate,
        )
    }
}

@Composable
private fun MyInfoItem(label: String, onClick: () -> Unit, description: String? = null, muted: Boolean = false) {
    val c = Ormo.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.s24, vertical = Space.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            Text(label, style = OrmoType.bodyMdMedium, color = if (muted) c.textTertiary else c.textPrimary)
            description?.let { Text(it, style = OrmoType.bodySmRegular, color = c.textSecondary) }
        }
        Icon(OrmoIcons.ChevronRight, null, tint = c.iconTertiary, modifier = Modifier.size(24.dp))
    }
}

// ───────────── 5.1.3 약관 및 정책 ─────────────

/** 약관 및 정책 (디자인 5.1.3): 서비스 이용약관 / 개인정보 처리방침 — 개인정보 처리방침은 앱 안에서 항상 열 수 있어야 한다 (앱스토어 심사). */
@Composable
fun LegalListScreen(onBack: () -> Unit) {
    val c = Ormo.colors
    var open by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(c.bgDefault)) {
        OrmoTopNav(title = "약관 및 정책", onBack = onBack)
        MyInfoItem("서비스 이용약관", onClick = { open = "서비스 이용약관" })
        MyInfoItem("개인정보 처리방침", onClick = { open = "개인정보 처리방침" })
    }
    // TODO(spec 5.1.3): 약관 전문은 운영 중인 웹 페이지 주소로 받아 앱 안에서 연다 — 주소 확정 전이라 안내만 띄운다 (0.2.5 와 같은 처리)
    open?.let { OrmoDialog(title = it, body = "약관 전문 페이지는 준비 중이에요", confirmLabel = "확인", onConfirm = { open = null }) }
}

// ───────────── 5.2.1 비밀번호 바꾸기 ─────────────

data class ChangePasswordUi(val form: ChangePasswordFormState = ChangePasswordFormState(), val submitting: Boolean = false)

class ChangePasswordViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(ChangePasswordUi())
    val ui: StateFlow<ChangePasswordUi> = _ui
    private val _messages = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val messages = kotlinx.coroutines.flow.flow { for (m in _messages) emit(m) }

    fun onCurrent(v: String) = _ui.update { it.copy(form = it.form.onCurrentChanged(v)) }
    fun onNew(v: String) = _ui.update { it.copy(form = it.form.onNewChanged(v)) }
    fun onConfirm(v: String) = _ui.update { it.copy(form = it.form.onConfirmChanged(v)) }

    /** [변경하기]: 성공하면 모든 기기가 로그아웃되고 로그인 화면에서 '비밀번호를 변경했어요' (AppNav 가 세션 종료를 보고 이동) */
    fun submit() {
        val s = _ui.value
        if (!s.form.canSubmit || s.submitting) return
        _ui.update { it.copy(submitting = true) }
        viewModelScope.launch {
            when (val r = c.auth.changePassword(s.form.current, s.form.new)) {
                is Outcome.Ok -> Unit
                is Outcome.Fail -> {
                    val f = r.failure
                    _ui.update {
                        it.copy(submitting = false, form = if (f == PasswordFailure.NETWORK || f == PasswordFailure.OTHER) it.form else it.form.onServerFailure(f))
                    }
                    when (f) {
                        PasswordFailure.NETWORK -> _messages.send("인터넷 연결을 확인해주세요")
                        PasswordFailure.OTHER -> _messages.send("잠시 후 다시 시도해주세요")
                        else -> Unit
                    }
                }
            }
        }
    }

    companion object {
        fun factory(c: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChangePasswordViewModel(c) as T
        }
    }
}

/**
 * 비밀번호 바꾸기 (디자인 5.2.1 / Figma): 새 비밀번호 + 규칙 두 개(한 줄) + 새 비밀번호 확인, [변경하기].
 * Figma 에는 '현재 비밀번호' 칸이 없지만 서버가 현재 비밀번호를 확인하므로 맨 위에 둔다 (TODO(spec 5.2.1): 확인 필요).
 */
@Composable
fun ChangePasswordScreen(vm: ChangePasswordViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val f = ui.form
    val snackbar = remember { OrmoSnackbarState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.show(it, SnackbarDurations.PARTIAL_FAILURE) } }
    FormScreen(
        firstStep = true,
        title = "비밀번호를 변경해주세요",
        onBack = onBack,
        snackbar = snackbar,
        bottom = { OrmoButton("변경하기", vm::submit, Modifier.fillMaxWidth(), size = ButtonSize.XL, enabled = f.canSubmit || ui.submitting, loading = ui.submitting) },
    ) {
        OrmoPasswordField(
            value = f.current,
            onValueChange = vm::onCurrent,
            label = "현재 비밀번호",
            error = if (f.serverFailure == PasswordFailure.WRONG_CURRENT) "현재 비밀번호가 맞지 않아요" else null,
            autoFocus = true,
        )
        OrmoPasswordField(
            value = f.new,
            onValueChange = vm::onNew,
            label = "새 비밀번호",
            error = when (f.serverFailure) {
                PasswordFailure.SAME_AS_OLD -> "기존 비밀번호와 다른 비밀번호를 입력해 주세요"
                PasswordFailure.INVALID_FORMAT -> "비밀번호 규칙을 다시 확인해주세요"
                else -> null
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
            PasswordRuleRow("8자 이상", f.check.longEnough)
            PasswordRuleRow("영문과 숫자 포함", f.check.hasLetterAndDigit)
        }
        OrmoPasswordField(
            value = f.confirm,
            onValueChange = vm::onConfirm,
            label = "새 비밀번호 확인",
            error = if (f.mismatch) "비밀번호가 일치하지 않아요" else null,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (f.canSubmit) vm.submit() }),
        )
    }
}

// ───────────── 5.3.1 탈퇴 ─────────────

class WithdrawViewModel(private val c: AppContainer) : ViewModel() {
    private val _form = MutableStateFlow(WithdrawFormState())
    val form: StateFlow<WithdrawFormState> = _form
    private val _blocked = MutableStateFlow<WithdrawGate.Blocked?>(null)
    val blocked: StateFlow<WithdrawGate.Blocked?> = _blocked

    fun onPassword(v: String) = _form.update { it.onPasswordChanged(v) }

    /** [탈퇴하기]: 성공하면 계정 데이터를 정리하고 로그인 화면에서 '탈퇴가 완료됐어요' (AppNav 가 세션 종료를 보고 이동) */
    fun submit() {
        val s = _form.value
        if (!s.canSubmit) return
        _form.update { it.onSubmitStarted() }
        viewModelScope.launch {
            when (val r = c.auth.withdraw(s.password)) {
                is Outcome.Ok -> Unit
                is Outcome.Fail -> {
                    _form.update { it.onFailure(r.failure) }
                    if (r.failure == WithdrawFailure.ACTIVE_ORDERS) {
                        c.orders.refreshActive()
                        _blocked.value = WithdrawGate.Blocked(c.orders.active.value.size.coerceAtLeast(1))
                    }
                }
            }
        }
    }

    fun dismissBlocked() { _blocked.value = null }

    companion object {
        fun factory(c: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WithdrawViewModel(c) as T
        }
    }
}

/** 탈퇴 (디자인 5.3.1 / Figma): '탈퇴하기 전에 / 확인해주세요', 비밀번호 칸, 안내 세 줄, 빨간 [탈퇴하기]. 진행 중 주문이 있으면 5.3.2 다이얼로그. */
@Composable
fun WithdrawScreen(vm: WithdrawViewModel, onBack: () -> Unit, onOrders: () -> Unit) {
    val c = Ormo.colors
    val form by vm.form.collectAsState()
    val blocked by vm.blocked.collectAsState()
    FormScreen(
        firstStep = true,
        title = "탈퇴하기 전에\n확인해주세요",
        onBack = onBack,
        bottom = { OrmoButton("탈퇴하기", vm::submit, Modifier.fillMaxWidth(), kind = ButtonKind.Critical, size = ButtonSize.XL, enabled = form.canSubmit || form.submitting, loading = form.submitting) },
    ) {
        OrmoPasswordField(
            value = form.password,
            onValueChange = vm::onPassword,
            label = "비밀번호",
            error = when (form.failure) {
                WithdrawFailure.WRONG_PASSWORD -> "비밀번호가 맞지 않아요"
                WithdrawFailure.NETWORK -> "인터넷 연결을 확인해주세요"
                WithdrawFailure.OTHER -> "잠시 후 다시 시도해주세요"
                else -> null
            },
            autoFocus = true,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Space.s12), modifier = Modifier.padding(top = Space.s8)) {
            listOf(
                "이름과 이메일은 바로 삭제돼요",
                "주문·결제 기록은 법에 따라 5년 동안 보관해요",
                "같은 이메일로 다시 가입할 수 있어요",
            ).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s8), verticalAlignment = Alignment.Top) {
                    Text("•", style = OrmoType.bodySmRegular, color = c.textTertiary)
                    Text(line, style = OrmoType.bodySmRegular, color = c.textTertiary)
                }
            }
        }
    }
    blocked?.let { g ->
        OrmoDialog(title = g.title, body = g.body, confirmLabel = "주문 보기", onConfirm = { vm.dismissBlocked(); onOrders() }, dismissLabel = "닫기", onDismiss = vm::dismissBlocked)
    }
}

// ───────────── 5.5.1 알림 설정 ─────────────

class NotificationSettingsViewModel(private val c: AppContainer) : ViewModel() {
    val state = c.notificationSettings.state

    init { viewModelScope.launch { c.notificationSettings.load() } }

    fun setOs(granted: Boolean) = c.notificationSettings.setOsPermission(granted)

    /** 누르는 즉시 저장. 실패하면 그 스위치만 되돌아가고 스낵바 */
    fun toggle(s: NotificationSwitch, on: Boolean) { viewModelScope.launch { c.notificationSettings.toggle(s, on) } }

    companion object {
        fun factory(c: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NotificationSettingsViewModel(c) as T
        }
    }
}

/**
 * 알림 설정 (디자인 5.5.1 / Figma): [주문 알림] [잠금 화면 실시간 현황] 스위치 — 누르는 즉시 저장, 저장 버튼 없음.
 * OS 알림이 꺼져 있으면 위에 주황 안내 상자(+ 설정 열기)를 보이고 스위치는 꺼진 채 비활성.
 */
@Composable
fun NotificationSettingsScreen(vm: NotificationSettingsViewModel, onBack: () -> Unit) {
    val c = Ormo.colors
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val snackbar = remember { OrmoSnackbarState() }
    OnResume { vm.setOs(osNotificationsGranted(context)) }
    LaunchedEffect(state.failed) {
        if (state.failed != null) snackbar.show("저장하지 못했어요. 다시 시도해주세요", SnackbarDurations.PARTIAL_FAILURE)
    }
    Box(Modifier.fillMaxSize().background(c.bgDefault)) {
        Column(Modifier.fillMaxSize()) {
            OrmoTopNav(title = "알림 설정", onBack = onBack)
            state.osOffNotice?.let { n ->
                Row(
                    Modifier.padding(horizontal = Space.pageMargin, vertical = Space.s8).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.fillWarningWeak)
                        .clickable(role = Role.Button, onClickLabel = "설정 열기") { openAppNotificationSettings(context) }.padding(Space.s16),
                    horizontalArrangement = Arrangement.spacedBy(Space.s8),
                ) {
                    Box(Modifier.padding(top = 2.dp).size(18.dp).clip(CircleShape).background(c.textWarning), contentAlignment = Alignment.Center) {
                        Text("!", style = OrmoType.bodyXsBold, color = c.textOnFill)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                        Text(n.title, style = OrmoType.bodySmSemibold, color = c.textWarning)
                        Text(n.body, style = OrmoType.bodySmRegular, color = c.textSecondary)
                    }
                }
            }
            SettingRow("주문 알림", "음료가 준비되거나\n주문 상태가 변경되면 알려드려요", state.isOn(NotificationSwitch.ORDER_PUSH), state.isEnabled(NotificationSwitch.ORDER_PUSH)) {
                vm.toggle(NotificationSwitch.ORDER_PUSH, it)
            }
            SettingRow("잠금 화면 실시간 현황", "주문 상태를 잠금 화면에서 확인할 수 있어요", state.isOn(NotificationSwitch.LIVE), state.isEnabled(NotificationSwitch.LIVE)) {
                vm.toggle(NotificationSwitch.LIVE, it)
            }
        }
        OrmoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter), bottomOffset = Space.s32)
    }
}

@Composable
private fun SettingRow(title: String, body: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = Ormo.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.s24, vertical = Space.s16), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            Text(title, style = OrmoType.bodyMdMedium, color = c.textPrimary)
            Text(body, style = OrmoType.bodySmRegular, color = c.textSecondary)
        }
        OrmoSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled, contentDescription = title)
    }
}
