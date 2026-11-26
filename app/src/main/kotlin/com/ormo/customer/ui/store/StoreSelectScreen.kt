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
import androidx.compose.runtime.setValue
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
import com.ormo.customer.ui.components.OrmoBadge
import com.ormo.customer.ui.components.OrmoBottomSheet
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.OrmoTopNav
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
    /** 위치 관련 기기 설정 (내 위치 사용 · 약관 동의 · 안내 시트를 보여 줬는가) */
    val locationPref: com.ormo.customer.session.PrefsLocationPreference,
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
            override fun <T : ViewModel> create(modelClass: Class<T>): T = StoreSelectViewModel(c.stores, c.cart, c.locationPreference) { c.accountKey } as T
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

    // ── 위치 (Figma 1.1.1 위치 권한 · 5.5.1) ──
    val context = androidx.compose.ui.platform.LocalContext.current
    val useLocation by vm.locationPref.useLocation.collectAsState()
    val consented by vm.locationPref.consented.collectAsState()
    val asked by vm.locationPref.asked.collectAsState()
    var osGranted by remember { androidx.compose.runtime.mutableStateOf(com.ormo.customer.session.hasCoarseLocationPermission(context)) }
    var deniedOnce by remember { androidx.compose.runtime.mutableStateOf(false) }
    var showAsk by remember { androidx.compose.runtime.mutableStateOf(false) }
    var showTerms by remember { androidx.compose.runtime.mutableStateOf(false) }
    // 설정에서 돌아올 때마다 휴대폰 위치 권한을 다시 확인한다
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) osGranted = com.ormo.customer.session.hasCoarseLocationPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        osGranted = granted
        deniedOnce = !granted
    }
    // 처음 들어오면 (한 번도 안 물었고 권한이 없으면) 위치 권한 안내 시트를 한 번만 띄운다
    LaunchedEffect(ui.loading, ui.failed, asked, osGranted) {
        if (!ui.loading && !ui.failed && !asked && !osGranted) showAsk = true
    }
    val locationOn = osGranted && useLocation
    var userLocation by remember { androidx.compose.runtime.mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(locationOn) { userLocation = if (locationOn) com.ormo.customer.session.currentApproxLocation(context) else null }
    val startLocation = {
        when {
            !consented -> showAsk = true
            !osGranted && deniedOnce -> com.ormo.customer.ui.myinfo.openAppDetailsSettings(context) // 이미 한 번 거절 → 휴대폰 설정에서 켠다
            !osGranted -> permissionLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            else -> vm.locationPref.set(true)
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgDefault)) {
        OrmoTopNav(onBack = if (hideBack) null else onBack)
        Column(Modifier.padding(start = Space.s20, end = Space.s20, top = 20.dp, bottom = 10.dp)) { // Figma: 제목 글자 y=126, 학교 이름 아래 카드까지 26
            Text("어느 카페에서 주문할까요?", style = OrmoType.displayXsSemibold, color = c.textPrimary, modifier = Modifier.semantics { heading() })
            ui.stores.firstOrNull()?.schoolName?.let { Text(it, style = OrmoType.bodyMdRegular, color = c.textSecondary, modifier = Modifier.padding(top = Space.s4)) }
        }
        Box(Modifier.height(16.dp))
        when {
            ui.failed -> com.ormo.customer.ui.components.OrmoResult(
                icon = OrmoIcons.WifiOff,
                title = "매장 정보를 불러오지 못했어요",
                body = "잠시 후 다시 시도해주세요",
                action = "다시 시도" to vm::load,
            )
            else -> DelayedSkeleton(
                loading = ui.loading,
                skeleton = {
                    Column(Modifier.padding(horizontal = Space.s20), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                        repeat(3) { SkeletonBlock(Modifier.fillMaxWidth().height(90.dp)) }
                    }
                },
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    // 위치가 꺼져 있으면 목록 위에 안내 (Figma 1.1.1 위치 꺼짐): 안내 시트를 이미 한 번 본 뒤에만
                    if (!locationOn && asked) item(key = "location-callout") { LocationCallout(onTurnOn = startLocation) }
                    items(ui.stores, key = { it.id }) { store ->
                        StoreCard(store, nowLocal, distance = StoreRules.distanceLabel(store, userLocation?.first, userLocation?.second), onClick = { vm.onStoreClicked(store) })
                    }
                }
            }
        }
    }

    ui.selected?.let { store ->
        StoreSheet(store, vm, ui.conflict, nowLocal, onInfo = { vm.dismissSheet(); onInfo(store.id) })
    }

    if (showAsk) {
        LocationAskSheet(
            onDismiss = { showAsk = false; vm.locationPref.markAsked() }, // 한 번 답하면 다시 자동으로 띄우지 않는다
            onOpenTerms = { showTerms = true },
            onAgree = {
                showAsk = false
                vm.locationPref.setConsented(true)
                vm.locationPref.markAsked()
                if (!osGranted) permissionLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            },
        )
    }
    if (showTerms) {
        // TODO(spec 0.2.5 · 1.1.1): 위치기반서비스 이용약관 전문 화면은 주소 확정 전이라 안내만 띄운다
        com.ormo.customer.ui.components.OrmoDialog(
            title = "위치기반서비스 이용약관",
            body = "약관 전문 페이지는 준비 중이에요",
            confirmLabel = "확인",
            onConfirm = { showTerms = false },
        )
    }
}

