package com.ormo.customer.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.ormo.customer.R
import com.ormo.customer.ui.theme.Ormo
import kotlinx.coroutines.delay

/**
 * 스플래시 (디자인 0.0). 로고 모션(1.6초)이 끝나고 0.6초 뒤 이동한다 — 이동은 호출하는 쪽이 토큰 확인이 끝났을 때 한다.
 * 확인이 모션보다 늦으면 로고 마지막 상태로 기다리고(모션을 다시 재생하지 않음), '동작 줄이기'가 켜져 있으면 모션 없이 로고만 보여준다.
 * 모션: Figma 에서 내보낸 Lottie `ormo_logo_motion` (컵 → 김 왼쪽·오른쪽 → 'RMO' 워드마크). 로고 상자가 화면 세로 가운데보다 119 위인 구성이 파일에 들어 있다.
 */
@Composable
fun SplashScreen(onMotionFinished: () -> Unit) {
    var motionDone by remember { mutableStateOf(false) }
    LaunchedEffect(motionDone) {
        if (motionDone) {
            delay(600) // 모션 뒤 0.6초
            onMotionFinished()
        }
    }
    Box(Modifier.fillMaxSize().background(Ormo.colors.bgDefault).semantics { contentDescription = "ORMO" }) {
        OrmoLottie(
            R.raw.ormo_logo_motion,
            align = Alignment.Center,
            recolor = listOf(LottieRecolor("Wordmark", Ormo.colors.logoInk)), // 글자(RMO): 라이트는 검정, 다크는 흰색
            onFinished = { motionDone = true },
        )
    }
}
