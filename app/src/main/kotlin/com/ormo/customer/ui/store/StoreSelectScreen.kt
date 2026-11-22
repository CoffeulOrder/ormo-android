package com.ormo.customer.ui.store

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.ormo.customer.AppContainer
import com.ormo.customer.CartHolder
import com.ormo.customer.ui.auth.rememberNowMs
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.DelayedSkeleton
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoDialog
import com.ormo.customer.ui.components.OrmoDivider
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.OrmoTopNav
import com.ormo.customer.ui.components.SkeletonBlock
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space
import com.ormo.data.store.StoreRepository
import com.ormo.domain.store.Store
import com.ormo.domain.store.StoreChangeDecision
import com.ormo.domain.store.StoreRules
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

data class StoreSelectUi(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val stores: List<Store> = emptyList(),
    /** 다른 매장 메뉴가 장바구니에 있을 때 띄우는 경고 (1.1.2) */
    val confirm: StoreChangeDecision.ConfirmClearCart? = null,
    val pendingStoreId: String? = null,
)

class StoreSelectViewModel(
    private val repo: StoreRepository,
    private val cart: CartHolder,
    private val accountKey: () -> String,
) : ViewModel() {
    private val _ui = MutableStateFlow(StoreSelectUi())
    val ui: StateFlow<StoreSelectUi> = _ui.asStateFlow()

    private val _go = Channel<String>(Channel.BUFFERED)

    /** 메뉴 홈으로 이동할 매장 id */
    val goToMenu = _go.receiveAsFlow()

    init { load() }

    /** 목록 불러오기 (처음 / [다시 시도]). 불러오는 동안 스켈레톤. */
    fun load() {
        _ui.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            when (val r = repo.loadStores()) {
                is Outcome.Ok -> _ui.update { it.copy(loading = false, stores = r.value.listable) }
                is Outcome.Fail -> _ui.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    fun onStoreClicked(storeId: String) {
        val count = cart.cart.value.count
        when (val d = StoreRules.decideStoreChange(cart.storeId, count, storeId)) {
            StoreChangeDecision.Proceed -> choose(storeId)
            is StoreChangeDecision.ConfirmClearCart -> _ui.update { it.copy(confirm = d, pendingStoreId = storeId) }
        }
    }

    fun dismissConfirm() = _ui.update { it.copy(confirm = null, pendingStoreId = null) }

    /** [주문하기]: 장바구니를 비우고 새 매장 메뉴로. 비운 장바구니는 되돌릴 수 없다 (1.1.2). */
    fun confirmChange() {
        val id = _ui.value.pendingStoreId ?: return
        cart.clear()
        dismissConfirm()
        choose(id)
    }

    private fun choose(storeId: String) {
        viewModelScope.launch {
            repo.select(accountKey(), storeId) // 마지막에 고른 매장을 기억해 다음엔 바로 메뉴로
            _go.send(storeId)
        }
    }

    companion object {
        fun factory(c: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = StoreSelectViewModel(c.stores, c.cart) { c.accountKey } as T
        }
    }
}

/**
 * 매장 선택 (디자인 1.1.1): 제목 '어느 카페에서 주문할까요?'. 매장 행에 상태(영업 중 / 잠시 쉬어요 + 다시 여는 시각 / 영업 종료), 영업 종료는 흐리게 (메뉴는 볼 수 있음).
 * (i) → 매장 정보. 불러오는 동안 스켈레톤, 실패하면 '매장 정보를 불러오지 못했어요' + [다시 시도].
 * 가입 직후 첫 진입에는 뒤로가기를 숨긴다. 메뉴가 없는 매장은 목록에서 제외 (`StoreRules.listable`).
 * 거리·도보 시간은 위치 권한이 있을 때만 — 권한 요청은 아직 없어 표시하지 않는다.
 */
@Composable
fun StoreSelectScreen(
    vm: StoreSelectViewModel,
    hideBack: Boolean,
    onBack: () -> Unit,
    onInfo: (storeId: String) -> Unit,
    onStoreChosen: (storeId: String) -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val c = Ormo.colors
    val now = rememberNowMs(active = true)
    val nowLocal = remember(now / 30_000) { LocalDateTime.now() }

    LaunchedEffect(Unit) { vm.goToMenu.collect(onStoreChosen) }
    if (!hideBack) BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(c.bgDefault)) {
        OrmoTopNav(onBack = if (hideBack) null else onBack)
        Text(
            "어느 카페에서 주문할까요?",
            style = OrmoType.displayXsSemibold,
            color = c.textPrimary,
            modifier = Modifier.padding(horizontal = Space.s20, vertical = Space.s12),
        )
        when {
            ui.failed -> Column(
                Modifier.fillMaxSize().padding(horizontal = Space.pageMargin),
                verticalArrangement = Arrangement.spacedBy(Space.s8, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("매장 정보를 불러오지 못했어요", style = OrmoType.bodyLgSemibold, color = c.textPrimary)
                Text("잠시 후 다시 시도해주세요", style = OrmoType.bodyMdRegular, color = c.textSecondary)
                OrmoButton("다시 시도", vm::load, Modifier.padding(top = Space.s16), kind = ButtonKind.WeakNeutral, size = ButtonSize.M)
            }
            else -> DelayedSkeleton(
                loading = ui.loading,
                skeleton = {
                    Column(Modifier.padding(horizontal = Space.pageMargin), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                        repeat(3) { SkeletonBlock(Modifier.fillMaxWidth().height(72.dp)) }
                    }
                },
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.stores, key = { it.id }) { store ->
                        StoreRow(store, nowLocal, onClick = { vm.onStoreClicked(store.id) }, onInfo = { onInfo(store.id) })
                        OrmoDivider(inset = true)
                    }
                }
            }
        }
    }

    ui.confirm?.let { d ->
        OrmoDialog(
            title = d.title,
            body = d.detail,
            confirmLabel = "주문하기",
            onConfirm = vm::confirmChange,
            dismissLabel = "취소",
            onDismiss = vm::dismissConfirm,
        )
    }
}

@Composable
private fun StoreRow(store: Store, now: LocalDateTime, onClick: () -> Unit, onInfo: () -> Unit) {
    val c = Ormo.colors
    val label = StoreRules.statusLabel(store.status, now)
    val dimmed = StoreRules.dimmed(store.status)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = Space.pageMargin, end = Space.s8)
            .semantics { contentDescription = "${store.name}, ${label.text}${label.detail?.let { ", $it" }.orEmpty()}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = Space.s16).alpha(if (dimmed) 0.4f else 1f), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            Text(store.name, style = OrmoType.bodyLgSemibold, color = c.textStrong)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                Text(
                    label.text,
                    style = OrmoType.bodySmSemibold,
                    color = when (store.status) {
                        is com.ormo.domain.store.StoreStatus.Open -> c.textBrand
                        is com.ormo.domain.store.StoreStatus.Break -> c.textWarning
                        else -> c.textTertiary
                    },
                )
                label.detail?.let { Text(it, style = OrmoType.bodySmRegular, color = c.textTertiary) }
            }
        }
        Box(Modifier.size(44.dp).clickable(role = Role.Button, onClickLabel = "${store.name} 매장 정보", onClick = onInfo), contentAlignment = Alignment.Center) {
            Icon(OrmoIcons.Info, contentDescription = null, tint = c.iconSecondary, modifier = Modifier.size(Size.iconLg))
        }
    }
}
