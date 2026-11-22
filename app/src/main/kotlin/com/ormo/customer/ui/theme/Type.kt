package com.ormo.customer.ui.theme

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.ormo.customer.R

// 디자인 명세 2.3: 폰트는 Pretendard, 자간 0. 스타일 이름은 크기/굵기 (Body md/Semibold → bodyMdSemibold).
// 글꼴 파일(OFL 라이선스, licenses/Pretendard-OFL.txt)은 res/font 의 4개 굵기.
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal), // 400
    Font(R.font.pretendard_medium, FontWeight.Medium), // 500
    Font(R.font.pretendard_semibold, FontWeight.SemiBold), // 600
    Font(R.font.pretendard_bold, FontWeight.Bold), // 700
)

private fun style(size: Int, line: Int, w: FontWeight) = TextStyle(
    fontFamily = Pretendard,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = w,
    letterSpacing = 0.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

object OrmoType {
    private val R = FontWeight.Normal
    private val M = FontWeight.Medium
    private val S = FontWeight.SemiBold
    private val B = FontWeight.Bold

    val bodyXsMedium = style(12, 18, M); val bodyXsSemibold = style(12, 18, S); val bodyXsBold = style(12, 18, B)
    val bodySmRegular = style(14, 20, R); val bodySmMedium = style(14, 20, M)
    val bodySmSemibold = style(14, 20, S); val bodySmBold = style(14, 20, B)
    val bodyMdRegular = style(16, 24, R); val bodyMdMedium = style(16, 24, M)
    val bodyMdSemibold = style(16, 24, S); val bodyMdBold = style(16, 24, B)
    val bodyLgRegular = style(18, 28, R); val bodyLgMedium = style(18, 28, M)
    val bodyLgSemibold = style(18, 28, S); val bodyLgBold = style(18, 28, B)
    val bodyXlRegular = style(20, 30, R); val bodyXlSemibold = style(20, 30, S); val bodyXlBold = style(20, 30, B)
    val displayXsSemibold = style(24, 32, S)
    val displaySmRegular = style(30, 38, R); val displaySmSemibold = style(30, 38, S)

    /** 픽업 번호 (4.3.2) */
    val display2xlSemibold = style(72, 90, S)
}
