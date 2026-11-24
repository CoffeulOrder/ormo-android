package com.ormo.customer.ui.order

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ormo.core.Outcome
import com.ormo.customer.AppContainer
import com.ormo.customer.BuildConfig
import com.ormo.customer.fake.FakeOrderServer
import com.ormo.customer.ui.components.BottomCta
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.DialogTone
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoDialog
import com.ormo.customer.ui.components.OrmoSnackbarHost
import com.ormo.customer.ui.components.OrmoSnackbarState
import com.ormo.customer.ui.components.OrmoThickDivider
import com.ormo.customer.ui.components.OrmoTopNav
import com.ormo.customer.ui.components.SnackbarDurations
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space
import com.ormo.data.orders.CancelFailure
import com.ormo.data.orders.OrderRepository
import com.ormo.domain.model.OrderStatus
import com.ormo.domain.orders.CancelButton
import com.ormo.domain.orders.Order
import com.ormo.domain.orders.OrderAction
import com.ormo.domain.orders.OrderDetailRules
import com.ormo.domain.orders.OrderDetailView
import com.ormo.domain.orders.TimeFormat
import com.ormo.domain.payment.DialogText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailUi(
    val order: Order? = null,
    val failed: Boolean = false,
    val confirmCancel: Boolean = false,
    val cancelling: Boolean = false,
    /** 재주문 중 띄울 다이얼로그 (모두 품절 / 매장 변경 경고) */
    val reorderDialog: ReorderDialog? = null,
)

sealed interface ReorderDialog {
    data class Info(val text: DialogText) : ReorderDialog
    data class ConfirmClear(val order: Order, val plan: com.ormo.domain.orders.ReorderPlan, val count: Int) : ReorderDialog
}

sealed interface DetailEvent {
    data class Toast(val text: String) : DetailEvent
    data object GoCheckout : DetailEvent
}

/**
 * 주문 상세 (디자인 4.3) 의 화면 뒤: 보이는 동안 서버 상태를 5초마다 다시 받고(진행 중일 때만), 취소(4.4)·재주문을 맡는다.
 * 화면 전체가 **서버 상태 하나**([OrderDetailRules.view])로 정해진다.
 */
