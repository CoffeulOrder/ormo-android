package com.ormo.customer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ormo.customer.ui.theme.Base
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Shapes
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space

/** 입력칸 높이: L 56 / M 48 (디자인 4.3) */
enum class FieldSize(val height: androidx.compose.ui.unit.Dp) { L(Size.fieldL), M(Size.fieldM) }

/**
 * 입력칸 (디자인 4.3 Field + Text Input).
 * 구조: 라벨(body-sm Semibold, Text/Secondary) → 8 → 입력칸 → 도움말·오류 문구.
 * 입력칸: 높이 56(L)/48(M), 반경 16, 좌우 16, 안쪽 간격 8, 배경 Background/Default.
 * 테두리: 기본 1dp Grey Opacity 300, Focused 는 2dp(Fill/Brand), Invalid 는 2dp(Fill/Danger).
 * 값은 body-md Regular + Text/Primary, 뒤에 붙는 도메인([suffix])은 Text/Tertiary. 커서는 Fill/Brand.
 * 지우기 버튼은 Focused 일 때만, 뒤에 글자·아이콘이 붙는 칸([suffix] 또는 [trailing] 이 있으면)은 끈다.
 * Invalid: 도움말 자리에 오류 메시지가 아이콘과 함께 나온다.
 */
@Composable
fun OrmoField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    helper: String? = null,
    error: String? = null,
    size: FieldSize = FieldSize.L,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    suffix: String? = null,
    /** 오른쪽 24dp 아이콘 영역 (도메인 선택 chevron, 비밀번호 eye 등) */
    trailing: (@Composable () -> Unit)? = null,
    clearable: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val c = Ormo.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val invalid = error != null

    val borderColor = when {
        invalid -> c.fillDanger
        focused -> c.fillBrand
        else -> Base.GreyOpacity300
    }
    val borderWidth = if (invalid || focused) Size.borderThick else Size.borderThin

    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(label, style = OrmoType.bodySmSemibold, color = c.textSecondary)
            Box(Modifier.height(Space.s8))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            interactionSource = interaction,
            textStyle = OrmoType.bodyMdRegular.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.fillBrand),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(size.height)
                        .clip(Shapes.control56)
                        .background(c.bgDefault)
                        .border(borderWidth, borderColor, Shapes.control56)
                        .padding(horizontal = Space.s16),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s8),
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = OrmoType.bodyMdRegular, color = c.textQuaternary)
                        }
                        inner()
                    }
                    if (suffix != null) {
                        Text(suffix, style = OrmoType.bodyMdRegular, color = c.textTertiary)
                    }
                    val showClear = clearable && focused && value.isNotEmpty() && suffix == null && trailing == null && enabled
                    if (showClear) {
                        Icon(
                            OrmoIcons.ClearCircle,
                            contentDescription = "지우기",
                            tint = c.iconTertiary,
                            modifier = Modifier
                                .size(Size.iconLg)
                                .clickable(role = Role.Button) { onValueChange("") },
                        )
                    }
                    if (trailing != null) {
                        Box(Modifier.size(Size.iconLg), contentAlignment = Alignment.Center) { trailing() }
                    }
                }
            },
        )
        if (invalid || helper != null) {
            Box(Modifier.height(Space.s8))
            if (invalid) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                    Icon(OrmoIcons.Alert, contentDescription = null, tint = c.iconDanger, modifier = Modifier.size(Size.iconSm))
                    Text(error!!, style = OrmoType.bodySmMedium, color = c.textDanger)
                }
            } else {
                Text(helper!!, style = OrmoType.bodySmRegular, color = c.textTertiary)
            }
        }
    }
}

/** 비밀번호 입력칸: 우측 eye 아이콘으로 표시/숨김 (디자인 4.3) */
@Composable
fun OrmoPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    helper: String? = null,
    error: String? = null,
    size: FieldSize = FieldSize.L,
    enabled: Boolean = true,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var visible by remember { mutableStateOf(false) }
    val c = Ormo.colors
    OrmoField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        helper = helper,
        error = error,
        size = size,
        enabled = enabled,
        trailing = {
            Icon(
                if (visible) OrmoIcons.Eye else OrmoIcons.EyeOff,
                contentDescription = if (visible) "비밀번호 숨기기" else "비밀번호 표시",
                tint = c.iconSecondary,
                modifier = Modifier
                    .size(Size.iconLg)
                    .clickable(role = Role.Button) { visible = !visible },
            )
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        keyboardActions = keyboardActions,
    )
}

/** 비밀번호 규칙 한 줄: check 아이콘 16 + body-sm Medium, 충족하면 Text/Brand (규칙 사이 12, 아이콘-글자 4) */
@Composable
fun PasswordRuleRow(text: String, satisfied: Boolean, modifier: Modifier = Modifier) {
    val c = Ormo.colors
    val color = if (satisfied) c.textBrand else c.textTertiary
    Row(modifier.semantics { contentDescription = text + if (satisfied) ", 충족" else ", 미충족" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
        Icon(OrmoIcons.Check, contentDescription = null, tint = if (satisfied) c.iconBrand else c.iconTertiary, modifier = Modifier.size(Size.iconSm))
        Text(text, style = OrmoType.bodySmMedium, color = color)
    }
}
