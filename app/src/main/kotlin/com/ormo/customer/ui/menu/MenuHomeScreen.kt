package com.ormo.customer.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ormo.core.Outcome
import com.ormo.core.formatWon
import com.ormo.customer.AppContainer
import com.ormo.customer.CartHolder
import com.ormo.customer.ui.auth.rememberNowMs
import com.ormo.customer.ui.components.BadgeKind
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.DelayedSkeleton
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoDivider
import com.ormo.customer.ui.components.OrmoThickDivider
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.OrmoSnackbarHost
import com.ormo.customer.ui.components.OrmoSnackbarState
import com.ormo.customer.ui.components.SkeletonBlock
import com.ormo.customer.ui.components.SnackbarDurations
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Shapes
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space
import com.ormo.data.menu.MenuRepository
import com.ormo.data.menu.MenuStatus
import com.ormo.data.menu.SyncReason
import com.ormo.data.store.StoreRepository
import com.ormo.domain.common.CommonErrorRules
import com.ormo.domain.home.MenuHomeRules
import com.ormo.domain.model.MenuItem
import com.ormo.domain.order.OptionScreenState
import com.ormo.domain.rules.CartLimit
import com.ormo.domain.rules.OptionRules
import com.ormo.domain.rules.MenuBadgeRules
import com.ormo.domain.store.OrderAvailability
import com.ormo.domain.store.Store
import com.ormo.domain.store.StoreRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

data class MenuHomeUi(
    val store: Store? = null,
    val refreshing: Boolean = false,
)

class MenuHomeViewModel(
    val storeId: String,
    val menus: MenuRepository,
    private val stores: StoreRepository,
    private val cartHolder: CartHolder,
) : ViewModel() {
    private val _ui = MutableStateFlow(MenuHomeUi())
    val ui: StateFlow<MenuHomeUi> = _ui.asStateFlow()

    val cart = cartHolder.cart

    private val _messages = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            loadStore()
            menus.sync(SyncReason.SCREEN) // 화면 진입 때 버전 체크 (30초 안에 다시 들어오면 생략)
            menus.refreshSoldOut(storeId) // 품절이 풀리면 다음 진입 또는 당겨서 새로고침 때 반영
        }
    }

    private suspend fun loadStore() {
        (stores.loadStores() as? Outcome.Ok)?.let { r -> _ui.update { it.copy(store = r.value.all.firstOrNull { s -> s.id == storeId }) } }
    }

    /** 당겨서 새로고침: 메뉴 강제 갱신 + 품절 + 영업 상태. 실패하면 전체 화면 대신 스낵바 (6.1.1 일부만 실패). */
    fun refresh() {
        if (_ui.value.refreshing) return
        _ui.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val before = menus.syncFailures.value
            menus.sync(SyncReason.SCREEN, force = true)
            menus.refreshSoldOut(storeId)
            loadStore()
            if (menus.syncFailures.value > before) _messages.send(CommonErrorRules.PARTIAL_FAILURE_SNACKBAR)
            _ui.update { it.copy(refreshing = false) }
        }
    }

    fun retry() { viewModelScope.launch { menus.retry() } }

    /**
     * 옵션이 없는 메뉴를 바로 담는다 (시트 없이 담고 토스트, 2.2).
     * 같은 메뉴면 수량이 합쳐지고, 줄 20개·수량 20개 한도는 [CartHolder] 가 지킨다.
     */
    fun addDirect(item: MenuItem): String {
        val change = cartHolder.add(storeId, item, OptionScreenState.start(item).choiceIds)
        return when (change.limit) {
            null -> "장바구니에 메뉴를 추가했어요"
            CartLimit.QTY_EXCEEDED -> "한 번에 20개까지 주문할 수 있어요"
            CartLimit.TOO_MANY_LINES -> "장바구니에는 메뉴를 20개까지 담을 수 있어요"
        }
    }

    companion object {
        fun factory(c: AppContainer, storeId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MenuHomeViewModel(storeId, c.menus, c.stores, c.cart) as T
        }
    }
}

