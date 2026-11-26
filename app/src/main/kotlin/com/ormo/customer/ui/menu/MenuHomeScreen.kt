package com.ormo.customer.ui.menu

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.ormo.customer.ui.auth.rememberReducedMotion
import com.ormo.customer.ui.order.LIVE_CLOSE_MS
import com.ormo.customer.ui.order.LIVE_OPEN_MS
import com.ormo.customer.ui.order.LIVE_PAGE_MS
import com.ormo.customer.ui.order.LIVE_SWAP_MS
import com.ormo.customer.ui.order.LiveBarHeight
import com.ormo.customer.ui.order.LiveOrderExpandedBars
import com.ormo.customer.ui.order.LiveOrderHeaderTitle
import com.ormo.customer.ui.order.LiveOrderPanel
import com.ormo.customer.ui.order.LivePanelState
import com.ormo.customer.ui.order.livePanelHeight
import com.ormo.customer.ui.theme.Base
import com.ormo.customer.ui.theme.topInset
import com.ormo.domain.orders.LiveBarView
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.ormo.customer.ui.order.LiveFloatEasing
import com.ormo.customer.ui.theme.bottomInset
import com.ormo.customer.ui.theme.ctaBottomPadding
import com.ormo.customer.ui.theme.ormoNavigationBarsPadding
import com.ormo.customer.ui.theme.ormoStatusBarsPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import com.ormo.customer.ui.components.registerBottomBar
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

/** 장바구니 바가 올라오고 플로팅 버튼이 밀려 올라가는 시간 (Figma 2.1.4: 0.4초) */
private const val CART_SLIDE_MS = 400

data class MenuHomeUi(
    val store: Store? = null,
    val refreshing: Boolean = false,
)

