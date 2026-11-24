package com.ormo.customer.ui.order

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ormo.customer.ui.components.Cart
import com.ormo.customer.ui.auth.MarkBadge
import com.ormo.customer.ui.auth.OrmoMark
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ormo.core.formatWon
import com.ormo.customer.ui.auth.rememberNowMs
import com.ormo.customer.ui.components.BadgeKind
import com.ormo.customer.ui.components.BottomCta
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.OrmoBadge
import com.ormo.customer.ui.components.OrmoBottomSheet
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoCheckbox
import com.ormo.customer.ui.components.OrmoDialog
import com.ormo.customer.ui.components.OrmoDivider
import com.ormo.customer.ui.components.OrmoRadio
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.OrmoSnackbarHost
import com.ormo.customer.ui.components.OrmoSnackbarState
import com.ormo.customer.ui.components.OrmoStepper
import com.ormo.customer.ui.components.OrmoThickDivider
import com.ormo.customer.ui.components.OrmoTopNav
import com.ormo.customer.ui.components.SnackbarDurations
import com.ormo.customer.ui.menu.badgeLabel
import com.ormo.customer.ui.theme.Base
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Shapes
import com.ormo.customer.ui.theme.Size
import com.ormo.customer.ui.theme.Space
import com.ormo.domain.order.CheckoutState
import com.ormo.domain.order.PaymentMethod
import com.ormo.domain.order.PickupMethod
import com.ormo.domain.store.Store
import com.ormo.domain.payment.PaymentMessages
import com.ormo.domain.payment.PaymentPhase
import com.ormo.domain.payment.PrepareError
import com.ormo.domain.rules.CartLine
import com.ormo.domain.rules.OptionRules
import java.time.LocalDateTime

/**
 * 장바구니 + 주문서 (디자인 2.3, 장바구니 시트와 3.1.1 주문서를 한 화면으로 합침) 와 결제 흐름 화면(3.1.2, 3.2.2, 3.3.1, 3.3.2).
 *
 * - 메뉴 줄: 같은 메뉴·옵션은 한 줄, 수량 −/+ (1개에서 − 는 삭제 + 4초 되돌리기 스낵바), 담은 뒤 품절된 줄은 흐리게 + '품절'.
 * - 수령 방법(매장 섭취 / 테이크아웃)은 꼭 골라야 하고, 결제수단은 마지막에 쓴 것을 기억.
 * - 결제 전 필수 동의: '매장이 주문을 받은 뒤에는 취소할 수 없어요' (주문마다 다시 체크). 체크해야 [결제하기]가 켜진다.
 * - 결제하면 **품절 메뉴를 빼고** 주문, 버튼 금액도 품절을 뺀 금액. 모두 품절일 때만 비활성.
 * - 결제 진행 단계별로 화면이 바뀐다: 영업 중지 다이얼로그 → 결제창 → 결제 확인 중(뒤로가기 막음) → 성공 / 실패(5번까지) / 5번 초과 / 만료.
 */
