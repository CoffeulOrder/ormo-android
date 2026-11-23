package com.ormo.customer.ui.auth

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ormo.customer.ui.theme.Ormo
import kotlinx.coroutines.delay

/**
 * 스플래시 (디자인 0.0). 로고 모션(약 1초)이 끝나고 0.6초 뒤 이동한다 — 이동은 호출하는 쪽이 토큰 확인이 끝났을 때 한다.
 * 확인이 모션보다 늦으면 로고 마지막 상태로 기다리고(모션을 다시 재생하지 않음), '동작 줄이기'가 켜져 있으면 모션 없이 로고만 보여준다.
 * 모션: [LogoMotion] (컵 → 김 왼쪽·오른쪽 → 워드마크).
 */
@Composable
fun SplashScreen(onMotionFinished: () -> Unit) {
    val reduced = rememberReducedMotion()
    val time = remember { Animatable(if (reduced) LogoMotion.DURATION_MS.toFloat() else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) time.animateTo(LogoMotion.DURATION_MS.toFloat(), tween(LogoMotion.DURATION_MS, easing = LinearEasing))
        delay(600) // 모션 뒤 0.6초
        onMotionFinished()
    }
    Box(Modifier.fillMaxSize().background(Ormo.colors.bgDefault), contentAlignment = Alignment.Center) {
        // 디자인: 로고 상자 중심이 화면 세로 중심보다 119 위
        OrmoLogo(
            Modifier.offset(y = (-119).dp).semantics { contentDescription = "ORMO" },
            state = LogoMotion.at(time.value),
        )
    }
}
