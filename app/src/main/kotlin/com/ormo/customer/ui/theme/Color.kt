package com.ormo.customer.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// 디자인 명세 2.1·2.2 — Figma 의 #RRGGBBAA 를 Compose 의 0xAARRGGBB 로 바꿔 적었다. 값은 Figma 변수 이름을 그대로 따른다.

/** 기본 팔레트 (Base). 화면에서는 직접 쓰지 말고 [OrmoColors] 의 의미 토큰을 쓴다. */
object Base {
    val Blue50 = Color(0xFFEBF5FF); val Blue100 = Color(0xFFC9E4FF); val Blue200 = Color(0xFF91C6FF)
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
 * 라이트·다크 두 벌 — 앱은 기기의 시스템 설정(다크 모드)을 그대로 따른다 (앱 안 설정 없음). 다크 값은 브랜드 가이드(Branding_ORMO)의 ORMO v3 Primitive(Grey 50~900 · Blue)와
 * Semantic(bg/dark = Black · bg/dark-raised = Grey 900 · text/on-dark = White · on-dark-muted = Grey 400 · border/on-dark = Grey 800)에서 가져왔다.
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
    // 그 밖의 선·바탕 (예전에는 Base 팔레트를 화면에서 직접 썼다)
    val borderStrong: Color = Color(0x2E001D3A), // 체크 원 테두리·입력 칸 선
    val hairline: Color = Color(0x0D022047), // 인라인 세로 구분선
    val handle: Color = Color(0xFFD1D6DB), // 바텀 시트 손잡이·꺼진 스위치
    val indicator: Color = Color(0xFF333D4B), // 탭 밑줄
    val bannerEnd: Color = Color(0xFFEBF5FF), // 주문 현황 구역 그라데이션 끝
    val track: Color = Color(0xFFC9E4FF), // 고리 바탕·꺼진 점
    val shimmer: Color = Color(0xB3FFFFFF), // 로딩 스켈레톤 반짝임
    val isDark: Boolean = false,
) {
    companion object {
        /** 다크 모드: 브랜드 가이드의 Grey/Blue 단계에서 고른 값 */
        fun dark() = OrmoColors(
            textPrimary = Color(0xFFF4F5F7), // Grey 50
            textSecondary = Color(0xFFC2C6CD), // Grey 200
            textTertiary = Color(0xFF89919F), // Grey 400 (text/on-dark-muted)
            textQuaternary = Color(0xFF6C7584), // Grey 500
            textStrong = Color(0xFFFFFFFF), // text/on-dark
            textBrand = Color(0xFF5CA9FF), // Blue 400
            textSuccess = Color(0xFF2EC98A),
            textDanger = Color(0xFFFF6B78),
            textWarning = Color(0xFFFFA733),
            textOnFill = Color(0xFFFFFFFF),
            fillBrand = Color(0xFF298DFF), // Blue 500 (bg/brand)
            fillBrandWeak = Color(0x3D298DFF),
            fillNeutral = Color(0xFF6C7584), // Grey 500
            fillNeutralWeak = Color(0x1AFFFFFF),
            fillSuccess = Color(0xFF03B26C),
            fillSuccessWeak = Color(0x3303B26C),
            fillDanger = Color(0xFFF04452),
            fillDangerWeak = Color(0x33F04452),
            fillWarningWeak = Color(0x33FFB331),
            fillInverse = Color(0xFF000000),
            fillInverseWeak = Color(0x30DEDEFF),
            fillPressed = Color(0x1AFFFFFF),
            iconPrimary = Color(0xFFA1A7B2), // Grey 300
            iconSecondary = Color(0xFF89919F), // Grey 400
            iconTertiary = Color(0xFF6C7584), // Grey 500
            iconQuaternary = Color(0xFF4B515B), // Grey 600
            iconBrand = Color(0xFF298DFF),
            iconSuccess = Color(0xFF2EC98A),
            iconDanger = Color(0xFFFF6B78),
            iconWarning = Color(0xFFFFA733),
            bgDefault = Color(0xFF000000), // bg/dark = Base/Black
            bgFloated = Color(0xFF222529), // Grey 800 — 시트·다이얼로그
            bgLower = Color(0xFF131518), // Grey 900 (bg/dark-raised) — 회색 띠·카드
            bgDim = Color(0x99000000),
            borderDefault = Color(0xFF222529), // border/on-dark = Grey 800
            shadowMedium = Color(0x66000000),
            shadowTiny = Color(0x4D000000),
            logoInk = Color(0xFFFFFFFF), // logo/reverse
            logoMark = Color(0xFF298DFF),
            logoReverse = Color(0xFFFFFFFF),
            borderStrong = Color(0xFF4B515B), // Grey 600
            hairline = Color(0xFF222529),
            handle = Color(0xFF4B515B),
            indicator = Color(0xFFF4F5F7),
            bannerEnd = Color(0xFF001129), // Blue 800
            track = Color(0xFF002E6A), // Blue 700
            shimmer = Color(0x14FFFFFF),
            isDark = true,
        )
    }
}