/** 위치가 꺼져 있을 때 목록 위 안내 (Figma Callout · Warning): 아이콘 + '위치를 켜면 매장까지 거리를 볼 수 있어요' + [위치 켜기] */
@Composable
private fun LocationCallout(onTurnOn: () -> Unit) {
    val c = Ormo.colors
    Row(
        Modifier
            .padding(horizontal = Space.s20)
            .padding(bottom = Space.s8)
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(c.fillWarningWeak)
            .padding(horizontal = Space.s16, vertical = Space.s12),
        horizontalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        Icon(OrmoIcons.Alert, null, tint = c.iconWarning, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Text("위치를 켜면 매장까지 거리를 볼 수 있어요", style = OrmoType.bodySmRegular, color = c.textSecondary)
            Text(
                "위치 켜기",
                style = OrmoType.bodySmBold,
                color = c.textWarning,
                modifier = Modifier.clickable(role = Role.Button, onClick = onTurnOn),
            )
        }
    }
}

/**
 * 위치 권한 안내 시트 (Figma 1.1.1 · 0.2.5 약관 시트와 같은 틀): '가까운 매장을 알려드릴게요' + 위치 아이콘 + '[선택] 위치기반서비스 이용약관 동의 >' + [동의하기].
 * 처음엔 체크하지 않아 [동의하기]가 꺼져 있다. [동의하기]를 누른 뒤에야 휴대폰 위치 권한 창을 띄운다. 바깥을 누르거나 아래로 내리면 닫히고 다시 자동으로 묻지 않는다.
 */
@Composable
private fun LocationAskSheet(onDismiss: () -> Unit, onOpenTerms: () -> Unit, onAgree: () -> Unit) {
    val c = Ormo.colors
    var agreed by remember { androidx.compose.runtime.mutableStateOf(false) }
    com.ormo.customer.ui.components.OrmoBottomSheet(
        onDismissRequest = onDismiss,
        title = "가까운 매장을 알려드릴게요",
        footer = { OrmoButton("동의하기", onAgree, Modifier.fillMaxWidth(), size = ButtonSize.XL, enabled = agreed) },
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = Space.s24), contentAlignment = Alignment.Center) {
            Box(Modifier.size(64.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.fillBrandWeak), contentAlignment = Alignment.Center) {
                Icon(OrmoIcons.MapPin, null, tint = c.iconBrand, modifier = Modifier.size(32.dp))
            }
        }
        com.ormo.customer.ui.components.TermsRow(
            label = "[선택] 위치기반서비스 이용약관 동의",
            checked = agreed,
            onCheckedChange = { agreed = it },
            onOpen = onOpenTerms,
        )
    }
}

/** 매장 카드: 패딩 16/20, 간격 12, 사진 90 (radius 8), 제목 body-md semibold + 상태 body-sm medium, 주소 body-sm, 거리·도보 (Figma Store Card) */
@Composable
private fun StoreCard(store: Store, now: LocalDateTime, distance: String?, onClick: () -> Unit) {
    val c = Ormo.colors
    val label = StoreRules.statusLabel(store.status, now)
    val dimmed = StoreRules.dimmed(store.status)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = com.ormo.customer.ui.theme.pressIndication(), role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s20, vertical = Space.s16)
            .semantics { contentDescription = "${store.displayName}, ${label.text}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        Column(Modifier.weight(1f).alpha(if (dimmed) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            // 이름이 두 줄로 넘어가도 상태 글자는 **이름 첫 줄의 가운데**에 맞춘다 (상태 칸 높이 = 이름 한 줄 높이)
            val nameLineHeight = with(androidx.compose.ui.platform.LocalDensity.current) { OrmoType.bodyMdSemibold.lineHeight.toDp() }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s8), verticalAlignment = Alignment.Top) {
                Text(store.displayName, style = OrmoType.bodyMdSemibold, color = c.textPrimary, modifier = Modifier.weight(1f, fill = false))
                Box(Modifier.height(nameLineHeight), contentAlignment = Alignment.Center) {
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
            }
            store.address?.let { Text(it, style = OrmoType.bodySmRegular, color = c.textSecondary) }
            // 거리·도보 시간은 위치가 켜져 있을 때만 (기기 안에서 계산)
            distance?.let { distance ->
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
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.s8), modifier = Modifier.padding(bottom = Space.s16)) {
            // 매장 이름은 줄바꿈(자르지 않는다). 상태 뱃지는 이름 오른쪽, **이름 맨 윗줄의 세로 가운데**에 맞춘다. (i) 는 맨 오른쪽
            val firstLine = with(androidx.compose.ui.platform.LocalDensity.current) { OrmoType.bodyXlBold.lineHeight.toDp() }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                Text(
                    store.displayName,
                    style = OrmoType.bodyXlBold,
                    color = c.textStrong,
                    modifier = Modifier.weight(1f, fill = false).padding(top = (48.dp - firstLine) / 2),
                )
                Box(Modifier.padding(top = (48.dp - firstLine) / 2).height(firstLine), contentAlignment = Alignment.Center) {
                    OrmoBadge(
                        label.text,
                        when (store.status) { is StoreStatus.Open -> BadgeKind.Optional; is StoreStatus.Break -> BadgeKind.Warning; else -> BadgeKind.SoldOut },
                    )
                }
            }
            Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = "매장 정보", onClick = onInfo), contentAlignment = Alignment.Center) {
                // 면(fill) 아이콘 (Figma Icon/info)
                Icon(com.ormo.customer.ui.components.FigmaIcons.Info, null, tint = c.iconTertiary, modifier = Modifier.size(Size.iconLg))
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