/**
 * 메뉴 홈 (디자인 2.1.1 / 2.1.2): 상단 [매장 이름 ▾](→ 매장 선택), 매장 정보(i), 검색, 내 정보. 카테고리 탭(위 고정, 누르면 스크롤, 스크롤하면 따라감),
 * 메뉴 목록, 하단 장바구니 바. 당겨서 새로고침.
 *
 * 상태: 불러오는 중(스켈레톤, 0.3초 안이면 안 띄움) / 오류(메뉴가 없을 때만 전체 화면) / **주문 불가**(안내 상자 + [다른 매장 보기], 장바구니 바 숨김,
 * 메뉴 줄은 열리지만 담기 꺼짐) / **빈 메뉴**('아직 메뉴가 없어요') / 목록. 보는 동안 영업 상태가 바뀌면 바로 반영.
 * TODO: 최근 주문 탭(주문 이력, M5), 신규·인기 뱃지(서버 데이터)는 아직 없다 — 품절 뱃지만.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuHomeScreen(
    vm: MenuHomeViewModel,
    onStoreSelect: () -> Unit,
    onSearch: () -> Unit,
    onMyInfo: () -> Unit,
    onCart: () -> Unit,
    onOption: (menuId: String) -> Unit,
    pendingToast: String?,
    onToastShown: (String) -> Unit,
) {
    val c = Ormo.colors
    val ui by vm.ui.collectAsState()
    val menuState by vm.menus.state.collectAsState()
    val soldOut by vm.menus.soldOut.collectAsState()
    val cart by vm.cart.collectAsState()
    val snackbar = remember { OrmoSnackbarState() }
    val now = rememberNowMs()
    val nowLocal = remember(now / 15_000) { LocalDateTime.now() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.show(it, SnackbarDurations.PARTIAL_FAILURE) } }
    // 옵션 화면에서 담고 돌아오면 '장바구니에 메뉴를 추가했어요' 2초 (2.2.2)
    LaunchedEffect(pendingToast) {
        if (pendingToast != null) {
            snackbar.show(pendingToast, SnackbarDurations.ADDED)
            onToastShown(pendingToast)
        }
    }

    val cafe = menuState.payload?.cafes?.firstOrNull { it.id == vm.storeId }
    val storeName = ui.store?.name ?: cafe?.name ?: ""
    val availability = ui.store?.let { StoreRules.availability(it.status, nowLocal) } ?: OrderAvailability.Available

    Box(Modifier.fillMaxSize().background(c.bgDefault)) {
        Column(Modifier.fillMaxSize()) {
            MenuHomeTopBar(storeName, onStoreSelect, onSearch, onMyInfo)
            when {
                menuState.status == MenuStatus.ERROR -> ErrorBody(onRetry = vm::retry, onOtherStore = onStoreSelect)
                menuState.status == MenuStatus.LOADING -> DelayedSkeleton(
                    loading = true,
                    skeleton = {
                        Column(Modifier.padding(Space.pageMargin), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                            repeat(4) { SkeletonBlock(Modifier.fillMaxWidth().height(96.dp)) }
                        }
                    },
                    content = {},
                )
                cafe == null || cafe.menus.isEmpty() -> EmptyMenuBody(onOtherStore = onStoreSelect)
                else -> {
                    val items = MenuHomeRules.applySoldOut(cafe.menus, soldOut[vm.storeId].orEmpty())
                    val layout = remember(items) { MenuHomeRules.layout(items) }
                    val badges = remember(cart) { MenuHomeRules.quantityBadges(cart) }
                    val listState = rememberLazyListState()
                    val scope = rememberCoroutineScope()
                    val selectedTab by remember(layout) { derivedStateOf { layout.sectionAt(listState.firstVisibleItemIndex) } }
                    val bars = MenuHomeRules.bottomBars(availability, cart.count, hasActiveOrder = false)

                    Column(Modifier.weight(1f)) {
                        CategoryTabs(layout.tabs, selectedTab, onSelect = { i ->
                            scope.launch { listState.animateScrollToItem(layout.headerIndexOf(i)) }
                        })
                        if (availability is OrderAvailability.Unavailable) {
                            UnavailableBox(availability.message, onOtherStore = onStoreSelect)
                        }
                        PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
                            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                                layout.sections.forEachIndexed { sectionIndex, section ->
                                    // 묶음 사이는 굵은 구분선 + 라벨(18 bold). 맨 앞 '최근 주문' 묶음은 탭이 이미 이름을 보여줘 라벨이 없다 (Figma 2.1.1)
                                    item(key = "h-${section.key}") {
                                        if (sectionIndex > 0 || !section.isRecent) {
                                            if (sectionIndex > 0) OrmoThickDivider()
                                            Text(
                                                section.title,
                                                style = OrmoType.bodyLgBold,
                                                color = c.textPrimary,
                                                modifier = Modifier.fillMaxWidth().padding(start = Space.s20, end = Space.s20, top = Space.s12).semantics { heading() },
                                            )
                                        } else {
                                            Box(Modifier.height(1.dp))
                                        }
                                    }
                                    section.items.forEachIndexed { menuIndex, menu ->
                                        item(key = "${section.key}-${menu.id}") {
                                            val rowState = MenuHomeRules.rowState(menu, availability)
                                            val badge = MenuBadgeRules.badge(
                                                soldOut = !rowState.tappable, registeredOn = null, isPopular = false, today = java.time.LocalDate.now(),
                                            )
                                            MenuRow(
                                                item = menu,
                                                state = rowState,
                                                quantity = badges[menu.id] ?: 0,
                                                badge = if (badge == com.ormo.domain.rules.MenuBadge.SOLD_OUT) BadgeKind.SoldOut else null,
                                                onClick = {
                                                    // 옵션이 없는 메뉴는 시트 없이 바로 담고 토스트, 있으면 옵션 선택 화면 (2.2). 주문 불가여도 옵션 화면은 열리고 담기만 꺼진다.
                                                    if (rowState.canAdd && !OptionRules.hasOptions(menu)) {
                                                        snackbar.show(vm.addDirect(menu), SnackbarDurations.ADDED)
                                                    } else {
                                                        onOption(menu.id)
                                                    }
                                                },
                                            )
                                            if (menuIndex < section.items.lastIndex) OrmoDivider()
                                        }
                                    }
                                }
                                // 메뉴 목록 맨 아래 여백 = 하단 바 높이 + 16 (2.1.4)
                                item { Box(Modifier.height(if (bars.any) 96.dp else Space.s32)) }
                            }
                        }
                    }
                    if (bars.showCartBar) CartBar(cart.count, cart.total, onCart)
                }
            }
        }
        OrmoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter), bottomOffset = 96.dp)
    }
}

/** 상단 (Figma 2.1.1): [매장 이름 ▾](→ 매장 선택) 20/30 semibold + 오른쪽 검색·내 정보 (40×40, 아이콘 24). 패딩 좌우 20, 위아래 8 */
@Composable
private fun MenuHomeTopBar(storeName: String, onStoreSelect: () -> Unit, onSearch: () -> Unit, onMyInfo: () -> Unit) {
    val c = Ormo.colors
    Row(
        Modifier.fillMaxWidth().background(c.bgDefault).statusBarsPadding().padding(horizontal = Space.s20, vertical = Space.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            Modifier.clickable(role = Role.Button, onClickLabel = "매장 변경", onClick = onStoreSelect).height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s8),
        ) {
            Text(storeName, style = OrmoType.bodyXlSemibold, color = c.textPrimary, maxLines = 1)
            Icon(OrmoIcons.ChevronDown, contentDescription = null, tint = c.iconPrimary, modifier = Modifier.size(Size.iconLg))
        }
        Row {
            Box(Modifier.size(40.dp).clickable(role = Role.Button, onClickLabel = "메뉴 검색", onClick = onSearch), contentAlignment = Alignment.Center) {
                Icon(OrmoIcons.Search, contentDescription = null, tint = c.iconPrimary, modifier = Modifier.size(Size.iconLg))
            }
            Box(Modifier.size(40.dp).clickable(role = Role.Button, onClickLabel = "내 정보", onClick = onMyInfo), contentAlignment = Alignment.Center) {
                Icon(OrmoIcons.Person, contentDescription = null, tint = c.iconPrimary, modifier = Modifier.size(Size.iconLg))
            }
        }
    }
}

