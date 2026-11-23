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
import com.ormo.customer.ui.components.WifiOff
import com.ormo.customer.ui.components.Wrench
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
        Column(modifier.fillMaxSize().background(c.bgDefault).padding(Space.pageMargin), verticalArrangement = Arrangement.spacedBy(Space.s24)) {
            com.ormo.customer.ui.components.SkeletonBlock(Modifier.padding(top = Space.s32).size(width = 120.dp, height = 16.dp))
            com.ormo.customer.ui.components.SkeletonBlock(Modifier.fillMaxWidth().size(width = 335.dp, height = 48.dp), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            repeat(6) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s12), verticalAlignment = Alignment.CenterVertically) {
                    com.ormo.customer.ui.components.SkeletonBlock(Modifier.size(48.dp), CircleShape)
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                        com.ormo.customer.ui.components.SkeletonBlock(Modifier.size(width = 230.dp, height = 14.dp))
                        com.ormo.customer.ui.components.SkeletonBlock(Modifier.size(width = 100.dp, height = 10.dp))
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
    Column(modifier.fillMaxSize().background(c.bgDefault), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.pageMargin), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(60.dp).clip(CircleShape).background(c.bgLower), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(
                    if (error is CommonError.Network) OrmoIcons.WifiOff else OrmoIcons.Wrench,
                    null, tint = c.iconTertiary, modifier = Modifier.size(28.dp),
                )
            }
            Text(error.title, style = OrmoType.bodyLgSemibold, color = c.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.s16))
            Text(error.body, style = OrmoType.bodyMdRegular, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.s4))
            val notice = (error as? CommonError.Maintenance)?.endsAtNotice
            if (notice != null) {
                Text(notice, style = OrmoType.bodyMdSemibold, color = c.textBrand, modifier = Modifier.padding(top = Space.s8))
            }
            OrmoButton(buttonLabel, onButton, Modifier.padding(top = Space.s16), size = ButtonSize.M)
        }
    }
}
