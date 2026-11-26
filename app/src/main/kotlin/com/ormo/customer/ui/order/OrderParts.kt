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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
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
    // Figma 4.3.1: 막대 4개(높이 8, 둥근 알약, 간격 4) → 8 아래 라벨(14 Medium, 현재 단계는 브랜드색) + 시각(12)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.forEachIndexed { i, s ->
                val color = when (s.state) {
                    StepState.DONE, StepState.CURRENT -> c.fillBrand
                    StepState.STOPPED -> c.fillDanger
                    StepState.PENDING, StepState.MISSED -> c.fillBrandWeak
                }
                // Figma 4.3: 맨 왼쪽 칸은 왼쪽 끝만, 맨 오른쪽 칸은 오른쪽 끝만 둥글고(반경 999) 가운데 칸은 모서리 없음
                val shape = when (i) {
                    0 -> RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                    steps.lastIndex -> RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                    else -> RoundedCornerShape(0.dp)
                }
                Box(Modifier.weight(1f).height(8.dp).clip(shape).background(color))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.forEach { s ->
                // 단계의 상태를 색만으로 알리지 않는다 → TalkBack 에는 "만드는 중, 현재 단계, 13:15" 처럼 한 번에 읽힌다
                val stateText = when (s.state) {
                    StepState.DONE -> "완료"
                    StepState.CURRENT -> "현재 단계"
                    StepState.STOPPED -> "중단됨"
                    StepState.MISSED -> "미수령"
                    StepState.PENDING -> "대기 중"
                }
                Column(
                    Modifier.weight(1f).semantics(mergeDescendants = true) {
                        contentDescription = "${s.label}, $stateText" + (s.time?.let { ", ${TimeFormat.clock(it)}" } ?: "")
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        s.label,
                        style = OrmoType.bodySmMedium,
                        color = when (s.state) { StepState.CURRENT -> c.textBrand; StepState.MISSED -> c.textDanger; else -> c.textTertiary },
                        textAlign = TextAlign.Center,
                    )
                    s.time?.let { Text(TimeFormat.clock(it), style = OrmoType.bodyXsMedium, color = c.textTertiary) }
                }
            }
        }
    }
}

/** 섹션 제목 (18 SemiBold) */
@Composable
fun OrderSectionTitle(text: String) {
    Text(text, style = OrmoType.bodyLgSemibold, color = Ormo.colors.textPrimary, modifier = Modifier.fillMaxWidth().padding(horizontal = Space.pageMargin, vertical = Space.s12).semantics { heading() })
}

/** 주문 내역: 메뉴 이름 + 'N개', 옵션 요약 (Figma 4.3.1) */
@Composable
fun OrderLinesBlock(order: Order, modifier: Modifier = Modifier) {
    val c = Ormo.colors
    // Figma: 항목 상자 위아래 8, 항목 사이 12 (이름 줄 24 + 8 + 옵션 줄 20)
    Column(modifier.fillMaxWidth().padding(horizontal = Space.pageMargin, vertical = Space.s8), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
        order.lines.forEach { l ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                // Figma 4.3.4: 이름(16 Semibold)과 'N개'(14 **Medium**)는 세로 가운데 맞춤, 사이 4
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.CenterVertically) {
                    Text(l.name, style = OrmoType.bodyMdSemibold, color = c.textPrimary)
                    Text("${l.qty}개", style = OrmoType.bodySmMedium, color = c.textSecondary)
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
        Text(label, style = OrmoType.bodyMdRegular, color = c.textTertiary) // Figma: 라벨 Text/Tertiary (C-15)
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
