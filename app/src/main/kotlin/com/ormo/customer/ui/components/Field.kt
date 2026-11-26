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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
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

/**
 * 키보드의 완료·이동·검색·보내기 키를 누르면 원래 동작(제출 등)을 한 뒤 **키보드를 내리고 포커스를 푼다**.
 * 다음(Next)·이전(Previous) 키는 그대로 둬서 다음 칸으로 넘어간다. 동작을 정하지 않은 키도 기본 동작(키보드 내림)과 같다.
 */
@Composable
fun KeyboardActions.hidingKeyboard(): KeyboardActions {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val original = this
    val hide = { keyboard?.hide(); focus.clearFocus() }
    return KeyboardActions(
        onDone = { original.onDone?.invoke(this); hide() },
        onGo = { original.onGo?.invoke(this); hide() },
        onSearch = { original.onSearch?.invoke(this); hide() },
        onSend = { original.onSend?.invoke(this); hide() },
        onNext = original.onNext,
        onPrevious = original.onPrevious,
    )
}

/**
 * 목록을 손가락으로 끌면(스크롤하면) 키보드를 내리고 포커스를 푼다 — 키보드가 가린 아래쪽 내용을 바로 볼 수 있게 (검색 결과 등).
 * 스크롤하는 목록(LazyColumn·verticalScroll)에 붙인다. 스크롤할 내용이 없어도 끌기만 하면 내려간다.
 */
@Composable
fun Modifier.hideKeyboardOnScroll(): Modifier {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    keyboard?.hide()
                    focus.clearFocus()
                }
                return Offset.Zero
            }
        }
    }
    return nestedScroll(connection)
}