class MenuHomeViewModel(
    val storeId: String,
    val menus: MenuRepository,
    private val stores: StoreRepository,
    private val cartHolder: CartHolder,
    private val orders: com.ormo.data.orders.OrderRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(MenuHomeUi())

    /** 진행 중 주문 전부 (먼저 받을 순서). 있으면 헤더 아래 실시간 주문 현황 구역 (2.1.4) — 끝나면 접히며 사라진다. */
    val liveBars: StateFlow<List<com.ormo.domain.orders.LiveBarView>> =
        kotlinx.coroutines.flow.MutableStateFlow<List<com.ormo.domain.orders.LiveBarView>>(emptyList()).also { out ->
            viewModelScope.launch { orders.active.collect { out.value = com.ormo.domain.orders.LiveOrderRules.bars(it) } }
        }

    /** 현황 구역에서 주문이 빠질 때 알림: 픽업 완료 '27번 음료를 받았어요' · 거절·취소 '주문이 취소됐어요'(5초). (문구, 보이는 시간 ms) */
    private val _liveEvents = kotlinx.coroutines.channels.Channel<Pair<String, Long>>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val liveEvents = _liveEvents.receiveAsFlow()

    private suspend fun announceFinished(orderId: String, number: Int?) {
        val order = (orders.load(orderId) as? Outcome.Ok)?.value ?: return
        when (order.status) {
            com.ormo.domain.model.OrderStatus.COMPLETED ->
                _liveEvents.send("${number?.let { "${it}번 " }.orEmpty()}음료를 받았어요" to 3_000L)
            com.ormo.domain.model.OrderStatus.REJECTED, com.ormo.domain.model.OrderStatus.CANCELED ->
                _liveEvents.send("주문이 취소됐어요" to 5_000L)
            else -> Unit
        }
    }

    val ui: StateFlow<MenuHomeUi> = _ui.asStateFlow()

    val cart = cartHolder.cart

    private val _messages = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        // 진행 중이던 주문이 목록에서 빠지면 끝난 이유를 알려 준다
        viewModelScope.launch {
            var before = emptyMap<String, Int?>()
            orders.active.collect { list ->
                val now = list.filter { it.status.isActive }.associate { it.id to it.pickupNumber }
                before.forEach { (id, number) -> if (id !in now) launch { announceFinished(id, number) } }
                before = now
            }
        }
        // 진입·복귀·푸시 수신 때 진행 중 주문을 다시 받는다. 화면이 떠 있는 동안은 주기적으로 (진행 중이면 5초, 아니면 20초)
        viewModelScope.launch {
            while (true) {
                orders.refreshActive()
                kotlinx.coroutines.delay(if (orders.active.value.isNotEmpty()) 5_000 else 20_000)
            }
        }
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
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MenuHomeViewModel(storeId, c.menus, c.stores, c.cart, c.orders) as T
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
    onOrderStatus: (orderId: String) -> Unit,
    pendingToast: String?,
    onToastShown: (String) -> Unit,
) {
    val c = Ormo.colors
    val ui by vm.ui.collectAsState()
    val menuState by vm.menus.state.collectAsState()
    val soldOut by vm.menus.soldOut.collectAsState()
    val cart by vm.cart.collectAsState()
    val liveBars by vm.liveBars.collectAsState()
    val snackbar = remember { OrmoSnackbarState() }
    val now = rememberNowMs()
    val nowLocal = remember(now / 15_000) { LocalDateTime.now() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.show(it, SnackbarDurations.PARTIAL_FAILURE) } }
    // 현황 구역에서 주문이 끝나 빠질 때: '27번 음료를 받았어요' / '주문이 취소됐어요'
    LaunchedEffect(Unit) { vm.liveEvents.collect { (text, ms) -> snackbar.show(text, ms) } }
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
    val bars = MenuHomeRules.bottomBars(availability, cart.count, hasActiveOrder = false)
    val listVisible = menuState.status != MenuStatus.ERROR && menuState.status != MenuStatus.LOADING && cafe != null && cafe.menus.isNotEmpty()

    // 장바구니 CTA 는 화면 위에 **떠 있다** (Figma 2.1.4 Bottom CTA: 흰 컨테이너 88 + 위쪽 흐림 40 이 메뉴 목록 위에 absolute) — 목록은 화면 끝까지 이어지고 끝에 CTA 높이만큼만 비운다
    var ctaHeight by remember { androidx.compose.runtime.mutableStateOf(0.dp) }

    // ---- 실시간 주문 현황 (Figma 2.1.4): 헤더 아래 현황 구역 → 메뉴를 끌어 올리면 헤더 안으로 접힘 → 헤더를 누르면 펼침 패널 ----
    val density = LocalDensity.current
    val reduced = rememberReducedMotion()
    val live = remember { LivePanelState() }
    var shownBars by remember { androidx.compose.runtime.mutableStateOf<List<LiveBarView>>(emptyList()) }
    if (liveBars.isNotEmpty()) shownBars = liveBars // 끝나서 사라지는 동안에도 마지막 내용을 그린다
    val panelFactor by animateFloatAsState(if (liveBars.isNotEmpty()) 1f else 0f, tween(if (reduced) 0 else LIVE_PAGE_MS, easing = LiveFloatEasing), label = "live-panel")
    val liveOn = panelFactor > 0.001f
    val fullPx = with(density) { livePanelHeight(shownBars.size).toPx() }
    LaunchedEffect(fullPx) { live.collapse = live.collapse.coerceIn(0f, fullPx) }
    LaunchedEffect(liveOn) { if (!liveOn) { live.collapse = 0f; live.expanded = false } }
    LaunchedEffect(liveBars.size) { if (liveBars.size < 2) live.expanded = false }
    BackHandler(enabled = live.expanded) { live.expanded = false }
    val folded = liveBars.isNotEmpty() && live.collapse >= fullPx - 0.5f // 현황 구역이 헤더 밑까지 접힘
    val swap by animateFloatAsState(if (folded) 1f else 0f, tween(if (reduced) 200 else LIVE_SWAP_MS, easing = LiveFloatEasing), label = "live-swap")
    val panelDp = with(density) { (fullPx * panelFactor - live.collapse).coerceAtLeast(0f).toDp() }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val expandedFull = minOf(LiveBarHeight * shownBars.size + 8.dp, screenHeight * 0.6f)
    val expandedDp by animateDpAsState(
        if (live.expanded) expandedFull else 0.dp,
        tween(if (live.expanded) LIVE_OPEN_MS else LIVE_CLOSE_MS, easing = LiveFloatEasing),
        label = "live-expanded",
    )
    val expandedFraction = if (expandedFull > 0.dp) (expandedDp / expandedFull).coerceIn(0f, 1f) else 0f
    val headerBottom = topInset() + 56.dp // 상태 표시줄 + 헤더 56
    // 메뉴를 끌면 현황 구역부터 접고(위로), 목록 맨 위에서 더 내리면 다시 편다 (아래 목록의 nestedScroll)
    val fullPxState = rememberUpdatedState(fullPx)
    val liveShownState = rememberUpdatedState(liveBars.isNotEmpty())
    val collapseConnection = remember(live) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!liveShownState.value || available.y >= 0f) return Offset.Zero
                val d = minOf(-available.y, fullPxState.value - live.collapse).coerceAtLeast(0f)
                live.collapse += d
                return Offset(0f, -d)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!liveShownState.value || available.y <= 0f || live.collapse <= 0f) return Offset.Zero
                val d = minOf(available.y, live.collapse)
                live.collapse -= d
                return Offset(0f, d)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(c.bgDefault)) {
        // 상태 표시줄부터 이어지는 흰색 → 하늘색(Blue 50) 한 장 그라데이션 (끝 = 현황 아래). 메뉴 시트(위 모서리 24)가 이 위를 덮는다.
        if (liveOn) {
            val gradH = headerBottom + maxOf(panelDp, expandedDp) + 24.dp * (1f - expandedFraction)
            val stop = ((headerBottom + maxOf(panelDp, expandedDp) - 8.dp) / gradH).coerceIn(0.05f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(gradH)
                    .alpha(panelFactor)
                    .clip(RoundedCornerShape(bottomStart = 20.dp * expandedFraction, bottomEnd = 20.dp * expandedFraction))
                    .background(Brush.verticalGradient(0f to c.bgDefault, stop to c.bannerEnd, 1f to c.bannerEnd)),
            )
        }
        Column(Modifier.fillMaxSize()) {
            MenuHomeTopBar(
                storeName = storeName,
                onStoreSelect = onStoreSelect,
                onSearch = onSearch,
                onMyInfo = onMyInfo,
                liveBars = shownBars,
                liveOn = liveOn,
                swap = swap,
                reduced = reduced,
                onLive = {
                    if (liveBars.size == 1) onOrderStatus(liveBars.first().orderId) else live.expanded = !live.expanded
                },
            )
            if (liveOn) {
                // 현황 구역: 메뉴를 끌어 올리면 높이가 줄어 헤더 밑으로 들어간다 (내용은 아래 선에 붙어 같이 올라감)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                            val h = (fullPx * panelFactor - live.collapse).coerceIn(0f, placeable.height.toFloat()).roundToInt()
                            layout(placeable.width, h) { placeable.placeRelative(0, h - placeable.height) }
                        },
                ) {
                    LiveOrderPanel(shownBars, onOrder = onOrderStatus)
                }
            }
            // 현황이 있을 땐 메뉴 영역이 위 모서리 24 의 흰 시트가 된다
            Column(
                Modifier
                    .weight(1f)
                    .then(
                        if (liveOn) Modifier.clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(c.bgDefault) else Modifier,
                    ),
            ) {
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
                    val listState = rememberLazyListState()
                    val scope = rememberCoroutineScope()
                    val selectedTab by remember(layout) { derivedStateOf { layout.sectionAt(listState.firstVisibleItemIndex) } }

                    Column(Modifier.weight(1f)) {
                        CategoryTabs(layout.tabs, selectedTab, onSelect = { i ->
                            scope.launch { listState.animateScrollToItem(layout.headerIndexOf(i)) }
                        })
                        if (availability is OrderAvailability.Unavailable) {
                            UnavailableBox(availability.message, onOtherStore = onStoreSelect)
                        }
                        PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
                            LazyColumn(Modifier.fillMaxSize().nestedScroll(collapseConnection), state = listState) {
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
                                // 메뉴 목록 맨 아래 여백: 장바구니 바에 마지막 메뉴가 가려지지 않게
                                item { Box(Modifier.height(if (bars.showCartBar) ctaHeight else Space.s32)) }
                            }
                        }
                    }
                }
            }
            }
        }
        // 펼침 패널 (헤더 현황을 눌렀을 때, 주문 2건 이상): 헤더 바로 아래에 막대들이 줄줄이, 뒤는 Dim 20%. 열기 0.36초 · 닫기 0.22초.
        if (liveOn && expandedDp > 0.dp) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = headerBottom + expandedDp)
                    .alpha(expandedFraction)
                    .background(c.bgDim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "닫기") { live.expanded = false },
            )
            // 패널 바탕: 헤더에서 이어지는 같은 그라데이션 (맨 위 y=0 흰색 → 패널 끝 Blue 50). 메뉴가 비치지 않게 불투명으로 깐다.
            val panelBrush = Brush.verticalGradient(
                colors = listOf(c.bgDefault, c.bannerEnd),
                startY = -with(density) { headerBottom.toPx() },
                endY = with(density) { (expandedDp - 8.dp).coerceAtLeast(1.dp).toPx() },
            )
            Box(
                Modifier
                    .padding(top = headerBottom)
                    .fillMaxWidth()
                    .height(expandedDp)
                    .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                    .background(panelBrush),
            ) {
                LiveOrderExpandedBars(
                    bars = shownBars,
                    maxHeight = expandedFull,
                    onOrder = { id -> live.expanded = false; onOrderStatus(id) },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
        // 장바구니 CTA: 아래에서 올라온다 (0.4초). 화면 위에 떠 있고 흰 컨테이너 + 위쪽 흐림으로 목록과 이어진다.
        if (listVisible) {
            AnimatedVisibility(
                visible = bars.showCartBar,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(CART_SLIDE_MS, easing = LiveFloatEasing)) { it },
                exit = slideOutVertically(tween(CART_SLIDE_MS, easing = LiveFloatEasing)) { it },
            ) {
                CartBar(cart.count, cart.total, onCart, onHeightChange = { ctaHeight = it })
            }
        }
        OrmoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * 상단 (Figma 2.1.1 · 2.1.4): [매장 이름 ▾](→ 매장 선택) 20/30 semibold + 오른쪽 검색·내 정보 (40×40, 아이콘 24). 패딩 좌우 20, 위아래 8.
 * 현황 구역이 헤더 밑으로 접히면(swap = 1) 매장 이름은 위로 빠지고 같은 자리에 헤더 현황(원형 고리 + 단계 + 내 앞 n건)이 올라온다 (0.45초).
 * 헤더 현황을 누르면 주문 1건은 그 주문 상세, 2건 이상은 펼침 패널. 움직임 줄이기면 위치는 그대로 두고 페이드만.
 */
@Composable
private fun MenuHomeTopBar(
    storeName: String,
    onStoreSelect: () -> Unit,
    onSearch: () -> Unit,
    onMyInfo: () -> Unit,
    liveBars: List<LiveBarView>,
    liveOn: Boolean,
    swap: Float,
    reduced: Boolean,
    onLive: () -> Unit,
) {
    val c = Ormo.colors
    val travel = with(LocalDensity.current) { 48.dp.toPx() }
    Row(
        // 현황이 있으면 배경은 뒤의 하늘색 그라데이션이 보이도록 비운다
        Modifier.fillMaxWidth().then(if (liveOn) Modifier else Modifier.background(c.bgDefault)).ormoStatusBarsPadding().padding(horizontal = Space.s20, vertical = 4.dp), // 버튼 48 + 위아래 4 = Figma 의 40 + 8·8
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.weight(1f).height(48.dp).clipToBounds()) {
            Row(
                Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer {
                        translationY = if (reduced) 0f else -travel * swap
                        alpha = 1f - swap
                    }
                    .clickable(enabled = swap < 0.5f, role = Role.Button, onClickLabel = "매장 변경", onClick = onStoreSelect)
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s8),
            ) {
                Text(storeName, style = OrmoType.bodyXlSemibold, color = c.textPrimary, maxLines = 1)
                Icon(OrmoIcons.ChevronDown, contentDescription = null, tint = c.iconPrimary, modifier = Modifier.size(Size.iconLg))
            }
            if (liveOn && liveBars.isNotEmpty()) {
                LiveOrderHeaderTitle(
                    bars = liveBars,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer {
                            translationY = if (reduced) 0f else travel * (1f - swap)
                            alpha = swap
                        }
                        .clickable(
                            enabled = swap >= 0.5f,
                            role = Role.Button,
                            onClickLabel = if (liveBars.size > 1) "주문 현황 펼치기" else "주문 상세 보기",
                            onClick = onLive,
                        )
                        .height(48.dp)
                        .semantics(mergeDescendants = true) { contentDescription = liveBars.first().contentDescription },
                )
            }
        }
        // 두 버튼은 터치 영역 48 이 8 만큼 겹쳐, 아이콘 중심은 Figma(40 간격)와 같다
        Row(Modifier.offset(x = 4.dp), horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = "메뉴 검색", onClick = onSearch), contentAlignment = Alignment.Center) {
                Icon(OrmoIcons.Search, contentDescription = null, tint = c.iconPrimary, modifier = Modifier.size(Size.iconLg))
            }
            Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = "내 정보", onClick = onMyInfo), contentAlignment = Alignment.Center) {
                Icon(OrmoIcons.Person, contentDescription = null, tint = c.iconPrimary, modifier = Modifier.size(Size.iconLg))
            }
        }
    }
}

