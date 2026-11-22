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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
) {
    val c = Ormo.colors
    Row(
        modifier = modifier
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        CheckCircle(checked)
        if (label != null) Text(label, style = OrmoType.bodyMdRegular, color = c.textPrimary)
    }
}

@Composable
private fun CheckCircle(checked: Boolean) {
    val c = Ormo.colors
    val bg by animateColorAsState(if (checked) c.fillBrand else Color.Transparent, tween(120), label = "check-bg")
    Box(
        Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(bg)
            .border(Size.borderThick, if (checked) c.fillBrand else Base.GreyOpacity300, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(OrmoIcons.Check, contentDescription = null, tint = c.textOnFill, modifier = Modifier.size(Size.iconSm))
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
            horizontalArrangement = Arrangement.spacedBy(Space.s8),
        ) {
            CheckCircle(checked)
            Text(
                label,
                style = if (strong) OrmoType.bodyLgSemibold else OrmoType.bodyMdRegular,
                color = c.textPrimary,
            )
        }
        if (onOpen != null) {
            Box(
                Modifier
                    .size(44.dp)
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

/** Hairline 1px(Border/Default), Hairline Inset(좌우 page-margin), Thick 8px(섹션 구분, Background/Lower) */
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
    Box(modifier.fillMaxWidth().height(8.dp).background(Ormo.colors.bgLower))
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
enum class BadgeKind { Required, Optional, Popular, New, SoldOut }

@Composable
fun OrmoBadge(text: String, kind: BadgeKind, modifier: Modifier = Modifier) {
    val c = Ormo.colors
    val (bg, fg) = when (kind) {
        BadgeKind.Required -> c.fillDangerWeak to c.textDanger
        BadgeKind.Optional -> c.fillBrandWeak to c.textBrand
        BadgeKind.Popular -> c.fillBrand to c.textOnFill
        BadgeKind.New -> c.fillSuccess to c.textOnFill
        BadgeKind.SoldOut -> c.fillNeutralWeak to c.textSecondary
    }
    Box(
        modifier
            .height(Size.badgeHeight)
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
            .then(if (applyStatusBarInset) Modifier.statusBarsPadding() else Modifier)
            .height(56.dp)
            .padding(horizontal = Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(40.dp)
                    .clickable(role = Role.Button, onClickLabel = "뒤로가기", onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(OrmoIcons.Back, contentDescription = "뒤로가기", tint = iconColor, modifier = Modifier.size(Size.iconLg))
            }
        } else if (layout == NavLayout.Center) {
            Box(Modifier.size(40.dp))
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
            Box(Modifier.size(40.dp))
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
