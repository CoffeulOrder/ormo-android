package com.ormo.customer.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ormo.customer.R
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * 로고 모션 한 레이어의 값 (Figma `0.0 스플래시` → `ORMO Logo · Motion` 의 키프레임을 그대로 옮김).
 * 길이는 dp(디자인 px 와 같은 단위), 불투명도는 0~1.
 */
data class LogoLayerState(val opacity: Float, val offsetX: Float = 0f, val offsetY: Float = 0f)

data class LogoMotionState(
    val cup: LogoLayerState,
    val steamLeft: LogoLayerState,
    val steamRight: LogoLayerState,
    val wordmark: LogoLayerState,
)

/**
 * 로고 모션 (0.0 스플래시): 컵이 위에서 내려앉고(0.05~0.6초, 살짝 튕김) → 김이 왼쪽·오른쪽 순서로 피어오르고(0.38~0.83초)
 * → 'RMO' 워드마크가 왼쪽에서 밀려 들어옴(0.6~1.0초). 한 번만 재생하고 반복하지 않는다 (명세). 끝나면 [DURATION_MS] 이후 값이 계속 유지된다.
 * 시간→값 계산은 순수 함수라 화면 없이 테스트할 수 있다.
 */
object LogoMotion {
    /** 모션이 끝나는 시각 (워드마크 도착) */
    const val DURATION_MS = 1000

    private val easeOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f) // CSS ease-out
    private val easeInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f) // CSS ease-in-out

    /** [start]~[end] 구간에서 [from]→[to] (구간 앞은 from, 뒤는 to) */
    private fun seg(t: Float, start: Float, end: Float, from: Float, to: Float, easing: Easing): Float {
        if (t <= start) return from
        if (t >= end) return to
        return from + (to - from) * easing.transform((t - start) / (end - start))
    }

    /** 모션을 건너뛸 때(동작 줄이기)·끝난 뒤의 모습 = ORMO 로고 */
    val FINAL = at(DURATION_MS.toFloat())

    fun at(timeMs: Float): LogoMotionState {
        val t = timeMs
        // 컵: -28 → 0 (0.05~0.42초) → 2 (~0.5초) → 0 (~0.6초). 불투명도 0.05~0.3초
        val cupY = when {
            t < 420f -> seg(t, 50f, 420f, -28f, 0f, easeOut)
            t < 500f -> seg(t, 420f, 500f, 0f, 2f, easeInOut)
            else -> seg(t, 500f, 600f, 2f, 0f, easeOut)
        }
        return LogoMotionState(
            cup = LogoLayerState(seg(t, 50f, 300f, 0f, 1f, easeOut), offsetY = cupY),
            steamLeft = LogoLayerState(seg(t, 380f, 680f, 0f, 1f, easeOut), offsetY = seg(t, 380f, 730f, 10f, 0f, easeOut)),
            steamRight = LogoLayerState(seg(t, 480f, 780f, 0f, 1f, easeOut), offsetY = seg(t, 480f, 830f, 10f, 0f, easeOut)),
            wordmark = LogoLayerState(seg(t, 600f, 950f, 0f, 1f, easeOut), offsetX = seg(t, 600f, 1000f, -12f, 0f, easeOut)),
        )
    }
}

/**
 * ORMO 로고 (Figma 에서 내려받은 벡터: 컵 · 김 2개 · 'RMO' 워드마크). 'O' 는 컵이다.
 * 기본은 완성된 모습. 스플래시는 [state] 로 레이어별 모션 값을 넘긴다.
 * 크기는 디자인 그대로 170×72dp (워드마크 왼쪽 44.59, 컵 왼쪽 1.9 …).
 */
@Composable
fun OrmoLogo(modifier: Modifier = Modifier, state: LogoMotionState = LogoMotion.FINAL) {
    Box(modifier.size(LOGO_WIDTH, LOGO_HEIGHT)) {
        LogoLayer(R.drawable.ormo_logo_wordmark, 44.59f, 21.03f, 124.249f, 49.8105f, state.wordmark)
        LogoLayer(R.drawable.ormo_logo_cup, 1.89f, 23.10f, 52.9789f, 46.8309f, state.cup)
        LogoLayer(R.drawable.ormo_logo_steam_l, 20.33f, 8.32f, 10.862f, 14.5218f, state.steamLeft)
        LogoLayer(R.drawable.ormo_logo_steam_r, 32.31f, 2.02f, 14.2322f, 19.8977f, state.steamRight)
    }
}

val LOGO_WIDTH: Dp = 170.dp
val LOGO_HEIGHT: Dp = 72.dp

