package com.ormo.customer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ormo.customer.ui.auth.CommonErrorScreen
import com.ormo.customer.ui.auth.LoginScreen
import com.ormo.customer.ui.auth.LoginViewModel
import com.ormo.customer.ui.auth.ResetScreen
import com.ormo.customer.ui.auth.ResetViewModel
import com.ormo.customer.ui.auth.SignupScreen
import com.ormo.customer.ui.auth.SignupViewModel
import com.ormo.customer.ui.auth.SplashScreen
import com.ormo.customer.ui.catalog.ComponentCatalog
import com.ormo.customer.ui.home.HomePlaceholder
import com.ormo.customer.ui.menu.MenuHomeScreen
import com.ormo.customer.ui.order.CheckoutScreen
import com.ormo.customer.ui.order.CheckoutViewModel
import com.ormo.customer.ui.order.OptionScreen
import com.ormo.customer.ui.order.OptionViewModel
import com.ormo.customer.ui.order.OrderCompleteScreen
import com.ormo.customer.ui.menu.MenuHomeViewModel
import com.ormo.customer.ui.menu.MenuSearchScreen
import com.ormo.customer.ui.menu.MenuSearchViewModel
import com.ormo.customer.ui.store.StoreInfoScreen
import com.ormo.customer.ui.store.StoreSelectScreen
import com.ormo.customer.ui.store.StoreSelectViewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.compose.runtime.produceState
import com.ormo.core.Outcome
import com.ormo.data.store.StoreLoadFailure
import com.ormo.domain.store.StoreEntry
import com.ormo.domain.store.StoreRules
import com.ormo.domain.store.OrderAvailability
import java.time.LocalDateTime
import com.ormo.data.auth.AuthNotice
import com.ormo.data.auth.AuthStatus
import com.ormo.domain.common.CommonError
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val SIGNUP = "signup"
    const val RESET = "reset"
    const val ENTRY = "entry"
    const val STORES = "stores/{hideBack}"
    const val STORE_INFO = "store_info/{id}"
    const val MENU = "menu/{id}"
    const val SEARCH = "search/{id}"
    const val MYINFO = "myinfo"
    const val OPTION = "option/{storeId}/{menuId}"
    const val CHECKOUT = "checkout"
    const val COMPLETE = "order_complete/{orderId}"
    fun option(storeId: String, menuId: String) = "option/$storeId/$menuId"
    fun complete(orderId: String) = "order_complete/$orderId"
    fun stores(hideBack: Boolean) = "stores/$hideBack"
    fun storeInfo(id: String) = "store_info/$id"
    fun menu(id: String) = "menu/$id"
    fun search(id: String) = "search/$id"

    /** 로그인한 사람만 보는 화면들 (세션이 끝나면 로그인으로 보낸다) */
    val authed = setOf(ENTRY, STORES, STORE_INFO, MENU, SEARCH, MYINFO, OPTION, CHECKOUT, COMPLETE)
    const val EXPIRED = "session_expired"
    const val OFFLINE = "offline"
    const val CATALOG = "catalog"
}

/**
 * 앱 전체 화면 이동.
 *
 * 시작: 스플래시 → (토큰 확인 결과) 로그인 기록이 있으면 홈, 없으면 로그인, 네트워크 오류면 6.1.1.
 * 로그인·가입 성공은 그 화면이 직접 이동시킨다. 그 뒤 **홈에서 세션이 끝나면**(로그아웃·만료·비밀번호 변경·탈퇴) 여기서 로그인으로 보낸다
 * (만료는 6.1.2 '다시 로그인해주세요' 화면).
 */
