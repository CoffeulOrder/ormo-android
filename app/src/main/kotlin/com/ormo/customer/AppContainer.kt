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

    // 인증은 실제 BE 와 계약이 맞다 (USE_FAKE_AUTH=false). 나머지는 BE 에 없는 부분이 있어 USE_FAKE_BACKEND 로 따로 정한다.
    private val fake: FakeBackend? = if (BuildConfig.USE_FAKE_AUTH) FakeBackend() else null
    private val client: ApiClient? =
        if (!BuildConfig.USE_FAKE_AUTH || !BuildConfig.USE_FAKE_BACKEND) ApiClient(BuildConfig.API_BASE_URL, sessions) else null

    val authApi: AuthApi = fake?.auth ?: RemoteAuthApi(client!!)

    // BE 에 학교 목록 API 가 아직 없어 앱에 넣어 둔 목록을 쓴다 (TODO(BE): GET /schools → RemoteSchoolDirectoryApi)
    val schools: SchoolDirectoryApi = fake?.schools ?: com.ormo.data.auth.BundledSchoolDirectory()

    val cart = CartHolder()
    val toasts = ToastBus()

    // ---- 주문·결제 (M4) ----
    // BE·PG 가 없어 가짜 서버와 가짜 PG 창을 쓴다 (FakePaymentServer). 결제 진행은 앱 범위 하나 — 화면이 사라져도 결제 확인은 이어진다.
    // 실서버 모드(-Pormo.realBackend=true)에서는 매장·메뉴·주문·결제 상태·내 주문이 BE 로 간다. 결제창은 개발 서버 전용 DEV-1 로 확정하고,
    // 실제 PG(PortOne)는 환경 전환 뒤 연결한다 (릴리스는 결제 불가 게이트웨이).
    private val realCatalog = !BuildConfig.USE_FAKE_BACKEND
    private val storeDirectory: com.ormo.data.store.StoreDirectory =
        if (realCatalog) com.ormo.data.backend.RemoteStoreDirectory(client!!) else FakeStoreDirectory()
    val fakePayments = FakePaymentServer(FakeStoreDirectory(), currentStoreId = { cart.storeId })
    private val pendingPayments = PrefsPendingPaymentStore(context)
    val payments = if (realCatalog) {
        PaymentCoordinator(
            com.ormo.data.backend.RemoteOrderApi(client!!) { cart.storeId },
            if (BuildConfig.DEBUG) com.ormo.data.backend.DevPaymentGateway(client) else com.ormo.data.backend.UnavailablePaymentGateway,
            pendingPayments,
        )
    } else {
        PaymentCoordinator(fakePayments.orderApi, fakePayments.gateway, pendingPayments)
    }
    val paymentMethods = PrefsPaymentMethodStore(context)

    // ---- 주문 확인·내 주문 (M5) ----
    // 가짜 내 주문 서버: 결제가 승인되면 접수 대기 주문이 생기고, 개발용 상태 패널이 매장 쪽 동작을 대신한다.
    val fakeOrders = com.ormo.customer.fake.FakeOrderServer()
    val orders = com.ormo.data.orders.OrderRepository(if (realCatalog) com.ormo.data.backend.RemoteMyOrdersApi(client!!) else fakeOrders)
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

    // ---- 베타 제외 기능 (M7): 쿠폰·이벤트 배너·내 메뉴·추천인 ----
    // 새 디자인(Figma)에 화면이 없어 UI 는 없고, 플래그(전부 꺼짐)·규칙·저장소만 준비해 둔다. 꺼져 있으면 서버에 묻지 않는다.
    val featureFlags = com.ormo.core.FeatureFlags.BETA
    private val promoApi = com.ormo.data.promo.UnavailablePromoApi // TODO(M8): 서버가 생기면 RemoteVoucherApi 등으로 교체
    val vouchers = com.ormo.data.promo.VoucherRepository(promoApi, featureFlags)
    val myMenus = com.ormo.data.promo.MyMenuRepository(promoApi, featureFlags)
    val events = com.ormo.data.promo.EventRepository(promoApi, featureFlags)
    val banners = com.ormo.data.promo.BannerManager(
        api = promoApi,
        hideStore = com.ormo.customer.session.PrefsBannerHideStore(context),
        scope = appScope,
        imageReady = { false }, // TODO(M8): Coil prefetch (이미지가 안 받아지면 팝업을 띄우지 않는다)
        userKey = { accountKey },
        flags = featureFlags,
    )

    /** 로그아웃·만료 때 비울 개인 데이터. 마지막 매장·최근 검색어는 계정별로 남기는 게 명세 의도라 넣지 않는다 (PR M3 참고). */
    val resetter = SessionResetter(
        listOf(
            cart,
            vouchers,
            myMenus,
            banners,
            object : Resettable {
                override fun reset() { orders.reset() } // 다른 계정의 주문이 남지 않게
            },
            object : Resettable {
                override fun reset() { appScope.launch { payments.reset() } } // 다른 계정의 진행 중 주문을 이어받지 않도록
            },
        ),
    )

    val auth = AuthRepository(
        authApi, sessions, store, resetter, appScope,
        // 탈퇴 때만: 계정별로 남겨 둔 기기 데이터를 지운다 (로그아웃에서는 남긴다, PR M3·M5)
        onWithdrawn = { key ->
            if (key != null) {
                val k = key.lowercase()
                recentSearches.deleteAccount(k)
                stores.forget(k)
                notificationPrompts.clear(k)
                paymentMethods.clear(k)
            }
        },
    )

    // ---- 내 정보·알림 설정 (M6) ----
    // 알림 설정 API 가 없어 가짜 서버(메모리)를 쓴다. OS 알림 권한은 화면이 보일 때마다 다시 확인해 넣는다.
    val notificationSettings = com.ormo.data.profile.NotificationSettingsRepository(com.ormo.customer.fake.FakeNotificationSettingsApi())
    val locationPreference = com.ormo.customer.session.PrefsLocationPreference(context)

    // ---- 매장·메뉴 (M3) ----
    // BE 가 없어 가짜 데이터를 쓴다 (BuildConfig.USE_FAKE_BACKEND). 메뉴 캐시·최근 검색어는 백업 제외 영역에 둔다.
    private val menuApi = if (BuildConfig.USE_FAKE_BACKEND) FakeMenuApi() else com.ormo.data.backend.BackendMenuApi(client!!)
    val menus = MenuRepository(menuApi, FileMenuCache(File(context.noBackupFilesDir, "menu")), appScope)
    val stores = StoreRepository(storeDirectory, PrefsSelectedStoreStore(context))
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
