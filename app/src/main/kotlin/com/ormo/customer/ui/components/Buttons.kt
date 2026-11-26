package com.ormo.customer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Shapes
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space

/** 디자인 명세 4.1: 크기 S 32 · M 40 · L 48 · XL 56(하단 CTA) */
enum class ButtonSize(val height: Dp, val shape: RoundedCornerShape, val horizontalPadding: Dp) {
    /** TODO(spec 4.1): S 의 반경·글자 크기는 명세에 없어 38→10 을 따라 가정 */
    S(32.dp, Shapes.control38, 12.dp),

    /** Figma Result(오류·빈 상태 안내)의 [다시 시도] 등: 높이 38 · 반경 10 · 좌우 16 */
    Result(38.dp, Shapes.control38, 16.dp),
    M(40.dp, Shapes.control40, 16.dp),
    L(48.dp, Shapes.control48, 16.dp),
    XL(56.dp, Shapes.control56, 24.dp),
}

/**
 * 버튼 종류. **한 화면 묶음에 Solid 는 1개만** (주요 행동).
 * - Solid: 주요 행동 (Fill/Brand + On Fill). [Critical] 은 되돌릴 수 없는 행동 (Fill/Danger).
 * - Weak: Fill/Brand Weak 배경 + Text/Brand (WeakBrand) 또는 Neutral Weak 배경 + Text/Primary (WeakNeutral). WeakCritical: 연한 빨강 배경 + 빨간 글자 (주문 취소하기, Figma 4.3.1)
 * - Outline: 테두리만
 * - Ghost: 텍스트 버튼 (높이 40, 반경 12, 좌우 16, body-md Medium, Text/Tertiary)
 */
enum class ButtonKind { Solid, Critical, WeakCritical, WeakBrand, WeakNeutral, Outline, Ghost }

private const val DOUBLE_TAP_GUARD_MS = 500L

/**
 * 중복 탭을 요청 1번으로 처리하는 클릭 래퍼 (디자인 4.1 규칙). 같은 버튼을 [DOUBLE_TAP_GUARD_MS] 안에 다시 눌러도 무시한다.
 * 화면 쪽에서 진행 중 상태(loading)로 막는 것이 우선이고, 이것은 한 번 더의 안전장치다.
 */
@Composable
fun rememberGuardedClick(onClick: () -> Unit): () -> Unit {
    var last by remember { mutableLongStateOf(0L) }
    return {
        val now = System.currentTimeMillis()
        if (now - last >= DOUBLE_TAP_GUARD_MS) {
            last = now
            onClick()
        }
    }
}

