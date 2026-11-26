package com.ormo.customer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space

/**
 * 처리 결과·빈 상태·오류 안내 (Figma Result): 회색 원 60(안에 아이콘 32) → 제목(body-lg Semibold) / 설명(body-md Regular, 사이 4) → 버튼(높이 38, 사이 16).
 * 묶음 안쪽 여백은 위 32 · 좌우 40 · 아래 40, 요소 사이 20. 가로는 화면 폭에서 좌우 40만 빼고 채우므로(글이 길면 줄바꿈) 어떤 폭에서도 잘리지 않는다.
 *
 * **세로 위치**: Figma(812 높이)에서 묶음(262)의 위쪽이 y=235 — 남는 높이를 위 235 : 아래 315 비율로 나눠 화면 크기가 달라도 같은 자리(조금 위쪽 중앙)에 둔다.
 * 남는 높이가 모자란 작은 화면·큰 글자 설정에서는 비율 배치 대신 스크롤로 바꿔 아무것도 잘리지 않게 한다.
 *
 * @param action 주 버튼(Solid). [secondary] 가 있으면 둘을 가로로(Double): 보조(Weak) 왼쪽, 주 오른쪽.
 */
@Composable
fun OrmoResult(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    /** 설명 아래에 더 둘 내용 (점검 종료 시각 등) */
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    action: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
) {
    val c = Ormo.colors
    val block: @Composable ColumnScope.() -> Unit = {
        Column(
            Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = Space.s32, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s20),
        ) {
            Box(Modifier.size(60.dp).clip(CircleShape).background(c.fillNeutralWeak), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = c.iconTertiary, modifier = Modifier.size(32.dp))
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                    Text(title, style = OrmoType.bodyLgSemibold, color = c.textStrong, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                    if (body != null) Text(body, style = OrmoType.bodyMdRegular, color = c.textSecondary, textAlign = TextAlign.Center)
                }
                extra?.invoke(this)
                if (action != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                        if (secondary != null) OrmoButton(secondary.first, secondary.second, kind = ButtonKind.WeakNeutral, size = ButtonSize.Result)
                        OrmoButton(action.first, action.second, size = ButtonSize.Result)
                    }
                }
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        // 묶음 높이(대략 262, 글이 길면 더 큼) 위아래로 비율 여유가 있을 때만 비율 배치
        if (maxHeight >= 420.dp) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(RESULT_TOP_WEIGHT))
                block()
                Spacer(Modifier.weight(RESULT_BOTTOM_WEIGHT))
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { block() }
        }
    }
}

/** Figma 6.1.1 (812 높이): 묶음 262 의 위가 y=235, 아래 남는 315 */
private const val RESULT_TOP_WEIGHT = 235f
private const val RESULT_BOTTOM_WEIGHT = 315f