@Composable
fun CheckoutScreen(
    vm: CheckoutViewModel,
    onBack: () -> Unit,
    onViewMenu: () -> Unit,
    onPaid: (orderId: String) -> Unit,
) {
    val c = Ormo.colors
    val checkout by vm.checkout.collectAsState()
    val payment by vm.payment.collectAsState()
    val pgWindow by vm.fakePg.pgWindow.collectAsState()
    val snackbar = remember { OrmoSnackbarState() }
    var scrollToPayment by remember { mutableStateOf(0) }
    val store by vm.store.collectAsState()
    var failureDismissed by remember { mutableStateOf(false) }
    val now = rememberNowMs()
    val phase = payment.phase

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.show(it, SnackbarDurations.PARTIAL_FAILURE) } }
    // 결제창을 닫고 돌아와 있는 동안 만료 시각이 지났는지 본다 (3.3.2)
    LaunchedEffect(now / 1000) { vm.checkExpiry() }
    // 새 실패가 올 때마다 실패 화면을 다시 보인다
    LaunchedEffect(phase) { if (phase !is PaymentPhase.Failed) failureDismissed = false }

    // 삭제 되돌리기 스낵바 4초 (2.3)
    val undo = checkout.activeUndo(now)
    LaunchedEffect(checkout.removed) {
        val r = checkout.removed
        if (r != null) {
            snackbar.show(r.message, SnackbarDurations.UNDO, "되돌리기") { vm.undo() }
        }
    }

    // 결제 성공 → 주문 접수 (4.1.1)
    LaunchedEffect(phase == PaymentPhase.Succeeded) {
        if (phase == PaymentPhase.Succeeded) vm.onPaid()?.let(onPaid)
    }
    // 서버가 결제창 전에 막은 이유(네트워크 등)는 토스트로
    LaunchedEffect(payment.prepareError) {
        when (payment.prepareError) {
            PrepareError.NETWORK -> snackbar.show("인터넷 연결을 확인해주세요", SnackbarDurations.PARTIAL_FAILURE)
            PrepareError.ALL_SOLD_OUT -> snackbar.show("담은 메뉴가 모두 품절이에요", SnackbarDurations.PARTIAL_FAILURE)
            PrepareError.OTHER -> snackbar.show("잠시 후 다시 시도해주세요", SnackbarDurations.PARTIAL_FAILURE)
            null -> Unit
        }
        if (payment.prepareError != null) vm.clearPrepareError()
    }

    // 결제 확인 중(3.2.2)·준비 중·결제창에서는 뒤로가기를 막는다
    BackHandler(enabled = payment.backBlocked) { }

    Box(Modifier.fillMaxSize().background(c.bgDefault)) {
        when {
            phase is PaymentPhase.Confirming || phase == PaymentPhase.Succeeded -> ConfirmingScreen(
                checkFailed = (phase as? PaymentPhase.Confirming)?.checkFailed == true,
                onRecheck = vm::recheck,
                onGiveUp = vm::giveUpWaiting,
            )
            phase is PaymentPhase.Failed && !failureDismissed -> ResultScreen(
                title = PaymentMessages.failed(phase.code).title,
                body = PaymentMessages.failed(phase.code).body,
                primary = "다시 결제하기" to vm::pay,
                secondary = "결제수단 변경하기" to { failureDismissed = true; scrollToPayment++ },
            )
            phase == PaymentPhase.Exhausted -> ResultScreen(
                title = PaymentMessages.EXHAUSTED.title,
                body = PaymentMessages.EXHAUSTED.body,
                primary = "장바구니로 이동" to vm::leaveTerminal, // 장바구니 메뉴는 그대로
            )
            phase == PaymentPhase.Expired -> ResultScreen(
                title = PaymentMessages.EXPIRED.title,
                body = PaymentMessages.EXPIRED.body,
                primary = "다시 주문하기" to vm::leaveTerminal,
                expired = true,
            )
            else -> CheckoutContent(
                checkout = checkout,
                store = store,
                scrollToPayment = scrollToPayment,
                paying = payment.busy,
                onBack = onBack,
                onViewMenu = onViewMenu,
                onPickup = vm::choosePickup,
                onPaymentMethod = vm::choosePayment,
                onConsent = vm::setConsent,
                onQty = vm::setQty,
                onRemove = vm::remove,
                onPay = vm::pay,
            )
        }
        OrmoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter), bottomOffset = 150.dp)

        // 가짜 PG 창 (시험용): PG 가 확정되면 실제 결제창(Activity)으로 바뀐다
        pgWindow?.let { req ->
            FakePgWindow(
                orderId = req.orderId,
                amount = req.amount,
                methodLabel = req.method.label,
                onApprove = { vm.fakePg.approve(req.orderId) },
                onApproveSlow = { vm.fakePg.approveSlow(req.orderId) },
                onFail = { vm.fakePg.fail(req.orderId) },
                onClose = vm.fakePg::close,
                onExpire = { vm.fakePg.expire(req.orderId) },
            )
        }
    }

    // 3.1.2 주문 중지: 영업 종료 / 잠시 멈춤 — 결제창 전에 서버가 막았다. 품절은 막지 않고 빼고 결제한다.
    (phase as? PaymentPhase.Blocked)?.let { blocked ->
        val text = PaymentMessages.blocked(blocked.status, LocalDateTime.now())
        OrmoDialog(title = text.title, body = text.body, confirmLabel = "확인", onConfirm = vm::dismissBlocked, onDismissRequest = null)
    }
}

