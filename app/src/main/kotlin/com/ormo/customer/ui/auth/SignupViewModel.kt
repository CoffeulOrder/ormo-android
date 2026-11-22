package com.ormo.customer.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ormo.core.Outcome
import com.ormo.customer.AppContainer
import com.ormo.data.auth.AuthRepository
import com.ormo.data.auth.SchoolDirectoryApi
import com.ormo.domain.auth.ConsentId
import com.ormo.domain.auth.ConsentState
import com.ormo.domain.auth.EmailDomains
import com.ormo.domain.auth.PasswordFormState
import com.ormo.domain.auth.PasswordStage
import com.ormo.domain.auth.SendCodeFailure
import com.ormo.domain.auth.SignupNotice
import com.ormo.domain.auth.SignupRejection
import com.ormo.domain.auth.SignupState
import com.ormo.domain.auth.SignupStep
import com.ormo.domain.auth.VerifyPurpose
import com.ormo.domain.rules.NameRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 이메일 단계에서 칸 아래에 보여줄 안내 (서버 응답 때문에 생김) */
enum class EmailProblem { DAILY_LIMIT, TOO_SOON }

data class SignupUi(
    val s: SignupState = SignupState(),
    val domains: EmailDomains? = null,
    val domainsFailed: Boolean = false,
    /** 이메일 단계 [인증코드 받기] 요청 중 */
    val sending: Boolean = false,
    val emailProblem: EmailProblem? = null,
    val nameInput: String = "",
    val password: PasswordFormState = PasswordFormState(),
    val consent: ConsentState = ConsentState(),
    val submitting: Boolean = false,
) {
    val nameError: NameRules.Error?
        get() = NameRules.validate(nameInput).takeIf { it == NameRules.Error.INVALID_CHARS || it == NameRules.Error.TOO_LONG }
    val canConfirmName: Boolean get() = NameRules.validate(nameInput) == null
}

sealed interface SignupEvent {
    data class Snackbar(val message: String) : SignupEvent

    /** 완료 화면(0.2.6) 모션이 끝났다 → 매장 선택으로. loggedIn=false 면 가입은 됐지만 자동 로그인이 안 됨 → 로그인 화면 */
    data class Finished(val loggedIn: Boolean) : SignupEvent
}

/**
 * 회원가입 (디자인 0.2): 한 화면에 하나씩 이메일 → 코드 → 이름 → 비밀번호 → 약관 → 완료.
 * 순수 상태는 `:domain` 의 [SignupState]/[PasswordFormState]/[ConsentState], 코드 화면은 [VerificationController].
 */
