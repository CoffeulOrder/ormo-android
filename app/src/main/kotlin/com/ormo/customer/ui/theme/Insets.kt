package com.ormo.customer.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 시스템 바 여백. Figma 프레임(375×812)은 iPhone 기준이라 위에 상태 표시줄 **50**, 아래에 홈 인디케이터 **34** 가 이미 들어 있다.
 * 안드로이드의 실제 값(대개 24~48 / 0~24)이 더 작으면 화면 요소가 Figma 보다 위·아래로 붙어 버리므로,
 * 실제 값과 Figma 값 중 **큰 쪽**을 써서 모든 화면의 세로 위치를 Figma 와 같게 맞춘다. (노치가 커서 더 크면 실제 값이 이긴다.)
 */
object DesignInsets {
    val StatusBar: Dp = 50.dp
    val NavigationBar: Dp = 34.dp
}

@Composable
fun topInset(): Dp = maxOf(WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), DesignInsets.StatusBar)

@Composable
fun bottomInset(): Dp {
    // 키보드가 올라와 있으면 키보드가 홈 인디케이터 자리를 덮으므로 여백이 필요 없다 (imePadding 이 이미 올려 줌)
    if (WindowInsets.ime.getBottom(LocalDensity.current) > 0) return 0.dp
    return maxOf(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), DesignInsets.NavigationBar)
}

@Composable
fun Modifier.ormoStatusBarsPadding(): Modifier = padding(top = topInset())

@Composable
fun Modifier.ormoNavigationBarsPadding(): Modifier = padding(bottom = bottomInset())
