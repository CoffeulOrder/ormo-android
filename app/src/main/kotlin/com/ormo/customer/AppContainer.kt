package com.ormo.customer

import android.content.Context
import com.ormo.core.SessionResetter
import com.ormo.customer.fake.FakeBackend
import com.ormo.customer.session.KeystoreSessionStore
import com.ormo.data.auth.AuthApi
import com.ormo.data.auth.AuthRepository
import com.ormo.data.auth.RemoteAuthApi
import com.ormo.data.auth.RemoteSchoolDirectoryApi
import com.ormo.data.auth.SchoolDirectoryApi
import com.ormo.data.remote.ApiClient
import com.ormo.data.remote.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 앱 전체에서 하나만 쓰는 객체들을 만들어 두는 곳 (손으로 하는 의존성 주입 — 규모가 작아 DI 프레임워크는 쓰지 않는다).
 * 화면(ViewModel)은 여기서 필요한 것만 꺼내 쓴다.
 */
class AppContainer(context: Context) {
    /** 앱 범위 코루틴: 세션 만료 구독·서버 로그아웃 요청처럼 화면보다 오래 사는 작업 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val sessions = SessionManager()
    private val store = KeystoreSessionStore(context)

    private val fake: FakeBackend? = if (BuildConfig.USE_FAKE_BACKEND) FakeBackend() else null
    private val client: ApiClient? = if (fake == null) ApiClient(BuildConfig.API_BASE_URL, sessions) else null

    val authApi: AuthApi = fake?.auth ?: RemoteAuthApi(client!!)
    val schools: SchoolDirectoryApi = fake?.schools ?: RemoteSchoolDirectoryApi(client!!)

    /** 로그아웃·만료 때 비울 개인 데이터 저장소들 (M3 이후 주문·장바구니 상태 등이 여기에 들어온다) */
    val resetter = SessionResetter(emptyList())

    val auth = AuthRepository(authApi, sessions, store, resetter, appScope)

    /**
     * 로그인 화면이 이메일을 미리 채울 때 쓰는 일회용 값 (가입 중 "이미 가입된 이메일" → [로그인하기], 비밀번호 재설정 완료, 로그인 만료).
     * 화면이 가져가면 비워진다.
     */
    @Volatile
    var pendingLoginEmail: String? = null

    fun takePendingLoginEmail(): String? = pendingLoginEmail.also { pendingLoginEmail = null }
}
