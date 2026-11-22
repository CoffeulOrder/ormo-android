package com.ormo.customer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space

/** 로그인 뒤 도착하는 임시 화면. 매장 선택·메뉴 홈(M3)이 만들어지면 교체한다. */
@Composable
fun HomePlaceholder(name: String?, onLogout: () -> Unit, onCatalog: (() -> Unit)? = null, onOrders: (() -> Unit)? = null) {
    val c = Ormo.colors
    Column(
        Modifier.fillMaxSize().background(c.bgDefault).padding(Space.pageMargin),
        verticalArrangement = Arrangement.spacedBy(Space.s12, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${name ?: "고객"}님, 환영해요", style = OrmoType.displayXsSemibold, color = c.textPrimary, textAlign = TextAlign.Center)
        Text("내 정보는 다음 단계(M6)에서 만들어요", style = OrmoType.bodyMdRegular, color = c.textSecondary)
        if (onOrders != null) OrmoButton("주문 내역", onOrders)
        OrmoButton("로그아웃", onLogout, kind = ButtonKind.WeakNeutral)
        if (onCatalog != null) OrmoButton("컴포넌트 카탈로그 (개발용)", onCatalog, kind = ButtonKind.Ghost)
    }
}
