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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.ormo.customer.ui.components.OrmoField
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.OrmoSnackbarHost
import com.ormo.customer.ui.components.OrmoSnackbarState
import com.ormo.customer.ui.components.OrmoTopNav
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

    /** 결과(또는 인기 메뉴)를 눌렀다. 임시: 옵션 화면(M4) 대신 기본 옵션으로 담는다 (메뉴 홈과 같음). */
    fun onMenuClicked(item: MenuItem, canAdd: Boolean): String {
        if (_query.value.isNotBlank()) remember(_query.value)
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
fun MenuSearchScreen(vm: MenuSearchViewModel, availability: () -> OrderAvailability, onBack: () -> Unit) {
    val c = Ormo.colors
    val query by vm.query.collectAsState()
    val view by vm.view.collectAsState()
    val snackbar = remember { OrmoSnackbarState() }

    Box(Modifier.fillMaxSize().background(c.bgDefault)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            OrmoTopNav(onBack = onBack)
            Column(Modifier.padding(horizontal = Space.s20)) {
                OrmoField(
                    value = query,
                    onValueChange = vm::onQueryChanged,
                    placeholder = "메뉴 이름으로 검색해보세요",
                    autoFocus = true, // 아이콘을 누르면 키보드가 바로 올라온다
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { }),
                )
            }
            Box(Modifier.padding(top = Space.s8))
            val canAdd = availability() is OrderAvailability.Available
            when (val v = view) {
                is SearchView.Start -> SearchStart(v, vm, canAdd, snackbar)
                is SearchView.Results -> LazyColumn(Modifier.weight(1f)) {
                    item {
                        Text(v.countLabel, style = OrmoType.bodySmSemibold, color = c.textTertiary, modifier = Modifier.padding(horizontal = Space.pageMargin, vertical = Space.s8))
                    }
                    items(v.hits, key = { it.item.id }) { hit ->
                        val rowState = MenuHomeRules.rowState(hit.item, availability())
                        MenuRow(
                            item = hit.item,
                            state = rowState,
                            quantity = 0,
                            badge = if (!rowState.tappable) BadgeKind.SoldOut else null,
                            highlight = hit.range,
                            onClick = { snackbar.show(vm.onMenuClicked(hit.item, rowState.canAdd), SnackbarDurations.ADDED) },
                        )
                    }
                }
                is SearchView.NoResult -> Column(Modifier.weight(1f)) {
                    Text(v.title, style = OrmoType.bodyXlBold, color = c.textPrimary, modifier = Modifier.padding(start = Space.pageMargin, end = Space.pageMargin, top = Space.s24))
                    Text(v.hint, style = OrmoType.bodyMdRegular, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.pageMargin).padding(top = Space.s4))
                    if (v.showsPopular) PopularList(v.popular, vm, canAdd, snackbar, title = "이 매장 인기 메뉴")
                }
            }
        }
        OrmoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter), bottomOffset = 24.dp)
    }
}

@Composable
private fun SearchStart(v: SearchView.Start, vm: MenuSearchViewModel, canAdd: Boolean, snackbar: OrmoSnackbarState) {
    val c = Ormo.colors
    LazyColumn(Modifier.fillMaxSize()) {
        // 기록이 없는 묶음은 숨긴다
        if (v.showsRecent) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.pageMargin, vertical = Space.s8), verticalAlignment = Alignment.CenterVertically) {
                    Text("최근 검색어", style = OrmoType.bodyLgBold, color = c.textStrong, modifier = Modifier.weight(1f))
                    OrmoButton("전체 삭제", vm::clearRecent, kind = ButtonKind.Ghost, size = ButtonSize.S)
                }
            }
            items(v.recent, key = { "r-$it" }) { term ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { vm.searchRecent(term) }.padding(start = Space.pageMargin, end = Space.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(term, style = OrmoType.bodyMdRegular, color = c.textPrimary, modifier = Modifier.weight(1f).padding(vertical = Space.s12))
                    Box(Modifier.size(44.dp).clickable(role = Role.Button, onClickLabel = "$term 삭제") { vm.removeRecent(term) }, contentAlignment = Alignment.Center) {
                        Icon(OrmoIcons.Close, contentDescription = null, tint = c.iconSecondary, modifier = Modifier.size(Size.iconMd))
                    }
                }
            }
            item { Box(Modifier.padding(top = Space.s16)) }
        }
        if (v.showsPopular) {
            item { PopularList(v.popular, vm, canAdd, snackbar, title = "많이 찾는 메뉴") }
        }
    }
}

@Composable
private fun PopularList(items: List<MenuItem>, vm: MenuSearchViewModel, canAdd: Boolean, snackbar: OrmoSnackbarState, title: String) {
    val c = Ormo.colors
    Column(Modifier.padding(top = Space.s24)) {
        Text(title, style = OrmoType.bodyLgBold, color = c.textStrong, modifier = Modifier.padding(horizontal = Space.pageMargin).padding(bottom = Space.s8))
        items.forEachIndexed { i, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { snackbar.show(vm.onMenuClicked(item, canAdd && !item.soldOut), SnackbarDurations.ADDED) }
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
