package com.ormo.customer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.dp
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Shapes
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space

/** 다이얼로그 톤: Neutral(Brand Solid) / Critical(되돌릴 수 없는 행동) */
enum class DialogTone { Neutral, Critical }

/** 다이얼로그 버튼 배치: Horizontal(나란히) / Vertical(긴 라벨) / Single(확인만) */
enum class DialogLayout { Horizontal, Vertical, Single }

/**
 * 알림 다이얼로그 (디자인 4.4): 폭 320, 반경 24, 배경 Background/Floated, Shadow/Large, 화면 중앙.
 * 패딩 위 24·좌우 24·아래 16, 본문과 버튼 사이 24. 뒤 배경 Background/Dim. 제목 body-xl Bold + Text/Strong,
 * 본문 body-md Regular + Text/Secondary, 제목과 본문 사이 8. 버튼 높이 48·반경 14·body-md Semibold:
 * 닫기는 Fill/Neutral Weak + Text/Primary, 확인·진행은 Fill/Brand(Critical 이면 Fill/Danger) + Text/On Fill.
 * 등장: 알파 0.2초 + 크기 0.94→1 (3.1.2 명세).
 *
 * @param dismissLabel 닫기 버튼 문구. Horizontal 이면 왼쪽, Vertical 이면 확인 버튼 아래. null 이면 Single.
 * @param onDismissRequest 바깥 터치·뒤로가기. null 이면 닫히지 않는다 (필수 확인 다이얼로그).
 */
@Composable
fun OrmoDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    layout: DialogLayout = if (dismissLabel == null) DialogLayout.Single else DialogLayout.Horizontal,
    tone: DialogTone = DialogTone.Neutral,
    onDismissRequest: (() -> Unit)? = onDismiss,
) {
    val c = Ormo.colors
    Dialog(
        onDismissRequest = { onDismissRequest?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = onDismissRequest != null,
            dismissOnClickOutside = onDismissRequest != null,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // 뒤 배경: Background/Dim (#00000033 = 알파 20%)
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(c.bgDim.alpha)

        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(durationMillis = 200)) }
        val progress = appear.value

        Column(
            modifier = modifier
                .width(Size.dialogWidth)
                .alpha(progress)
                .scale(0.94f + 0.06f * progress)
                .ormoShadow(shadowLarge(c), Shapes.dialog)
                .clip(Shapes.dialog)
                .background(c.bgFloated)
                .padding(start = Space.s24, end = Space.s24, top = Space.s24, bottom = Space.s16),
        ) {
            Text(title, style = OrmoType.bodyXlBold, color = c.textStrong, modifier = Modifier.semantics { heading() })
            if (body != null) {
                Box(Modifier.height(Space.s8))
                Text(body, style = OrmoType.bodyMdRegular, color = c.textSecondary)
            }
            Box(Modifier.height(Space.s24))
            DialogButtons(layout, confirmLabel, onConfirm, dismissLabel, onDismiss, tone)
        }
    }
}

@Composable
private fun DialogButtons(
    layout: DialogLayout,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String?,
    onDismiss: (() -> Unit)?,
    tone: DialogTone,
) {
    val confirmKind = if (tone == DialogTone.Critical) ButtonKind.Critical else ButtonKind.Solid
    when {
        layout == DialogLayout.Single || dismissLabel == null ->
            OrmoButton(confirmLabel, onConfirm, Modifier.fillMaxWidth(), confirmKind, ButtonSize.L)
        layout == DialogLayout.Horizontal -> Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
            OrmoButton(dismissLabel, { onDismiss?.invoke() }, Modifier.weight(1f), ButtonKind.WeakNeutral, ButtonSize.L)
            OrmoButton(confirmLabel, onConfirm, Modifier.weight(1f), confirmKind, ButtonSize.L)
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            OrmoButton(confirmLabel, onConfirm, Modifier.fillMaxWidth(), confirmKind, ButtonSize.L)
            OrmoButton(dismissLabel, { onDismiss?.invoke() }, Modifier.fillMaxWidth(), ButtonKind.WeakNeutral, ButtonSize.L)
        }
    }
}