/** 입력칸 안쪽 좌우 여백: 테두리 1 + 16 (Figma: 테두리가 안쪽이라 내용은 17 에서 시작) */
private val INPUT_PAD = 17.dp

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
    /** 오류 문구 없이 빨간 테두리만 (로그인 실패: 두 칸 모두 빨갛게, 문구는 한 곳에만) */
    invalid: Boolean = false,
    size: FieldSize = FieldSize.L,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    suffix: String? = null,
    /** 접미사·아이콘 영역을 눌렀을 때 (도메인 선택 시트) */
    onSuffixClick: (() -> Unit)? = null,
    /** 오른쪽 24dp 아이콘 영역 (도메인 선택 chevron, 비밀번호 eye 등) */
    trailing: (@Composable () -> Unit)? = null,
    clearable: Boolean = true,
    /** 화면(칸)이 처음 나타날 때 키보드를 올린다 */
    autoFocus: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val c = Ormo.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val requester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus && enabled) requester.requestFocus() }
    val isInvalid = error != null || invalid

    val borderColor = when {
        isInvalid -> c.fillDanger
        focused -> c.fillBrand
        else -> c.borderStrong
    }
    val borderWidth = if (isInvalid || focused) Size.borderThick else Size.borderThin

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
            keyboardActions = keyboardActions.hidingKeyboard(),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                // TalkBack: 칸의 이름(라벨)과 오류를 함께 읽는다. 라벨은 칸 밖의 글자라 연결되지 않으므로 직접 붙인다.
                .semantics {
                    val name = label ?: placeholder
                    if (name != null) contentDescription = name
                    if (error != null) error(error)
                },
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = size.height)
                        .clip(Shapes.control56)
                        .background(c.bgDefault)
                        .border(borderWidth, borderColor, Shapes.control56)
                        // Figma: 테두리 1dp 는 **안쪽(INSIDE)** 이라 내용은 테두리 + 좌우 여백 16 = 17 에서 시작·끝난다 (C-01).
                        // 아이콘 버튼은 48dp 터치 영역을 갖는다 → 오른쪽 여백을 그만큼 줄여 아이콘 위치(끝에서 17)는 그대로
                        .padding(
                            start = INPUT_PAD,
                            end = if (suffix == null && (trailing != null || (clearable && focused && value.isNotEmpty()))) INPUT_PAD - 12.dp else INPUT_PAD,
                        ),
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
                        val suffixText: @Composable () -> Unit = { Text(suffix, style = OrmoType.bodyMdRegular, color = c.textTertiary) }
                        if (onSuffixClick != null) {
                            // 도메인 선택 (C-02): '@도메인' → 8 → chevron(24) 을 **하나의 터치 영역**(높이 48 이상)으로 묶는다.
                            // chevron 을 따로 48×48 상자에 넣으면 배치 폭이 커져 접미 글자가 왼쪽으로 밀린다.
                            Row(
                                Modifier.heightIn(min = 48.dp).focusProperties { canFocus = false }.clickable(role = Role.Button, onClickLabel = "학교 메일 주소 선택", onClick = onSuffixClick),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.s8),
                            ) {
                                suffixText()
                                if (trailing != null) Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { trailing() }
                            }
                        } else {
                            suffixText()
                        }
                    }
                    val showClear = clearable && focused && value.isNotEmpty() && suffix == null && trailing == null && enabled
                    if (showClear) {
                        Box(
                            Modifier.size(48.dp).focusProperties { canFocus = false }.clickable(role = Role.Button, onClickLabel = "입력 지우기") { onValueChange("") },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(OrmoIcons.ClearCircle, contentDescription = null, tint = c.iconTertiary, modifier = Modifier.size(Size.iconLg))
                        }
                    }
                    if (trailing != null && suffix == null) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { trailing() }
                    }
                }
            },
        )
        if (error != null || helper != null) {
            Box(Modifier.height(Space.s8))
            if (error != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                    Icon(OrmoIcons.Alert, contentDescription = null, tint = c.iconDanger, modifier = Modifier.size(Size.iconSm))
                    // 오류가 나타나면 TalkBack 이 바로 읽어 준다
                    Text(error!!, style = OrmoType.bodySmMedium, color = c.textDanger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            } else {
                Text(helper!!, style = OrmoType.bodySmRegular, color = c.textSecondary) // Figma: Text/Secondary (C-05)
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
    invalid: Boolean = false,
    size: FieldSize = FieldSize.L,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    autoFocus: Boolean = false,
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
        invalid = invalid,
        size = size,
        enabled = enabled,
        autoFocus = autoFocus,
        trailing = {
            Box(
                Modifier
                    .size(48.dp)
                    .focusProperties { canFocus = false }.clickable(role = Role.Button, onClickLabel = if (visible) "비밀번호 숨기기" else "비밀번호 표시") { visible = !visible }
                    .semantics { stateDescription = if (visible) "비밀번호가 보이는 중" else "비밀번호가 가려진 중" },
                contentAlignment = Alignment.Center,
            ) {
                // 가려진 상태(••••)에서는 눈이 감긴(사선) 아이콘, 보이는 상태(abcd)에서는 눈이 뜬 아이콘
                Icon(if (visible) OrmoIcons.Eye else OrmoIcons.EyeOff, contentDescription = null, tint = c.iconPrimary, modifier = Modifier.size(Size.iconLg)) // Figma: Icon/Primary (C-03)
            }
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
    )
}

/** 비밀번호 규칙 한 줄: check 아이콘 16 + body-sm Medium, 충족하면 Text/Brand (규칙 사이 12, 아이콘-글자 4) */
@Composable
fun PasswordRuleRow(text: String, satisfied: Boolean, modifier: Modifier = Modifier) {
    val c = Ormo.colors
    val color = if (satisfied) c.textBrand else c.textTertiary
    Row(modifier.semantics { contentDescription = text + if (satisfied) ", 충족" else ", 미충족" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
        // Figma: 아이콘 박스 20 안에 check 그림(획 3) — 박스 20, 그림은 16 크기로 (C-09)
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Icon(OrmoIcons.Check, contentDescription = null, tint = if (satisfied) c.iconBrand else c.iconTertiary, modifier = Modifier.size(Size.iconSm))
        }
        Text(text, style = OrmoType.bodySmMedium, color = color)
    }
}
