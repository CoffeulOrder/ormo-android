package com.ormo.customer

import com.ormo.core.Resettable
import com.ormo.domain.model.MenuItem
import com.ormo.domain.order.CheckoutState
import com.ormo.domain.rules.CartChange
import com.ormo.domain.rules.CartState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 장바구니 + 주문서 상태([CheckoutState])를 화면들이 같이 보도록 들고 있는 곳.
 * **임시**: 디자인 5.1.2 는 장바구니를 서버에 두고 로그아웃해도 남긴다고 하므로, 서버 연동(M8)이 되면 서버와 동기화하는 저장소로 바뀐다.
 * 지금은 메모리에만 있다. 담긴 메뉴가 속한 매장([storeId])을 같이 기억해 다른 매장 메뉴를 담을 때 경고(1.1.2)에 쓴다.
 */
class CartHolder : Resettable {
    private val _checkout = MutableStateFlow(CheckoutState())

    /** 주문서까지 포함한 전체 상태 (장바구니 화면) */
    val checkout: StateFlow<CheckoutState> = _checkout

    private val _cart = MutableStateFlow(CartState())

    /** 담긴 메뉴만 (메뉴 홈의 수량 뱃지·장바구니 바) */
    val cart: StateFlow<CartState> = _cart

    @Volatile
    var storeId: String? = null
        private set

    fun update(f: (CheckoutState) -> CheckoutState) {
        _checkout.update(f)
        _cart.value = _checkout.value.cart
        if (_cart.value.isEmpty) storeId = null
    }

    /** 담기. 한도에 걸리면 변경 없이 결과의 limit 으로 알린다. */
    fun add(storeId: String, item: MenuItem, choiceIds: Collection<String>, qty: Int = 1): CartChange {
        val change = _checkout.value.cart.add(item, choiceIds, qty)
        if (change.limit == null) {
            update { it.copy(cart = change.state) }
            this.storeId = storeId
        }
        return change
    }

    /** 재주문(4.2.1·4.3.4): 지난 주문을 현재 메뉴로 다시 담는다. 한 줄이라도 담겼으면 이 장바구니의 매장이 된다. */
    fun applyReorder(storeId: String, plan: com.ormo.domain.orders.ReorderPlan): com.ormo.domain.orders.ReorderResult {
        val r = plan.applyTo(_checkout.value.cart)
        if (r.addedLines > 0) {
            update { it.copy(cart = r.cart) }
            this.storeId = storeId
        }
        return r
    }

    /** 매장을 바꿀 때 비운다 (1.1.2). 수령 방법·결제수단 선택도 처음으로. */
    fun clear() {
        _checkout.value = CheckoutState()
        _cart.value = CartState()
        storeId = null
    }

    override fun reset() = clear()
}