@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val auth = container.auth
    val status by auth.status.collectAsState()
    val notice by auth.notice.collectAsState()
    val profile by auth.profile.collectAsState()
    val scope = rememberCoroutineScope()

    // 메뉴 데이터는 학교 단위: 로그인하면 그 학교의 메뉴(캐시 → 서버 버전 체크)를 불러오고, 로그아웃하면 비운다
    val schoolId by auth.schoolId.collectAsState()
    val pendingToast by container.toasts.pending.collectAsState()
    LaunchedEffect(status, schoolId) {
        container.menus.setSchool(if (status == AuthStatus.SIGNED_IN) schoolId else null)
    }

    // 앱을 껐다 켰을 때 결제 확인 중이던 주문이 있으면 서버에 물어 이어간다 (3.2.2)
    LaunchedEffect(status) { if (status == AuthStatus.SIGNED_IN) container.payments.resume() }

    var restoreTick by remember { mutableStateOf(0) }
    LaunchedEffect(restoreTick) { auth.restore() }

    // 홈에 있는 동안 세션이 끝나면 로그인(또는 만료 안내)으로
    LaunchedEffect(status) {
        if (status == AuthStatus.SIGNED_OUT && nav.currentDestination?.route in Routes.authed) {
            val expired = notice == AuthNotice.SESSION_EXPIRED
            nav.navigate(if (expired) Routes.EXPIRED else Routes.LOGIN) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }

    NavHost(nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            // 모션이 끝났고 토큰 확인도 끝났을 때 이동. 확인이 늦으면 로고에서 기다린다.
            var motionDone by remember { mutableStateOf(false) }
            SplashScreen(onMotionFinished = { motionDone = true })
            LaunchedEffect(motionDone, status) {
                if (!motionDone) return@LaunchedEffect
                when (status) {
                    AuthStatus.SIGNED_IN -> nav.goClearing(Routes.ENTRY, Routes.SPLASH)
                    AuthStatus.SIGNED_OUT -> nav.goClearing(Routes.LOGIN, Routes.SPLASH)
                    AuthStatus.OFFLINE -> nav.goClearing(Routes.OFFLINE, Routes.SPLASH)
                    AuthStatus.RESTORING -> Unit
                }
            }
        }
        composable(Routes.OFFLINE) {
            CommonErrorScreen(CommonError.Network, "다시 시도", onButton = {
                nav.goClearing(Routes.SPLASH, Routes.OFFLINE)
                restoreTick++
            })
        }
        composable(Routes.EXPIRED) {
            CommonErrorScreen(CommonError.SessionExpired, "로그인하기", onButton = {
                auth.clearNotice()
                nav.goClearing(Routes.LOGIN, Routes.EXPIRED)
            })
        }
        composable(Routes.LOGIN) {
            val prefill = remember { container.takePendingLoginEmail() }
            val vm: LoginViewModel = viewModel(factory = LoginViewModel.factory(container, prefill))
            LoginScreen(
                vm = vm,
                notice = notice,
                onNoticeShown = { if (notice != AuthNotice.SESSION_EXPIRED) auth.clearNotice() },
                onLoggedIn = { nav.goClearing(Routes.ENTRY, Routes.LOGIN) },
                onForgotPassword = { nav.navigate(Routes.RESET) },
                onSignup = { nav.navigate(Routes.SIGNUP) },
            )
        }
        composable(Routes.SIGNUP) {
            val vm: SignupViewModel = viewModel(factory = SignupViewModel.factory(container))
            SignupScreen(
                vm = vm,
                onClose = { nav.popBackStack() },
                // 가입 후 바로 로그인된 상태 → (매장 선택은 M3) 지금은 홈 자리로. 자동 로그인이 안 됐으면 로그인 화면으로
                onFinished = { loggedIn ->
                    container.firstEntryAfterSignup = loggedIn // 가입 직후 첫 진입은 매장 선택에서 뒤로가기를 숨긴다 (1.1.1)
                    nav.goClearing(if (loggedIn) Routes.ENTRY else Routes.LOGIN, Routes.LOGIN)
                },
                onLoginWithEmail = { email ->
                    container.pendingLoginEmail = email
                    nav.goClearing(Routes.LOGIN, Routes.LOGIN)
                },
            )
        }
        composable(Routes.RESET) {
            val vm: ResetViewModel = viewModel(factory = ResetViewModel.factory(container))
            ResetScreen(
                vm = vm,
                onClose = { nav.popBackStack() },
                onLogin = { email ->
                    container.pendingLoginEmail = email
                    nav.goClearing(Routes.LOGIN, Routes.LOGIN)
                },
            )
        }
        composable(Routes.ENTRY) {
            // 로그인 뒤 어디로 갈지: 기억한 매장이 있으면 메뉴 홈, 없으면 매장 선택 (목록을 못 받아도 기억한 매장이 있으면 메뉴 캐시로)
            var tick by remember { mutableStateOf(0) }
            var failed by remember { mutableStateOf(false) }
            LaunchedEffect(tick) {
                failed = false
                val first = container.firstEntryAfterSignup
                when (val r = container.stores.resolveEntry(container.accountKey, first)) {
                    is Outcome.Ok -> {
                        container.firstEntryAfterSignup = false
                        when (val e = r.value) {
                            is StoreEntry.MenuHome -> nav.goClearing(Routes.menu(e.storeId), Routes.ENTRY)
                            is StoreEntry.StoreSelect -> nav.goClearing(Routes.stores(e.hideBack), Routes.ENTRY)
                        }
                    }
                    is Outcome.Fail -> failed = true
                }
            }
            if (failed) CommonErrorScreen(CommonError.Network, "다시 시도", onButton = { tick++ })
        }
        composable(Routes.STORES, arguments = listOf(navArgument("hideBack") { type = NavType.BoolType })) { entry ->
            val hideBack = entry.arguments?.getBoolean("hideBack") ?: false
            val vm: StoreSelectViewModel = viewModel(factory = StoreSelectViewModel.factory(container))
            StoreSelectScreen(
                vm = vm,
                hideBack = hideBack,
                onBack = { nav.popBackStack() },
                onInfo = { id -> nav.navigate(Routes.storeInfo(id)) },
                onStoreChosen = { id ->
                    // 메뉴 홈은 매장 선택 위에 쌓지 않는다: 이미 메뉴 홈에서 왔으면 그 홈을 새 매장으로 바꾼다
                    nav.navigate(Routes.menu(id)) { popUpTo(nav.graph.id) { inclusive = false; saveState = false }; launchSingleTop = true }
                },
            )
        }
        composable(Routes.STORE_INFO, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            StoreInfoScreen(storeId = entry.arguments?.getString("id").orEmpty(), repo = container.stores, onBack = { nav.popBackStack() })
        }
        composable(Routes.MENU, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val vm: MenuHomeViewModel = viewModel(key = "menu-$id", factory = MenuHomeViewModel.factory(container, id))
            MenuHomeScreen(
                vm = vm,
                onStoreSelect = { nav.navigate(Routes.stores(false)) },
                onSearch = { nav.navigate(Routes.search(id)) },
                onMyInfo = { nav.navigate(Routes.MYINFO) },
                onCart = { nav.navigate(Routes.CHECKOUT) },
                onOption = { menuId -> nav.navigate(Routes.option(id, menuId)) },
                pendingToast = pendingToast,
                onToastShown = container.toasts::consume,
            )
        }
        composable(Routes.SEARCH, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val vm: MenuSearchViewModel = viewModel(key = "search-$id", factory = MenuSearchViewModel.factory(container, id))
            // 검색 결과에서 담을 수 있는지는 매장의 영업 상태로 정한다 (2.1.2)
            val store by produceState<com.ormo.domain.store.Store?>(null, id) {
                value = (container.stores.loadStores() as? Outcome.Ok)?.value?.all?.firstOrNull { it.id == id }
            }
            MenuSearchScreen(
                vm = vm,
                availability = { store?.let { StoreRules.availability(it.status, LocalDateTime.now()) } ?: OrderAvailability.Available },
                onBack = { nav.popBackStack() },
                onOption = { menuId -> nav.navigate(Routes.option(id, menuId)) },
            )
        }
        composable(Routes.OPTION, arguments = listOf(navArgument("storeId") { type = NavType.StringType }, navArgument("menuId") { type = NavType.StringType })) { entry ->
            val storeId = entry.arguments?.getString("storeId").orEmpty()
            val menuId = entry.arguments?.getString("menuId").orEmpty()
            val vm: OptionViewModel = viewModel(key = "option-$storeId-$menuId", factory = OptionViewModel.factory(container, storeId, menuId))
            OptionScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onSearch = { nav.navigate(Routes.search(storeId)) },
                onMyInfo = { nav.navigate(Routes.MYINFO) },
                onAdded = { goCheckout ->
                    if (goCheckout) nav.navigate(Routes.CHECKOUT) { popUpTo(Routes.OPTION) { inclusive = true } } else nav.popBackStack()
                },
            )
        }
        composable(Routes.CHECKOUT) {
            val vm: CheckoutViewModel = viewModel(factory = CheckoutViewModel.factory(container))
            CheckoutScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onViewMenu = {
                    // 메뉴 홈으로: 이미 스택에 있으면 거기로, 없으면(앱을 다시 켠 뒤 등) 마지막 매장 메뉴를 새로 연다
                    if (!nav.popBackStack(Routes.MENU, inclusive = false)) nav.navigate(Routes.stores(false)) { popUpTo(Routes.CHECKOUT) { inclusive = true } }
                },
                onPaid = { orderId -> nav.navigate(Routes.complete(orderId)) { popUpTo(Routes.CHECKOUT) { inclusive = true } } },
            )
        }
        composable(Routes.COMPLETE, arguments = listOf(navArgument("orderId") { type = NavType.StringType })) { entry ->
            OrderCompleteScreen(
                orderId = entry.arguments?.getString("orderId").orEmpty(),
                onMore = { if (!nav.popBackStack(Routes.MENU, inclusive = false)) nav.navigate(Routes.stores(false)) { popUpTo(Routes.COMPLETE) { inclusive = true } } },
            )
        }
        composable(Routes.MYINFO) {
            // 내 정보(M6)가 만들어질 때까지 임시: 로그아웃만 된다
            HomePlaceholder(
                name = profile?.name,
                onLogout = { scope.launch { auth.signOut() } },
                onCatalog = if (BuildConfig.DEBUG) ({ nav.navigate(Routes.CATALOG) }) else null,
            )
        }
        composable(Routes.CATALOG) { ComponentCatalog() }
    }
}

private fun NavHostController.goClearing(route: String, clear: String) {
    navigate(route) { popUpTo(clear) { inclusive = true } }
}
