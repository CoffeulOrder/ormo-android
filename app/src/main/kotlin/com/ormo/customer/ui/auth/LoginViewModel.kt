package com.ormo.customer.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ormo.core.Outcome
import com.ormo.customer.AppContainer
import com.ormo.data.auth.AuthRepository
import com.ormo.data.auth.SchoolDirectoryApi
import com.ormo.domain.auth.EmailDomains
import com.ormo.domain.auth.LoginFormState
import com.ormo.domain.auth.LoginMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUi(
    val form: LoginFormState = LoginFormState(),
    /** null = 아직 못 받음 (로딩 중이거나 실패) */
    val domains: EmailDomains? = null,
    val domainsFailed: Boolean = false,
    val showRestricted: Boolean = false,
)

sealed interface LoginEvent {
    data object LoggedIn : LoginEvent
    data class Snackbar(val message: String) : LoginEvent
}

class LoginViewModel(
    private val auth: AuthRepository,
    private val schools: SchoolDirectoryApi,
    prefillEmail: String?,
    private val onSignedIn: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _ui = MutableStateFlow(LoginUi())
    val ui: StateFlow<LoginUi> = _ui.asStateFlow()

    private val events = Channel<LoginEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var pendingPrefill: String? = prefillEmail

    init { loadDomains() }

    /** 학교 메일 도메인 목록은 서버가 준다 (D-23). 실패하면 로그인할 수 없으므로 다시 시도할 수 있게 한다. */
    fun loadDomains() {
        viewModelScope.launch {
            _ui.update { it.copy(domainsFailed = false) }
            try {
                val d = schools.fetchEmailDomains()
                _ui.update { ui ->
                    var form = ui.form
                    pendingPrefill?.let { form = form.onEmailPasted(it, d.domains) }
                    pendingPrefill = null
                    if (form.domain == null) form = form.onDomainSelected(d.default?.domain ?: "")
                    ui.copy(domains = d, form = form)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(domainsFailed = true) }
            }
        }
    }

    fun onEmailChanged(raw: String) {
        val domains = _ui.value.domains?.domains.orEmpty()
        if ('@' !in raw && raw == _ui.value.form.emailId) return
        _ui.update {
            // 이메일 전체를 붙여 넣으면(@ 포함) 아이디와 도메인을 나눈다
            it.copy(form = if ('@' in raw) it.form.onEmailPasted(raw, domains) else it.form.onEmailChanged(raw))
        }
    }

    fun onDomainSelected(domain: String) = _ui.update { it.copy(form = it.form.onDomainSelected(domain)) }

    fun onPasswordChanged(raw: String) {
        // 값이 그대로면 무시 (키보드가 같은 값을 다시 알려 줄 때 방금 뜬 오류 문구가 지워지지 않게)
        if (raw == _ui.value.form.password) return
        _ui.update { it.copy(form = it.form.onPasswordChanged(raw)) }
    }

    fun dismissRestricted() = _ui.update { it.copy(showRestricted = false) }

    fun submit() {
        val now = clock()
        val cur = _ui.value
        if (!cur.form.canSubmit(now)) return // 중복 탭은 요청 1회
        val email = cur.form.email ?: return
        _ui.update { it.copy(form = it.form.onSubmitStarted()) }
        viewModelScope.launch {
            val schoolId = cur.domains?.schoolIdOf(cur.form.domain)
            when (val r = auth.signIn(email, cur.form.password, schoolId)) {
                is Outcome.Ok -> {
                    onSignedIn(email)
                    _ui.update { it.copy(form = it.form.copy(submitting = false, password = "")) }
                    events.send(LoginEvent.LoggedIn)
                }
                is Outcome.Fail -> {
                    val after = clock()
                    _ui.update { it.copy(form = it.form.onFailure(r.failure, after)) }
                    when (_ui.value.form.message) {
                        LoginMessage.LOCKED -> events.send(LoginEvent.Snackbar("비밀번호 입력 횟수를 초과했어요"))
                        LoginMessage.RESTRICTED -> _ui.update { it.copy(showRestricted = true) }
                        LoginMessage.NETWORK -> events.send(LoginEvent.Snackbar("인터넷 연결을 확인해주세요"))
                        LoginMessage.OTHER -> events.send(LoginEvent.Snackbar("잠시 후 다시 시도해주세요"))
                        else -> Unit
                    }
                }
            }
        }
    }

    companion object {
        fun factory(c: AppContainer, prefillEmail: String?) = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LoginViewModel(c.auth, c.schools, prefillEmail, onSignedIn = c::onSignedIn) as T
        }
    }
}
