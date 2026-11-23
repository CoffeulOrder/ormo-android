package com.ormo.customer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ormo.customer.ui.theme.ormoStatusBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import com.ormo.customer.ui.theme.Base
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Shapes
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space

// ───────────── 체크박스 · 행 (디자인 4.6) ─────────────

/**
 * 원형 체크박스: 컨트롤 20×20, 테두리 2dp Grey Opacity 300, 반경 full, 체크 마크 16. 라벨 body-md Regular + Text/Primary, 간격 8.
 * 체크되면 Fill/Brand 로 채우고 흰 체크 (채움 색은 명세에 명시가 없어 가정).
 */
@Composable
fun OrmoCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    gap: androidx.compose.ui.unit.Dp = Space.s8,
) {
    val c = Ormo.colors
    Row(
        modifier = modifier
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        OrmoCheckCircle(checked)
        if (label != null) Text(label, style = OrmoType.bodyMdRegular, color = c.textPrimary)
    }
}

@Composable
fun OrmoCheckCircle(checked: Boolean, size: androidx.compose.ui.unit.Dp = 20.dp) {
    val c = Ormo.colors
    val bg by animateColorAsState(if (checked) c.fillBrand else Color.Transparent, tween(120), label = "check-bg")
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .border(Size.borderThick, if (checked) c.fillBrand else Base.GreyOpacity300, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val markSize = if (size >= 24.dp) Size.iconMd else Size.iconSm
        if (checked) {
            Icon(OrmoIcons.Check, contentDescription = null, tint = c.textOnFill, modifier = Modifier.size(markSize))
        } else {
            // Figma 옵션 목록·약관 시트: 꺼진 원 안에도 옅은 체크 표시가 보인다
            Icon(OrmoIcons.Check, contentDescription = null, tint = Base.GreyOpacity300, modifier = Modifier.size(markSize))
        }
    }
}

/**
 * 약관 행 (디자인 4.6, 0.2.4): 전체 동의 행은 위아래 12, 개별 행은 위아래 4. 행 끝에 44×44 탭 영역의 chevron-right 24 ([onOpen] 이 있을 때).
 * @param strong 전체 동의 행 (굵은 라벨)
 */
@Composable
fun TermsRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    onOpen: (() -> Unit)? = null,
) {
    val c = Ormo.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = if (strong) Space.s12 else Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s12),
        ) {
            OrmoCheckCircle(checked, size = 24.dp)
            Text(
                label,
                style = OrmoType.bodyMdRegular,
                color = c.textPrimary,
            )
        }
        if (onOpen != null) {
            Box(
                Modifier
                    .size(44.dp) // Figma 4.6: 44×44
                    .clickable(role = Role.Button, onClickLabel = "$label 보기", onClick = onOpen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(OrmoIcons.ChevronRight, contentDescription = null, tint = c.iconSecondary, modifier = Modifier.size(Size.iconLg))
            }
        }
    }
}

// ───────────── 스위치 (디자인 2.4: 50×30, 내부 여백 3) ─────────────

@Composable
fun OrmoSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val c = Ormo.colors
    val track by animateColorAsState(
        when {
            checked && enabled -> c.fillBrand
            checked -> c.fillBrandWeak
            else -> Base.Grey300
        },
        tween(150), label = "switch-track",
    )
    val thumbSize = Size.switchH - Size.switchInset * 2
    val x by animateDpAsState(if (checked) Size.switchW - Size.switchInset * 2 - thumbSize else 0.dp, tween(150), label = "switch-thumb")
    Box(
        modifier = modifier
            .size(Size.switchW, Size.switchH)
            .clip(Shapes.chip)
            .background(track)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                } else {
                    Modifier
                },
            )
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .padding(Size.switchInset),
    ) {
        Box(
            Modifier
                .offset(x = x)
                .size(thumbSize)
                .ormoShadow(shadowSmall(c), CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

// ───────────── 구분선 (디자인 4.7) ─────────────

/** Hairline 1px(Border/Default), Hairline Inset(좌우 page-margin), Thick(섹션 구분, Background/Lower — Figma Divider 컴포넌트 값 12) */
@Composable
fun OrmoDivider(modifier: Modifier = Modifier, inset: Boolean = false) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = if (inset) Space.pageMargin else 0.dp)
            .height(Size.borderThin)
            .background(Ormo.colors.borderDefault),
    )
}

