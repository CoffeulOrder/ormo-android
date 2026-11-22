package com.ormo.customer.ui.order

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.ormo.customer.ui.auth.OrmoLogo
import com.ormo.customer.ui.components.BottomCta
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space

/**
 * 주문 완료 (디자인 4.1.1) **임시 화면**: 결제가 끝나 주문이 접수됐다는 것만 보여준다.
 * 픽업 번호·내 앞 N건·[주문 상태 보기]·알림 권한 시트·주문 현황 바는 M5 화면 단계에서 만든다.
 */
@Composable
fun OrderCompleteScreen(orderId: String, onMore: () -> Unit) {
    val c = Ormo.colors
    Column(Modifier.fillMaxSize().background(c.bgDefault)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(Space.pageMargin),
            verticalArrangement = Arrangement.spacedBy(Space.s8, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OrmoLogo()
            Text("주문이 접수됐어요", style = OrmoType.displayXsSemibold, color = c.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.s16))
            Text("주문 번호 $orderId", style = OrmoType.bodyMdRegular, color = c.textSecondary)
            Text("주문 상태 화면은 다음 단계(M5)에서 만들어요", style = OrmoType.bodySmRegular, color = c.textTertiary)
        }
        BottomCta { OrmoButton("추가 주문하기", onMore, Modifier.fillMaxWidth(), size = ButtonSize.XL) }
    }
}
