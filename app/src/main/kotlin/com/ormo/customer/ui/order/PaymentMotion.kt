package com.ormo.customer.ui.order

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.ormo.customer.ui.auth.MarkBadge
import com.ormo.customer.ui.auth.OrmoMark
import com.ormo.customer.ui.auth.rememberReducedMotion

/**
 * 결제 확인(3.2.2)·결제 실패(3.3.1) 심볼 모션. 값은 Figma 모션 키프레임 그대로 (ms 단위, 거리는 dp).
 */

private val Ease = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** (시각 ms, 값) 사이를 구간마다 ease-in-out 으로 잇는다 */
private fun track(t: Float, vararg keys: Pair<Int, Float>): Float {
    if (t <= keys.first().first) return keys.first().second
    for (i in 1 until keys.size) {
        val (t1, v1) = keys[i]
        if (t <= t1) {
            val (t0, v0) = keys[i - 1]
            return v0 + (v1 - v0) * Ease.transform((t - t0) / (t1 - t0))
        }
    }
    return keys.last().second
}

/** 확인 중: 1초마다 김이 번갈아 오르내리고 컵이 살짝 눌린다 */
@Composable
fun ConfirmingMark() {
    if (rememberReducedMotion()) {
        OrmoMark()
        return
    }
    val t: State<Float> = rememberInfiniteTransition(label = "confirming")
        .animateFloat(0f, 1000f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    OrmoMark(
        cupFx = { scaleY = track(t.value, 0 to 1f, 200 to 0.97f, 500 to 1f) },
        steamLeftFx = {
            alpha = track(t.value, 0 to 1f, 400 to 0.55f, 850 to 1f)
            rotationZ = track(t.value, 0 to 0f, 400 to 6f, 850 to 0f)
            translationY = track(t.value, 0 to 0f, 400 to -8f, 850 to 0f) * density
        },
        steamRightFx = {
            alpha = track(t.value, 150 to 1f, 550 to 0.55f, 1000 to 1f)
            rotationZ = track(t.value, 150 to 0f, 550 to -6f, 1000 to 0f)
            translationY = track(t.value, 150 to 0f, 550 to -10f, 1000 to 0f) * density
        },
    )
}

/** 실패 화면 등장 모션의 진행 시각 (0 → [FAILURE_MS] ms, 한 번만) */
private const val FAILURE_MS = 1000f

class FailureMotion(private val time: () -> Float) {
    /** 심볼 전체: 좌우로 흔들림 */
    val symbol: Modifier = Modifier.graphicsLayer {
        translationX = track(time(), 100 to 0f, 180 to -9f, 260 to 8f, 340 to -6f, 420 to 4f, 500 to -2f, 580 to 0f) * density
    }

    /** 제목·본문: 10dp 아래에서 떠오름 */
    val text: Modifier = Modifier.graphicsLayer {
        val p = track(time(), 400 to 0f, 750 to 1f)
        alpha = p
        translationY = (1f - p) * 10f * density
    }

    /** 하단 버튼: 32dp 아래에서 올라옴 */
    val bottom: Modifier = Modifier.graphicsLayer {
        val p = track(time(), 650 to 0f, 1000 to 1f)
        alpha = p
        translationY = (1f - p) * 32f * density
    }

    /** 컵 + 사라지는 김 + 튀어나오는 빨간 ✕ 배지. 끝 모습은 김 없는 컵 */
    @Composable
    fun Mark() {
        OrmoMark(
            modifier = symbol,
            badge = MarkBadge.Danger,
            cupFx = { rotationZ = track(time(), 100 to 0f, 180 to -4f, 260 to 4f, 340 to -3f, 420 to 2f, 500 to -1f, 580 to 0f) },
            steamLeftFx = {
                val t = time()
                alpha = track(t, 100 to 1f, 350 to 0f)
                scaleX = track(t, 100 to 1f, 350 to 0.4f)
                scaleY = scaleX
                translationY = track(t, 0 to 0f, 100 to -4f, 350 to 6f) * density
            },
            steamRightFx = {
                val t = time()
                alpha = track(t, 140 to 1f, 390 to 0f)
                scaleX = track(t, 140 to 1f, 390 to 0.4f)
                scaleY = scaleX
                translationY = track(t, 0 to 0f, 140 to -4f, 390 to 6f) * density
            },
            badgeFx = {
                val t = time()
                alpha = track(t, 500 to 0f, 600 to 1f)
                scaleX = track(t, 500 to 0f, 720 to 1.15f, 860 to 1f)
                scaleY = scaleX
            },
        )
    }
}

@Composable
fun rememberFailureMotion(): FailureMotion {
    val reduced = rememberReducedMotion()
    val time = remember { mutableFloatStateOf(if (reduced) FAILURE_MS else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) {
            Animatable(0f).animateTo(FAILURE_MS, tween(FAILURE_MS.toInt(), easing = LinearEasing)) { time.floatValue = value }
        }
    }
    return remember { FailureMotion { time.floatValue } }
}
