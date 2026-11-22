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
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space
import com.ormo.domain.home.HomeSection
import com.ormo.domain.home.MenuRowState
import com.ormo.domain.model.MenuItem

/**
 * 메뉴 줄 (디자인 2.1.1, 사진 없음): 뱃지, 이름, 가격, 두 줄 설명. 담으면 수량 뱃지가 붙는다. **줄 높이는 내용과 상관없이 같다.**
 * 품절은 흐리게 + '품절' 뱃지 + 눌러지지 않음. 주문 불가여도 줄은 눌린다 (2.2.1 이 열리고 담기만 꺼짐).
 *
 * TODO(spec 2.1.1): 이름은 2줄까지 보이는 것이 명세인데, 줄 높이를 고정하려고 한 줄로 줄였다 — Figma 에서 줄 높이 확인.
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
            .height(ROW_HEIGHT)
            .alpha(if (state.dimmed) 0.4f else 1f)
            .then(if (state.tappable) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Space.pageMargin, vertical = Space.s12)
            .semantics {
                contentDescription = buildString {
                    append(item.name).append(", ").append(formatWon(item.price))
                    if (state.dimmed) append(", 품절")
                    if (quantity > 0) append(", 장바구니에 ${quantity}개")
                }
            },
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            // 뱃지 자리(높이 24)는 뱃지가 없어도 비워 둔다: 줄 높이를 같게 하려고
            Box(Modifier.height(Size.badgeHeight)) {
                if (badge != null) OrmoBadge(badgeLabel(badge), badge)
            }
            Text(name, style = OrmoType.bodyLgSemibold, color = c.textStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatWon(item.price), style = OrmoType.bodyMdMedium, color = c.textPrimary, maxLines = 1)
            if (item.desc.isNotBlank()) {
                Text(item.desc, style = OrmoType.bodySmRegular, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (quantity > 0) {
            Box(
                Modifier
                    .padding(start = Space.s12)
                    .size(Size.quantityIcon + 6.dp)
                    .clip(CircleShape)
                    .background(c.fillBrand),
                contentAlignment = Alignment.Center,
            ) {
                Text(quantity.coerceAtMost(99).toString(), style = OrmoType.bodyXsBold, color = c.textOnFill)
            }
        }
    }
}

private val ROW_HEIGHT = 128.dp

fun badgeLabel(kind: BadgeKind): String = when (kind) {
    BadgeKind.SoldOut -> "품절"
    BadgeKind.New -> "신규"
    BadgeKind.Popular -> "인기"
    BadgeKind.Required -> "필수"
    BadgeKind.Optional -> "선택"
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
 * 카테고리 탭 (높이 51, 위에 고정): 누르면 해당 카테고리로 스크롤, 탭이 많으면 가로 스크롤. 지금 보이는 카테고리는 굵게 + 아래 밑줄.
 */
@Composable
fun CategoryTabs(sections: List<HomeSection>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Ormo.colors
    val listState = rememberLazyListState()
    LaunchedEffect(selected) { if (selected in sections.indices) listState.animateScrollToItem(selected) }
    LazyRow(
        modifier.fillMaxWidth().height(Size.tabHeight).background(c.bgDefault),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(sections, key = { _, s -> s.key }) { i, section ->
            val on = i == selected
            Column(
                Modifier
                    .height(Size.tabHeight)
                    .clickable(role = Role.Tab, onClickLabel = "${section.title} 카테고리로 이동") { onSelect(i) }
                    .padding(horizontal = Space.s12),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        section.title,
                        style = if (on) OrmoType.bodyMdSemibold else OrmoType.bodyMdMedium,
                        color = if (on) c.textStrong else c.textTertiary,
                        maxLines = 1,
                    )
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) c.textStrong else androidx.compose.ui.graphics.Color.Transparent))
            }
        }
    }
}
