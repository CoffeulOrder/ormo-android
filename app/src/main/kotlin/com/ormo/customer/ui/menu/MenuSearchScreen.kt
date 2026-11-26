package com.ormo.customer.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ormo.customer.ui.theme.ormoStatusBarsPadding
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import com.ormo.customer.ui.theme.Shapes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ormo.customer.AppContainer
import com.ormo.customer.CartHolder
import com.ormo.customer.fake.FakeCatalog
import com.ormo.customer.ui.components.BadgeKind
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoDivider
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.OrmoSnackbarHost
import com.ormo.customer.ui.components.OrmoSnackbarState
import com.ormo.customer.ui.components.hidingKeyboard
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.ormo.customer.ui.components.OrmoThickDivider
import com.ormo.customer.ui.components.hideKeyboardOnScroll
import com.ormo.customer.ui.components.SnackbarDurations
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space
import com.ormo.data.menu.MenuRepository
import com.ormo.data.search.RecentSearchRepository
import com.ormo.domain.home.MenuHomeRules
import com.ormo.domain.model.MenuItem
import com.ormo.domain.order.OptionScreenState
import com.ormo.domain.rules.OptionRules
import com.ormo.domain.search.SearchRules
import com.ormo.domain.search.SearchView
import com.ormo.domain.store.OrderAvailability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * 메뉴 검색 (디자인 2.1.3): 서버 검색 없이 앱에서 거른다. 한 글자씩 입력할 때마다 갱신하되 **0.2초 디바운스**.
 * 최근 검색어는 계정별로 기기에 저장 (이 매장 기준 최대 10개). 기록은 결과를 눌렀을 때 남긴다 (글자마다 저장하지 않음).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MenuSearchViewModel(
    private val storeId: String,
    private val menus: MenuRepository,
    private val recents: RecentSearchRepository,
    private val cart: CartHolder,
    private val accountKey: () -> String,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _recent = MutableStateFlow<List<String>>(emptyList())

    init { viewModelScope.launch { _recent.value = recents.load(accountKey(), storeId) } }

    /** 입력이 멈춘 뒤 [SearchRules.DEBOUNCE_MS] 가 지나야 거른다. 비우면 바로 시작 화면으로. */
    private val debounced = _query.debounce { if (it.isEmpty()) 0L else SearchRules.DEBOUNCE_MS }

    val view: StateFlow<SearchView> = combine(debounced, _recent, menus.state, menus.soldOut) { q, recent, state, sold ->
        val cafe = state.payload?.cafes?.firstOrNull { it.id == storeId }
        val items = MenuHomeRules.applySoldOut(cafe?.menus.orEmpty(), sold[storeId].orEmpty())
        SearchRules.view(items, q, recent, FakeCatalog.popularIds[storeId].orEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchView.Start(emptyList(), emptyList()))

    fun onQueryChanged(q: String) { _query.value = q }

    private fun remember(term: String) {
        viewModelScope.launch { _recent.value = recents.add(accountKey(), storeId, term) }
    }

    fun removeRecent(term: String) { viewModelScope.launch { _recent.value = recents.remove(accountKey(), storeId, term) } }
    fun clearRecent() { viewModelScope.launch { _recent.value = recents.clear(accountKey(), storeId) } }

    /** 검색어를 눌렀을 때: 그 검색어로 바로 검색 */
    fun searchRecent(term: String) { _query.value = term }

    /** 현재 검색어를 최근 검색어에 남긴다 (결과를 눌렀을 때) */
    fun remember() { if (_query.value.isNotBlank()) remember(_query.value) }

    /** 옵션이 없는 메뉴를 바로 담는다 (옵션이 있으면 화면이 옵션 선택으로 보낸다) */
    fun onMenuClicked(item: MenuItem, canAdd: Boolean): String {
        remember()
        if (!canAdd) return "지금은 담을 수 없어요"
        val change = cart.add(storeId, item, OptionScreenState.start(item).choiceIds)
        return if (change.limit == null) "장바구니에 메뉴를 추가했어요" else "한 번에 20개까지 주문할 수 있어요"
    }

    companion object {
        fun factory(c: AppContainer, storeId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MenuSearchViewModel(storeId, c.menus, c.recentSearches, c.cart) { c.accountKey } as T
        }
    }
}

@Composable
fun MenuSearchScreen(vm: MenuSearchViewModel, availability: () -> OrderAvailability, onBack: () -> Unit, onOption: (menuId: String) -> Unit) {
    val c = Ormo.colors
    val query by vm.query.collectAsState()
    val view by vm.view.collectAsState()
    val snackbar = remember { OrmoSnackbarState() }

    Box(Modifier.fillMaxSize().background(c.bgDefault)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // 상단: 뒤로가기 + 검색 입력 (Figma 2.1.3). 입력칸은 Fill/Neutral Weak 바탕, 왼쪽 검색 아이콘, 오른쪽 지우기
            Row(
                Modifier.fillMaxWidth().ormoStatusBarsPadding().padding(start = Space.s8, end = Space.s20, top = Space.s8, bottom = Space.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = "뒤로가기", onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(OrmoIcons.Back, contentDescription = "뒤로가기", tint = c.iconPrimary, modifier = Modifier.size(Size.iconLg))
                }
                SearchInput(query, vm::onQueryChanged, onSearch = vm::remember, modifier = Modifier.weight(1f))
            }
            val canAdd = availability() is OrderAvailability.Available
            when (val v = view) {
                is SearchView.Start -> SearchStart(v, vm, canAdd, snackbar, onOption)
                is SearchView.Results -> LazyColumn(Modifier.weight(1f).hideKeyboardOnScroll()) {
                    item {
                        Text(v.countLabel, style = OrmoType.bodyMdMedium, color = c.textSecondary, modifier = Modifier.padding(start = Space.pageMargin, end = Space.pageMargin, top = Space.s16, bottom = Space.s4))
                    }
                    itemsIndexed(v.hits, key = { _, h -> h.item.id }) { index, hit ->
                        val rowState = MenuHomeRules.rowState(hit.item, availability())
                        MenuRow(
                            item = hit.item,
                            state = rowState,
                            badge = if (!rowState.tappable) BadgeKind.SoldOut else null,
                            highlight = hit.range,
                            compact = true,
                            onClick = {
                                if (rowState.canAdd && !OptionRules.hasOptions(hit.item)) snackbar.show(vm.onMenuClicked(hit.item, true), SnackbarDurations.ADDED)
                                else { vm.remember(); onOption(hit.item.id) }
                            },
                        )
                        if (index < v.hits.lastIndex) OrmoDivider()
                    }
                }
                is SearchView.NoResult -> Column(Modifier.weight(1f).fillMaxWidth().hideKeyboardOnScroll().verticalScroll(rememberScrollState())) {
                    // Figma 2.1.3 결과 없음: 가운데 회색 원 + 돋보기, 제목·안내(가운데), 굵은 구분선 아래 '인기 메뉴' + 메뉴 줄
                    Column(Modifier.fillMaxWidth().padding(top = 39.dp, bottom = 40.dp, start = 40.dp, end = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(60.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.fillNeutralWeak), contentAlignment = Alignment.Center) {
                            Icon(OrmoIcons.Search, contentDescription = null, tint = c.iconTertiary, modifier = Modifier.size(32.dp))
                        }
                        Text(
                            v.title,
                            style = OrmoType.bodyLgSemibold,
                            color = c.textStrong,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(top = 20.dp).semantics { heading() },
                        )
                        Text(v.hint, style = OrmoType.bodyMdRegular, color = c.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = Space.s4))
                    }
                    if (v.showsPopular) {
                        OrmoThickDivider()
                        Text("인기 메뉴", style = OrmoType.bodyLgSemibold, color = c.textPrimary, modifier = Modifier.padding(start = Space.pageMargin, end = Space.pageMargin, top = Space.s12))
                        v.popular.forEachIndexed { index, item ->
                            val rowState = MenuHomeRules.rowState(item, availability())
                            MenuRow(
                                item = item,
                                state = rowState,
                                badge = if (!rowState.tappable) BadgeKind.SoldOut else null,
                                onClick = {
                                    if (rowState.canAdd && !OptionRules.hasOptions(item)) snackbar.show(vm.onMenuClicked(item, true), SnackbarDurations.ADDED)
                                    else onOption(item.id)
                                },
                            )
                            if (index < v.popular.lastIndex) OrmoDivider()
                        }
                        Box(Modifier.height(Space.s32))
                    }
                }
            }
        }
        OrmoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter), bottomOffset = 24.dp)
    }
}

