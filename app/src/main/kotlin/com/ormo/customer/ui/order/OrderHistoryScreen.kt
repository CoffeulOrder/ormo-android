package com.ormo.customer.ui.order

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ormo.core.formatWon
import com.ormo.customer.AppContainer
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.Cup
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoDialog
import com.ormo.customer.ui.components.OrmoDivider
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.OrmoSnackbarHost
import com.ormo.customer.ui.components.OrmoSnackbarState
import com.ormo.customer.ui.components.OrmoThickDivider
import com.ormo.customer.ui.components.OrmoTopNav
import com.ormo.customer.ui.components.SnackbarDurations
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space
import com.ormo.data.orders.OrderRepository
import com.ormo.domain.orders.HistoryAction
import com.ormo.domain.orders.HistoryState
import com.ormo.domain.orders.Order
import com.ormo.domain.orders.OrderHistoryRules
import com.ormo.domain.orders.TimeFormat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 내 주문 (4.2.1) 의 화면 뒤: 지난 주문을 20개씩 이어 불러오고, [재주문하기]를 맡는다. */
class OrderHistoryViewModel(
    private val repo: OrderRepository,
    private val reorderer: Reorderer,
) : ViewModel() {
    val history: StateFlow<HistoryState> = repo.history

    private val _dialog = MutableStateFlow<ReorderDialog?>(null)
    val dialog: StateFlow<ReorderDialog?> = _dialog

    private val _goCheckout = Channel<Unit>(Channel.BUFFERED)
    val goCheckout = _goCheckout.receiveAsFlow()

    init { refresh() }

    fun refresh() { viewModelScope.launch { repo.loadFirstPage() } }
    fun loadMore() { viewModelScope.launch { repo.loadMore() } }

    fun reorder(o: Order) {
        when (val p = reorderer.prepare(o)) {
            is Reorderer.Prep.Dialog -> _dialog.value = ReorderDialog.Info(p.text)
            is Reorderer.Prep.ConfirmClear -> _dialog.value = ReorderDialog.ConfirmClear(o, p.plan, p.count)
            is Reorderer.Prep.Ready -> viewModelScope.launch { reorderer.commit(o, p.plan, false); _goCheckout.send(Unit) }
        }
    }

    fun confirmClear(d: ReorderDialog.ConfirmClear) {
        _dialog.value = null
        viewModelScope.launch { reorderer.commit(d.order, d.plan, true); _goCheckout.send(Unit) }
    }

    fun dismissDialog() { _dialog.value = null }

    companion object {
        fun factory(c: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = OrderHistoryViewModel(c.orders, c.reorderer) as T
        }
    }
}

/**
 * 내 주문 (디자인 4.2.1 / Figma '주문 내역'): 지난 주문 카드(매장 · 날짜, 메뉴, 금액 + 상태, 버튼), 카드 사이 굵은 구분선, 빈 상태 [메뉴 보기].
 * 취소·거절·환불은 금액에 취소선 + [메뉴 보러 가기], 그 밖은 [재주문하기].
 */
