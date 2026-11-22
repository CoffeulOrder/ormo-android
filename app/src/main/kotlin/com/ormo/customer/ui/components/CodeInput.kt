package com.ormo.customer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ormo.customer.ui.theme.Base
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Shapes
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space

/**
 * 인증코드 6칸 입력 (디자인 0.2.2). 숫자 키패드, 6자리가 모두 차면 버튼 없이 바로 확인 (호출하는 쪽이 `value` 길이로 판단).
 * 보이지 않는 입력칸 하나 위에 칸 6개를 그린다 → 붙여넣기·자동 완성(SMS/메일 코드)·삭제가 평소 입력처럼 동작한다.
 * 칸 모양(크기·간격)은 명세에 없어 가정: 높이 56, 반경 16, 칸 사이 8, 현재 칸은 Fill/Brand 2dp.
 *
 * @param autoFocus 화면에 들어오면 키보드를 올린다
 * @param invalid 오류 표시 (빨간 테두리)
 */
@Composable
fun OrmoCodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = 6,
    enabled: Boolean = true,
    invalid: Boolean = false,
    autoFocus: Boolean = true,
) {
    val c = Ormo.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val requester = remember { FocusRequester() }
    LaunchedEffect(autoFocus, enabled) { if (autoFocus && enabled) requester.requestFocus() }

    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { ch -> ch in '0'..'9' }.take(length)) },
        enabled = enabled,
        singleLine = true,
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        cursorBrush = SolidColor(Color.Transparent),
        textStyle = OrmoType.bodyMdRegular.copy(color = Color.Transparent),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(requester)
            .semantics { contentDescription = "인증코드 ${length}자리, ${value.length}자리 입력됨" },
        decorationBox = { inner ->
            Box {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                    repeat(length) { i ->
                        val current = focused && enabled && i == value.length.coerceAtMost(length - 1)
                        val border = when {
                            invalid -> c.fillDanger
                            current -> c.fillBrand
                            else -> Base.GreyOpacity300
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(Size.heightXl)
                                .clip(Shapes.control56)
                                .background(if (enabled) c.bgDefault else c.fillNeutralWeak)
                                .border(if (invalid || current) Size.borderThick else Size.borderThin, border, Shapes.control56),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                value.getOrNull(i)?.toString().orEmpty(),
                                style = OrmoType.displayXsSemibold,
                                color = if (enabled) c.textPrimary else c.textQuaternary,
                            )
                        }
                    }
                }
                // 실제 입력칸은 칸들 위에 투명하게 겹쳐 터치·붙여넣기를 받는다
                Box(Modifier.fillMaxWidth().height(1.dp)) { inner() }
            }
        },
    )
}
