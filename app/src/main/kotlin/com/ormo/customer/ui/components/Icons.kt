package com.ormo.customer.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 화면에서 쓰는 아이콘 이름 모음 (디자인 명세 4.7). **Figma 의 Icon 컴포넌트(이름 Icon/…)를 그대로 옮긴 [FigmaIcons] 를 가리키는 별칭**이다 —
 * 모양을 여기서 새로 그리지 않는다. 색은 호출하는 쪽에서 `Icon(tint = Ormo.colors.icon...)` 로 입힌다.
 * 예외: 입력칸 지우기 [ClearCircle] 는 Figma 에 아이콘 컴포넌트가 없어 같은 규격으로 만든 것.
 */
object OrmoIcons {
    val Check: ImageVector get() = FigmaIcons.Check
    val ChevronDown: ImageVector get() = FigmaIcons.ChevronDown
    val ChevronRight: ImageVector get() = FigmaIcons.ChevronRight

    /** 뒤로가기 (Icon/arrow-back-ios) */
    val Back: ImageVector get() = FigmaIcons.ArrowBackIos
    val Close: ImageVector get() = FigmaIcons.Close

    /** 오류 안내 (Icon/alert-circle) */
    val Alert: ImageVector get() = FigmaIcons.AlertCircle
    val Plus: ImageVector get() = FigmaIcons.Plus
    val Minus: ImageVector get() = FigmaIcons.Minus

    /** 삭제 (Icon/trash) */
    val Trash: ImageVector get() = FigmaIcons.Trash
    val Search: ImageVector get() = FigmaIcons.Search

    /** 내 정보 (Icon/user) */
    val Person: ImageVector get() = FigmaIcons.User

    /** 매장 정보 (Icon/info) */
    val Info: ImageVector get() = FigmaIcons.Info

    /** 비밀번호가 가려진 상태에서 보여주는 아이콘 (Icon/eye) */
    val Eye: ImageVector get() = FigmaIcons.Eye

    /** 비밀번호가 보이는 상태 (Icon/eye-off) */
    val EyeOff: ImageVector get() = FigmaIcons.EyeOff

    val MapPin: ImageVector get() = FigmaIcons.MapPin
    val Shoes: ImageVector get() = FigmaIcons.Shoes
    val Coffee: ImageVector get() = FigmaIcons.Coffee
    val CheckCircle: ImageVector get() = FigmaIcons.CheckCircle
    val BellRing: ImageVector get() = FigmaIcons.BellRing
    val Cart: ImageVector get() = FigmaIcons.Cart
    val Clock: ImageVector get() = FigmaIcons.Clock
    val Download: ImageVector get() = FigmaIcons.Download
    val WifiOff: ImageVector get() = FigmaIcons.WifiOff
    val Build: ImageVector get() = FigmaIcons.Build

    /** 이전 이름 (화면 코드가 쓰던 이름) */
    val Bell: ImageVector get() = FigmaIcons.BellRing
    val Cup: ImageVector get() = FigmaIcons.Coffee
    val Wrench: ImageVector get() = FigmaIcons.Build

    /** 이미지 자리 표시 (Figma Image Frame 안, Figma Icon 컴포넌트에는 없음) */
    val ImagePlaceholder: ImageVector get() = FigmaIcons.ImagePlaceholder

    /** 원 안에 도형을 **구멍**으로 낸 채움 아이콘. Icon(tint) 는 모든 색을 한 색으로 덮으므로 흰 획 대신 EvenOdd 구멍을 쓴다. */
    private fun filledCircle(name: String, hole: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ) {
            moveTo(12f, 3f)
            arcTo(9f, 9f, 0f, true, true, 12f, 21f)
            arcTo(9f, 9f, 0f, true, true, 12f, 3f)
            close()
            hole()
        }.build()

    /** 입력칸 지우기: 원 안에 X 모양 구멍 (Figma 에 아이콘 컴포넌트가 없어 같은 규격으로 제작) */
    val ClearCircle: ImageVector = filledCircle("clear") {
        // '+' 모양 다각형을 45° 돌려 X 로 만든다 (팔 길이 4.4, 반폭 0.85)
        val l = 4.4f
        val w = 0.85f
        val pts = listOf(w to w, w to l, -w to l, -w to w, -l to w, -l to -w, -w to -w, -w to -l, w to -l, w to -w, l to -w, l to w)
        val k = 0.70710678f
        pts.forEachIndexed { i, (x, y) ->
            val px = 12f + (x - y) * k
            val py = 12f + (x + y) * k
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}
