package com.ormo.customer.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// 디자인 명세 2.4. 디자인 기준 화면 폭은 375dp, 상태바 높이 50 (Figma 기준; 실제 앱은 시스템 인셋을 쓴다).

object Space {
    val s0 = 0.dp; val s2 = 2.dp; val s4 = 4.dp; val s8 = 8.dp; val s12 = 12.dp
    val s16 = 16.dp; val s20 = 20.dp; val s24 = 24.dp; val s32 = 32.dp; val s40 = 40.dp

    /** 페이지 좌우 여백 */
    /** 화면 좌우 여백. Figma 프레임의 글자 시작 x 는 압도적으로 20 (시트 안은 24 → [sheetMargin]) */
    val pageMargin = 20.dp
    val sheetMargin = 24.dp
}

object Radius {
    val none = 0.dp; val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val r14 = 14.dp
    val lg = 16.dp; val xl = 20.dp; val x2l = 24.dp; val full = 999.dp; val sheet = 28.dp
}

/** 컨트롤 높이 → 반경: 38→10, 40→12, 48→14, 56→16 */
object Shapes {
    val control38 = RoundedCornerShape(10.dp)
    val control40 = RoundedCornerShape(12.dp)
    val control48 = RoundedCornerShape(14.dp)
    val control56 = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val dialog = RoundedCornerShape(24.dp)
    val chip = RoundedCornerShape(999.dp)
}

object Size {
    val heightSm = 36.dp; val heightMd = 40.dp; val heightLg = 48.dp; val heightXl = 56.dp
    val fieldM = 48.dp; val fieldL = 56.dp
    val buttonM = 38.dp; val buttonPaddingXl = 28.dp
    val iconXs = 12.dp; val iconSm = 16.dp; val iconMd = 20.dp; val iconLg = 24.dp; val iconXl = 32.dp
    val borderThin = 1.dp; val borderThick = 2.dp
    val dialogWidth = 320.dp
    val handleW = 40.dp; val handleH = 4.dp
    val tabHeight = 51.dp; val badgeHeight = 24.dp
    val resultAsset = 60.dp; val quantityIcon = 18.dp
    val switchW = 50.dp; val switchH = 30.dp; val switchInset = 3.dp
}