@Composable
private fun SearchStart(v: SearchView.Start, vm: MenuSearchViewModel, canAdd: Boolean, snackbar: OrmoSnackbarState, onOption: (String) -> Unit) {
    val c = Ormo.colors
    LazyColumn(Modifier.fillMaxSize().hideKeyboardOnScroll()) {
        // 기록이 없는 묶음은 숨긴다
        if (v.showsRecent) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.pageMargin, vertical = Space.s12), verticalAlignment = Alignment.CenterVertically) {
                    Text("최근 검색", style = OrmoType.bodyLgSemibold, color = c.textPrimary, modifier = Modifier.weight(1f))
                    OrmoButton("전체 삭제", vm::clearRecent, kind = ButtonKind.Ghost, size = ButtonSize.S)
                }
            }
            items(v.recent, key = { "r-$it" }) { term ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { vm.searchRecent(term) }.padding(start = Space.pageMargin, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(term, style = OrmoType.bodyMdMedium, color = c.textPrimary, modifier = Modifier.weight(1f).padding(vertical = Space.s12)) // Figma: 행 높이 48 · Medium 16
                    Box(Modifier.size(40.dp).clickable(role = Role.Button, onClickLabel = "$term 삭제") { vm.removeRecent(term) }, contentAlignment = Alignment.Center) {
                        Icon(OrmoIcons.Close, contentDescription = null, tint = c.iconTertiary, modifier = Modifier.size(Size.iconMd))
                    }
                }
            }
            item { Box(Modifier.padding(top = Space.s16)) }
        }
        if (v.showsPopular) {
            item { PopularList(v.popular, vm, canAdd, snackbar, title = "많이 찾는 메뉴", onOption = onOption) }
        }
    }
}

