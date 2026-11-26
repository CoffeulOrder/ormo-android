package com.ormo.customer.ui.order

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ormo.core.Outcome
import com.ormo.core.formatWon
import com.ormo.customer.AppContainer
import com.ormo.customer.CartHolder
import com.ormo.customer.ToastBus
import com.ormo.customer.ui.components.BadgeKind
import com.ormo.customer.ui.components.BottomCta
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.OrmoBadge
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoCheckCircle
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.OrmoRadio
import com.ormo.customer.ui.components.OrmoSnackbarHost
import com.ormo.customer.ui.components.OrmoSnackbarState
import com.ormo.customer.ui.components.OrmoStepper
import com.ormo.customer.ui.components.OrmoThickDivider
import com.ormo.customer.ui.components.OrmoTopNav
import com.ormo.customer.ui.components.SnackbarDurations
import com.ormo.customer.ui.menu.badgeLabel
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space
import com.ormo.data.menu.MenuRepository
import com.ormo.data.store.StoreRepository
import com.ormo.domain.home.MenuHomeRules
import com.ormo.domain.model.MenuItem
import com.ormo.domain.model.OptionGroup
import com.ormo.domain.order.OptionNotice
import com.ormo.domain.order.OptionScreenState
import com.ormo.domain.rules.CartLimit
import com.ormo.domain.store.OrderAvailability
import com.ormo.domain.store.StoreRules
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

data class OptionUi(
    val state: OptionScreenState? = null,
    val availability: OrderAvailability = OrderAvailability.Available,
)

sealed interface OptionEvent {
    data class Notice(val text: String) : OptionEvent
    data class Added(val goCheckout: Boolean) : OptionEvent
}

/**
 * 옵션 선택 (디자인 2.2) 의 화면 뒤: 메뉴 하나의 옵션·수량 상태([OptionScreenState])를 들고, 담기를 장바구니에 넘긴다.
 * 메뉴는 현재 메뉴(품절 반영)에서 찾고, 메뉴가 갱신되면 선택 상태는 그대로 두고 메뉴만 바꾼다.
 */
class OptionViewModel(
    private val storeId: String,
    private val menuId: String,
    private val menus: MenuRepository,
    private val stores: StoreRepository,
    private val cart: CartHolder,
    private val toasts: ToastBus,
) : ViewModel() {
    private val _ui = MutableStateFlow(OptionUi())
    val ui: StateFlow<OptionUi> = _ui.asStateFlow()

    private val _events = Channel<OptionEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(menus.state, menus.soldOut) { state, sold ->
                val items = state.payload?.cafes?.firstOrNull { it.id == storeId }?.menus.orEmpty()
                MenuHomeRules.applySoldOut(items, sold[storeId].orEmpty()).firstOrNull { it.id == menuId }
            }.collect { item ->
                if (item != null) _ui.update { it.copy(state = it.state?.copy(item = item) ?: OptionScreenState.start(item)) }
            }
        }
        viewModelScope.launch {
            val store = (stores.loadStores() as? Outcome.Ok)?.value?.all?.firstOrNull { it.id == storeId }
            if (store != null) _ui.update { it.copy(availability = StoreRules.availability(store.status, LocalDateTime.now())) }
        }
    }

    private fun edit(f: (OptionScreenState) -> com.ormo.domain.order.OptionEdit) {
        val cur = _ui.value.state ?: return
        val r = f(cur)
        _ui.update { it.copy(state = r.state) }
        r.notice?.let { viewModelScope.launch { _events.send(OptionEvent.Notice(it.text)) } }
    }

    fun toggle(group: OptionGroup, choiceId: String) = edit { it.toggle(group.id, choiceId) }
    fun increase() = edit { it.increase() }
    fun decrease() = edit { it.decrease() }
    fun notifyQtyLimit() { viewModelScope.launch { _events.send(OptionEvent.Notice(OptionNotice.QtyLimit.text)) } }

    /** [담기]/[바로 주문]. 같은 메뉴·옵션이 이미 있어 합계가 20개를 넘으면 담기지 않고 한도 안내. */
    fun add(goCheckout: Boolean) {
        val s = _ui.value.state ?: return
        if (!s.canAdd || _ui.value.availability !is OrderAvailability.Available) return
        val change = cart.add(storeId, s.item, s.choiceIds, s.qty)
        viewModelScope.launch {
            when (change.limit) {
                null -> {
                    if (!goCheckout) toasts.post("장바구니에 메뉴를 추가했어요") // 담기 완료 스낵바 2초 (2.2.2)
                    _events.send(OptionEvent.Added(goCheckout))
                }
                CartLimit.QTY_EXCEEDED -> _events.send(OptionEvent.Notice(OptionNotice.QtyLimit.text))
                CartLimit.TOO_MANY_LINES -> _events.send(OptionEvent.Notice("장바구니에는 메뉴를 20개까지 담을 수 있어요"))
            }
        }
    }

    companion object {
        fun factory(c: AppContainer, storeId: String, menuId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = OptionViewModel(storeId, menuId, c.menus, c.stores, c.cart, c.toasts) as T
        }
    }
}

