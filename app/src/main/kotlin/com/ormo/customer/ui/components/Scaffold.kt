package com.ormo.customer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ormo.customer.ui.theme.ormoNavigationBarsPadding
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import androidx.compose.ui.unit.dp
import com.ormo.customer.ui.theme.Space

/**
 * 입력 폼 화면의 공통 틀 (디자인 4.3): 상단 내비게이션, 제목 display-xs Semibold + Text/Primary (좌우 20, 위아래 12),
 * 폼 본문 패딩 20·칸 사이 간격 24, 맨 아래에 하단 CTA. 키보드가 올라오면 버튼이 키보드 위에 붙는다.
 *
 * @param snackbar 화면 위에 띄울 스낵바 (버튼 위 16)
 */
@Composable
fun FormScreen(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    snackbar: OrmoSnackbarState? = null,
    /** 이 값이 바뀌면 본문을 맨 아래까지 스크롤한다 (키보드가 올라와 좁을 때 방금 뜬 오류 문구가 가려지지 않게) */
    scrollToEndKey: Any? = null,
    /** 흐름의 첫 화면(로그인·이메일·인증코드)은 Figma 에서 제목이 y=126, 쌓이는 화면(이름·비밀번호)은 y=118 에 놓인다 */
    firstStep: Boolean = false,
    bottomPadding: androidx.compose.ui.unit.Dp = Space.s32,
    /** 제목 글자 아래 → 첫 요소까지 (Figma: 첫 화면 30, 쌓이는 화면 32, 인증코드 화면 20) */
    titleGap: androidx.compose.ui.unit.Dp? = null,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Ormo.colors
    val scroll = rememberScrollState()
    LaunchedEffect(scrollToEndKey) {
        if (scrollToEndKey != null) scroll.animateScrollTo(scroll.maxValue)
    }
    Box(modifier.fillMaxSize().background(c.bgDefault)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            OrmoTopNav(onBack = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(scroll)
                    .padding(horizontal = Space.s20),
                verticalArrangement = Arrangement.Top,
            ) {
                if (title != null) {
                    Text(
                        title,
                        style = OrmoType.displayXsSemibold,
                        color = c.textPrimary,
                        // Figma: 제목 글자 위 — 첫 화면(로그인·이메일·인증코드) 20, 쌓이는 화면 12
                        modifier = Modifier.padding(top = if (firstStep) 20.dp else 12.dp).semantics { heading() },
                    )
                    Box(Modifier.height(titleGap ?: if (firstStep) 30.dp else 32.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.s24)) { content() }
            }
            if (bottom != null) BottomCta(bottomPadding = bottomPadding, content = bottom) else Box(Modifier.ormoNavigationBarsPadding())
        }
        if (snackbar != null) {
            OrmoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().ormoNavigationBarsPadding(), bottomOffset = 140.dp)
        }
    }
}

