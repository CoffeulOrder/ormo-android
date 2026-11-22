package com.ormo.customer

import android.content.Context
import com.ormo.core.SessionResetter
import com.ormo.customer.fake.FakeBackend
import com.ormo.customer.fake.FakeMenuApi
import com.ormo.customer.fake.FakePaymentServer
import com.ormo.customer.session.PrefsPaymentMethodStore
import com.ormo.customer.session.PrefsPendingPaymentStore
import com.ormo.core.Resettable
import com.ormo.data.payment.PaymentCoordinator
import kotlinx.coroutines.launch
import com.ormo.customer.fake.FakeStoreDirectory
import com.ormo.customer.session.PrefsSelectedStoreStore
import com.ormo.data.menu.FileMenuCache
import com.ormo.data.menu.MenuRepository
import com.ormo.data.search.FileRecentSearchStore
import com.ormo.data.search.RecentSearchRepository
import com.ormo.data.store.StoreRepository
import java.io.File
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

    val cart = CartHolder()
    val toasts = ToastBus()

    // ---- 주문·결제 (M4) ----
    // BE·PG 가 없어 가짜 서버와 가짜 PG 창을 쓴다 (FakePaymentServer). 결제 진행은 앱 범위 하나 — 화면이 사라져도 결제 확인은 이어진다.
    private val storeDirectory = FakeStoreDirectory()
    val fakePayments = FakePaymentServer(storeDirectory, currentStoreId = { cart.storeId })
    private val pendingPayments = PrefsPendingPaymentStore(context)
    val payments = PaymentCoordinator(fakePayments.orderApi, fakePayments.gateway, pendingPayments)
    val paymentMethods = PrefsPaymentMethodStore(context)

    // ---- 주문 확인·내 주문 (M5) ----
    // 가짜 내 주문 서버: 결제가 승인되면 접수 대기 주문이 생기고, 개발용 상태 패널이 매장 쪽 동작을 대신한다.
    val fakeOrders = com.ormo.customer.fake.FakeOrderServer()
    val orders = com.ormo.data.orders.OrderRepository(fakeOrders)
    val reorderer by lazy { com.ormo.customer.ui.order.Reorderer(menus, cart, stores) { accountKey } }
    val notificationPrompts = com.ormo.customer.session.PrefsNotificationPromptStore(context)

    init {
        fakePayments.onPaid = { orderId, draft, method ->
            val cafe = menus.state.value.payload?.cafes?.firstOrNull { it.id == cart.storeId }
            val lines = draft.lines.mapNotNull { l ->
                val item = cafe?.menus?.firstOrNull { it.id == l.menuId } ?: return@mapNotNull null
                com.ormo.domain.orders.OrderLine(
                    menuId = item.id,
                    name = item.name,
                    choiceIds = l.choiceIds,
                    optionSummary = com.ormo.domain.rules.OptionRules.optionSummary(item, l.choiceIds),
                    qty = l.qty,
                    amount = com.ormo.domain.rules.OptionRules.unitPrice(item, l.choiceIds) * l.qty,
                )
            }
            fakeOrders.create(orderId, cafe?.id ?: cart.storeId.orEmpty(), cafe?.name.orEmpty(), lines, draft.expectedAmount, draft.pickup, method?.label)
        }
    }

    /** 로그아웃·만료 때 비울 개인 데이터. 마지막 매장·최근 검색어는 계정별로 남기는 게 명세 의도라 넣지 않는다 (PR M3 참고). */
    val resetter = SessionResetter(
        listOf(
            cart,
            object : Resettable {
                override fun reset() { orders.reset() } // 다른 계정의 주문이 남지 않게
            },
            object : Resettable {
                override fun reset() { appScope.launch { payments.reset() } } // 다른 계정의 진행 중 주문을 이어받지 않도록
            },
        ),
    )

    val auth = AuthRepository(authApi, sessions, store, resetter, appScope)

    // ---- 매장·메뉴 (M3) ----
    // BE 가 없어 가짜 데이터를 쓴다 (BuildConfig.USE_FAKE_BACKEND). 메뉴 캐시·최근 검색어는 백업 제외 영역에 둔다.
    private val menuApi = if (fake != null) FakeMenuApi() else com.ormo.data.menu.RemoteMenuApi(client!!)
    val menus = MenuRepository(menuApi, FileMenuCache(File(context.noBackupFilesDir, "menu")), appScope)
    val stores = StoreRepository(FakeStoreDirectory(), PrefsSelectedStoreStore(context))
    val recentSearches = RecentSearchRepository(FileRecentSearchStore(File(context.noBackupFilesDir, "recent-searches.json")))

    private val accountPrefs = context.getSharedPreferences("ormo_account", Context.MODE_PRIVATE)

    /**
     * 계정별 기기 데이터(마지막 매장·최근 검색어)의 키. 로그인할 때 이메일로 정하고, 자동 로그인으로 들어오면 저장해 둔 값을 쓴다.
     * (프로필의 이메일은 늦게 도착하므로 여기서 따로 들고 있다.)
     */
    var accountKey: String
        get() = accountPrefs.getString("key", null) ?: "guest"
        private set(v) { accountPrefs.edit().putString("key", v).apply() }

    fun onSignedIn(email: String?) {
        if (!email.isNullOrBlank()) accountKey = email.lowercase()
    }

    /** 가입 직후 첫 진입이면 매장 선택에서 뒤로가기를 숨긴다 (1.1.1) */
    @Volatile
    var firstEntryAfterSignup: Boolean = false

    /**
     * 로그인 화면이 이메일을 미리 채울 때 쓰는 일회용 값 (가입 중 "이미 가입된 이메일" → [로그인하기], 비밀번호 재설정 완료, 로그인 만료).
     * 화면이 가져가면 비워진다.
     */
    @Volatile
    var pendingLoginEmail: String? = null

    fun takePendingLoginEmail(): String? = pendingLoginEmail.also { pendingLoginEmail = null }
}
