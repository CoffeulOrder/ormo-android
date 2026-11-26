package com.ormo.customer.ui.order

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.ormo.customer.ui.theme.ormoStatusBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentSize
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import com.ormo.customer.ui.theme.topInset
import com.ormo.customer.ui.auth.OrmoMark
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ormo.core.Outcome
import com.ormo.customer.AppContainer
import com.ormo.customer.ui.components.BottomCta
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.OrmoBottomSheet
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.SheetHeaderAlign
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space
import com.ormo.data.orders.NotificationPromptStore
import com.ormo.data.orders.OrderRepository
import com.ormo.domain.model.OrderStatus
import com.ormo.domain.orders.NotificationPromptRules
import com.ormo.domain.orders.Order
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CompleteUi(val order: Order? = null, val isFirstOrder: Boolean = false, val dismissCount: Int = 0)

/** 주문 완료(4.1.1)의 화면 뒤: 주문을 불러와 픽업 번호·내 앞 대기를 채우고, 알림 권한 안내 시트를 띄울지 정한다 (4.5.1). */
class OrderCompleteViewModel(
    private val orderId: String,
    private val repo: OrderRepository,
    private val prompts: com.ormo.customer.session.PrefsNotificationPromptStore,
    private val accountKey: () -> String,
) : ViewModel() {
    private val _ui = MutableStateFlow(CompleteUi())
    val ui: StateFlow<CompleteUi> = _ui

    init {
        viewModelScope.launch {
            val first = prompts.recordOrderPlaced(accountKey())
            val dismissed = prompts.dismissCount(accountKey())
            _ui.update { it.copy(isFirstOrder = first, dismissCount = dismissed) }
            (repo.load(orderId) as? Outcome.Ok)?.let { r -> _ui.update { it.copy(order = r.value) } }
            repo.refreshActive() // 홈 주문 현황 바
        }
    }

    fun shouldAskNotification(osGranted: Boolean): Boolean =
        NotificationPromptRules.shouldShow(osGranted, _ui.value.dismissCount, _ui.value.isFirstOrder)

    fun recordDismiss() { viewModelScope.launch { prompts.recordDismissal(accountKey()) } }

    companion object {
        fun factory(c: AppContainer, orderId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = OrderCompleteViewModel(orderId, c.orders, c.notificationPrompts) { c.accountKey } as T
        }
    }
}

/**
 * 주문 완료 (디자인 4.1.1 / Figma): 마스코트(임시) + '픽업 번호' + 큰 번호, 매장·내 앞 대기·현재 상태 카드, [주문 상태 보기] / [추가 주문하기].
 * 첫 주문에서 [주문 상태 보기]를 누르면 알림 권한 안내 시트(4.5.1)가 먼저 뜬다.
 */
