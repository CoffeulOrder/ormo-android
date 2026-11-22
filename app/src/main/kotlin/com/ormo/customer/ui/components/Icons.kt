package com.ormo.customer.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 아이콘 (디자인 명세 4.7): 24 그리드, ORMO 자체 제작(획 2.4, 둥근 끝), eye 는 Material Symbols Rounded.
 * ⚠️ 실제 아이콘 에셋이 아직 없어 **같은 규격(24 그리드·획 2.4·둥근 끝)으로 임시 제작**했다. Figma 에서 내보낸 벡터로 교체할 것.
 * 색은 호출하는 쪽에서 `Icon(tint = Ormo.colors.icon...)` 로 입힌다.
 */
object OrmoIcons {
    private const val STROKE = 2.4f

    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()

    val Check: ImageVector = icon("check") {
        moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f)
    }

    /** Figma Icon/chevron-down */
    val ChevronDown: ImageVector get() = FigmaIcons.ChevronDown

    val ChevronRight: ImageVector = icon("chevron-right") {
        moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f)
    }

    /** 뒤로가기 (왼쪽 화살표) */
    val Back: ImageVector = icon("back") {
        moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f)
    }

    val Close: ImageVector = icon("close") {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }

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

    /** 입력칸 지우기: 원 안에 X 모양 구멍 */
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

    /** 오류 안내: 원 안에 느낌표 모양 구멍 */
    val Alert: ImageVector = filledCircle("alert") {
        moveTo(11f, 7f); lineTo(13f, 7f); lineTo(13f, 13.2f); lineTo(11f, 13.2f); close()
        moveTo(12f, 15.2f)
        arcTo(1.2f, 1.2f, 0f, true, true, 12f, 17.6f)
        arcTo(1.2f, 1.2f, 0f, true, true, 12f, 15.2f)
        close()
    }

    val Plus: ImageVector = icon("plus") {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    val Minus: ImageVector = icon("minus") {
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    /** 삭제 (휴지통) */
    val Trash: ImageVector = icon("trash") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(9f, 7f); lineTo(9f, 4.5f); lineTo(15f, 4.5f); lineTo(15f, 7f)
        moveTo(6.5f, 7f); lineTo(7.5f, 19.5f); lineTo(16.5f, 19.5f); lineTo(17.5f, 7f)
    }

    /** 검색 (돋보기) */
    /** Figma Icon/search */
    val Search: ImageVector get() = FigmaIcons.Search

    /** 내 정보 (사람) */
    /** Figma Icon/user */
    val Person: ImageVector get() = FigmaIcons.User

    /** 매장 정보 (i) */
    val Info: ImageVector = icon("info") {
        moveTo(21f, 12f)
        arcTo(9f, 9f, 0f, true, true, 3f, 12f)
        arcTo(9f, 9f, 0f, true, true, 21f, 12f)
        close()
        moveTo(12f, 11f); lineTo(12f, 16.5f)
        moveTo(12f, 7.6f); lineTo(12f, 7.7f)
    }

    private fun PathBuilder.eyeOutline() {
        moveTo(2.5f, 12f)
        curveTo(5f, 7.5f, 8.2f, 5.5f, 12f, 5.5f)
        curveTo(15.8f, 5.5f, 19f, 7.5f, 21.5f, 12f)
        curveTo(19f, 16.5f, 15.8f, 18.5f, 12f, 18.5f)
        curveTo(8.2f, 18.5f, 5f, 16.5f, 2.5f, 12f)
        close()
        moveTo(14.8f, 12f)
        arcTo(2.8f, 2.8f, 0f, true, true, 9.2f, 12f)
        arcTo(2.8f, 2.8f, 0f, true, true, 14.8f, 12f)
        close()
    }

    /** 비밀번호 표시 */
    /** Figma Icon/eye (비밀번호가 가려진 상태에서 보여주는 아이콘) */
    val Eye: ImageVector get() = FigmaIcons.Eye

    /** 비밀번호가 보이는 상태 */
    val EyeOff: ImageVector get() = FigmaIcons.EyeOff
}

/** Figma 에서 내려받은 그 밖의 아이콘 */
val OrmoIcons.MapPin: ImageVector get() = FigmaIcons.MapPin
val OrmoIcons.Shoes: ImageVector get() = FigmaIcons.Shoes
val OrmoIcons.ImagePlaceholder: ImageVector get() = FigmaIcons.ImagePlaceholder
