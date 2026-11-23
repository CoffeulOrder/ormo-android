package com.ormo.customer.ui.store

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import com.ormo.customer.ui.components.BadgeKind
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.DelayedSkeleton
import com.ormo.customer.ui.components.InfoRow
import com.ormo.customer.ui.components.MapPin
import com.ormo.customer.ui.components.OrmoBadge
import com.ormo.customer.ui.components.OrmoBottomSheet
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.OrmoTopNav
import com.ormo.customer.ui.components.ImagePlaceholder
import com.ormo.customer.ui.components.Shoes
import com.ormo.customer.ui.components.SkeletonBlock
import com.ormo.customer.ui.theme.Base
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space
import com.ormo.data.store.StoreRepository
import com.ormo.domain.store.Store
import com.ormo.domain.store.StoreChangeDecision
import com.ormo.domain.store.StoreInfoRules
import com.ormo.domain.store.StoreRules
import com.ormo.domain.store.StoreStatus
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
    /** 매장 행을 눌러 시트가 열린 매장 (1.1.2) */
    val selected: Store? = null,
    /** 다른 매장 메뉴가 장바구니에 있을 때 시트에 띄우는 경고 */
    val conflict: StoreChangeDecision.ConfirmClearCart? = null,
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

    /** 매장 행을 눌렀다 → 매장 시트(위치·운영시간, 장바구니 경고)를 연다 (Figma 1.1.2) */
    fun onStoreClicked(store: Store) {
        val d = StoreRules.decideStoreChange(cart.storeId, cart.cart.value.count, store.id)
        _ui.update { it.copy(selected = store, conflict = d as? StoreChangeDecision.ConfirmClearCart) }
    }

    fun dismissSheet() = _ui.update { it.copy(selected = null, conflict = null) }

    /** [주문하기]: 다른 매장 메뉴가 담겨 있으면 장바구니를 비우고(되돌릴 수 없음) 그 매장 메뉴로 (1.1.2) */
    fun confirmSelected() {
        val id = _ui.value.selected?.id ?: return
        if (_ui.value.conflict != null) cart.clear()
        dismissSheet()
        viewModelScope.launch {
            repo.select(accountKey(), id) // 마지막에 고른 매장을 기억해 다음엔 바로 메뉴로
            _go.send(id)
        }
    }

    suspend fun loadInfo(storeId: String) = repo.loadInfo(storeId)

    companion object {
        fun factory(c: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = StoreSelectViewModel(c.stores, c.cart) { c.accountKey } as T
        }
    }
}

/**
 * 매장 선택 (Figma 1.1.1): 제목 '어느 카페에서 주문할까요?' + 학교 이름, 매장 카드(이름 + 영업 상태, 주소, 거리·도보 시간, 사진 90×90).
 * 영업 종료 카드는 내용 50% 흐리게 (메뉴는 볼 수 있음). 카드를 누르면 **매장 시트**(위치·운영시간·[주문하기], 장바구니 경고)가 열린다.
 * 불러오는 동안 스켈레톤, 실패하면 '매장 정보를 불러오지 못했어요' + [다시 시도]. 가입 직후 첫 진입에는 뒤로가기를 숨긴다.
 * 거리·도보 시간은 위치 권한이 있을 때만 — 권한 요청은 아직 없어 표시하지 않는다. 사진은 아직 없어 자리 표시만.
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
        Column(Modifier.padding(start = Space.s20, end = Space.s20, top = 20.dp, bottom = 10.dp)) { // Figma: 제목 글자 y=126, 학교 이름 아래 카드까지 26
            Text("어느 카페에서 주문할까요?", style = OrmoType.displayXsSemibold, color = c.textPrimary, modifier = Modifier.semantics { heading() })
            ui.stores.firstOrNull()?.schoolName?.let { Text(it, style = OrmoType.bodyMdRegular, color = c.textSecondary, modifier = Modifier.padding(top = Space.s4)) }
        }
        Box(Modifier.height(16.dp))
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
                    Column(Modifier.padding(horizontal = Space.s20), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                        repeat(3) { SkeletonBlock(Modifier.fillMaxWidth().height(90.dp)) }
                    }
                },
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.stores, key = { it.id }) { store -> StoreCard(store, nowLocal, onClick = { vm.onStoreClicked(store) }) }
                }
            }
        }
    }

    ui.selected?.let { store ->
        StoreSheet(store, vm, ui.conflict, nowLocal, onInfo = { vm.dismissSheet(); onInfo(store.id) })
    }
}

/** 매장 카드: 패딩 16/20, 간격 12, 사진 90 (radius 8), 제목 body-md semibold + 상태 body-sm medium, 주소 body-sm, 거리·도보 (Figma Store Card) */
@Composable
private fun StoreCard(store: Store, now: LocalDateTime, onClick: () -> Unit) {
    val c = Ormo.colors
    val label = StoreRules.statusLabel(store.status, now)
    val dimmed = StoreRules.dimmed(store.status)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s20, vertical = Space.s16)
            .semantics { contentDescription = "${store.displayName}, ${label.text}${label.detail?.let { ", $it" }.orEmpty()}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        Column(Modifier.weight(1f).alpha(if (dimmed) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s8), verticalAlignment = Alignment.CenterVertically) {
                Text(store.displayName, style = OrmoType.bodyMdSemibold, color = c.textPrimary, modifier = Modifier.weight(1f, fill = false))
                Text(
                    label.text,
                    style = OrmoType.bodySmMedium,
                    color = when (store.status) {
                        is StoreStatus.Open -> c.textBrand
                        is StoreStatus.Break -> c.textWarning
                        else -> c.textTertiary
                    },
                    maxLines = 1,
                )
            }
            label.detail?.let { Text(it, style = OrmoType.bodySmRegular, color = c.textTertiary) }
            store.address?.let { Text(it, style = OrmoType.bodySmRegular, color = c.textSecondary) }
            // 거리·도보 시간은 위치 권한이 있을 때만 (권한 요청 연결 전: 표시 안 함)
            StoreRules.distanceLabel(store, null, null)?.let { distance ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s12), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                        Icon(OrmoIcons.MapPin, null, tint = c.iconSecondary, modifier = Modifier.size(Size.iconMd))
                        Text(distance.substringBefore(" ·"), style = OrmoType.bodySmRegular, color = c.textTertiary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                        Icon(OrmoIcons.Shoes, null, tint = c.iconSecondary, modifier = Modifier.size(Size.iconMd))
                        Text(distance.substringAfter("· 도보 "), style = OrmoType.bodySmRegular, color = c.textTertiary)
                    }
                }
            }
        }
        ImageFrame(Modifier.alpha(if (dimmed) 0.5f else 1f))
    }
}

