package com.ormo.customer.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ormo.core.Outcome
import com.ormo.customer.AppContainer
import com.ormo.data.auth.AuthRepository
import com.ormo.data.auth.SchoolDirectoryApi
import com.ormo.domain.auth.EmailDomains
import com.ormo.domain.auth.PasswordFailure
import com.ormo.domain.auth.PasswordFormState
import com.ormo.domain.auth.PasswordStage
import com.ormo.domain.auth.ResetState
import com.ormo.domain.auth.ResetStep
import com.ormo.domain.auth.SendCodeFailure
import com.ormo.domain.auth.VerifyPurpose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResetUi(
    val s: ResetState = ResetState(),
    val domains: EmailDomains? = null,
    val domainsFailed: Boolean = false,
    val sending: Boolean = false,
    val emailProblem: EmailProblem? = null,
    val password: PasswordFormState = PasswordFormState(),
    val submitting: Boolean = false,
)

sealed interface ResetEvent {
    data class Snackbar(val message: String) : ResetEvent
}

/**
 * 비밀번호 재설정 (디자인 0.3): 이메일 → 인증코드(0.2.2 와 같은 화면) → 새 비밀번호 → 완료.
 * **미가입 이메일이어도 같은 안내**를 보여준다 (가입 여부를 알려주지 않음).
 */
class ResetViewModel(
    private val auth: AuthRepository,
    private val schools: SchoolDirectoryApi,
) : ViewModel() {
    private val _ui = MutableStateFlow(ResetUi())
    val ui: StateFlow<ResetUi> = _ui.asStateFlow()

    private val events = Channel<ResetEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val verification = VerificationController(
        scope = viewModelScope,
        auth = auth,
        purpose = VerifyPurpose.PASSWORD_RESET,
        onVerified = { token -> _ui.update { it.copy(s = it.s.codeVerified(token)) } },
    )

    init { loadDomains() }

    fun loadDomains() {
        viewModelScope.launch {
            _ui.update { it.copy(domainsFailed = false) }
            try {
                val d = schools.fetchEmailDomains()
                _ui.update { ui -> ui.copy(domains = d, s = if (ui.s.domain == null) ui.s.onEmailChanged(ui.s.emailId, d.default?.domain) else ui.s) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(domainsFailed = true) }
            }
        }
    }

    fun onEmailChanged(raw: String) = _ui.update { it.copy(s = it.s.onEmailChanged(raw, it.s.domain), emailProblem = null) }
    fun onDomainSelected(domain: String) = _ui.update { it.copy(s = it.s.onEmailChanged(it.s.emailId, domain), emailProblem = null) }

    fun requestCode() {
        val cur = _ui.value
        if (!cur.s.canRequestCode || cur.sending) return
        val email = cur.s.email ?: return
        _ui.update { it.copy(sending = true, emailProblem = null) }
        viewModelScope.launch {
            when (val r = auth.sendEmailCode(email, VerifyPurpose.PASSWORD_RESET)) {
                is Outcome.Ok -> {
                    verification.started(r.value)
                    _ui.update { it.copy(s = it.s.codeSent()) }
                }
                is Outcome.Fail -> when (r.failure) {
                    SendCodeFailure.DailyLimit -> _ui.update { it.copy(emailProblem = EmailProblem.DAILY_LIMIT) }
                    is SendCodeFailure.TooSoon -> _ui.update { it.copy(emailProblem = EmailProblem.TOO_SOON) }
                    SendCodeFailure.Network -> events.send(ResetEvent.Snackbar("인터넷 연결을 확인해주세요"))
                    // 가입 여부를 알려주지 않으므로 '이미 가입' 같은 응답이 와도 같은 안내(= 코드 화면)로 보낸다
                    SendCodeFailure.AlreadyRegistered -> {
                        verification.started(com.ormo.domain.auth.SendCodeResult(300, 60))
                        _ui.update { it.copy(s = it.s.codeSent()) }
                    }
                    SendCodeFailure.Other -> events.send(ResetEvent.Snackbar("잠시 후 다시 시도해주세요"))
                }
            }
            _ui.update { it.copy(sending = false) }
        }
    }

    fun onCodeChanged(raw: String) {
        val email = _ui.value.s.email ?: return
        verification.onCodeChanged(raw, email)
    }

    fun resendCode() {
        val email = _ui.value.s.email ?: return
        verification.resend(email)
    }

    fun onPasswordChanged(v: String) = _ui.update { it.copy(password = it.password.onPasswordChanged(v)) }
    fun onConfirmChanged(v: String) = _ui.update { it.copy(password = it.password.onConfirmChanged(v)) }
    fun advancePassword() = _ui.update { it.copy(password = it.password.advance()) }

    fun submit() {
        val cur = _ui.value
        if (cur.submitting || !cur.password.canSubmit) return
        val email = cur.s.email ?: return
        val token = cur.s.verificationToken ?: return
        _ui.update { it.copy(submitting = true) }
        viewModelScope.launch {
            when (val r = auth.resetPassword(email, token, cur.password.password)) {
                is Outcome.Ok -> _ui.update { it.copy(s = it.s.succeeded(), submitting = false, password = PasswordFormState()) }
                is Outcome.Fail -> {
                    when (r.failure) {
                        PasswordFailure.VERIFICATION_INVALID -> {
                            verification.reset()
                            _ui.update { it.copy(s = it.s.verificationExpired(), submitting = false, password = PasswordFormState()) }
                            events.send(ResetEvent.Snackbar("인증이 만료됐어요. 다시 인증해주세요"))
                        }
                        PasswordFailure.NETWORK -> { _ui.update { it.copy(submitting = false) }; events.send(ResetEvent.Snackbar("인터넷 연결을 확인해주세요")) }
                        PasswordFailure.OTHER, PasswordFailure.WRONG_CURRENT -> { _ui.update { it.copy(submitting = false) }; events.send(ResetEvent.Snackbar("잠시 후 다시 시도해주세요")) }
                        // 현재 비밀번호와 같음 / 규칙 위반: 확인 칸 아래에 문구 (입력을 바꾸면 사라진다)
                        PasswordFailure.SAME_AS_OLD, PasswordFailure.INVALID_FORMAT ->
                            _ui.update { it.copy(submitting = false, password = it.password.onServerFailure(r.failure)) }
                    }
                }
            }
        }
    }

    /** 완료 화면의 [로그인하기]: 로그인 화면에서 이메일을 채워 준다 */
    fun emailForLogin(): String? = _ui.value.s.email

    fun back(): Boolean {
        val cur = _ui.value
        return when (cur.s.step) {
            ResetStep.EMAIL -> false
            ResetStep.DONE -> false
            ResetStep.PASSWORD -> {
                if (cur.password.stage == PasswordStage.CONFIRM) {
                    _ui.update { it.copy(password = it.password.copy(stage = PasswordStage.PASSWORD, confirm = "")) }
                } else {
                    _ui.update { it.copy(s = it.s.back(), password = PasswordFormState()) }
                }
                true
            }
            ResetStep.CODE -> {
                verification.reset()
                _ui.update { it.copy(s = it.s.back()) }
                true
            }
        }
    }

    companion object {
        fun factory(c: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ResetViewModel(c.auth, c.schools) as T
        }
    }
}
