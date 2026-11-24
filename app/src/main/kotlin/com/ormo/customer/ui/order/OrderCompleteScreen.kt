package com.ormo.customer.ui.order

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import com.ormo.customer.ui.theme.topInset
import com.ormo.customer.ui.auth.OrmoMark
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ormo.core.Outcome
import com.ormo.customer.AppContainer
import com.ormo.customer.ui.components.BottomCta
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.OrmoBottomSheet
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.Bell
import com.ormo.customer.ui.components.SheetHeaderAlign
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space
import com.ormo.data.orders.NotificationPromptStore
import com.ormo.data.orders.OrderRepository
import com.ormo.domain.model.OrderStatus
import com.ormo.domain.orders.NotificationPromptRules
import com.ormo.domain.orders.Order
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CompleteUi(val order: Order? = null, val isFirstOrder: Boolean = false, val dismissCount: Int = 0)

/** 주문 완료(4.1.1)의 화면 뒤: 주문을 불러와 픽업 번호·내 앞 대기를 채우고, 알림 권한 안내 시트를 띄울지 정한다 (4.5.1). */
class OrderCompleteViewModel(
    private val orderId: String,
    private val repo: OrderRepository,
    private val prompts: com.ormo.customer.session.PrefsNotificationPromptStore,
    private val accountKey: () -> String,
) : ViewModel() {
    private val _ui = MutableStateFlow(CompleteUi())
    val ui: StateFlow<CompleteUi> = _ui

    init {
        viewModelScope.launch {
            val first = prompts.recordOrderPlaced(accountKey())
            val dismissed = prompts.dismissCount(accountKey())
            _ui.update { it.copy(isFirstOrder = first, dismissCount = dismissed) }
            (repo.load(orderId) as? Outcome.Ok)?.let { r -> _ui.update { it.copy(order = r.value) } }
            repo.refreshActive() // 홈 주문 현황 바
        }
    }

    fun shouldAskNotification(osGranted: Boolean): Boolean =
        NotificationPromptRules.shouldShow(osGranted, _ui.value.dismissCount, _ui.value.isFirstOrder)

    fun recordDismiss() { viewModelScope.launch { prompts.recordDismissal(accountKey()) } }

    companion object {
        fun factory(c: AppContainer, orderId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = OrderCompleteViewModel(orderId, c.orders, c.notificationPrompts) { c.accountKey } as T
        }
    }
}

/**
 * 주문 완료 (디자인 4.1.1 / Figma): 마스코트(임시) + '픽업 번호' + 큰 번호, 매장·내 앞 대기·현재 상태 카드, [주문 상태 보기] / [추가 주문하기].
 * 첫 주문에서 [주문 상태 보기]를 누르면 알림 권한 안내 시트(4.5.1)가 먼저 뜬다.
 */
@Composable
fun OrderCompleteScreen(vm: OrderCompleteViewModel, onStatus: (orderId: String) -> Unit, onMore: () -> Unit, orderId: String) {
    val c = Ormo.colors
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onStatus(orderId) }

    fun osGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    val order = ui.order
    Box(Modifier.fillMaxSize().background(c.bgDefault)) {
        Column(Modifier.fillMaxSize()) {
            Column(
                // Figma 4.1.1: 심볼 상자 위가 y=117 (상태 표시줄 포함 좌표) — 맨 위부터 쌓는다
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.pageMargin).padding(top = topInset() + 67.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                OrmoMark(badge = com.ormo.customer.ui.auth.MarkBadge.Success)
                Text("픽업 번호", style = OrmoType.bodyXlRegular, color = c.textSecondary, modifier = Modifier.padding(top = 38.dp))
                Text(order?.pickupNumber?.toString() ?: "-", style = OrmoType.display2xlSemibold, color = c.textPrimary)
                if (order != null) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 28.dp).clip(RoundedCornerShape(20.dp)).background(c.bgLower).padding(Space.s20),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LabelValueRow("매장", order.storeName)
                        order.aheadCount?.let { LabelValueRow("내 앞 대기", "${it}건") }
                        LabelValueRow("현재 상태", statusText(order))
                    }
                }
            }
            BottomCta {
                OrmoButton("주문 상태 보기", {
                    if (vm.shouldAskNotification(osGranted())) showSheet = true else onStatus(orderId)
                }, Modifier.fillMaxWidth(), size = ButtonSize.XL)
                OrmoButton("추가 주문하기", onMore, Modifier.fillMaxWidth(), kind = ButtonKind.WeakBrand, size = ButtonSize.XL)
            }
        }
    }

    if (showSheet) {
        // 4.5.1 알림 권한 안내: [다음에] / [알림 받기]
        // Figma 4.5.1: 시트 위에서 제목 글자 +40, 안내 +74(제목과 6 간격), 종 +148(64), 버튼 +226, 아래 34
        OrmoBottomSheet(
            onDismissRequest = { showSheet = false },
            contentVerticalPadding = 0.dp,
            footerTopPadding = 14.dp,
            footerBottomPadding = 34.dp,
            footer = {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                    OrmoButton("다음에", { showSheet = false; vm.recordDismiss(); onStatus(orderId) }, Modifier.weight(1f), kind = ButtonKind.WeakNeutral, size = ButtonSize.XL)
                    OrmoButton("알림 받기", {
                        showSheet = false
                        if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else onStatus(orderId)
                    }, Modifier.weight(1f), size = ButtonSize.XL)
                }
            },
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(15.dp))
                Text(
                    NotificationPromptRules.TITLE,
                    style = OrmoType.bodyXlBold,
                    color = c.textStrong,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(NotificationPromptRules.BODY, style = OrmoType.bodyMdRegular, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                Box(Modifier.padding(top = 50.dp).size(64.dp).clip(CircleShape).background(c.fillBrandWeak), contentAlignment = Alignment.Center) {
                    Icon(OrmoIcons.Bell, null, tint = c.iconBrand, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

private fun statusText(o: Order): String = when (o.status) {
    OrderStatus.REQUESTED -> "매장에서 확인하고 있어요"
    OrderStatus.MAKING -> "음료를 만들고 있어요"
    OrderStatus.READY -> "음료가 준비됐어요"
    else -> "-"
}
