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
    const val HOME = "home"
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

    var restoreTick by remember { mutableStateOf(0) }
    LaunchedEffect(restoreTick) { auth.restore() }

    // 홈에 있는 동안 세션이 끝나면 로그인(또는 만료 안내)으로
    LaunchedEffect(status) {
        if (status == AuthStatus.SIGNED_OUT && nav.currentDestination?.route == Routes.HOME) {
            val expired = notice == AuthNotice.SESSION_EXPIRED
            nav.navigate(if (expired) Routes.EXPIRED else Routes.LOGIN) { popUpTo(Routes.HOME) { inclusive = true } }
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
                    AuthStatus.SIGNED_IN -> nav.goClearing(Routes.HOME, Routes.SPLASH)
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
                onLoggedIn = { nav.goClearing(Routes.HOME, Routes.LOGIN) },
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
                onFinished = { loggedIn -> nav.goClearing(if (loggedIn) Routes.HOME else Routes.LOGIN, Routes.LOGIN) },
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
        composable(Routes.HOME) {
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