/**
 * 옵션 선택 전체 화면 (디자인 2.2 / Figma 2.2.1): 상단 [뒤로 · 검색 · 내 정보], 메뉴 이름·설명·가격 + **수량 알약(−/+)**,
 * 옵션 그룹(필수는 기본값을 미리 고르고 해제 불가, 단일 선택 = 라디오 · 복수 선택 = 원형 체크, 최대 개수 안내 스낵바),
 * 품절 옵션은 흐리게 + '품절', 추가금이 없으면 '+0원', 수량 1~20, **버튼 금액은 (기본가+옵션)×수량으로 실시간**.
 * [담기] → 스낵바 / [바로 주문] → 장바구니·주문서. 주문 불가 매장에서는 둘 다 꺼진다.
 */
@Composable
fun OptionScreen(
    vm: OptionViewModel,
    onBack: () -> Unit,
    onAdded: (goCheckout: Boolean) -> Unit,
    onSearch: () -> Unit = {},
    onMyInfo: () -> Unit = {},
) {
    val c = Ormo.colors
    val ui by vm.ui.collectAsState()
    val snackbar = remember { OrmoSnackbarState() }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is OptionEvent.Notice -> snackbar.show(e.text, SnackbarDurations.PARTIAL_FAILURE)
                is OptionEvent.Added -> onAdded(e.goCheckout)
            }
        }
    }
    val s = ui.state
    val unavailable = ui.availability as? OrderAvailability.Unavailable
    // 하단 CTA 는 화면 위에 떠 있다 — 스크롤 내용은 화면 끝까지 이어지고 끝에 CTA 높이만큼만 비운다
    var ctaHeight by remember { androidx.compose.runtime.mutableStateOf(0.dp) }

    Box(Modifier.fillMaxSize().background(c.bgDefault)) {
        Column(Modifier.fillMaxSize()) {
            OrmoTopNav(onBack = onBack, actions = {
                // 터치 영역 48 두 개가 8 겹쳐, 아이콘 중심 간격은 Figma 의 40
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                    NavAction(OrmoIcons.Search, "검색", onSearch)
                    NavAction(OrmoIcons.Person, "내 정보", onMyInfo)
                }
            })
            if (s == null) {
                Box(Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = Space.pageMargin, vertical = Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                        Text(s.item.name, style = OrmoType.bodyXlSemibold, color = c.textPrimary)
                        if (s.item.desc.isNotBlank()) Text(s.item.desc, style = OrmoType.bodyMdMedium, color = c.textSecondary)
                        // 영업 중이 아닐 때의 '○○:○○부터 주문할 수 있어요' 안내는 옵션 화면 디자인(2.2.1)에 없어 보이지 않는다 (담기·바로 주문 버튼만 꺼진다)
                        if (unavailable == null && s.soldOut) {
                            Text("품절된 메뉴예요", style = OrmoType.bodyMdSemibold, color = c.textDanger)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(formatWon(s.item.price), style = OrmoType.bodyXlBold, color = c.textPrimary, modifier = Modifier.weight(1f))
                            OrmoStepper(
                                quantity = s.qty,
                                onMinus = vm::decrease,
                                onPlus = vm::increase,
                                minusEnabled = s.canDecrease,
                                plusEnabled = s.canIncrease,
                                onPlusDisabledClick = vm::notifyQtyLimit,
                            )
                        }
                    }
                    if (s.item.optionGroups.isNotEmpty()) OrmoThickDivider()
                    s.item.optionGroups.forEach { group ->
                        GroupSection(group, s, onToggle = { vm.toggle(group, it) })
                    }
                    Spacer(Modifier.height(ctaHeight))
                }
            }
        }
        if (s != null) {
            val enabled = s.canAdd && unavailable == null
            BottomCta(Modifier.align(Alignment.BottomCenter), onHeightChange = { ctaHeight = it }) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                    // Figma(375): 보조 127 · 주 200 (간격 8). 고정 폭이 아니라 비율로 나눠서 360dp 이하 폰에서도 "바로 주문"이 줄바꿈되지 않게 한다
                    OrmoButton("바로 주문", { vm.add(goCheckout = true) }, Modifier.weight(127f), kind = ButtonKind.WeakBrand, size = ButtonSize.XL, enabled = enabled)
                    OrmoButton(s.addLabel, { vm.add(goCheckout = false) }, Modifier.weight(200f), size = ButtonSize.XL, enabled = enabled)
                }
            }
        }
        OrmoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun NavAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = Ormo.colors.iconPrimary, modifier = Modifier.size(24.dp))
    }
}