@Composable
private fun CheckoutContent(
    checkout: CheckoutState,
    store: Store?,
    scrollToPayment: Int,
    paying: Boolean,
    onBack: () -> Unit,
    onViewMenu: () -> Unit,
    onPickup: (PickupMethod) -> Unit,
    onPaymentMethod: (PaymentMethod) -> Unit,
    onConsent: (Boolean) -> Unit,
    onQty: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onPay: () -> Unit,
) {
    val c = Ormo.colors
    Column(Modifier.fillMaxSize()) {
        OrmoTopNav(title = "장바구니", onBack = onBack)
        if (checkout.isEmpty) {
            // 2.3.2 빈 상태 (Figma: 회색 원 60 + 장바구니 아이콘 y=268, 제목 16, 파란 [메뉴 보기] M)
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.pageMargin).padding(top = 268.dp - 106.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(60.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.bgLower), contentAlignment = Alignment.Center) {
                    Icon(OrmoIcons.Cart, null, tint = c.iconTertiary, modifier = Modifier.size(32.dp))
                }
                Text("장바구니가 비어 있어요", style = OrmoType.bodyLgSemibold, color = c.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
                OrmoButton("메뉴 보기", onViewMenu, Modifier.padding(top = 20.dp), size = ButtonSize.M)
            }
            return@Column
        }
        val scroll = rememberScrollState()
        // 결제 실패 뒤 [결제수단 변경하기] → 주문서의 결제수단 목록이 보이도록 맨 아래로
        LaunchedEffect(scrollToPayment) { if (scrollToPayment > 0) scroll.animateScrollTo(scroll.maxValue) }
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            // 수령 방법: 매장 이름·주소 + 라디오 두 개 (Figma 2.3.1)
            SectionLabel("수령 방법")
            if (store != null) {
                Column(Modifier.fillMaxWidth().padding(start = Space.pageMargin, end = Space.pageMargin, bottom = Space.s20), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                    Text(store.displayName, style = OrmoType.bodyMdSemibold, color = c.textPrimary)
                    if (!store.address.isNullOrBlank()) Text(store.address!!, style = OrmoType.bodySmRegular, color = c.textSecondary)
                }
                OrmoDivider()
            }
            Column(Modifier.fillMaxWidth().padding(Space.pageMargin), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
                RadioRow("매장 섭취", checkout.pickup == PickupMethod.DINE_IN) { onPickup(PickupMethod.DINE_IN) }
                RadioRow("테이크아웃", checkout.pickup == PickupMethod.TAKEOUT) { onPickup(PickupMethod.TAKEOUT) }
            }
            OrmoThickDivider()

            SectionLabel("주문 메뉴")
            checkout.cart.lines.forEach { line ->
                LineRow(line, soldOut = checkout.isSoldOut(line), amount = checkout.lineAmount(line), onQty = { onQty(line.key, it) }, onRemove = { onRemove(line.key) })
            }
            if (checkout.soldOutLines.isNotEmpty() && !checkout.allSoldOut) {
                Text(
                    "품절된 메뉴는 빼고 주문돼요",
                    style = OrmoType.bodySmMedium,
                    color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = Space.pageMargin, vertical = Space.s8),
                )
            }
            Spacer(Modifier.height(Space.s4))
            OrmoThickDivider()

            // 결제수단: 인라인 라디오 (Figma 2.3.1)
            SectionLabel("결제수단", bottomPadding = 0.dp)
            Column(Modifier.fillMaxWidth().padding(Space.pageMargin), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                FAKE_PAYMENT_METHODS.forEach { m ->
                    RadioRow(m.label, checkout.paymentMethod?.id == m.id) { onPaymentMethod(m) }
                }
            }
        }
        BottomCta {
            // 결제 전 필수 동의 (청약철회 제한 고지, 법적 필수). 총 결제 금액 줄은 따로 두지 않고 버튼에 금액을 표시한다.
            OrmoCheckbox(
                checked = checkout.consented,
                onCheckedChange = onConsent,
                label = "매장이 주문을 받은 뒤에는 취소할 수 없어요",
                gap = Space.s12,
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.s8),
            )
            OrmoButton(checkout.payLabel, onPay, Modifier.fillMaxWidth(), size = ButtonSize.XL, enabled = checkout.canPay(paying) || paying, loading = paying)
            // 보조 행동: 아래 Ghost 텍스트 버튼 (Text/Tertiary)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                OrmoButton("메뉴 추가하기", onViewMenu, kind = ButtonKind.Ghost)
            }
        }
    }
}

