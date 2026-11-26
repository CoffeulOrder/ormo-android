package com.ormo.customer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalOrmoColors = staticCompositionLocalOf { OrmoColors() }

object Ormo {
    val colors: OrmoColors
        @Composable get() = LocalOrmoColors.current
}

/**
 * 앱 테마. **기기의 시스템 설정(라이트/다크)을 그대로 따른다** — 앱 안에 다크 모드 설정은 없다.
 * 화면은 `Ormo.colors` / `OrmoType` / `Space` 등 토큰만 쓴다. Material 테마는 ModalBottomSheet·진행 표시 같은 일부 Material 부품이
 * 필요로 해서 최소한만 맞춰 둔다 (색은 Ormo 토큰에서 가져옴).
 */
@Composable
fun OrmoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) OrmoColors.dark() else OrmoColors()
    val material = if (dark) {
        darkColorScheme(
            primary = c.fillBrand, onPrimary = c.textOnFill, background = c.bgDefault, onBackground = c.textPrimary,
            surface = c.bgFloated, onSurface = c.textPrimary, error = c.fillDanger,
        )
    } else {
        lightColorScheme(
            primary = c.fillBrand, onPrimary = c.textOnFill, background = c.bgDefault, onBackground = c.textPrimary,
            surface = c.bgFloated, onSurface = c.textPrimary, error = c.fillDanger,
        )
    }
    CompositionLocalProvider(LocalOrmoColors provides c) {
        MaterialTheme(colorScheme = material) {
            // 눌렀을 때 터치 영역을 알려 주는 회색 물결/강조를 앱 전체에서 끈다. 매장 카드·메뉴 줄만 [PressIndication] 으로 따로 켠다.
            CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides NoIndication, content = content)
        }
    }
}

/** 눌림 효과 없음 */
private object NoIndication : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        object : androidx.compose.ui.Modifier.Node() {}

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = -1
}

/** 눌림 효과(회색 물결)가 필요한 곳(매장 카드·메뉴 줄)에서 `clickable(indication = PressIndication, …)` 으로 쓴다 */
@Composable
fun pressIndication(): androidx.compose.foundation.Indication = androidx.compose.material3.ripple()
