package com.ormo.customer.ui.auth

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ormo.customer.ui.components.BottomCta
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space
import com.ormo.domain.common.CommonError
import kotlinx.coroutines.delay

/** 시스템 "애니메이션 배율"이 0 이면(동작 줄이기) 모션을 건너뛴다 (0.0 스플래시 개발 메모) */
@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/**
 * 1초마다 현재 시각을 돌려주는 틱. [active] 가 false 면 멈춘다 (잠금 카운트다운·인증코드 타이머 용).
 * 시각 계산 자체는 `:domain` 의 순수 함수가 하므로, 화면은 이 값을 넘기기만 한다.
 */
@Composable
fun rememberNowMs(active: Boolean = true): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            now = System.currentTimeMillis()
            delay(1000 - (now % 1000)) // 초 경계에 맞춰 갱신해 표시가 밀리지 않게
        }
    }
    return now
}

/**
 * ORMO 로고 (임시): 파란 원 심볼 + 'RMO' 워드마크. 실제 로고(컵 + 김 + 워드마크 레이어 7개, 0.0 명세)는 에셋이 오면 교체한다.
 */
@Composable
fun OrmoLogo(modifier: Modifier = Modifier, wordmarkVisible: Boolean = true) {
    val c = Ormo.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(c.logoMark))
        if (wordmarkVisible) Text("RMO", style = OrmoType.displaySmSemibold, color = c.logoInk)
    }
}

/** 공통 오류 화면 (6.1): 제목 + 본문 + 버튼 하나. 네트워크·로그인 만료·점검에 쓴다. */
@Composable
fun CommonErrorScreen(error: CommonError, buttonLabel: String, onButton: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ormo.colors
    Column(modifier.fillMaxSize().background(c.bgDefault)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.pageMargin),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(error.title, style = OrmoType.displayXsSemibold, color = c.textPrimary, textAlign = TextAlign.Center)
            Text(
                error.body,
                style = OrmoType.bodyMdRegular,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Space.s8),
            )
            val notice = (error as? CommonError.Maintenance)?.endsAtNotice
            if (notice != null) {
                Text(notice, style = OrmoType.bodyMdSemibold, color = c.textBrand, modifier = Modifier.padding(top = Space.s8))
            }
        }
        BottomCta { OrmoButton(buttonLabel, onButton, Modifier.fillMaxWidth(), size = ButtonSize.XL) }
    }
}
