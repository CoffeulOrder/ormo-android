package com.ormo.customer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalOrmoColors = staticCompositionLocalOf { OrmoColors() }

object Ormo {
    val colors: OrmoColors
        @Composable get() = LocalOrmoColors.current
}

/**
 * 앱 테마. 라이트 고정 (다크 모드 값은 Figma 에 없음).
 * 화면은 `Ormo.colors` / `OrmoType` / `Space` 등 토큰만 쓴다. Material 테마는 ModalBottomSheet·진행 표시 같은 일부 Material 부품이
 * 필요로 해서 최소한만 맞춰 둔다 (색은 Ormo 토큰에서 가져옴).
 */
@Composable
fun OrmoTheme(content: @Composable () -> Unit) {
    val c = OrmoColors()
    val material = lightColorScheme(
        primary = c.fillBrand,
        onPrimary = c.textOnFill,
        background = c.bgDefault,
        onBackground = c.textPrimary,
        surface = c.bgFloated,
        onSurface = c.textPrimary,
        error = c.fillDanger,
    )
    CompositionLocalProvider(LocalOrmoColors provides c) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
