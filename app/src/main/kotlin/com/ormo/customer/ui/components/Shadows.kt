package com.ormo.customer.ui.components

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ormo.customer.ui.theme.OrmoColors

/** 그림자 한 겹: y 오프셋, 블러 반경, 색 */
class ShadowLayer(val y: Dp, val blur: Dp, val color: Color)

/** 디자인 명세 2.5: Shadow/Large = y 8, 블러 32, 색 Shadow/Medium. Shadow/Small = (y 1, 블러 4) + (y 2, 블러 8), 색 Shadow/Tiny. */
fun shadowLarge(c: OrmoColors) = listOf(ShadowLayer(8.dp, 32.dp, c.shadowMedium))
fun shadowSmall(c: OrmoColors) = listOf(ShadowLayer(1.dp, 4.dp, c.shadowTiny), ShadowLayer(2.dp, 8.dp, c.shadowTiny))

/**
 * Figma 식 그림자(오프셋·블러를 따로 지정). Compose 의 `Modifier.shadow` 는 elevation 하나뿐이라 완전히 같지 않다.
 * - API 28+: 블러 마스크로 직접 그린다 (Figma 값에 가깝다).
 * - API 26~27: 하드웨어 가속 캔버스에서 블러 마스크가 안 되므로 elevation 근사.
 * 차이는 디자이너와 확인할 것 (명세 3장 "구현 시 주의").
 */
fun Modifier.ormoShadow(layers: List<ShadowLayer>, shape: Shape): Modifier {
    if (Build.VERSION.SDK_INT < 28) {
        val elevation = layers.maxOfOrNull { it.blur.value / 2f }?.dp ?: 0.dp
        return shadow(elevation, shape, clip = false, ambientColor = layers.first().color, spotColor = layers.first().color)
    }
    return drawBehind {
        val radius = when (shape) {
            is RoundedCornerShape -> shape.topStart.toPx(size, this)
            else -> 0f
        }
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            layers.forEach { layer ->
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = layer.color.toArgb()
                    // 가우시안 블러의 시그마 ≈ 블러 반경 / 2
                    maskFilter = BlurMaskFilter((layer.blur.toPx() / 2f).coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
                }
                native.save()
                native.translate(0f, layer.y.toPx())
                native.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
                native.restore()
            }
        }
    }
}