@Composable
fun OrmoThickDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(12.dp).background(Ormo.colors.bgLower))
}

/** 인라인 세로 구분선 */
@Composable
fun OrmoVerticalDivider(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 12.dp) {
    Box(modifier.width(Size.borderThin).height(height).background(Ormo.colors.borderDefault))
}

// ───────────── 뱃지 (디자인 4.7) ─────────────

/**
 * 뱃지: 높이 24, 10자 이내, 눌러지지 않음.
 * 옵션 그룹은 Weak — 필수는 Critical Weak, 선택은 Brand Weak. 메뉴 상태는 Solid — 인기 Brand Solid, 신규 Positive Solid, 품절 Neutral Weak.
 * 한 메뉴에는 하나만 보여준다 (우선순위 품절 > 신규 > 인기: `MenuBadgeRules`).
 */
enum class BadgeKind { Required, Optional, Popular, New, SoldOut, Warning }

@Composable
fun OrmoBadge(text: String, kind: BadgeKind, modifier: Modifier = Modifier) {
    val c = Ormo.colors
    val (bg, fg) = when (kind) {
        BadgeKind.Required -> c.fillDangerWeak to c.textDanger
        BadgeKind.Optional -> c.fillBrandWeak to c.textBrand
        BadgeKind.Popular -> c.fillBrand to c.textOnFill
        BadgeKind.New -> c.fillSuccess to c.textOnFill
        BadgeKind.SoldOut -> c.fillNeutralWeak to c.textSecondary
        BadgeKind.Warning -> c.fillWarningWeak to c.textWarning
    }
    Box(
        modifier
            .heightIn(min = Size.badgeHeight)
            .clip(Shapes.control40)
            .background(bg)
            .padding(horizontal = Space.s8),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.take(10), style = OrmoType.bodyXsSemibold, color = fg, maxLines = 1)
    }
}

// ───────────── 상단 내비게이션 (디자인 4.7) ─────────────

enum class NavLayout { Center, Left, Large }

/**
 * Top Navigation: 높이 56, 좌우 8. 뒤로가기 40×40(아이콘 24), 가운데 제목 body-lg Semibold + Text/Strong.
 * Layout: Center · Left · Large(탭 루트). Theme: Default · Transparent(이미지 위, 흰 아이콘).
 * 상태바 높이만큼 위를 비운다 ([statusBarsPadding]).
 */
@Composable
fun OrmoTopNav(
    modifier: Modifier = Modifier,
    title: String? = null,
    layout: NavLayout = NavLayout.Center,
    onBack: (() -> Unit)? = null,
    transparent: Boolean = false,
    /** 화면 맨 위에 놓을 때만 true (상태바 높이만큼 비움). 목록 안의 견본처럼 맨 위가 아닐 때 false */
    applyStatusBarInset: Boolean = true,
    actions: @Composable () -> Unit = {},
) {
    val c = Ormo.colors
    val iconColor = if (transparent) Color.White else c.iconPrimary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (transparent) Modifier else Modifier.background(c.bgDefault))
            .then(if (applyStatusBarInset) Modifier.ormoStatusBarsPadding() else Modifier)
            .heightIn(min = 56.dp)
            // 아이콘 버튼은 터치 영역 48(Figma 40)이라, 좌우 여백을 4 줄여 아이콘 위치는 Figma 와 같게
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button, onClickLabel = "뒤로가기", onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(OrmoIcons.Back, contentDescription = "뒤로가기", tint = iconColor, modifier = Modifier.size(Size.iconLg))
            }
        } else if (layout == NavLayout.Center) {
            Box(Modifier.size(48.dp))
        }
        when (layout) {
            NavLayout.Center -> Text(
                title.orEmpty(),
                style = OrmoType.bodyLgSemibold,
                color = if (transparent) Color.White else c.textStrong,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            NavLayout.Left -> Text(
                title.orEmpty(),
                style = OrmoType.bodyLgSemibold,
                color = if (transparent) Color.White else c.textStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (onBack == null) Space.s16 else Space.s4)
                    .semantics { heading() },
            )
            NavLayout.Large -> Text(
                title.orEmpty(),
                style = OrmoType.displayXsSemibold,
                color = if (transparent) Color.White else c.textStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Space.s16)
                    .semantics { heading() },
            )
        }
        if (layout == NavLayout.Center && actions == EmptyActions) {
            Box(Modifier.size(48.dp))
        } else {
            actions()
        }
    }
}

