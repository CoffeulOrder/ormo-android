package com.ormo.customer.ui.order

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ormo.customer.ui.theme.ormoStatusBarsPadding
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ormo.core.Outcome
import com.ormo.core.formatWon
import com.ormo.customer.AppContainer
import com.ormo.customer.ui.components.OrmoDivider
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space
import com.ormo.domain.orders.Order
import com.ormo.domain.orders.TimeFormat
import com.ormo.domain.rules.OptionRules
import com.ormo.domain.store.StoreInfoRules

/**
 * 영수증 (디자인 4.3.6 / Figma): 매장, '27번 | 3,000원', 메뉴·단가·수량·금액 표, 주문·할인·결제 금액, 결제 수단·카드 번호·승인 번호·승인 일시, 판매자 정보.
 * 값은 서버가 준 그대로 보여준다 (카드 번호는 서버가 마스킹). 취소 주문은 '취소 영수증'.
 */
@Composable
fun ReceiptScreen(container: AppContainer, orderId: String, onClose: () -> Unit) {
    val c = Ormo.colors
    val order by produceState<Order?>(null, orderId) { value = (container.orders.load(orderId) as? Outcome.Ok)?.value }
    val seller by produceState<List<com.ormo.domain.store.InfoLine>?>(null, order?.storeId) {
        val id = order?.storeId ?: return@produceState
        value = (container.stores.loadInfo(id) as? Outcome.Ok)?.value?.let { StoreInfoRules.sellerLines(it.seller) }
    }
    Column(Modifier.fillMaxSize().background(c.bgDefault)) {
        // Figma 4.3.6: 닫기(X) · '영수증' · 저장(다운로드) 아이콘
        Row(Modifier.fillMaxWidth().ormoStatusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = "닫기", onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(OrmoIcons.Close, "닫기", tint = c.iconPrimary, modifier = Modifier.size(24.dp))
            }
            Text("영수증", style = OrmoType.bodyLgSemibold, color = c.textStrong, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            // 영수증 저장은 아직 연결되지 않았다 (파일 저장 기능 필요) → 모양만 두고 누를 수 없게 한다
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(OrmoIcons.Download, null, tint = c.iconSecondary, modifier = Modifier.size(24.dp))
            }
        }
        val o = order
        if (o != null) {
            val won = { n: Int -> n.toString().reversed().chunked(3).joinToString(",").reversed() }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.pageMargin).padding(bottom = Space.s32)) {
                Text(o.storeName, style = OrmoType.bodyMdRegular, color = c.textSecondary, modifier = Modifier.padding(top = 22.dp))
                Text("${o.pickupNumber?.let { "${it}번" } ?: "-"} | ${formatWon(o.totalAmount)}", style = OrmoType.displayXsSemibold, color = c.textPrimary, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).background(c.textStrong))
                // 표 머리: 메뉴명 · 단가 · 수량 · 금액
                Row(Modifier.fillMaxWidth().padding(vertical = Space.s12)) {
                    Text("메뉴명", style = OrmoType.bodySmRegular, color = c.textSecondary, modifier = Modifier.weight(1f))
                    Text("단가", style = OrmoType.bodySmRegular, color = c.textSecondary, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                    Text("수량", style = OrmoType.bodySmRegular, color = c.textSecondary, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
                    Text("금액", style = OrmoType.bodySmRegular, color = c.textSecondary, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                }
                OrmoDivider()
                Column(Modifier.padding(vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
                    o.lines.forEach { l ->
                        Column {
                            Row(Modifier.fillMaxWidth()) {
                                Text(l.name, style = OrmoType.bodyMdSemibold, color = c.textPrimary, modifier = Modifier.weight(1f))
                                // 단가 = 줄 금액 ÷ 수량 (옵션 가격 포함 — 서버가 옵션별 금액을 주면 줄을 나눈다)
                                Text(won(if (l.qty > 0) l.amount / l.qty else l.amount), style = OrmoType.bodyMdRegular, color = c.textPrimary, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                                Text(l.qty.toString(), style = OrmoType.bodyMdRegular, color = c.textPrimary, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
                                Text(won(l.amount), style = OrmoType.bodyMdRegular, color = c.textPrimary, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                            }
                            if (l.optionSummary.isNotBlank()) Text(l.optionSummary.replace(" | ", " / "), style = OrmoType.bodySmRegular, color = c.textSecondary)
                        }
                    }
                }
                OrmoDivider()
                Column(Modifier.padding(vertical = Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                    ReceiptRow("주문 금액", won(o.totalAmount), dim = true)
                    ReceiptRow("할인 금액", "0", dim = true)
                    Spacer(Modifier.height(12.dp))
                    ReceiptRow("결제 금액", won(o.totalAmount), bold = true)
                }
                OrmoDivider()
                Column(Modifier.padding(vertical = Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                    o.paymentMethod?.let { ReceiptRow("결제 수단", it) }
                    o.cardMasked?.let { ReceiptRow("카드 번호", it) }
                    o.approvalNumber?.let { ReceiptRow("승인 번호", it) }
                    ReceiptRow("승인 일시", TimeFormat.fullDateTime(o.placedAt))
                }
                OrmoDivider()
                Column(Modifier.padding(top = Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                    // Figma 순서: 상호 · 대표자 · 사업자등록번호 · 대표 번호 · 주소
                    seller?.filter { it.label != "통신판매업 신고번호" }?.sortedBy { if (it.label == "주소") 1 else 0 }?.forEach { ReceiptRow(if (it.label == "연락처") "대표 번호" else it.label, it.value) }
                }
            }
        } else {
            Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReceiptRow(label: String, value: String, bold: Boolean = false, dim: Boolean = false) {
    val c = Ormo.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = if (bold) OrmoType.bodyMdBold else OrmoType.bodySmRegular, color = if (bold) c.textStrong else c.textSecondary)
        Text(
            value,
            style = if (bold) OrmoType.bodyMdBold else OrmoType.bodySmRegular,
            color = if (bold) c.textStrong else if (dim) c.textSecondary else c.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = Space.s16),
        )
    }
}