/** 섹션 제목: 18 Semibold, 위아래 12, 좌우 20 */
@Composable
private fun SectionLabel(text: String, bottomPadding: androidx.compose.ui.unit.Dp = Space.s12) {
    Text(
        text,
        style = OrmoType.bodyLgSemibold,
        color = Ormo.colors.textPrimary,
        modifier = Modifier.fillMaxWidth().padding(start = Space.pageMargin, end = Space.pageMargin, top = Space.s12, bottom = bottomPadding),
    )
}

/** 라디오 한 줄 (Figma Radio M 20 + 라벨 16 Regular, 간격 12) */
@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick), // Figma: 행 24 + 간격 12 (터치 영역을 늘리면 간격이 달라진다)
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        OrmoRadio(selected, size = 20.dp)
        Text(label, style = OrmoType.bodyMdRegular, color = Ormo.colors.textPrimary)
    }
}

/** 주문 메뉴 한 줄 (Figma): 이름 16 Semibold, 옵션 요약 14 Medium, 줄 금액 16 Semibold + 수량 알약(S 40) */
@Composable
private fun LineRow(line: CartLine, soldOut: Boolean, amount: Int, onQty: (Int) -> Unit, onRemove: () -> Unit) {
    val c = Ormo.colors
    val summary = OptionRules.optionSummary(line.item, line.choiceIds)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.pageMargin, vertical = Space.s8).alpha(if (soldOut) 0.4f else 1f),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
            Text(line.item.name, style = OrmoType.bodyMdSemibold, color = c.textPrimary)
            if (soldOut) OrmoBadge(badgeLabel(BadgeKind.SoldOut), BadgeKind.SoldOut)
        }
        if (summary.isNotEmpty()) Text(summary, style = OrmoType.bodySmMedium, color = c.textSecondary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(formatWon(amount), style = OrmoType.bodyMdSemibold, color = c.textPrimary, modifier = Modifier.weight(1f))
            OrmoStepper(
                quantity = line.qty,
                onMinus = { if (line.qty <= 1) onRemove() else onQty(line.qty - 1) },
                onPlus = { onQty(line.qty + 1) },
                plusEnabled = true,
                minusIsDelete = line.qty <= 1,
                small = true,
            )
        }
    }
}

/**
 * 결제 확인·결과 화면 공통 (Figma 3.2.2 / 3.3.1 / 3.3.2): 심볼 상자가 (137,233), 제목 글자는 y=421, 본문 24 줄간격 12 아래.
 * 확인 중 = 심볼만, 실패 = 빨간 ✕ 배지, 만료 = 짙은 남색 컵(김 없음) + 회색 시계 배지.
 */
@Composable
private fun StatusScreen(
    title: String,
    body: String,
    mark: @Composable () -> Unit,
    extra: @Composable () -> Unit = {},
    bottom: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null,
) {
    val c = Ormo.colors
    Column(Modifier.fillMaxSize().background(c.bgDefault)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.pageMargin).padding(top = 233.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            mark()
            Text(title, style = OrmoType.displayXsSemibold, color = c.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 55.dp).semantics { heading() })
            Text(body, style = OrmoType.bodyMdMedium, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            extra()
        }
        if (bottom != null) BottomCta(content = bottom)
    }
}