@Composable
fun OrmoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Solid,
    size: ButtonSize = ButtonSize.L,
    enabled: Boolean = true,
    /** 로딩 중에는 라벨을 숨기고 폭을 유지하며 탭을 받지 않는다 */
    loading: Boolean = false,
) {
    val c = Ormo.colors
    val interaction = remember { MutableInteractionSource() }
    val guarded = rememberGuardedClick(onClick)

    val ghost = kind == ButtonKind.Ghost
    val height = if (ghost) 40.dp else size.height
    val shape = if (ghost) Shapes.control40 else size.shape
    val hPad = if (ghost) 16.dp else size.horizontalPadding

    val (bg, fg) = when {
        !enabled && (kind == ButtonKind.Solid || kind == ButtonKind.Critical || kind == ButtonKind.WeakCritical) -> c.fillNeutralWeak to c.textQuaternary
        !enabled -> Color.Transparent to c.textQuaternary
        kind == ButtonKind.Solid -> c.fillBrand to c.textOnFill
        kind == ButtonKind.Critical -> c.fillDanger to c.textOnFill
        kind == ButtonKind.WeakCritical -> c.fillDangerWeak to c.textDanger
        kind == ButtonKind.WeakBrand -> c.fillBrandWeak to c.textBrand // Figma: 연한 파랑 바탕 + 파란(Text/Brand) 글자
        kind == ButtonKind.WeakNeutral -> c.fillNeutralWeak to c.textPrimary
        kind == ButtonKind.Outline -> Color.Transparent to c.textPrimary
        else -> Color.Transparent to c.textTertiary
    }
    val textStyle: TextStyle = when {
        ghost -> OrmoType.bodyMdMedium
        size == ButtonSize.XL -> OrmoType.bodyLgSemibold
        size == ButtonSize.S -> OrmoType.bodySmSemibold
        else -> OrmoType.bodyMdSemibold
    }

    Box(
        modifier = modifier
            .heightIn(min = height)
            .clip(shape)
            .background(bg)
            .then(if (kind == ButtonKind.Outline) Modifier.border(BorderStroke(Size.borderThin, if (enabled) Ormo.colors.iconQuaternary else c.borderDefault), shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = guarded,
            )
            .padding(horizontal = hPad)
            .semantics { if (loading) stateDescription = "처리 중" },
        contentAlignment = Alignment.Center,
    ) {
        // 로딩 중에도 라벨을 그려 폭을 유지하고 투명하게 둔다
        // 버튼 글자는 항상 한 줄: 폭이 좁은 폰(360dp 이하)이나 글자를 키운 설정에서는 줄바꿈 대신 글자를 25%까지 줄여 맞춘다. 그래도 안 들어가면 말줄임.
        val maxSp = textStyle.fontSize
        Text(
            text,
            style = textStyle,
            color = fg,
            modifier = Modifier.alpha(if (loading) 0f else 1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                minFontSize = (maxSp.value * 0.75f).sp,
                maxFontSize = maxSp,
                stepSize = 0.5.sp,
            ),
        )
        if (loading) {
            CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(Size.iconMd))
        }
    }
}

/** 하단 CTA 주 버튼 (4.1 XL 규격): 높이 56, 반경 16, 좌우 24, Fill/Brand, body-lg Semibold */
@Composable
fun PrimaryButtonXl(text: String, enabled: Boolean, loading: Boolean = false, onClick: () -> Unit) {
    OrmoButton(text, onClick, Modifier.fillMaxWidth(), ButtonKind.Solid, ButtonSize.XL, enabled, loading)
}

/**
 * 하단 CTA (디자인 4.2): 화면 맨 아래에 고정되는 주요 행동 영역.
 * 위쪽 Scroll Fog 두께 40 (Background/Default → 투명 그라데이션), 컨테이너 배경 Background/Default, 아래 패딩 32, 버튼 영역 좌우 20, 버튼 간격 8.
 * 버튼 배치: 1개 / 2개 가로 / 2개 세로(문구가 길 때). 보조 액션(Ghost)은 아래에 위 여백 8.
 */
@Composable
fun BottomCta(
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = Space.s32,
    /**
     * 위쪽 흐림까지 합친 **전체 높이**를 알려 준다. 하단 CTA 는 화면 위에 **떠 있다**(Figma 는 화면 맨 아래에 absolute) —
     * 스크롤 내용은 CTA 자리를 따로 잡지 않고 화면 끝까지 이어지며, 끝에 이 높이만큼 빈 칸을 둬서 마지막 줄이 흐림 아래로 가려지지 않게 한다.
     */
    onHeightChange: ((androidx.compose.ui.unit.Dp) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Ormo.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onHeightChange != null) Modifier.onSizeChanged { onHeightChange(with(density) { it.height.toDp() }) } else Modifier,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(Brush.verticalGradient(listOf(c.bgDefault.copy(alpha = 0f), c.bgDefault))),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .registerBottomBar() // 하단 토스트가 이 영역 위로 뜨도록 높이를 알린다
                .background(c.bgDefault)
                .padding(start = Space.s20, end = Space.s20, bottom = com.ormo.customer.ui.theme.ctaBottomPadding(bottomPadding)),
            verticalArrangement = Arrangement.spacedBy(Space.s8),
            content = content,
        )
    }
}