/** 이미지 틀 (Figma Image Frame): 90×90, radius 8, Fill/Neutral Weak 바탕 + 1px 안쪽 선 + 가운데 이미지 아이콘 (사진이 아직 없어 자리 표시) */
@Composable
private fun ImageFrame(modifier: Modifier = Modifier) {
    val c = Ormo.colors
    Box(
        modifier
            .size(90.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(c.fillNeutralWeak)
            .border(1.dp, c.borderDefault, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(OrmoIcons.ImagePlaceholder, null, tint = c.iconQuaternary, modifier = Modifier.size(32.dp))
    }
}

/**
 * 매장 시트 (Figma 1.1.2): 매장 이름 + 상태 뱃지 + (i)(→ 매장 정보), 위치, 운영시간, 장바구니에 다른 매장 메뉴가 있으면 경고 상자
 * '매장을 변경하면 장바구니의 메뉴가 삭제돼요', [주문하기].
 */
@Composable
private fun StoreSheet(store: Store, vm: StoreSelectViewModel, conflict: StoreChangeDecision.ConfirmClearCart?, now: LocalDateTime, onInfo: () -> Unit) {
    val c = Ormo.colors
    val info by produceState<com.ormo.domain.store.StoreInfo?>(null, store.id) { value = (vm.loadInfo(store.id) as? Outcome.Ok)?.value }
    val label = StoreRules.statusLabel(store.status, now)
    OrmoBottomSheet(
        onDismissRequest = vm::dismissSheet,
        footer = { OrmoButton("주문하기", vm::confirmSelected, Modifier.fillMaxWidth(), size = ButtonSize.XL) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8), modifier = Modifier.padding(bottom = Space.s16)) {
            Text(store.displayName, style = OrmoType.bodyXlBold, color = c.textStrong, modifier = Modifier.weight(1f, fill = false))
            OrmoBadge(
                label.text,
                when (store.status) { is StoreStatus.Open -> BadgeKind.Optional; is StoreStatus.Break -> BadgeKind.Warning; else -> BadgeKind.SoldOut },
            )
            Box(Modifier.weight(1f))
            Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = "매장 정보", onClick = onInfo), contentAlignment = Alignment.Center) {
                Icon(OrmoIcons.Info, null, tint = c.iconTertiary, modifier = Modifier.size(Size.iconLg))
            }
        }
        val i = info
        if (i != null) {
            Text("위치", style = OrmoType.bodyLgSemibold, color = c.textPrimary)
            i.location?.let { Text(it, style = OrmoType.bodyMdRegular, color = c.textSecondary, modifier = Modifier.padding(top = Space.s4, bottom = Space.s12)) }
            Text("운영시간", style = OrmoType.bodyLgSemibold, color = c.textPrimary, modifier = Modifier.padding(bottom = Space.s8))
            Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                StoreInfoRules.hoursLines(i.weeklyHours).forEach { HourRow(it.label, it.value) }
            }
        }
        if (conflict != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = Space.s16).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(c.fillWarningWeak).padding(Space.s12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s8),
            ) {
                Icon(OrmoIcons.Alert, null, tint = c.iconWarning, modifier = Modifier.size(Size.iconMd))
                Text(conflict.title, style = OrmoType.bodySmRegular, color = c.textPrimary)
            }
        }
    }
}

@Composable
private fun HourRow(label: String, value: String) {
    val c = Ormo.colors
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = OrmoType.bodyMdRegular, color = c.textTertiary, modifier = Modifier.weight(1f))
        Text(value, style = OrmoType.bodyMdRegular, color = c.textPrimary)
    }
}
