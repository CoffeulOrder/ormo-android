package com.ormo.customer.ui.order

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ormo.customer.AppContainer
import com.ormo.customer.CartHolder
import com.ormo.customer.fake.FakePaymentServer
import com.ormo.data.menu.MenuRepository
import com.ormo.data.payment.PaymentCoordinator
import com.ormo.data.payment.PaymentMethodStore
import com.ormo.data.store.StoreRepository
import com.ormo.domain.store.Store
import com.ormo.domain.home.MenuHomeRules
import com.ormo.domain.order.PaymentMethod
import com.ormo.domain.order.PickupMethod
import com.ormo.domain.payment.PaymentState
import com.ormo.domain.rules.CartLimit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 결제 수단 후보. **가짜 PG 용 임시 목록** — PG(KG이니시스 + 간편결제, 포트원 v2)가 확정되면 PG 가 주는 수단으로 바뀐다 (3.2.1).
 */
val FAKE_PAYMENT_METHODS = listOf(
    PaymentMethod("card", "신용·체크카드"),
    PaymentMethod("toss", "토스페이"),
    PaymentMethod("naver", "네이버페이"),
    PaymentMethod("kakao", "카카오페이"),
)

/**
 * 장바구니 + 주문서(2.3) 의 화면 뒤. 상태는 `:domain` 의 [com.ormo.domain.order.CheckoutState] 를 [CartHolder] 가 들고 있고,
 * 결제 진행은 앱 범위 [PaymentCoordinator] 가 맡는다 (이 ViewModel 이 사라져도 결제 확인은 이어진다).
 */
@OptIn(FlowPreview::class)
class CheckoutViewModel(
    private val storeId: () -> String?,
    private val cartHolder: CartHolder,
    private val menus: MenuRepository,
    private val payments: PaymentCoordinator,
    private val methods: PaymentMethodStore,
    private val accountKey: () -> String,
    private val appScope: CoroutineScope,
    val fakePg: FakePaymentServer,
    private val stores: StoreRepository,
) : ViewModel() {
    val checkout = cartHolder.checkout

    /** 수령 방법 위에 보이는 매장 이름·주소 (Figma 2.3.1) */
    private val _store = kotlinx.coroutines.flow.MutableStateFlow<Store?>(null)
    val store: StateFlow<Store?> = _store
    val payment: StateFlow<PaymentState> = payments.state

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            val id = storeId() ?: return@launch
            _store.value = (stores.loadStores() as? com.ormo.core.Outcome.Ok)?.value?.all?.firstOrNull { it.id == id }
        }
        // 기억해 둔 마지막 결제수단 (2.3)
        viewModelScope.launch {
            if (cartHolder.checkout.value.paymentMethod == null) {
                methods.load(accountKey())?.let { saved ->
                    cartHolder.update { it.choosePayment(FAKE_PAYMENT_METHODS.firstOrNull { m -> m.id == saved.id } ?: saved) }
                }
            }
        }
        // 메뉴가 갱신되면 장바구니를 최신 메뉴·품절에 맞춘다: 사라진 메뉴는 빠지고 가격·옵션·품절은 최신 값 (2.3: 담은 뒤 품절된 메뉴는 흐리게)
        viewModelScope.launch {
            combine(menus.state, menus.soldOut) { state, sold ->
                val id = storeId() ?: return@combine null
                val items = state.payload?.cafes?.firstOrNull { it.id == id }?.menus ?: return@combine null
                MenuHomeRules.applySoldOut(items, sold[id].orEmpty())
            }.collect { items ->
                if (items != null && !cartHolder.checkout.value.isEmpty) {
                    val r = cartHolder.checkout.value.reconcile(items)
                    if (r.state.cart != cartHolder.checkout.value.cart) cartHolder.update { it.copy(cart = r.state.cart) }
                    if (r.result.removed.isNotEmpty()) _messages.send("판매가 끝난 메뉴는 장바구니에서 빠졌어요")
                }
            }
        }
        // 주문서가 바뀌면(메뉴·옵션·수량·수령 방법) 이전 주문을 버리고 새 키로 시작한다. 결제수단만 바뀐 건 같은 주문.
        viewModelScope.launch {
            cartHolder.checkout.map { it.toDraft() }.distinctUntilChanged { a, b -> a?.fingerprint == b?.fingerprint }
                .collect { draft -> appScope.launch { payments.onDraftChanged(draft) } }
        }
    }

    fun choosePickup(p: PickupMethod) = cartHolder.update { it.choosePickup(p) }

    fun choosePayment(m: PaymentMethod) {
        cartHolder.update { it.choosePayment(m) }
        viewModelScope.launch { methods.save(accountKey(), m) }
    }

    fun setConsent(v: Boolean) = cartHolder.update { it.setConsent(v) }

    /** 수량 바꾸기. 0 이하면 삭제(+ 4초 되돌리기), 20개를 넘기면 한도 안내 (2.3) */
    fun setQty(key: String, qty: Int) {
        val r = cartHolder.checkout.value.setQty(key, qty, System.currentTimeMillis())
        cartHolder.update { r.state }
        if (r.limit == CartLimit.QTY_EXCEEDED) viewModelScope.launch { _messages.send("한 번에 20개까지 주문할 수 있어요") }
    }

    fun remove(key: String) = cartHolder.update { it.remove(key, System.currentTimeMillis()) }
    fun undo() = cartHolder.update { it.undo(System.currentTimeMillis()) }
    fun dismissUndo() = cartHolder.update { it.dismissUndo() }

    /** [결제하기]: 서버가 영업 상태·품절·금액을 다시 확인하고 결제창을 연다 (3.1.2). 연타해도 한 번 (`PaymentState.begin`). */
    fun pay() {
        val s = cartHolder.checkout.value
        val draft = s.toDraft() ?: return
        val method = s.paymentMethod ?: return
        if (!s.canPay(payment.value.busy)) return
        appScope.launch { payments.pay(draft, method) }
    }

    fun pollOnce() { appScope.launch { payments.pollOnce() } }
    fun giveUpWaiting() = payments.giveUpWaiting()
    fun dismissBlocked() = payments.dismissBlocked()
    fun clearPrepareError() = payments.clearPrepareError()
    /** 5번 초과·만료 뒤 주문서로 돌아온다: 새 주문이므로 취소 불가 동의는 다시 받는다 (주문마다 다시 체크, 2.3) */
    fun leaveTerminal() {
        cartHolder.update { it.setConsent(false) }
        appScope.launch { payments.leaveTerminal() }
    }
    fun checkExpiry() = payments.checkExpiry(System.currentTimeMillis())

    /** 결제가 끝났다: 결제된 줄은 비우고(품절로 빠진 줄은 남김) 주문 번호를 돌려준다. 결제 진행 상태도 정리. */
    fun onPaid(): String? {
        val orderId = payment.value.order?.orderId
        cartHolder.update { it.afterOrderPlaced() }
        appScope.launch { payments.leaveTerminal() }
        return orderId
    }

    companion object {
        fun factory(c: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CheckoutViewModel({ c.cart.storeId }, c.cart, c.menus, c.payments, c.paymentMethods, { c.accountKey }, c.appScope, c.fakePayments, c.stores) as T
        }
    }
}
