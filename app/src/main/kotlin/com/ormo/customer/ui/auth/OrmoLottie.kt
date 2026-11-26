package com.ormo.customer.ui.auth

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition

/**
 * Figma 에서 내보낸 Lottie 모션(`res/raw/ormo_*.json`)을 한 번 재생하고 마지막 모습에서 멈춘다.
 * JSON 은 Figma 프레임(375×812) 그대로의 화면 크기 구성이다 — 그래서 **375×812dp 로 1:1** 그려 [align] 위치에 둔다
 * (디자인의 좌표 = 화면 위쪽 기준이면 [Alignment.TopCenter], 세로 가운데 기준이면 [Alignment.Center]). 폭이 더 좁은 화면은 좌우 끝이 잘린다.
 * '동작 줄이기'가 켜져 있으면 재생하지 않고 마지막 모습만 보여준다. 장식이므로 접근성 이름은 부르는 쪽이 정한다.
 *
 * @param onFinished 재생이 끝났을 때 한 번 (동작 줄이기면 바로). 파일을 읽지 못해도 [fallbackMs] 뒤에 불러서 화면이 멈추지 않게 한다.
 */
@Composable
fun OrmoLottie(
    @RawRes res: Int,
    modifier: Modifier = Modifier,
    align: Alignment = Alignment.TopCenter,
    fallbackMs: Long = 4_000,
    /** true(기본)면 화면 전체 상자에 375×812 를 [align] 위치로 그린다. false 면 [modifier] 가 크기·위치를 직접 정한다 (상자 안에 옮겨 그릴 때) */
    fill: Boolean = true,
    /** 다크 모드 등에서 일부 레이어 색만 바꾼다 (Lottie 는 라이트 바탕 기준으로 만들어져 검은 글자 등이 어두운 바탕에서 사라진다) */
    recolor: List<LottieRecolor> = emptyList(),
    onFinished: (() -> Unit)? = null,
) {
    val reduced = rememberReducedMotion()
    val dynamic = rememberLottieDynamicProperties(
        *recolor.map { r ->
            rememberLottieDynamicProperty(
                property = if (r.stroke) LottieProperty.STROKE_COLOR else LottieProperty.COLOR,
                value = r.color.toArgb(),
                keyPath = arrayOf("**", r.layer, "**"),
            )
        }.toTypedArray(),
    )
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(res))
    val playing by animateLottieCompositionAsState(composition, iterations = 1, isPlaying = !reduced)
    val progress = if (reduced) 1f else playing
    var fired by remember { mutableStateOf(false) }
    LaunchedEffect(composition, progress) {
        if (!fired && composition != null && progress >= 1f) { fired = true; onFinished?.invoke() }
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(fallbackMs)
        if (!fired) { fired = true; onFinished?.invoke() }
    }
    if (!fill) {
        LottieAnimation(composition = composition, progress = { progress }, modifier = modifier, dynamicProperties = dynamic)
        return
    }
    Box(modifier.fillMaxSize().clipToBounds(), contentAlignment = align) {
        LottieAnimation(composition = composition, progress = { progress }, modifier = Modifier.requiredSize(375.dp, 812.dp), dynamicProperties = dynamic)
    }
}

/** 이름이 [layer] 인 레이어(그 아래 전부)의 채움(또는 선) 색을 [color] 로 */
class LottieRecolor(val layer: String, val color: Color, val stroke: Boolean = false)