class OrderDetailViewModel(
    private val orderId: String,
    private val repo: OrderRepository,
    private val reorderer: Reorderer,
    val fake: FakeOrderServer?,
) : ViewModel() {
    private val _ui = MutableStateFlow(DetailUi())
    val ui: StateFlow<DetailUi> = _ui.asStateFlow()
    private val _events = Channel<DetailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var polling: kotlinx.coroutines.Job? = null

    init { startPolling() }

    private fun startPolling() {
        polling?.cancel()
        polling = viewModelScope.launch {
            repo.observe(orderId).collect { r ->
                when (r) {
                    is Outcome.Ok -> _ui.update { it.copy(order = r.value, failed = false) }
                    is Outcome.Fail -> _ui.update { if (it.order == null) it.copy(failed = true) else it }
                }
            }
        }
    }

    fun retry() { _ui.update { it.copy(failed = false) }; startPolling() }

    fun askCancel() { _ui.update { it.copy(confirmCancel = true) } }
    fun dismissCancel() { _ui.update { it.copy(confirmCancel = false) } }

    /** 4.4: [취소하기]. 그 사이 매장이 받았으면(409) 최신 상태로 바꾸고 '주문이 접수되어 취소할 수 없어요' */
    fun confirmCancel() {
        _ui.update { it.copy(confirmCancel = false, cancelling = true) }
        viewModelScope.launch {
            when (val r = repo.cancel(orderId)) {
                is Outcome.Ok -> {
                    _ui.update { it.copy(order = r.value, cancelling = false) }
                    _events.send(DetailEvent.Toast(OrderDetailRules.CANCELED_TOAST))
                }
                is Outcome.Fail -> {
                    val f = r.failure
                    _ui.update { it.copy(order = (f as? CancelFailure.NotCancelable)?.latest ?: it.order, cancelling = false) }
                    _events.send(
                        DetailEvent.Toast(
                            when (f) {
                                is CancelFailure.NotCancelable -> OrderDetailRules.NOT_CANCELABLE_TOAST
                                CancelFailure.Network -> "인터넷 연결을 확인해주세요"
                                CancelFailure.Other -> "잠시 후 다시 시도해주세요"
                            },
                        ),
                    )
                }
            }
        }
    }

    // ---- 재주문 ----
    fun reorder() {
        val o = _ui.value.order ?: return
        when (val p = reorderer.prepare(o)) {
            is Reorderer.Prep.Dialog -> _ui.update { it.copy(reorderDialog = ReorderDialog.Info(p.text)) }
            is Reorderer.Prep.ConfirmClear -> _ui.update { it.copy(reorderDialog = ReorderDialog.ConfirmClear(o, p.plan, p.count)) }
            is Reorderer.Prep.Ready -> viewModelScope.launch { reorderer.commit(o, p.plan, clearFirst = false); _events.send(DetailEvent.GoCheckout) }
        }
    }

    fun confirmReorderClear(d: ReorderDialog.ConfirmClear) {
        _ui.update { it.copy(reorderDialog = null) }
        viewModelScope.launch { reorderer.commit(d.order, d.plan, clearFirst = true); _events.send(DetailEvent.GoCheckout) }
    }

    fun dismissReorderDialog() { _ui.update { it.copy(reorderDialog = null) } }

    // ---- 개발용 상태 패널 (가짜 서버) ----
    fun panel(action: (FakeOrderServer) -> Unit) {
        val f = fake ?: return
        action(f)
        viewModelScope.launch {
            val r = repo.load(orderId)
            if (r is Outcome.Ok) _ui.update { it.copy(order = r.value) }
            // 끝났던 폴링을 다시 시작 (진행 중으로 돌아갈 일은 없지만 단계가 바뀌면 이어 받는다)
            if (r is Outcome.Ok && r.value.status.isActive) startPolling()
        }
    }

    companion object {
        fun factory(c: AppContainer, orderId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                OrderDetailViewModel(orderId, c.orders, c.reorderer, if (BuildConfig.USE_FAKE_BACKEND) c.fakeOrders else null) as T
        }
    }
}

/**
 * 주문 상세 (디자인 4.3.1~4.3.4): 헤더(상태 문구 + 매장·날짜) → 단계 바 → 주문 내역 → 결제 정보 → 하단 버튼.
 * 접수 대기: [주문 취소하기](켜짐) / 만드는 중: 꺼짐 / 완료: [재주문][영수증 보기] / 거절·매장 취소: [다른 메뉴 고르기] ...
 */
