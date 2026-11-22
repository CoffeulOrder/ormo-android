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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = Space.s8), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable(role = Role.Button, onClickLabel = "닫기", onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(OrmoIcons.Close, "닫기", tint = c.iconPrimary, modifier = Modifier.size(24.dp))
            }
            Text("영수증", style = OrmoType.bodyLgSemibold, color = c.textStrong, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Box(Modifier.size(40.dp))
        }
        val o = order
        if (o != null) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.pageMargin).padding(bottom = Space.s32)) {
                Text(o.storeName, style = OrmoType.bodyLgRegular, color = c.textSecondary, modifier = Modifier.padding(top = Space.s16))
                Text("${o.pickupNumber?.let { "${it}번" } ?: "-"} | ${formatWon(o.totalAmount)}", style = OrmoType.displayXsSemibold, color = c.textPrimary, modifier = Modifier.padding(top = Space.s4, bottom = Space.s16))
                Box(Modifier.fillMaxWidth().height(2.dp).background(c.textPrimary))
                Row(Modifier.fillMaxWidth().padding(vertical = Space.s12)) {
                    Text("메뉴명", style = OrmoType.bodySmMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
                    Text("수량", style = OrmoType.bodySmMedium, color = c.textSecondary, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
                    Text("금액", style = OrmoType.bodySmMedium, color = c.textSecondary, textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
                }
                OrmoDivider()
                o.lines.forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(top = Space.s16)) {
                        Column(Modifier.weight(1f)) {
                            Text(l.name, style = OrmoType.bodyMdSemibold, color = c.textPrimary)
                            if (l.optionSummary.isNotBlank()) Text(l.optionSummary.replace(" | ", " / "), style = OrmoType.bodySmRegular, color = c.textSecondary)
                        }
                        Text(l.qty.toString(), style = OrmoType.bodyMdRegular, color = c.textPrimary, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
                        Text(l.amount.toString().reversed().chunked(3).joinToString(",").reversed(), style = OrmoType.bodyMdRegular, color = c.textPrimary, textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
                    }
                }
                OrmoDivider(Modifier.padding(top = Space.s16))
                Column(Modifier.padding(vertical = Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                    ReceiptRow("주문 금액", o.totalAmount.toString().reversed().chunked(3).joinToString(",").reversed())
                    ReceiptRow("할인 금액", "0")
                    ReceiptRow("결제 금액", o.totalAmount.toString().reversed().chunked(3).joinToString(",").reversed(), bold = true)
                }
                OrmoDivider()
                Column(Modifier.padding(vertical = Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                    o.paymentMethod?.let { ReceiptRow("결제 수단", it) }
                    o.cardMasked?.let { ReceiptRow("카드 번호", it) }
                    o.approvalNumber?.let { ReceiptRow("승인 번호", it) }
                    ReceiptRow("승인 일시", TimeFormat.fullDateTime(o.placedAt))
                }
                OrmoDivider()
                Column(Modifier.padding(top = Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                    seller?.filter { it.label != "통신판매업 신고번호" }?.forEach { ReceiptRow(it.label, it.value) }
                }
            }
        } else {
            Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReceiptRow(label: String, value: String, bold: Boolean = false) {
    val c = Ormo.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = if (bold) OrmoType.bodyMdSemibold else OrmoType.bodySmRegular, color = if (bold) c.textPrimary else c.textSecondary)
        Text(value, style = if (bold) OrmoType.bodyLgBold else OrmoType.bodySmRegular, color = c.textPrimary, textAlign = TextAlign.End, modifier = Modifier.padding(start = Space.s16))
    }
}