@Composable
private fun PopularList(items: List<MenuItem>, vm: MenuSearchViewModel, canAdd: Boolean, snackbar: OrmoSnackbarState, title: String, onOption: (String) -> Unit) {
    val c = Ormo.colors
    Column(Modifier.padding(top = Space.s24)) {
        Text(title, style = OrmoType.bodyLgBold, color = c.textStrong, modifier = Modifier.padding(horizontal = Space.pageMargin).padding(bottom = Space.s8))
        items.forEachIndexed { i, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) {
                        if (canAdd && !item.soldOut && !OptionRules.hasOptions(item)) snackbar.show(vm.onMenuClicked(item, true), SnackbarDurations.ADDED)
                        else if (!item.soldOut) onOption(item.id)
                    }
                    .padding(horizontal = Space.pageMargin, vertical = Space.s12),
                horizontalArrangement = Arrangement.spacedBy(Space.s12),
            ) {
                Text("${i + 1}", style = OrmoType.bodyMdBold, color = c.textBrand)
                Text(item.name, style = OrmoType.bodyMdMedium, color = if (item.soldOut) c.textQuaternary else c.textPrimary)
            }
        }
        OrmoDivider(inset = true)
    }
}

/** 검색 입력칸: 높이 48, 반경 16, Fill/Neutral Weak, 검색 아이콘 + 안내 '메뉴 이름으로 검색해보세요', 입력이 있으면 지우기 */
@Composable
private fun SearchInput(value: String, onChange: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ormo.colors
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() } // 아이콘을 누르면 키보드가 바로 올라온다
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = OrmoType.bodyMdRegular.copy(color = c.textPrimary),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.fillBrand),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // 검색은 입력할 때마다 이미 걸러져 있다 → 엔터는 키보드만 내린다 (결과가 키보드에 가려지지 않게)
        keyboardActions = KeyboardActions(onSearch = { onSearch() }).hidingKeyboard(), // 엔터: 최근 검색어에 남기고 키보드를 내린다
        modifier = modifier.fillMaxWidth().focusRequester(requester),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(Shapes.control48).background(c.fillNeutralWeak).padding(horizontal = Space.s16), // Figma: 48 · 반경 14 · 좌우 16
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s8),
            ) {
                Icon(OrmoIcons.Search, contentDescription = null, tint = c.iconSecondary, modifier = Modifier.size(Size.iconLg))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("메뉴 이름으로 검색해보세요", style = OrmoType.bodyMdRegular, color = c.textTertiary)
                    inner()
                }
                if (value.isNotEmpty()) {
                    // Figma: 동그라미 없는 연한 ✕
                    Icon(
                        OrmoIcons.Close, contentDescription = "지우기", tint = c.iconTertiary,
                        modifier = Modifier.size(Size.iconLg).clickable(role = Role.Button) { onChange("") },
                    )
                }
            }
        },
    )
}