@Composable
fun OrderDetailScreen(
    vm: OrderDetailViewModel,
    onBack: () -> Unit,
    onReceipt: (orderId: String) -> Unit,
    onBrowseMenu: (storeId: String) -> Unit,
    onGoCheckout: () -> Unit,
) {
    val c = Ormo.colors
    val ui by vm.ui.collectAsState()
    val snackbar = remember { OrmoSnackbarState() }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is DetailEvent.Toast -> snackbar.show(e.text, OrderDetailRules.TOAST_MS)
                DetailEvent.GoCheckout -> onGoCheckout()
            }
        }
    }
    val order = ui.order

    Box(Modifier.fillMaxSize().background(c.bgDefault)) {
        Column(Modifier.fillMaxSize()) {
            OrmoTopNav(title = "주문 상세", onBack = onBack)
            when {
                order == null && ui.failed -> Column(
                    Modifier.weight(1f).fillMaxWidth().padding(Space.pageMargin),
                    verticalArrangement = Arrangement.spacedBy(Space.s8, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("주문 정보를 불러오지 못했어요", style = OrmoType.bodyXlBold, color = c.textPrimary, modifier = Modifier.semantics { heading() })
                    OrmoButton("다시 시도", vm::retry, Modifier.padding(top = Space.s16), kind = ButtonKind.WeakNeutral, size = ButtonSize.M)
                }
                order == null -> Box(Modifier.weight(1f))
                else -> {
                    val view = OrderDetailRules.view(order)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        DetailHeader(order, view)
                        // Figma: 구분 띠(12) 뒤 12 여백, 섹션 사이도 앞뒤 12
                        OrmoThickDivider()
                        Spacer(Modifier.height(Space.s12))
                        OrderSectionTitle("주문 내역")
                        OrderLinesBlock(order)
                        Spacer(Modifier.height(Space.s12))
                        OrmoThickDivider()
                        Spacer(Modifier.height(Space.s12))
                        OrderSectionTitle("결제 정보")
                        PaymentInfoBlock(order)
                        Spacer(Modifier.height(Space.s24))
                        if (vm.fake != null) DevPanel(order, vm)
                    }
                    DetailBottom(
                        view = view,
                        cancelling = ui.cancelling,
                        onCancel = vm::askCancel,
                        onReorder = vm::reorder,
                        onReceipt = { onReceipt(order.id) },
                        onBrowse = { onBrowseMenu(order.storeId) },
                    )
                }
            }
        }
        OrmoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter), bottomOffset = 120.dp)
    }

    if (ui.confirmCancel) {
        // 4.4.1: [닫기] / [취소하기](위험)
        OrmoDialog(
            title = OrderDetailRules.CANCEL_CONFIRM.title,
            body = OrderDetailRules.CANCEL_CONFIRM.body,
            confirmLabel = "취소하기",
            onConfirm = vm::confirmCancel,
            dismissLabel = "닫기",
            onDismiss = vm::dismissCancel,
            tone = DialogTone.Critical,
        )
    }
    when (val d = ui.reorderDialog) {
        is ReorderDialog.Info -> OrmoDialog(title = d.text.title, body = d.text.body, confirmLabel = "확인", onConfirm = vm::dismissReorderDialog)
        is ReorderDialog.ConfirmClear -> OrmoDialog(
            title = "매장을 변경하면 장바구니의 메뉴가 삭제돼요",
            body = "장바구니에 ${d.count}개의 메뉴가 있어요",
            confirmLabel = "주문하기",
            onConfirm = { vm.confirmReorderClear(d) },
            dismissLabel = "닫기",
            onDismiss = vm::dismissReorderDialog,
        )
        null -> Unit
    }
}

@Composable
private fun DetailHeader(order: Order, view: OrderDetailView) {
    val c = Ormo.colors
    // Figma 4.3.1: 제목 글자 y=130(내비 아래 24) · 메타(16 Regular) 제목 아래 8 · 아래 24 → 단계 막대 y=218
    Column(Modifier.fillMaxWidth().padding(start = Space.pageMargin, end = Space.pageMargin, top = 24.dp), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        // 상태가 바뀌면(5초마다 새로고침) TalkBack 이 새 문구를 읽어 준다
        Text(view.headline.replace("매장 사정으로 ", "매장 사정으로\n"), style = OrmoType.displayXsSemibold, color = c.textPrimary, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
        Text("${order.storeName} · ${TimeFormat.dateDowClock(order.placedAt)}", style = OrmoType.bodyMdRegular, color = c.textSecondary)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.pageMargin).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // Figma 4.3.x 에는 큰 픽업 번호와 '○○:○○까지 받아주세요' 상자가 없다 (제목에 번호가 이미 있음) → 표시하지 않는다.
        // 값(view.bigPickupNumber · view.deadlineBox)은 규칙에 그대로 있다 — 다시 보이려면 여기만 되살리면 된다.
        // Figma: 매장 취소·거절 화면에는 사유 상자도 안내 상자도 없다. 그 밖의 안내는 회색 상자에 ⓘ 아이콘 + 14.
        // (사유 view.reasonBox 는 규칙에 남아 있다 — 되살리려면 여기에 Notice(it, danger = true))
        if (view.reasonBox == null) view.infoBox?.let { InfoNotice(it) }
        view.steps?.let { StepBar(it) }
    }
    Spacer(Modifier.height(Space.s24))
}