@Composable
private fun UnavailableBox(message: String, onOtherStore: () -> Unit) {
    val c = Ormo.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.pageMargin, vertical = Space.s8)
            .clip(Shapes.control48)
            .background(c.fillNeutralWeak)
            .padding(start = Space.s16, top = Space.s12, bottom = Space.s12, end = Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = OrmoType.bodyMdSemibold, color = c.textPrimary, modifier = Modifier.weight(1f))
        OrmoButton("다른 매장 보기", onOtherStore, kind = ButtonKind.WeakBrand, size = ButtonSize.S)
    }
}

@Composable
private fun EmptyMenuBody(onOtherStore: () -> Unit) {
    val c = Ormo.colors
    Column(
        Modifier.fillMaxSize().padding(Space.pageMargin),
        verticalArrangement = Arrangement.spacedBy(Space.s8, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("아직 메뉴가 없어요", style = OrmoType.bodyXlBold, color = c.textPrimary)
        Text("메뉴를 준비하고 있어요", style = OrmoType.bodyMdRegular, color = c.textSecondary)
        OrmoButton("다른 매장 보기", onOtherStore, Modifier.padding(top = Space.s16), kind = ButtonKind.WeakNeutral, size = ButtonSize.M)
    }
}

@Composable
private fun ErrorBody(onRetry: () -> Unit, onOtherStore: () -> Unit) {
    val c = Ormo.colors
    Column(
        Modifier.fillMaxSize().padding(Space.pageMargin),
        verticalArrangement = Arrangement.spacedBy(Space.s8, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("인터넷 연결을 확인해주세요", style = OrmoType.bodyXlBold, color = c.textPrimary)
        Text("연결이 복구되면 다시 불러올게요", style = OrmoType.bodyMdRegular, color = c.textSecondary)
        OrmoButton("다시 시도", onRetry, Modifier.padding(top = Space.s16), kind = ButtonKind.WeakNeutral, size = ButtonSize.M)
        OrmoButton("다른 매장 보기", onOtherStore, kind = ButtonKind.Ghost)
    }
}

/** 하단 장바구니 바 (2.1.1): 수량 뱃지 + '12,000원 보기'. 누르면 장바구니(M4). */
@Composable
private fun CartBar(count: Int, total: Int, onClick: () -> Unit) {
    val c = Ormo.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.bgDefault)
            .navigationBarsPadding()
            .padding(horizontal = Space.s16, vertical = Space.s12),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Size.heightXl)
                .clip(Shapes.control56)
                .background(c.fillBrand)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = Space.s20),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(c.textOnFill), contentAlignment = Alignment.Center) {
                Text(count.coerceAtMost(99).toString(), style = OrmoType.bodySmBold, color = c.textBrand)
            }
            Text("${formatWon(total)} 보기", style = OrmoType.bodyLgSemibold, color = c.textOnFill, modifier = Modifier.weight(1f).padding(end = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
