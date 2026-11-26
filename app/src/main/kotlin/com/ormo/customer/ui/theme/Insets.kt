package com.ormo.customer.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

    /** 하단 CTA 버튼 아랫선 ↔ 시스템 내비게이션 바 윗선 최소 간격 */
    val CtaGap: Dp = 16.dp
}

@Composable
fun topInset(): Dp = maxOf(WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), DesignInsets.StatusBar)

@Composable
fun bottomInset(): Dp {
    // 키보드가 올라와 있으면 키보드가 홈 인디케이터 자리를 덮으므로 여백이 필요 없다 (imePadding 이 이미 올려 줌)
    if (WindowInsets.ime.getBottom(LocalDensity.current) > 0) return 0.dp
    return maxOf(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), DesignInsets.NavigationBar)
}

/**
 * 하단 CTA 아래 여백. Figma 는 홈 인디케이터 위에 32 만 띄우지만, 안드로이드의 3버튼 내비게이션(약 48)이나 제스처 바 위에서는
 * 버튼이 시스템 바에 붙거나 겹쳐 보인다. 그래서 **실제 내비게이션 바 높이 + [CTA_GAP]** 보다 작아지지 않게 늘린다.
 * 키보드가 올라와 있으면 시스템 바가 가려지므로 Figma 값 그대로.
 */
@Composable
fun ctaBottomPadding(figma: Dp): Dp {
    if (WindowInsets.ime.getBottom(LocalDensity.current) > 0) return figma
    return maxOf(figma, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + DesignInsets.CtaGap)
}

@Composable
fun Modifier.ormoStatusBarsPadding(): Modifier = padding(top = topInset())

@Composable
fun Modifier.ormoNavigationBarsPadding(): Modifier = padding(bottom = bottomInset())

/** 내비게이션 바 자리를 덮는 흰 막의 불투명도 — 스크롤하는 내용이 뒤로 살짝 비쳐 보이도록 반투명 */
private const val NAV_SCRIM_ALPHA = 0.8f

/**
 * 시스템 내비게이션 바(뒤로가기·홈) 뒤에 까는 반투명 흰 막. 내용은 바 뒤까지 이어지고(스크롤하면 바 밑으로 지나간다),
 * 막 덕분에 바 아이콘은 읽히면서 지나가는 내용이 살짝 비친다. 터치는 막지 않는다. 키보드가 올라와 있으면(바가 가려짐) 그리지 않는다.
 */
@Composable
fun NavigationBarScrim(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    if (navBottom <= 0 || WindowInsets.ime.getBottom(density) > 0) return
    Box(
        modifier
            .fillMaxWidth()
            .height(with(density) { navBottom.toDp() })
            .background(Ormo.colors.bgDefault.copy(alpha = NAV_SCRIM_ALPHA)),
    )
}