/** 옵션 그룹: 라벨(18 Semibold) + 필수/선택 뱃지, 그 아래 행(위아래 12, 행 사이 24). 그룹 사이에 굵은 구분선은 없다 (Figma). */
@Composable
private fun GroupSection(group: OptionGroup, s: OptionScreenState, onToggle: (String) -> Unit) {
    val c = Ormo.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.pageMargin, vertical = Space.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        Text(group.name, style = OrmoType.bodyLgSemibold, color = c.textPrimary)
        val kind = if (group.required) BadgeKind.Required else BadgeKind.Optional
        OrmoBadge(badgeLabel(kind), kind)
        if (group.multi && group.maxSelect != null) {
            Text("최대 ${group.maxSelect}개", style = OrmoType.bodySmRegular, color = c.textTertiary)
        }
    }
    // 행 48(터치 영역) 안에서 글자가 가운데라, Figma(행 28 + 간격 24)와 같은 간격이 되도록 위 1 · 아래 3 만 둔다
    Column(Modifier.fillMaxWidth().padding(start = Space.pageMargin, end = Space.pageMargin, top = 1.dp, bottom = 3.dp), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
        group.choices.forEach { choice ->
            val selected = s.isSelected(choice.id)
            val dimmed = choice.soldOut && !selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp) // 터치 영역 48dp (행 사이 간격은 그만큼 줄여 전체 모양은 같게)
                    .alpha(if (dimmed) 0.4f else 1f)
                    .then(
                        if (group.multi) {
                            Modifier.toggleable(value = selected, enabled = !dimmed, role = Role.Checkbox) { onToggle(choice.id) }
                        } else {
                            Modifier.selectable(selected = selected, enabled = !dimmed, role = Role.RadioButton) { onToggle(choice.id) }
                        },
                    )
                    .semantics { contentDescription = "${choice.name}, ${OptionScreenState.priceLabel(choice)}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (group.multi) OrmoCheckCircle(selected, size = 24.dp) else OrmoRadio(selected)
                Text(choice.name, style = OrmoType.bodyLgRegular, color = c.textPrimary, modifier = Modifier.weight(1f).padding(start = Space.s12))
                Text(OptionScreenState.priceLabel(choice), style = OrmoType.bodyMdRegular, color = if (choice.soldOut) c.textDanger else c.textSecondary)
            }
        }
    }
}