class SignupViewModel(
    private val auth: AuthRepository,
    private val schools: SchoolDirectoryApi,
) : ViewModel() {
    private val _ui = MutableStateFlow(SignupUi())
    val ui: StateFlow<SignupUi> = _ui.asStateFlow()

    private val events = Channel<SignupEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val verification = VerificationController(
        scope = viewModelScope,
        auth = auth,
        purpose = VerifyPurpose.SIGNUP,
        onVerified = { token -> _ui.update { it.copy(s = it.s.codeVerified(token)) } },
    )

    private var loggedInAfterSignup = true

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

    // ---- 이메일 ----
    fun onEmailChanged(raw: String) = _ui.update { it.copy(s = it.s.onEmailChanged(raw, it.s.domain), emailProblem = null) }
    fun onDomainSelected(domain: String) = _ui.update { it.copy(s = it.s.onEmailChanged(it.s.emailId, domain), emailProblem = null) }

    fun requestCode() {
        val cur = _ui.value
        if (!cur.s.canRequestCode || cur.sending) return
        val email = cur.s.email ?: return
        _ui.update { it.copy(sending = true, emailProblem = null) }
        viewModelScope.launch {
            when (val r = auth.sendEmailCode(email, VerifyPurpose.SIGNUP)) {
                is Outcome.Ok -> {
                    verification.started(r.value)
                    _ui.update { it.copy(s = it.s.codeSent(), sending = false) }
                }
                is Outcome.Fail -> {
                    when (r.failure) {
                        SendCodeFailure.AlreadyRegistered -> _ui.update { it.copy(s = it.s.rejected(SignupRejection.EMAIL_TAKEN)) }
                        SendCodeFailure.DailyLimit -> _ui.update { it.copy(emailProblem = EmailProblem.DAILY_LIMIT) }
                        is SendCodeFailure.TooSoon -> _ui.update { it.copy(emailProblem = EmailProblem.TOO_SOON) }
                        SendCodeFailure.Network -> events.send(SignupEvent.Snackbar("인터넷 연결을 확인해주세요"))
                        SendCodeFailure.Other -> events.send(SignupEvent.Snackbar("잠시 후 다시 시도해주세요"))
                    }
                    _ui.update { it.copy(sending = false) }
                }
            }
        }
    }

    /** "이미 가입된 이메일이에요" 의 [로그인하기]: 로그인 화면에서 이메일을 채워 준다 */
    fun emailForLogin(): String? = _ui.value.s.email

    // ---- 인증코드 ----
    fun onCodeChanged(raw: String) {
        val email = _ui.value.s.email ?: return
        verification.onCodeChanged(raw, email)
    }

    fun resendCode() {
        val email = _ui.value.s.email ?: return
        verification.resend(email)
    }

    // ---- 이름 ----
    fun onNameChanged(raw: String) = _ui.update { it.copy(nameInput = raw) }

    fun confirmName() = _ui.update { if (it.canConfirmName) it.copy(s = it.s.nameConfirmed(it.nameInput)) else it }

    // ---- 비밀번호 ----
    fun onPasswordChanged(v: String) = _ui.update { it.copy(password = it.password.onPasswordChanged(v)) }
    fun onConfirmChanged(v: String) = _ui.update { it.copy(password = it.password.onConfirmChanged(v)) }
    fun advancePassword() = _ui.update { it.copy(password = it.password.advance()) }

    fun confirmPassword() = _ui.update {
        if (it.password.canSubmit) it.copy(s = it.s.passwordConfirmed(it.password.password)) else it
    }

    // ---- 약관 ----
    fun toggleAllConsent() = _ui.update { it.copy(consent = it.consent.toggleAll()) }
    fun toggleConsent(id: ConsentId) = _ui.update { it.copy(consent = it.consent.toggle(id)) }

    /** 시트를 아래로 밀어 닫으면 비밀번호 화면으로 돌아간다 */
    fun dismissConsent() = _ui.update { if (it.s.step == SignupStep.CONSENT) it.copy(s = it.s.back()) else it }

    fun submitSignup() {
        val cur = _ui.value
        if (cur.submitting) return
        val req = cur.s.toRequest(cur.consent) ?: return
        _ui.update { it.copy(submitting = true) }
        viewModelScope.launch {
            val schoolId = cur.domains?.schoolIdOf(cur.s.domain)
            when (val r = auth.signUp(req, schoolId)) {
                is Outcome.Ok -> {
                    loggedInAfterSignup = r.value
                    _ui.update { it.copy(s = it.s.succeeded(), submitting = false, password = PasswordFormState()) }
                    delay(DONE_MOTION_MS) // 완료 모션 2.8초 뒤 이동 (0.2.6)
                    events.send(SignupEvent.Finished(loggedInAfterSignup))
                }
                is Outcome.Fail -> {
                    val reason = r.failure
                    _ui.update { ui ->
                        val next = ui.s.rejected(reason)
                        // 인증부터 다시 해야 하면 입력해 둔 비밀번호·코드 화면 상태도 비운다
                        val restart = next.step == SignupStep.EMAIL
                        if (restart) verification.reset()
                        ui.copy(
                            s = next,
                            submitting = false,
                            password = if (restart || reason == SignupRejection.PASSWORD_INVALID) PasswordFormState() else ui.password,
                            consent = if (restart) ConsentState() else ui.consent,
                        )
                    }
                    when (reason) {
                        SignupRejection.NETWORK -> events.send(SignupEvent.Snackbar("인터넷 연결을 확인해주세요"))
                        SignupRejection.OTHER, SignupRejection.CONSENT_REQUIRED -> events.send(SignupEvent.Snackbar("잠시 후 다시 시도해주세요"))
                        SignupRejection.PASSWORD_INVALID -> events.send(SignupEvent.Snackbar("비밀번호 규칙을 다시 확인해주세요"))
                        SignupRejection.VERIFICATION_INVALID -> events.send(SignupEvent.Snackbar("인증이 만료됐어요. 다시 인증해주세요"))
                        SignupRejection.EMAIL_TAKEN -> Unit // 이메일 단계에 안내가 뜬다
                    }
                }
            }
        }
    }

    // ---- 뒤로가기 ----
    /** 처리했으면 true. false 면 화면을 닫는다 (이메일 단계). 완료 화면에서는 뒤로가기를 무시한다. */
    fun back(): Boolean {
        val cur = _ui.value
        return when (cur.s.step) {
            SignupStep.EMAIL -> false
            SignupStep.DONE -> true
            SignupStep.PASSWORD -> {
                if (cur.password.stage == PasswordStage.CONFIRM) {
                    _ui.update { it.copy(password = it.password.copy(stage = PasswordStage.PASSWORD, confirm = "")) }
                } else {
                    _ui.update { it.copy(s = it.s.back()) }
                }
                true
            }
            SignupStep.CODE -> {
                verification.reset()
                _ui.update { it.copy(s = it.s.back()) }
                true
            }
            else -> { _ui.update { it.copy(s = it.s.back()) }; true }
        }
    }

    val takenNotice: Boolean get() = _ui.value.s.notice == SignupNotice.EMAIL_TAKEN

    companion object {
        const val DONE_MOTION_MS = 2_800L

        fun factory(c: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SignupViewModel(c.auth, c.schools) as T
        }
    }
}
