package com.ormo.customer.ui.order

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ormo.customer.ui.theme.ormoNavigationBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space
import com.ormo.domain.model.LiveBarStage
import com.ormo.domain.orders.LiveBarView

/**
 * 메뉴 홈 하단 주문 현황 바 (디자인 2.1.4 / Figma): 번호 상자 + 제목 + 부제 + >, 아래 3칸 단계 바(주문 확인 · 만드는 중 · 픽업 대기).
 * 확인 중·만드는 중은 흰 카드, **픽업 대기는 브랜드 파랑 카드**. 누르면 주문 상세. 사용자가 닫을 수 없다.
 */
@Composable
fun LiveOrderBar(bar: LiveBarView, onClick: () -> Unit, modifier: Modifier = Modifier, bottomInset: Boolean = true) {
    val c = Ormo.colors
    val ready = bar.stage == LiveBarStage.READY
    val bg = if (ready) c.fillBrand else c.bgDefault
    val titleColor = if (ready) c.textOnFill else c.textPrimary
    val subColor = if (ready) c.textOnFill.copy(alpha = 0.85f) else c.textSecondary
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier.fillMaxWidth().background(c.bgDefault)
            .then(if (bottomInset) Modifier.ormoNavigationBarsPadding() else Modifier)
            .padding(horizontal = Space.s16).padding(top = Space.s8, bottom = Space.s12),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(12.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
                .clip(shape)
                .background(bg)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = bar.contentDescription }
                .padding(Space.s16),
            verticalArrangement = Arrangement.spacedBy(Space.s12),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (ready) c.textOnFill else c.fillBrandWeak),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(bar.pickupNumber?.toString() ?: "-", style = OrmoType.bodyXlBold, color = c.textBrand)
                }
                Column(Modifier.weight(1f)) {
                    Text(bar.title, style = OrmoType.bodyLgSemibold, color = titleColor, maxLines = 2)
                    bar.subtitle?.let { Text(it, style = OrmoType.bodySmRegular, color = subColor, maxLines = 2) }
                }
                Icon(OrmoIcons.ChevronRight, null, tint = if (ready) c.textOnFill else c.iconTertiary, modifier = Modifier.size(24.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(3) { i ->
                        val filled = i < bar.filledCells
                        val color = when {
                            ready -> if (filled) c.textOnFill else c.textOnFill.copy(alpha = 0.3f)
                            else -> if (filled) c.fillBrand else c.fillBrandWeak
                        }
                        Box(Modifier.weight(1f).height(6.dp).background(color))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("주문 확인", "만드는 중", "픽업 대기").forEachIndexed { i, label ->
                        val current = i == bar.filledCells - 1
                        Text(
                            label,
                            style = if (current) OrmoType.bodyXsSemibold else OrmoType.bodyXsMedium,
                            color = when {
                                ready -> if (current) c.textOnFill else c.textOnFill.copy(alpha = 0.75f)
                                current -> c.textBrand
                                else -> c.textTertiary
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