private val EmptyActions: @Composable () -> Unit = {}

// ───────────── 정보 행 (라벨 + 값) ─────────────

/** 라벨과 값이 한 줄에 나란히 있는 행 (매장 정보의 운영시간·판매자 정보) */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, labelWidth: Int = 100) {
    val c = Ormo.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
        Text(label, style = OrmoType.bodyMdMedium, color = c.textTertiary, modifier = Modifier.width(labelWidth.dp))
        Text(value, style = OrmoType.bodyMdRegular, color = c.textPrimary, modifier = Modifier.weight(1f))
    }
}

// ───────────── 수량 조절 (옵션 화면·장바구니) ─────────────

/**
 * 수량 −/+ (디자인 2.2: 수량 1~20, 1개일 때 −가, 20개일 때 +가 꺼짐). 버튼 40×40 탭 영역, 안쪽 아이콘 [Size.quantityIcon].
 * 꺼진 + 를 눌러도 안내('한 번에 20개까지 주문할 수 있어요')를 띄울 수 있도록 [onPlusDisabledClick] 을 받는다.
 */
@Composable
fun OrmoStepper(
    quantity: Int,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    minusEnabled: Boolean = true,
    plusEnabled: Boolean = true,
    onPlusDisabledClick: (() -> Unit)? = null,
    minusIsDelete: Boolean = false,
    /** 작은 크기(장바구니 줄): 높이 40 · 버튼 36 · 아이콘 16. 기본은 M: 높이 48 · 버튼 40 · 아이콘 18 */
    small: Boolean = false,
) {
    val c = Ormo.colors
    val button = if (small) 36.dp else 40.dp
    val icon = if (small) Size.iconSm else Size.quantityIcon
    // Figma Quantity Picker: 알약(반경 12, 테두리 1), 버튼과 값 사이 1×16 구분선, 값 최소 36 · 좌우 8 · Body md/Medium
    Row(
        modifier
            .heightIn(min = if (small) 40.dp else 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.bgDefault)
            .border(Size.borderThin, c.borderDefault, RoundedCornerShape(12.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(button)
                .clickable(enabled = minusEnabled, role = Role.Button, onClickLabel = if (minusIsDelete) "삭제" else "수량 줄이기", onClick = onMinus),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (minusIsDelete) OrmoIcons.Trash else OrmoIcons.Minus,
                contentDescription = null,
                tint = if (minusEnabled) c.iconPrimary else c.iconQuaternary,
                modifier = Modifier.size(Size.quantityIcon),
            )
        }
        Box(Modifier.width(1.dp).height(16.dp).background(Base.GreyOpacity100))
        Text(
            quantity.toString(),
            style = OrmoType.bodyMdMedium,
            color = c.textStrong,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 36.dp).padding(horizontal = Space.s8).semantics { contentDescription = "수량 ${quantity}개" },
        )
        Box(Modifier.width(1.dp).height(16.dp).background(Base.GreyOpacity100))
        Box(
            Modifier
                .size(button)
                .clickable(role = Role.Button, onClickLabel = "수량 늘리기") { if (plusEnabled) onPlus() else onPlusDisabledClick?.invoke() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(OrmoIcons.Plus, contentDescription = null, tint = if (plusEnabled) c.iconPrimary else c.iconQuaternary, modifier = Modifier.size(icon))
        }
    }
}

/** Radio (Figma 388:5699, L 24): 꺼짐 = 흰 바탕 + 2dp Grey Opacity 300 테두리, 켜짐 = Brand 원 + 흰 점 */
@Composable
fun OrmoRadio(selected: Boolean, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 24.dp) {
    val c = Ormo.colors
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (selected) c.fillBrand else c.bgDefault)
            .then(if (selected) Modifier else Modifier.border(Size.borderThick, Base.GreyOpacity300, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(size / 3).clip(CircleShape).background(c.textOnFill))
    }
}
