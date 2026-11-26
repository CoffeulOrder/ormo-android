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
import com.ormo.customer.ui.components.OrmoIcons
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
 * 공통 오류 화면 (디자인 6.1 / Figma): 인터넷 끊김·점검은 **회색 원 아이콘 + 제목 + 본문 + 작은 [다시 시도]** 가운데 정렬,
 * 로그인 만료(6.1.2)는 스켈레톤 화면 위에 '다시 로그인해주세요' 다이얼로그 + [로그인하기].
 */
@Composable
fun CommonErrorScreen(error: CommonError, buttonLabel: String, onButton: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ormo.colors
    if (error is CommonError.SessionExpired) {
        // Figma 6.1.2 스켈레톤: 작은 막대 120×16 (y=74) · 넓은 상자 335×48 (y=106) · 줄 6개(48 높이, 88 간격, 시작 y=182, 좌우 안쪽 40)
        Column(modifier.fillMaxSize().background(c.bgDefault).padding(horizontal = Space.pageMargin).padding(top = 74.dp)) {
            com.ormo.customer.ui.components.SkeletonBlock(Modifier.size(width = 120.dp, height = 16.dp))
            com.ormo.customer.ui.components.SkeletonBlock(Modifier.padding(top = 16.dp).fillMaxWidth().size(width = 335.dp, height = 48.dp), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            Column(Modifier.padding(top = 28.dp, start = 20.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(40.dp)) {
                repeat(6) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s12), verticalAlignment = Alignment.CenterVertically) {
                        com.ormo.customer.ui.components.SkeletonBlock(Modifier.size(48.dp), CircleShape)
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                            com.ormo.customer.ui.components.SkeletonBlock(Modifier.size(width = 234.dp, height = 14.dp))
                            com.ormo.customer.ui.components.SkeletonBlock(Modifier.size(width = 100.dp, height = 10.dp))
                        }
                    }
                }
            }
        }
        com.ormo.customer.ui.components.OrmoDialog(
            title = error.title,
            body = error.body.replace(". ", ".\n"),
            confirmLabel = buttonLabel,
            onConfirm = onButton,
            onDismissRequest = null,
        )
        return
    }
    // Figma 6.1.1 Result: 회색 원 60(아이콘 32) · 제목 · 설명 · 버튼 — 비율 배치는 OrmoResult 가 맡는다 (어떤 화면 크기에서도 같은 모양)
    val notice = (error as? CommonError.Maintenance)?.endsAtNotice
    com.ormo.customer.ui.components.OrmoResult(
        icon = if (error is CommonError.Network) OrmoIcons.WifiOff else OrmoIcons.Wrench,
        title = error.title,
        body = error.body,
        modifier = modifier.background(c.bgDefault),
        extra = if (notice != null) ({ Text(notice, style = OrmoType.bodyMdSemibold, color = c.textBrand, textAlign = TextAlign.Center) }) else null,
        action = buttonLabel to onButton,
    )
}
