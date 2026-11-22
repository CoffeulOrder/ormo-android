package com.ormo.customer.ui.order

import com.ormo.customer.CartHolder
import com.ormo.data.menu.MenuRepository
import com.ormo.data.store.StoreRepository
import com.ormo.domain.home.MenuHomeRules
import com.ormo.domain.orders.Order
import com.ormo.domain.orders.ReorderPlan
import com.ormo.domain.orders.ReorderRules
import com.ormo.domain.payment.DialogText
import com.ormo.domain.store.StoreChangeDecision
import com.ormo.domain.store.StoreRules

/**
 * 재주문 (디자인 4.2.1 [재주문하기], 4.3.4 [재주문]): 지난 주문을 **현재 메뉴 기준**으로 다시 장바구니에 담고 주문서로 보낸다.
 * 모두 품절이면 장바구니로 가지 않고 다이얼로그, 다른 매장 메뉴가 장바구니에 있으면 먼저 경고(1.1.2).
 * 일부만 품절이면 담고 장바구니의 '품절' 뱃지로 알린다 (결제 때 빠짐).
 */
class Reorderer(
    private val menus: MenuRepository,
    private val cart: CartHolder,
    private val stores: StoreRepository,
    private val accountKey: () -> String,
) {
    sealed interface Prep {
        data class Dialog(val text: DialogText) : Prep
        data class ConfirmClear(val plan: ReorderPlan, val count: Int) : Prep
        data class Ready(val plan: ReorderPlan) : Prep
    }

    fun prepare(order: Order): Prep {
        val items = menus.state.value.payload?.cafes?.firstOrNull { it.id == order.storeId }?.menus.orEmpty()
        val withSoldOut = MenuHomeRules.applySoldOut(items, menus.soldOut.value[order.storeId].orEmpty())
        val plan = ReorderRules.plan(order, withSoldOut)
        if (!plan.canGoToCart) return Prep.Dialog(ReorderPlan.ALL_SOLD_OUT)
        return when (val d = StoreRules.decideStoreChange(cart.storeId, cart.cart.value.lines.size, order.storeId)) {
            is StoreChangeDecision.ConfirmClearCart -> Prep.ConfirmClear(plan, d.itemCount)
            StoreChangeDecision.Proceed -> Prep.Ready(plan)
        }
    }

    /** 담고 이 매장을 마지막 매장으로 기억한다. 한도에 걸려 일부만 담겼어도 주문서로 간다. */
    suspend fun commit(order: Order, plan: ReorderPlan, clearFirst: Boolean) {
        if (clearFirst) cart.clear()
        cart.applyReorder(order.storeId, plan)
        stores.select(accountKey(), order.storeId)
    }
}
