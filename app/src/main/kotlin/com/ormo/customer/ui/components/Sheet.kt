package com.ormo.customer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ormo.customer.ui.theme.ormoNavigationBarsPadding
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ormo.customer.ui.theme.Base
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Radius
import com.ormo.customer.ui.theme.Shapes
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space

enum class SheetHeaderAlign { Left, Center }

/**
 * 바텀 시트 (디자인 4.5): 폭 화면 전체, 위쪽 모서리 28, Background/Floated, Shadow/Large.
 * 핸들 40×4 Base/Grey 300 반경 full 위아래 4. 시트 패딩 위 12·아래 16.
 * 헤더: 좌우 24·위아래 16, 제목 body-xl Bold + Text/Strong (Left · Center).
 * 콘텐츠: 좌우 24(page-margin), Slot 반경 12, 위아래 8.
 * 푸터: 위 16·좌우 20, 버튼 XL 56 (좌우 패딩 28). 바깥을 누르거나 아래로 밀면 닫힘.
 *
 * Material3 [ModalBottomSheet] 위에 얹었다 (스크림 색 = Background/Dim). 그림자는 시트 elevation 으로 근사.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrmoBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    headerAlign: SheetHeaderAlign = SheetHeaderAlign.Left,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    /** 본문 위아래 여백 (기본 8) */
    contentVerticalPadding: androidx.compose.ui.unit.Dp = Space.s8,
    /** 버튼 위 간격·아래 간격 (기본 16 · 20). 시트마다 Figma 값이 달라 조절할 수 있다 */
    footerTopPadding: androidx.compose.ui.unit.Dp = Space.s16,
    footerBottomPadding: androidx.compose.ui.unit.Dp = 20.dp,
    /** 제목 아래 여백 (기본 16) */
    titleBottomPadding: androidx.compose.ui.unit.Dp = Space.s16,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Ormo.colors
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = Shapes.sheet,
        containerColor = c.bgFloated,
        scrimColor = c.bgDim,
        tonalElevation = 0.dp,
        dragHandle = { SheetHandle() },
        // 아래 여백은 아래에서 직접 정한다 (Figma: 버튼 아래 20, 홈 인디케이터는 위에 겹침)
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(if (footer == null) Modifier.ormoNavigationBarsPadding() else Modifier) {
            if (title != null) {
                Text(
                    title,
                    style = OrmoType.bodyXlBold,
                    color = c.textStrong,
                    textAlign = if (headerAlign == SheetHeaderAlign.Center) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Space.sheetMargin, end = Space.sheetMargin, top = Space.s16, bottom = titleBottomPadding)
                        .semantics { heading() },
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.sheetMargin, vertical = contentVerticalPadding),
                content = content,
            )
            if (footer != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Space.s20, end = Space.s20, top = footerTopPadding, bottom = footerBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(Space.s8),
                    content = footer,
                )
            }
        }
    }
}

@Composable
private fun SheetHandle() {
    Box(
        Modifier
            .padding(top = Space.s8, bottom = Space.s4 + Space.s8) // Figma: 핸들 위 8
            .width(Size.handleW)
            .height(Size.handleH)
            .clip(Shapes.chip)
            .background(Base.Grey300),
    )
}

/** 시트 안 Slot 박스 (반경 12) */
@Composable
fun SheetSlot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(Radius.md)).background(Ormo.colors.bgLower)) { content() }
}
