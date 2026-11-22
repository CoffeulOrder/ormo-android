package com.ormo.customer.ui.order

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ormo.core.formatWon
import com.ormo.customer.ui.components.OrmoDivider
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space
import com.ormo.domain.orders.Order
import com.ormo.domain.orders.StepState
import com.ormo.domain.orders.StepView
import com.ormo.domain.orders.TimeFormat

/** 주문 상태 4칸 단계 바 + 라벨·시각 (Figma 4.3). 지금 단계 라벨은 파랑, 멈춘(거절·매장 취소) 칸은 빨강. */
@Composable
fun StepBar(steps: List<StepView>, modifier: Modifier = Modifier) {
    val c = Ormo.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            steps.forEach { s ->
                val color = when (s.state) {
                    StepState.DONE, StepState.CURRENT -> c.fillBrand
                    StepState.STOPPED -> c.fillDanger
                    StepState.PENDING -> c.fillBrandWeak
                }
                Box(Modifier.weight(1f).height(6.dp).background(color))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            steps.forEach { s ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        s.label,
                        style = if (s.state == StepState.CURRENT) OrmoType.bodyXsSemibold else OrmoType.bodyXsMedium,
                        color = if (s.state == StepState.CURRENT) c.textBrand else c.textTertiary,
                        textAlign = TextAlign.Center,
                    )
                    Text(s.time?.let { TimeFormat.clock(it) }.orEmpty(), style = OrmoType.bodyXsMedium, color = c.textTertiary)
                }
            }
        }
    }
}

/** 섹션 제목 (18 SemiBold) */
@Composable
fun OrderSectionTitle(text: String) {
    Text(text, style = OrmoType.bodyLgSemibold, color = Ormo.colors.textPrimary, modifier = Modifier.fillMaxWidth().padding(horizontal = Space.pageMargin, vertical = Space.s12))
}

/** 주문 내역: 메뉴 이름 + 'N개', 옵션 요약 (Figma 4.3.1) */
@Composable
fun OrderLinesBlock(order: Order, modifier: Modifier = Modifier) {
    val c = Ormo.colors
    Column(modifier.fillMaxWidth().padding(horizontal = Space.pageMargin), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
        order.lines.forEach { l ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.Bottom) {
                    Text(l.name, style = OrmoType.bodyMdSemibold, color = c.textPrimary)
                    Text("${l.qty}개", style = OrmoType.bodyMdRegular, color = c.textSecondary)
                }
                if (l.optionSummary.isNotBlank()) Text(l.optionSummary, style = OrmoType.bodySmRegular, color = c.textSecondary)
            }
        }
    }
}

@Composable
fun LabelValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    val c = Ormo.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = OrmoType.bodyMdRegular, color = c.textSecondary)
        Text(value, style = OrmoType.bodyMdRegular, color = c.textPrimary, textAlign = TextAlign.End, modifier = Modifier.padding(start = Space.s16))
    }
}

/** 결제 정보: 주문 금액·결제 수단·결제 일시, 구분선, 총 결제 금액 (Figma 4.3.1) */
@Composable
fun PaymentInfoBlock(order: Order, modifier: Modifier = Modifier) {
    val c = Ormo.colors
    Column(modifier.fillMaxWidth().padding(horizontal = Space.pageMargin), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
        LabelValueRow("주문 금액", formatWon(order.totalAmount))
        order.paymentMethod?.let { LabelValueRow("결제 수단", it) }
        LabelValueRow("결제 일시", TimeFormat.dateDowClock(order.placedAt))
        OrmoDivider()
        Row(Modifier.fillMaxWidth().padding(top = Space.s4), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("총 결제 금액", style = OrmoType.bodyLgSemibold, color = c.textPrimary)
            Text(formatWon(order.totalAmount), style = OrmoType.bodyLgBold, color = c.textPrimary)
        }
    }
}
