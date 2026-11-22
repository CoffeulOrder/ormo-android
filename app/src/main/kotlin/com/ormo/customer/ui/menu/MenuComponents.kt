package com.ormo.customer.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ormo.core.formatWon
import com.ormo.customer.ui.components.BadgeKind
import com.ormo.customer.ui.components.OrmoBadge
import com.ormo.customer.ui.theme.Base
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space
import com.ormo.domain.home.HomeSection
import com.ormo.domain.home.MenuRowState
import com.ormo.domain.model.MenuItem

/**
 * 메뉴 줄 (Figma Menu Card, 사진 없음): 패딩 20 · 간격 12. 뱃지(24) → 이름(18/28 semibold) → 가격(16/24 semibold) → 설명(14/20 regular secondary, 2줄 말줄임).
 * 뱃지가 없으면 그 자리는 없다 (줄 높이는 내용에 따라). 담으면 수량 뱃지가 붙는다.
 * 품절은 내용 50% 흐리게 + '품절' 뱃지, 눌러지지 않음. 주문 불가여도 줄은 눌린다 (2.2.1 이 열리고 담기만 꺼짐).
 * @param highlight 검색어와 같은 이름 구간(파란색 강조, 2.1.3)
 */
@Composable
fun MenuRow(
    item: MenuItem,
    state: MenuRowState,
    quantity: Int,
    modifier: Modifier = Modifier,
    badge: BadgeKind? = null,
    highlight: IntRange? = null,
    onClick: () -> Unit,
) {
    val c = Ormo.colors
    val name: AnnotatedString = highlighted(item.name, highlight, c.textBrand)
    Row(
        modifier
            .fillMaxWidth()
            .then(if (state.tappable) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(Space.s20)
            .semantics {
                contentDescription = buildString {
                    append(item.name).append(", ").append(formatWon(item.price))
                    if (state.dimmed) append(", 품절")
                    if (quantity > 0) append(", 장바구니에 ${quantity}개")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        Column(Modifier.weight(1f).alpha(if (state.dimmed) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                if (badge != null) OrmoBadge(badgeLabel(badge), badge)
                Column {
                    Text(name, style = OrmoType.bodyLgSemibold, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatWon(item.price), style = OrmoType.bodyMdSemibold, color = c.textPrimary, maxLines = 1)
                }
            }
            if (item.desc.isNotBlank()) {
                Text(item.desc, style = OrmoType.bodySmRegular, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (quantity > 0) {
            Box(
                Modifier.size(Size.quantityIcon + 6.dp).clip(CircleShape).background(c.fillBrand),
                contentAlignment = Alignment.Center,
            ) {
                Text(quantity.coerceAtMost(99).toString(), style = OrmoType.bodyXsBold, color = c.textOnFill)
            }
        }
    }
}

fun badgeLabel(kind: BadgeKind): String = when (kind) {
    BadgeKind.SoldOut -> "품절"
    BadgeKind.New -> "신규"
    BadgeKind.Popular -> "인기"
    BadgeKind.Required -> "필수"
    BadgeKind.Optional -> "선택"
    BadgeKind.Warning -> ""
}

/** 검색어와 같은 부분을 파란색으로 (2.1.3) */
fun highlighted(text: String, range: IntRange?, color: androidx.compose.ui.graphics.Color): AnnotatedString =
    if (range == null || range.first !in text.indices) {
        AnnotatedString(text)
    } else {
        buildAnnotatedString {
            val end = (range.last + 1).coerceAtMost(text.length)
            append(text.substring(0, range.first))
            withStyle(SpanStyle(color = color)) { append(text.substring(range.first, end)) }
            append(text.substring(end))
        }
    }

/**
 * 카테고리 탭 (Figma Tabs/Tab, Size L): 높이 51, 배경 Background/Default + 아래 1px Border/Default, 왼쪽 8, 탭 좌우 12.
 * 선택: Text/Primary + Bold 18/28, 글자 폭만큼 2px 밑줄(Grey 800, 끝 둥글게). 선택 안 됨: Text/Tertiary + Semibold.
 * 5개 이상이면 가로 스크롤 + 오른쪽 Scroll Fog(흰색 → 투명, 40).
 */
@Composable
fun CategoryTabs(sections: List<HomeSection>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Ormo.colors
    val listState = rememberLazyListState()
    LaunchedEffect(selected) { if (selected in sections.indices) listState.animateScrollToItem(selected) }
    Box(modifier.fillMaxWidth().background(c.bgDefault)) {
        LazyRow(
            Modifier.fillMaxWidth().height(Size.tabHeight),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(sections, key = { _, s -> s.key }) { i, section ->
                val on = i == selected
                Box(
                    Modifier
                        .height(Size.tabHeight)
                        .clickable(role = Role.Tab, onClickLabel = "${section.title} 카테고리로 이동") { onSelect(i) }
                        .padding(horizontal = Space.s12),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        section.title,
                        style = if (on) OrmoType.bodyLgBold else OrmoType.bodyLgSemibold,
                        color = if (on) c.textPrimary else c.textTertiary,
                        maxLines = 1,
                    )
                    if (on) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
                                .clip(CircleShape).background(Base.Grey800),
                        )
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(c.borderDefault))
        if (sections.size >= 5) {
            Box(
                Modifier.align(Alignment.CenterEnd).width(40.dp).height(Size.tabHeight)
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(c.bgDefault.copy(alpha = 0f), c.bgDefault))),
            )
        }
    }
}
