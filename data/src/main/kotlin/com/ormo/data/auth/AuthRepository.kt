package com.ormo.data.auth

import com.ormo.core.Outcome
import com.ormo.core.SessionResetter
import com.ormo.data.local.PersistedSession
import com.ormo.data.local.SessionStore
import com.ormo.data.remote.AppError
import com.ormo.data.remote.SessionManager
import com.ormo.domain.auth.LoginFailure
import com.ormo.domain.auth.PasswordFailure
import com.ormo.domain.auth.SendCodeFailure
import com.ormo.domain.auth.SendCodeResult
import com.ormo.domain.auth.SignUpRequest
import com.ormo.domain.auth.SignupRejection
import com.ormo.domain.auth.UserProfile
import com.ormo.domain.profile.WithdrawFailure
import com.ormo.domain.auth.VerifyCodeFailure
import com.ormo.domain.auth.VerifyPurpose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * RESTORING: 앱을 켠 직후 저장된 세션(자동 로그인)을 확인하는 중 → 로딩 화면
 * OFFLINE: 저장된 세션은 있는데 네트워크 때문에 복구하지 못함 → 재시도 화면 (세션은 지우지 않는다)
 * SIGNED_OUT: 로그인 필요 (저장된 세션 없음/거절됨/로그아웃)
 * SIGNED_IN: 로그인됨
 */
enum class AuthStatus { RESTORING, OFFLINE, SIGNED_OUT, SIGNED_IN }

/** 로그인 화면이 한 번 보여주고 지우는 안내 */
enum class AuthNotice {
    /** 6.1.2 "다시 로그인해주세요 / 보안을 위해 로그아웃됐어요. 장바구니에 담긴 메뉴는 그대로 유지돼요." */
    SESSION_EXPIRED,

    /** 5.2.1 성공 토스트 "비밀번호를 변경했어요" (2초) */
    PASSWORD_CHANGED,

    /** 5.3.1 "탈퇴가 완료됐어요. 그동안 ORMO를 이용해 주셔서 감사해요" (로그인 화면에서 안내) */
    WITHDRAWN,
}

/**
 * 로그인 상태 / 자동 로그인 / 로그아웃. RN `use-auth.tsx` 의 동작 + 새 디자인 명세(0.x, 5.x).
 *
 * - 자동 로그인: 한 번 로그인하면 다음부터 자동 (체크박스 없음). 리프레시 토큰 + 학교 id 만 [SessionStore] 에 저장한다.
 *   비밀번호는 어디에도 저장하지 않는다. 서버가 토큰을 회전시키면(갱신 응답) 저장값도 같이 바꾼다.
 * - 복구(시작): 저장 세션 → `POST /auth/refresh`. 성공 → SIGNED_IN, **서버 거절(4xx)** → 저장 삭제 + SIGNED_OUT,
 *   **네트워크 오류·서버 장애** → 저장을 지우지 않고 OFFLINE.
 * - 로그아웃: 이 기기 토큰만 지우고 자동 로그인 해제. 서버 폐기 요청은 실패해도 진행. 개인 데이터는 [SessionResetter] 로 초기화
 *   (장바구니는 서버에 남으므로 로컬 상태만 비운다). 메뉴 캐시(공개 데이터)는 건드리지 않는다.
 * - 세션 만료(갱신 거절, [SessionManager.expired]): 같은 정리 + SESSION_EXPIRED 안내.
 * - 비밀번호 변경 성공: 서버가 모든 기기 세션을 폐기하므로 이 기기도 로그아웃 + PASSWORD_CHANGED 안내 (디자인 5.2.1).
 *
 * @param scope 만료 이벤트 수신·저장소 기록·서버 로그아웃 요청이 도는 앱 범위
 */