@Composable
fun OrderCompleteScreen(vm: OrderCompleteViewModel, onStatus: (orderId: String) -> Unit, onMore: () -> Unit, orderId: String) {
    val c = Ormo.colors
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onStatus(orderId) }

    fun osGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    val order = ui.order
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bgDefault)) {
        val screenHeight = maxHeight
        // 하단 CTA 는 화면 위에 떠 있다 (위쪽 흐림 40 은 내용 위에 겹침) — 내용은 CTA 흐림 아래까지 쓰고, 모자라면 간격부터 줄이고, 그래도 모자라면 스크롤
        val ctaGuess = 40.dp + 56.dp + 8.dp + 56.dp + com.ormo.customer.ui.theme.ctaBottomPadding(Space.s32) // 실제 높이를 재기 전 첫 화면용 추정
        var ctaHeight by remember { mutableStateOf(ctaGuess) }
        val fogOverlap = 16.dp // 흐림(40) 중 내용이 겹쳐도 되는 높이 — 그 이상은 카드가 희미해지므로 간격을 줄여 피한다
        val tail = (ctaHeight - fogOverlap).coerceAtLeast(0.dp)
        val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.pageMargin), // 글자가 크거나 화면이 아주 작으면 스크롤 (카드가 잘리지 않게)
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Figma 4.1.1 의 간격(심볼 위 117 · 심볼↔'픽업 번호' 40 · 번호↔카드 28)을 기준으로, 화면이 낮으면 이 간격들만 비율로 줄인다
            AdaptiveStack(
                availableHeight = screenHeight - tail,
                preferred = listOf(topInset() + 67.dp, 40.dp, 0.dp, 28.dp),
                minimum = listOf(statusBar + 8.dp, 8.dp, 0.dp, 8.dp),
            ) {
                // 컵 + 초록 체크 배지 모션 (Figma 4.1.1, Lottie `ormo_cup_check_badge`): JSON 은 375×812 화면 구성이라, 심볼 자리(100.54×128, 화면 y=117)가 이 상자 모서리에 오도록 옮겨 그린다
                Box(Modifier.size(100.54.dp, 128.dp)) {
                    com.ormo.customer.ui.auth.OrmoLottie(
                        com.ormo.customer.R.raw.ormo_cup_check_badge,
                        modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(375.dp, 812.dp).offset(x = (-137.23).dp, y = (-117).dp),
                        fill = false,
                        recolor = listOf(com.ormo.customer.ui.auth.LottieRecolor("Badge border", c.bgDefault, stroke = true)), // 초록 배지 테두리 = 바탕색
                    )
                }
                Text("픽업 번호", style = OrmoType.bodyXlRegular, color = c.textSecondary) // Figma y=291 (QA: 2dp 아래로)
                Text(order?.pickupNumber?.toString() ?: "-", style = OrmoType.display2xlSemibold, color = c.textPrimary)
                if (order != null) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.bgLower).padding(Space.s20),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LabelValueRow("매장", order.storeName)
                        order.aheadCount?.let { LabelValueRow("내 앞 대기", "${it}건") }
                        LabelValueRow("현재 상태", statusText(order))
                    }
                }
            }
            Spacer(Modifier.height(tail))
        }
        // Figma 4.1.1: 오른쪽 위 닫기(X, 24) → 메뉴 홈 (F-032). 상단 내비게이션 높이 56, 터치 영역 48
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .ormoStatusBarsPadding()
                .padding(top = 4.dp, end = 4.dp)
                .size(48.dp)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "닫기", onClick = onMore),
            contentAlignment = Alignment.Center,
        ) {
            Icon(OrmoIcons.Close, contentDescription = "닫기", tint = c.textStrong, modifier = Modifier.size(24.dp))
        }
        BottomCta(Modifier.align(Alignment.BottomCenter), onHeightChange = { ctaHeight = it }) {
            OrmoButton("주문 상태 보기", {
                if (vm.shouldAskNotification(osGranted())) showSheet = true else onStatus(orderId)
            }, Modifier.fillMaxWidth(), size = ButtonSize.XL)
            OrmoButton("추가 주문하기", onMore, Modifier.fillMaxWidth(), kind = ButtonKind.WeakBrand, size = ButtonSize.XL)
        }
    }

    if (showSheet) {
        // 4.5.1 알림 권한 안내: [다음에] / [알림 받기]
        // Figma 4.5.1: 시트 위에서 제목 글자 +40, 안내 +74(제목과 6 간격), 종 +148(64), 버튼 +226, 아래 34
        OrmoBottomSheet(
            onDismissRequest = { showSheet = false },
            contentVerticalPadding = 0.dp,
            footerTopPadding = 14.dp,
            footerBottomPadding = 34.dp,
            footer = {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                    OrmoButton("다음에", { showSheet = false; vm.recordDismiss(); onStatus(orderId) }, Modifier.weight(1f), kind = ButtonKind.WeakNeutral, size = ButtonSize.XL)
                    OrmoButton("알림 받기", {
                        showSheet = false
                        if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else onStatus(orderId)
                    }, Modifier.weight(1f), size = ButtonSize.XL)
                }
            },
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(15.dp))
                Text(
                    NotificationPromptRules.TITLE,
                    style = OrmoType.bodyXlBold,
                    color = c.textStrong,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(NotificationPromptRules.BODY, style = OrmoType.bodyMdRegular, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                Box(Modifier.padding(top = 50.dp).size(64.dp).clip(CircleShape).background(c.fillBrandWeak), contentAlignment = Alignment.Center) {
                    Icon(OrmoIcons.Bell, null, tint = c.iconBrand, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

private fun statusText(o: Order): String = when (o.status) {
    OrderStatus.REQUESTED -> "매장에서 확인하고 있어요"
    OrderStatus.MAKING -> "음료를 만들고 있어요"
    OrderStatus.READY -> "음료가 준비됐어요"
    else -> "-"
}

/**
 * 위에서 아래로 쌓는 세로 배치. 각 자식 **앞**의 간격은 [preferred] 가 기본이고, 전체가 [availableHeight] 보다 길면
 * 간격만 [minimum] 까지 같은 비율로 줄인다. 그래도 길면 줄일 수 없으니 그대로 길어지고, 바깥의 스크롤이 맡는다.
 * 자식 높이(글자·카드)는 건드리지 않는다.
 */
@Composable
private fun AdaptiveStack(
    availableHeight: androidx.compose.ui.unit.Dp,
    preferred: List<androidx.compose.ui.unit.Dp>,
    minimum: List<androidx.compose.ui.unit.Dp>,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val w = constraints.maxWidth
        val placeables = measurables.map { it.measure(androidx.compose.ui.unit.Constraints(maxWidth = w)) }
        val n = placeables.size
        val pref = List(n) { preferred.getOrElse(it) { 0.dp }.roundToPx() }
        val min = List(n) { minOf(minimum.getOrElse(it) { 0.dp }.roundToPx(), pref[it]) }
        val fixed = placeables.sumOf { it.height }
        val need = fixed + pref.sum() - availableHeight.roundToPx()
        val reducible = pref.sum() - min.sum()
        val factor = if (need <= 0 || reducible <= 0) 0f else (need.toFloat() / reducible).coerceIn(0f, 1f)
        val gaps = List(n) { pref[it] - ((pref[it] - min[it]) * factor).roundToInt() }
        layout(w, fixed + gaps.sum()) {
            var y = 0
            placeables.forEachIndexed { i, p ->
                y += gaps[i]
                p.placeRelative((w - p.width) / 2, y)
                y += p.height
            }
        }
    }
}