@Composable
private fun LogoLayer(res: Int, x: Float, y: Float, w: Float, h: Float, s: LogoLayerState) {
    Image(
        painter = painterResource(res),
        contentDescription = null, // 장식: 로고 전체의 이름은 부르는 화면이 정한다
        modifier = Modifier
            .offset(x.dp, y.dp)
            .size(w.dp, h.dp)
            .graphicsLayer {
                alpha = s.opacity
                translationX = s.offsetX.dp.toPx()
                translationY = s.offsetY.dp.toPx()
            },
    )
}

/**
 * ORMO 심볼 (컵 + 김 2개, 워드마크 없음). Figma `ORMO Symbol`: 상자 100.54×128 안에 레이어를 비율로 배치한다.
 * 크기는 [width] 로 정하고 높이는 비율(128/100.54)을 따른다. 가입 완료·주문 완료 화면에 쓴다.
 */
enum class MarkBadge { None, Success, Danger, Neutral }

@Composable
fun OrmoMark(
    modifier: Modifier = Modifier,
    width: Dp = 100.54.dp,
    state: LogoMotionState = LogoMotion.FINAL,
    /** 컵 색을 바꾼다 (결제 만료: 짙은 남색, 김 없음) */
    cupTint: androidx.compose.ui.graphics.Color? = null,
    showSteam: Boolean = true,
    badge: MarkBadge = MarkBadge.None,
) {
    val height = width * (128f / 100.54f)
    Box(modifier.size(width, height)) {
        MarkLayer(R.drawable.ormo_logo_cup, width, height, left = 0.0155f, top = 0.3152f, right = 0.0156f, bottom = 0.0121f, s = state.cup, tint = cupTint)
        if (showSteam) {
            MarkLayer(R.drawable.ormo_logo_steam_l, width, height, left = 0.3528f, top = 0.1028f, right = 0.4486f, bottom = 0.6886f, s = state.steamLeft)
            MarkLayer(R.drawable.ormo_logo_steam_r, width, height, left = 0.5718f, top = 0.0123f, right = 0.1679f, bottom = 0.7019f, s = state.steamRight)
        }
        if (badge != MarkBadge.None) MarkBadgeView(badge, Modifier.offset(width * (62f / 100.54f), height * (92f / 128f)))
    }
}

/** 심볼 오른쪽 아래 배지: 지름 36 + 흰 테두리 3, 안에 20 아이콘 (Figma: 성공 초록 체크 · 실패 빨간 ✕ · 만료 회색 시계) */
@Composable
private fun MarkBadgeView(kind: MarkBadge, modifier: Modifier = Modifier) {
    val c = com.ormo.customer.ui.theme.Ormo.colors
    val bg = when (kind) { MarkBadge.Success -> c.fillSuccess; MarkBadge.Danger -> c.fillDanger; else -> c.fillNeutral }
    Box(
        modifier.size(42.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.bgDefault),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(bg), contentAlignment = androidx.compose.ui.Alignment.Center) {
            when (kind) {
                MarkBadge.Success -> androidx.compose.material3.Icon(com.ormo.customer.ui.components.OrmoIcons.Check, null, tint = c.textOnFill, modifier = Modifier.size(24.dp))
                MarkBadge.Danger -> androidx.compose.material3.Icon(com.ormo.customer.ui.components.OrmoIcons.Close, null, tint = c.textOnFill, modifier = Modifier.size(20.dp))
                else -> androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
                    val w = size.width
                    drawCircle(c.textOnFill, radius = w * 0.45f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.12f))
                    val ctr = androidx.compose.ui.geometry.Offset(w / 2, w / 2)
                    drawLine(c.textOnFill, ctr, androidx.compose.ui.geometry.Offset(w / 2, w * 0.25f), strokeWidth = w * 0.1f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(c.textOnFill, ctr, androidx.compose.ui.geometry.Offset(w * 0.68f, w * 0.6f), strokeWidth = w * 0.1f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun MarkLayer(res: Int, w: Dp, h: Dp, left: Float, top: Float, right: Float, bottom: Float, s: LogoLayerState, tint: androidx.compose.ui.graphics.Color? = null) {
    Image(
        colorFilter = tint?.let { androidx.compose.ui.graphics.ColorFilter.tint(it) },
        painter = painterResource(res),
        contentDescription = null,
        modifier = Modifier
            .offset(w * left, h * top)
            .size(w * (1f - left - right), h * (1f - top - bottom))
            .graphicsLayer { alpha = s.opacity; translationY = s.offsetY.dp.toPx() },
    )
}