class AuthRepository(
    private val api: AuthApi,
    private val sessions: SessionManager,
    private val store: SessionStore,
    private val resetter: SessionResetter,
    private val scope: CoroutineScope,
    /** 탈퇴 직전에 호출: 계정별로 남겨 둔 기기 데이터(최근 검색어·마지막 매장·알림 안내 횟수 등)를 지운다. 인자 = 계정 키(이메일) */
    private val onWithdrawn: suspend (accountKey: String?) -> Unit = {},
) {
    private val _status = MutableStateFlow(AuthStatus.RESTORING)
    val status: StateFlow<AuthStatus> = _status

    private val _notice = MutableStateFlow<AuthNotice?>(null)
    val notice: StateFlow<AuthNotice?> = _notice

    /** 로그인한 계정의 학교 (메뉴·매장 데이터의 단위). 로그아웃하면 null */
    private val _schoolId = MutableStateFlow<String?>(null)
    val schoolId: StateFlow<String?> = _schoolId

    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile

    /** 현재 세션을 저장소에 두고 있는가 (= 자동 로그인) */
    @Volatile
    private var persist = false

    /** 저장소 쓰기는 한 번에 하나씩 (회전·삭제가 뒤섞여 낡은 토큰이 되살아나지 않게) */
    private val storeLock = Mutex()

    init {
        // 갱신 거절 → 세션 종료 (구독이 먼저 걸리도록 즉시 시작)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.expired.collect { endSession(expired = true) }
        }
        sessions.onRefreshTokenRotated = { rt ->
            val sid = _schoolId.value
            if (persist) scope.launch { saveSession(PersistedSession(sid, rt)) }
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    // ---- 시작 시 복구 ----

    suspend fun restore() {
        _status.value = AuthStatus.RESTORING
        val saved = try {
            store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // 저장소를 읽을 수 없으면 "저장된 세션 없음" — 다시 로그인하면 된다
        }
        if (saved == null) {
            _status.value = AuthStatus.SIGNED_OUT
            return
        }
        try {
            val res = api.refresh(saved.refreshToken)
            val rt = res.refreshToken ?: saved.refreshToken
            persist = true
            _schoolId.value = saved.schoolId
            sessions.set(res.accessToken, rt)
            if (res.refreshToken != null) saveSession(PersistedSession(saved.schoolId, rt))
            res.name?.let { _profile.value = UserProfile(it, null) }
            _status.value = AuthStatus.SIGNED_IN
            loadProfileInBackground()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.Api) {
            if (SessionManager.isRejectionStatus(e.status)) {
                clearSession() // 서버가 거절 (만료/폐기/비밀번호 변경됨) → 다시 로그인
                _status.value = AuthStatus.SIGNED_OUT
            } else {
                _status.value = AuthStatus.OFFLINE // 서버 장애: 세션은 지우지 않는다
            }
        } catch (e: Exception) {
            _status.value = AuthStatus.OFFLINE // 네트워크 오류 등: 오프라인이라고 저장된 세션을 지우지 않는다
        }
    }

    // ---- 로그인 / 가입 ----

    /** 로그인. 성공하면 SIGNED_IN. 자동 로그인이면 리프레시 토큰+학교 id 를 저장하고, 아니면 예전 저장분을 지운다. */
    suspend fun signIn(
        email: String,
        password: String,
        schoolId: String?,
        autoLogin: Boolean = true,
    ): Outcome<Unit, LoginFailure> {
        val session = try {
            api.login(email, password, schoolId, autoLogin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Fail(AuthErrors.login(e))
        }
        establish(session, schoolId, autoLogin)
        return Outcome.Ok(Unit)
    }

    /**
     * 가입. 성공하면 바로 로그인한다 (디자인 0.2.6: 가입 후 바로 로그인). 응답에 토큰이 없으면 같은 이메일·비밀번호로 로그인한다.
     * 결과 `Ok(true)` = 로그인까지 됨, `Ok(false)` = 가입은 됐지만 자동 로그인이 안 됨(로그인 화면으로).
     */
    suspend fun signUp(req: SignUpRequest, schoolId: String?): Outcome<Boolean, SignupRejection> {
        val fromSignup = try {
            api.signUp(req)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Fail(AuthErrors.signUp(e))
        }
        val session = fromSignup ?: try {
            api.login(req.email, req.password, schoolId, autoLogin = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Ok(false)
        }
        establish(session, schoolId, autoLogin = true)
        return Outcome.Ok(true)
    }

    private suspend fun establish(session: LoginSession, requestedSchoolId: String?, autoLogin: Boolean) {
        val sid = session.schoolId ?: requestedSchoolId
        sessions.set(session.accessToken, session.refreshToken)
        persist = autoLogin
        _schoolId.value = sid
        if (autoLogin) saveSession(PersistedSession(sid, session.refreshToken)) else clearSession() // 예전 저장분이 남지 않게
        _notice.value = null
        _profile.value = session.name?.let { UserProfile(it, null) }
        _status.value = AuthStatus.SIGNED_IN
        loadProfileInBackground()
    }

    // ---- 로그아웃 / 만료 ----

    suspend fun signOut() = endSession(expired = false)

    private suspend fun endSession(expired: Boolean, notice: AuthNotice? = null, revokeOnServer: Boolean = !expired) {
        val rt = sessions.refreshToken
        persist = false
        sessions.clear()
        clearSession()
        resetter.resetAll()
        _schoolId.value = null
        _profile.value = null
        _notice.value = notice ?: if (expired) AuthNotice.SESSION_EXPIRED else null
        _status.value = AuthStatus.SIGNED_OUT
        // 서버 폐기는 실패해도 로그아웃은 끝난 것이다 (만료로 끝난 세션의 토큰은 이미 거절된 것이라 보내지 않는다)
        if (rt != null && revokeOnServer) {
            scope.launch {
                try {
                    api.logout(rt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 무시
                }
            }
        }
    }

    // ---- 회원 탈퇴 ----

    /**
     * 비밀번호를 확인한 뒤 탈퇴 (5.3.1). 진행 중 주문이 있으면 화면에서 먼저 막고([com.ormo.domain.profile.WithdrawRules.gate]),
     * 그 사이 생긴 경우는 서버가 거절한다([WithdrawFailure.ACTIVE_ORDERS]). 성공하면 기기 데이터를 지우고 로그아웃 + [AuthNotice.WITHDRAWN].
     * 계정이 사라졌으므로 서버에 로그아웃 요청은 보내지 않는다.
     */
    suspend fun withdraw(password: String): Outcome<Unit, WithdrawFailure> {
        val accountKey = _profile.value?.email
        try {
            api.withdraw(password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Fail(AuthErrors.withdraw(e))
        }
        try {
            onWithdrawn(accountKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 기기 데이터 정리 실패가 탈퇴 완료를 막지 않는다
        }
        endSession(expired = false, notice = AuthNotice.WITHDRAWN, revokeOnServer = false)
        return Outcome.Ok(Unit)
    }

    // ---- 비밀번호 ----

    /** 비밀번호 변경 (현재 비밀번호 확인). 성공하면 모든 기기가 로그아웃되므로 이 기기도 로그인 화면으로. */
    suspend fun changePassword(current: String, new: String): Outcome<Unit, PasswordFailure> {
        try {
            api.changePassword(current, new)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Fail(AuthErrors.password(e))
        }
        endSession(expired = false, notice = AuthNotice.PASSWORD_CHANGED)
        return Outcome.Ok(Unit)
    }

    /** 비밀번호 찾기 (로그인 전). 성공하면 로그인 화면으로 보내고 [AuthNotice.PASSWORD_CHANGED] 토스트를 띄운다. */
    suspend fun resetPassword(email: String, verificationToken: String, newPassword: String): Outcome<Unit, PasswordFailure> {
        try {
            api.resetPassword(email, verificationToken, newPassword)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Fail(AuthErrors.password(e))
        }
        _notice.value = AuthNotice.PASSWORD_CHANGED
        return Outcome.Ok(Unit)
    }

    // ---- 이메일 인증 ----

    suspend fun sendEmailCode(email: String, purpose: VerifyPurpose): Outcome<SendCodeResult, SendCodeFailure> =
        try {
            Outcome.Ok(api.sendEmailCode(email, purpose))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Fail(AuthErrors.sendCode(e))
        }

    /** 성공하면 가입/재설정 요청에 실을 1회용 인증 토큰 */
    suspend fun verifyEmailCode(email: String, purpose: VerifyPurpose, code: String): Outcome<String, VerifyCodeFailure> =
        try {
            Outcome.Ok(api.verifyEmailCode(email, purpose, code))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Fail(AuthErrors.verifyCode(e))
        }

    // ---- 내부 ----

    /** 내 정보(이름)를 한 번 받아 둔다. 실패해도 로그인에는 영향 없음 (메뉴 화면 인사말만 비어 있음). */
    suspend fun refreshProfile() {
        try {
            _profile.value = api.me()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 무시
        }
    }

    private fun loadProfileInBackground() {
        if (_profile.value?.email == null) scope.launch { refreshProfile() }
    }

    private suspend fun saveSession(s: PersistedSession) = storeLock.withLock {
        try {
            store.save(s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 저장 실패: 이번 실행에서만 로그인 유지 (다음 실행에서 다시 로그인)
        }
    }

    private suspend fun clearSession() = storeLock.withLock {
        try {
            store.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 무시
        }
    }
}
