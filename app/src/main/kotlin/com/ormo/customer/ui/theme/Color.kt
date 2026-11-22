package com.ormo.customer.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// 디자인 명세 2.1·2.2 — Figma 의 #RRGGBBAA 를 Compose 의 0xAARRGGBB 로 바꿔 적었다. 값은 Figma 변수 이름을 그대로 따른다.

/** 기본 팔레트 (Base). 화면에서는 직접 쓰지 말고 [OrmoColors] 의 의미 토큰을 쓴다. */
object Base {
    val Blue50 = Color(0xFFEBF5FF); val Blue200 = Color(0xFF91C6FF)
    val Blue300 = Color(0xFF67AFFE); val Blue400 = Color(0xFF459CFF)
    val Blue500 = Color(0xFF298DFF); val Blue700 = Color(0xFF006BDF)
    val Grey300 = Color(0xFFD1D6DB); val Grey400 = Color(0xFFB0B8C1)
    val Grey700 = Color(0xFF4E5968); val Grey800 = Color(0xFF333D4B)
    val Grey900 = Color(0xFF191F28)
    val GreyOpacity100 = Color(0x0D022047); val GreyOpacity300 = Color(0x2E001D3A)
    val Red500 = Color(0xFFF04452)
    val White = Color(0xFFFFFFFF); val Black = Color(0xFF000000)
}

/**
 * 의미 토큰 (Semantic). Material 역할과 1:1 이 아니라서 Material 테마 대신 `Ormo.colors` 로 꺼내 쓴다.
 * 라이트 테마만 있다 — 다크 모드 값은 Figma 에 없으므로 만들지 않는다.
 */
@Immutable
class OrmoColors(
    // Text
    val textPrimary: Color = Color(0xCC000C1E),
    val textSecondary: Color = Color(0xB2031228),
    val textTertiary: Color = Color(0x9400132B),
    val textQuaternary: Color = Color(0x75031832),
    val textStrong: Color = Color(0xFF191F28),
    val textBrand: Color = Color(0xFF006BDF),
    val textSuccess: Color = Color(0xFF029359),
    val textDanger: Color = Color(0xFFE42939),
    val textWarning: Color = Color(0xFFDD7D02),
    val textOnFill: Color = Color(0xFFFFFFFF),
    // Fill
    val fillBrand: Color = Color(0xFF298DFF),
    val fillBrandWeak: Color = Color(0x29298DFF),
    val fillNeutral: Color = Color(0xFF4E5968),
    val fillNeutralWeak: Color = Color(0x0D07194C),
    val fillSuccess: Color = Color(0xFF03B26C),
    val fillSuccessWeak: Color = Color(0x2902A262),
    val fillDanger: Color = Color(0xFFF04452),
    val fillDangerWeak: Color = Color(0x29F04452),
    val fillWarningWeak: Color = Color(0x29FFB331),
    val fillInverse: Color = Color(0xFFFFFFFF),
    val fillInverseWeak: Color = Color(0x30DEDEFF),
    val fillPressed: Color = Color(0x0D022047),
    // Icon
    val iconPrimary: Color = Color(0x9400132B),
    val iconSecondary: Color = Color(0x75031832),
    val iconTertiary: Color = Color(0x4F001936),
    val iconQuaternary: Color = Color(0x2E001D3A),
    val iconBrand: Color = Color(0xFF298DFF),
    val iconSuccess: Color = Color(0xFF02A262),
    val iconDanger: Color = Color(0xFFE42939),
    val iconWarning: Color = Color(0xFFEE8F11),
    // Background / border / shadow
    val bgDefault: Color = Color(0xFFFFFFFF),
    val bgFloated: Color = Color(0xFFFFFFFF),
    val bgLower: Color = Color(0xFFF2F4F6),
    val bgDim: Color = Color(0x33000000),
    val borderDefault: Color = Color(0x1A001B37),
    val shadowMedium: Color = Color(0x2E001D3A),
    val shadowTiny: Color = Color(0x1A001B37),
    // Logo
    val logoInk: Color = Color(0xFF000000),
    val logoMark: Color = Color(0xFF298DFF),
    val logoReverse: Color = Color(0xFFFFFFFF),
)