@Composable
private fun UnavailableBox(message: String, @Suppress("UNUSED_PARAMETER") onOtherStore: () -> Unit) {
    val c = Ormo.colors
    // Figma 2.1.2: 탭 아래 16, 높이 44 의 연한 회색 상자 — ⓘ 20 + 14 Medium 한 줄 (다른 매장은 위 매장 이름으로 바꾼다)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.pageMargin, end = Space.pageMargin, top = Space.s16, bottom = Space.s16)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(c.fillNeutralWeak)
            .padding(horizontal = Space.s16, vertical = Space.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        Icon(com.ormo.customer.ui.components.FigmaIcons.Info, null, tint = c.iconSecondary, modifier = Modifier.size(20.dp))
        Text(message, style = OrmoType.bodySmMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EmptyMenuBody(onOtherStore: () -> Unit) {
    // Figma Result · Empty: 회색 원 + 제목 + 설명 + 버튼
    com.ormo.customer.ui.components.OrmoResult(
        icon = OrmoIcons.Coffee,
        title = "아직 메뉴가 없어요",
        body = "메뉴를 준비하고 있어요",
        action = "다른 매장 보기" to onOtherStore,
    )
}

@Composable
private fun ErrorBody(onRetry: () -> Unit, onOtherStore: () -> Unit) {
    // Figma Result · Error (Double): [다른 매장 보기](보조) + [다시 시도]
    com.ormo.customer.ui.components.OrmoResult(
        icon = OrmoIcons.WifiOff,
        title = "인터넷 연결을 확인해주세요",
        body = "연결이 복구되면 다시 불러올게요",
        action = "다시 시도" to onRetry,
        secondary = "다른 매장 보기" to onOtherStore,
    )
}

/** 하단 장바구니 바 (2.1.1): 수량 뱃지 + '12,000원 보기'. 누르면 장바구니(M4). */
@Composable
private fun CartBar(count: Int, total: Int, onClick: () -> Unit, onHeightChange: (androidx.compose.ui.unit.Dp) -> Unit) {
    val c = Ormo.colors
    // 공통 하단 CTA (위쪽 흐림 40 + 흰 컨테이너, 아래 여백 32/시스템 바) — 하단 토스트도 이 영역 위로 뜬다
    com.ormo.customer.ui.components.BottomCta(onHeightChange = onHeightChange) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Size.heightXl)
                .clip(Shapes.control56)
                .background(c.fillBrand)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = "장바구니 ${count}개, ${formatWon(total)}, 보기" }
                .padding(horizontal = Space.s20),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Figma: '4,500원 보기' 글자 뒤에 흰 수량 원, 둘이 가운데
            Spacer(Modifier.weight(1f))
            Text("${formatWon(total)} 보기", style = OrmoType.bodyLgSemibold, color = c.textOnFill)
            Box(Modifier.padding(start = Space.s8).defaultMinSize(24.dp, 24.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(percent = 50)).background(c.textOnFill).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Text(count.coerceAtMost(99).toString(), style = OrmoType.bodySmBold, color = c.textBrand)
            }
            Spacer(Modifier.weight(1f))
        }
    }
}