/** 안내 상자 (Figma 4.3.5~): 연한 회색 라운드 + ⓘ 16 + 14 Medium 글자 */
@Composable
private fun InfoNotice(text: String) {
    val c = Ormo.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.fillNeutralWeak).padding(horizontal = Space.s16, vertical = Space.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        Icon(com.ormo.customer.ui.components.FigmaIcons.Info, null, tint = c.iconSecondary, modifier = Modifier.size(20.dp))
        Text(text, style = OrmoType.bodySmMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Notice(text: String, brand: Boolean = false, danger: Boolean = false) {
    val c = Ormo.colors
    val bg = when { brand -> c.fillBrandWeak; danger -> c.fillDangerWeak; else -> c.fillNeutralWeak }
    val fg = when { brand -> c.textBrand; danger -> c.textDanger; else -> c.textSecondary }
    Text(
        text,
        style = if (brand) OrmoType.bodyMdSemibold else OrmoType.bodyMdMedium,
        color = fg,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(Space.s16),
    )
}

@Composable
private fun DetailBottom(
    view: OrderDetailView,
    cancelling: Boolean,
    onCancel: () -> Unit,
    onReorder: () -> Unit,
    onReceipt: () -> Unit,
    onBrowse: () -> Unit,
) {
    if (view.cancel == CancelButton.HIDDEN && view.actions.isEmpty()) return
    BottomCta {
        when (view.cancel) {
            CancelButton.ENABLED -> OrmoButton("주문 취소하기", onCancel, Modifier.fillMaxWidth(), kind = ButtonKind.WeakCritical, size = ButtonSize.XL, loading = cancelling)
            CancelButton.DISABLED -> OrmoButton("주문 취소하기", {}, Modifier.fillMaxWidth(), kind = ButtonKind.WeakCritical, size = ButtonSize.XL, enabled = false)
            CancelButton.HIDDEN -> Unit
        }
        if (view.actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                view.actions.forEach { a ->
                    when (a) {
                        OrderAction.REORDER -> OrmoButton("재주문", onReorder, Modifier.weight(1f), kind = ButtonKind.WeakBrand, size = ButtonSize.XL)
                        OrderAction.VIEW_RECEIPT -> OrmoButton("영수증 보기", onReceipt, Modifier.weight(2f), size = ButtonSize.XL)
                        OrderAction.PICK_OTHER_MENU -> OrmoButton("다른 메뉴 고르기", onBrowse, Modifier.weight(1f), size = ButtonSize.XL)
                    }
                }
            }
        }
    }
}

/** 개발용 상태 패널: 매장 쪽에서 일어나는 일을 대신 일으켜 모든 상태 화면을 눌러 본다 (가짜 서버일 때만, M8 에서 삭제) */
@Composable
private fun DevPanel(order: Order, vm: OrderDetailViewModel) {
    val c = Ormo.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.pageMargin).padding(bottom = Space.s24)
            .clip(RoundedCornerShape(12.dp)).background(c.bgLower).padding(Space.s16),
        verticalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        Text("개발용 상태 패널 (${order.status.name})", style = OrmoType.bodySmMedium, color = c.textTertiary)
        val id = order.id
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
            OrmoButton("다음 단계", { vm.panel { it.advance(id) } }, Modifier.weight(1f), kind = ButtonKind.WeakBrand, size = ButtonSize.S, enabled = order.status.isActive)
            OrmoButton("거절", { vm.panel { it.reject(id, auto = false) } }, Modifier.weight(1f), kind = ButtonKind.WeakNeutral, size = ButtonSize.S, enabled = order.status == OrderStatus.REQUESTED)
            OrmoButton("자동 거절", { vm.panel { it.reject(id, auto = true) } }, Modifier.weight(1f), kind = ButtonKind.WeakNeutral, size = ButtonSize.S, enabled = order.status == OrderStatus.REQUESTED)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
            OrmoButton("매장 취소", { vm.panel { it.cancelByStore(id) } }, Modifier.weight(1f), kind = ButtonKind.WeakNeutral, size = ButtonSize.S, enabled = order.status.isActive)
            OrmoButton("미수령", { vm.panel { it.unclaimed(id) } }, Modifier.weight(1f), kind = ButtonKind.WeakNeutral, size = ButtonSize.S, enabled = order.status == OrderStatus.READY)
            OrmoButton("환불", { vm.panel { it.refund(id) } }, Modifier.weight(1f), kind = ButtonKind.WeakNeutral, size = ButtonSize.S, enabled = order.status == OrderStatus.COMPLETED || order.status == OrderStatus.UNCLAIMED)
        }
    }
}
