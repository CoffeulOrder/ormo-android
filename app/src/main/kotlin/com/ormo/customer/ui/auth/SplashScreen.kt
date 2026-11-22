package com.ormo.customer.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
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
import com.ormo.customer.ui.theme.Ormo
import kotlinx.coroutines.delay

/**
 * 스플래시 (디자인 0.0). 로고 모션이 끝나고 0.6초 뒤 이동한다 — 이동은 호출하는 쪽이 토큰 확인이 끝났을 때 한다.
 * 확인이 모션보다 늦으면 로고 마지막 상태로 기다리고, '동작 줄이기'가 켜져 있으면 모션 없이 로고만 보여준다.
 * 모션: 컵이 내려앉고 → 김 → 'RMO' 워드마크가 왼쪽에서 밀려 들어옴 (0.6~1.0초). **임시**: 컵·김 에셋이 없어 심볼 + 워드마크만 움직인다.
 */
@Composable
fun SplashScreen(onMotionFinished: () -> Unit) {
    val reduced = rememberReducedMotion()
    var wordmark by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) {
        if (!reduced) {
            delay(600)
            wordmark = true
            delay(400)
        }
        delay(600) // 모션 뒤 0.6초
        onMotionFinished()
    }
    Box(Modifier.fillMaxSize().background(Ormo.colors.bgDefault), contentAlignment = Alignment.Center) {
        OrmoLogo(wordmarkVisible = false)
        // 워드마크는 심볼 오른쪽에서 밀려 들어온다
        AnimatedVisibility(
            visible = wordmark,
            enter = fadeIn(tween(400)) + slideInHorizontally(tween(400)) { -it / 2 },
        ) {
            OrmoLogo()
        }
    }
}