@Composable
fun OrderHistoryScreen(
    vm: OrderHistoryViewModel,
    onBack: () -> Unit,
    onOrder: (orderId: String) -> Unit,
    onBrowseMenu: () -> Unit,
    onGoCheckout: () -> Unit,
) {
    val c = Ormo.colors
    val state by vm.history.collectAsState()
    val dialog by vm.dialog.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { vm.goCheckout.collect { onGoCheckout() } }
    // 끝에 가까워지면 다음 20개
    LaunchedEffect(list, state.orders.size) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= state.orders.size - 2 && state.canLoadMore && state.loadedOnce) vm.loadMore()
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgDefault)) {
        OrmoTopNav(title = "주문 내역", onBack = onBack)
        when {
            state.isEmpty -> Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(60.dp).clip(CircleShape).background(c.bgLower), contentAlignment = Alignment.Center) {
                    Icon(OrmoIcons.Cup, null, tint = c.iconSecondary, modifier = Modifier.size(28.dp))
                }
                Text(OrderHistoryRules.EMPTY_TITLE, style = OrmoType.bodyLgSemibold, color = c.textPrimary, modifier = Modifier.padding(top = Space.s16))
                OrmoButton("메뉴 보기", onBrowseMenu, Modifier.padding(top = Space.s16), size = ButtonSize.S)
            }
            state.failed && state.orders.isEmpty() -> Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("주문 내역을 불러오지 못했어요", style = OrmoType.bodyLgSemibold, color = c.textPrimary)
                OrmoButton("다시 시도", vm::refresh, Modifier.padding(top = Space.s16), kind = ButtonKind.WeakNeutral, size = ButtonSize.M)
            }
            else -> LazyColumn(Modifier.weight(1f), state = list) {
                items(state.orders, key = { it.id }) { order ->
                    HistoryCard(order, onOpen = { onOrder(order.id) }, onReorder = { vm.reorder(order) }, onBrowse = onBrowseMenu)
                    OrmoThickDivider()
                }
            }
        }
    }

    when (val d = dialog) {
        is ReorderDialog.Info -> OrmoDialog(title = d.text.title, body = d.text.body, confirmLabel = "확인", onConfirm = vm::dismissDialog)
        is ReorderDialog.ConfirmClear -> OrmoDialog(
            title = "매장을 변경하면 장바구니의 메뉴가 삭제돼요",
            body = "장바구니에 ${d.count}개의 메뉴가 있어요",
            confirmLabel = "주문하기",
            onConfirm = { vm.confirmClear(d) },
            dismissLabel = "닫기",
            onDismiss = vm::dismissDialog,
        )
        null -> Unit
    }
}

@Composable
private fun HistoryCard(order: Order, onOpen: () -> Unit, onReorder: () -> Unit, onBrowse: () -> Unit) {
    val c = Ormo.colors
    val card = OrderHistoryRules.card(order)
    // Figma 4.2.1: 머리 글자 y=135, 이름 +36, 옵션 +30, 구분선 +33, 금액 +22, 버튼 위 291(높이 48), 카드 아래 34
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 34.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "주문 상세", onClick = onOpen).padding(horizontal = Space.pageMargin, vertical = Space.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${order.storeName} · ${TimeFormat.dateDow(order.placedAt)}", style = OrmoType.bodySmRegular, color = c.textSecondary, modifier = Modifier.weight(1f))
            Icon(OrmoIcons.ChevronRight, null, tint = c.iconTertiary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.pageMargin).padding(top = 11.dp), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
            OrderLinesBlockInline(order)
        }
        OrmoDivider(Modifier.padding(top = 17.dp), inset = true)
        Row(Modifier.fillMaxWidth().padding(start = Space.pageMargin, end = Space.pageMargin, top = 16.dp, bottom = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                card.amountLabel,
                style = OrmoType.bodyMdSemibold, // Figma: 16
                color = if (card.strikeAmount) c.textTertiary else c.textPrimary,
                textDecoration = if (card.strikeAmount) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f),
            )
            Text(card.statusLabel, style = OrmoType.bodySmMedium, color = c.textSecondary)
        }
        OrmoButton(
            if (card.primary == HistoryAction.REORDER) "재주문하기" else "메뉴 보러 가기",
            if (card.primary == HistoryAction.REORDER) onReorder else onBrowse,
            Modifier.fillMaxWidth().padding(horizontal = Space.pageMargin),
            size = ButtonSize.L,
        )
    }
}

@Composable
private fun OrderLinesBlockInline(order: Order) {
    val c = Ormo.colors
    order.lines.forEach { l ->
        Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.Bottom) {
                Text(l.name, style = OrmoType.bodyMdSemibold, color = c.textPrimary)
                Text("${l.qty}개", style = OrmoType.bodySmRegular, color = c.textSecondary)
            }
            if (l.optionSummary.isNotBlank()) Text(l.optionSummary, style = OrmoType.bodySmRegular, color = c.textSecondary)
        }
    }
}