/** 결제 확인 중 (3.2.2): 뒤로가기 막음. 서버가 확정할 때까지. 확인이 계속 안 되면 [다시 확인] / [주문서로 나가기](주문은 유지). */
@Composable
private fun ConfirmingScreen(checkFailed: Boolean, onRecheck: () -> Unit, onGiveUp: () -> Unit) {
    val c = Ormo.colors
    StatusScreen(
        title = PaymentMessages.CONFIRMING.title,
        body = PaymentMessages.CONFIRMING.body,
        mark = { OrmoMark() },
        extra = {
            if (checkFailed) {
                // TODO(spec 3.2.2): 명세는 뒤로가기 막음뿐이라 갇히지 않게 하는 안내·탈출구는 가정
                Text("확인이 조금 늦어지고 있어요", style = OrmoType.bodyMdMedium, color = c.textTertiary, modifier = Modifier.padding(top = Space.s16))
            }
        },
        bottom = if (checkFailed) {
            {
                OrmoButton("다시 확인", onRecheck, Modifier.fillMaxWidth(), size = ButtonSize.XL)
                OrmoButton("주문서로 나가기", onGiveUp, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
            }
        } else {
            null
        },
    )
}

/** 결제 실패(3.3.1) / 5번 초과(3.3.1) / 만료(3.3.2) 공통 결과 화면: 심볼 + 제목 + 본문 + 버튼 1~2개 */
@Composable
private fun ResultScreen(
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
    expired: Boolean = false,
) {
    val c = Ormo.colors
    StatusScreen(
        title = title,
        body = body,
        mark = {
            if (expired) OrmoMark(cupTint = androidx.compose.ui.graphics.Color(0xFF001936), showSteam = false, badge = MarkBadge.Neutral)
            else OrmoMark(badge = MarkBadge.Danger)
        },
        bottom = {
            OrmoButton(primary.first, primary.second, Modifier.fillMaxWidth(), size = ButtonSize.XL)
            // Figma: 보조 버튼은 연한 파랑 채움 (결제수단 변경하기)
            if (secondary != null) OrmoButton(secondary.first, secondary.second, Modifier.fillMaxWidth(), kind = ButtonKind.WeakBrand, size = ButtonSize.XL)
        },
    )
}

/** 가짜 PG 결제창 (시험용). 시험하는 사람이 결과를 고른다. */
@Composable
private fun FakePgWindow(
    orderId: String,
    amount: Int,
    methodLabel: String,
    onApprove: () -> Unit,
    onApproveSlow: () -> Unit,
    onFail: () -> Unit,
    onClose: () -> Unit,
    onExpire: () -> Unit,
) {
    val c = Ormo.colors
    BackHandler { onClose() }
    Column(Modifier.fillMaxSize().background(c.bgLower).clickable(enabled = false) {}) {
        OrmoTopNav(title = "결제창 (가상·시험용)")
        Column(Modifier.weight(1f).padding(Space.pageMargin), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            Text("$methodLabel · ${formatWon(amount)}", style = OrmoType.bodyLgSemibold, color = c.textStrong)
            Text("주문 $orderId — PG 가 확정되면 실제 결제창으로 바뀌어요", style = OrmoType.bodySmRegular, color = c.textTertiary)
        }
        BottomCta {
            OrmoButton("결제 승인", onApprove, Modifier.fillMaxWidth(), size = ButtonSize.L)
            OrmoButton("결제 승인 (서버 반영이 늦음)", onApproveSlow, Modifier.fillMaxWidth(), kind = ButtonKind.WeakBrand, size = ButtonSize.L)
            OrmoButton("결제 실패 (한도 초과)", onFail, Modifier.fillMaxWidth(), kind = ButtonKind.WeakNeutral, size = ButtonSize.L)
            OrmoButton("주문 만료시키기", onExpire, Modifier.fillMaxWidth(), kind = ButtonKind.WeakNeutral, size = ButtonSize.L)
            OrmoButton("결제창 닫기", onClose, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
        }
    }
}
