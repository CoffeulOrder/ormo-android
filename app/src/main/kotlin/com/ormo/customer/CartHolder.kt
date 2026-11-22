package com.ormo.customer

import com.ormo.core.Resettable
import com.ormo.domain.model.MenuItem
import com.ormo.domain.rules.CartChange
import com.ormo.domain.rules.CartState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 장바구니 상태를 화면들이 같이 보도록 들고 있는 곳. **임시**: 디자인 5.1.2 는 장바구니를 서버에 두고 로그아웃해도 남긴다고 하므로,
 * 서버 연동(M4/M8)이 되면 이 클래스가 서버와 동기화하는 저장소로 바뀐다. 지금은 메모리에만 있다.
 * 담긴 메뉴가 속한 매장([storeId])을 같이 기억해 다른 매장 메뉴를 담을 때 경고(1.1.2)에 쓴다.
 */
class CartHolder : Resettable {
    private val _cart = MutableStateFlow(CartState())
    val cart: StateFlow<CartState> = _cart

    @Volatile
    var storeId: String? = null
        private set

    /** 담기. 한도에 걸리면 변경 없이 결과의 limit 으로 알린다. */
    fun add(storeId: String, item: MenuItem, choiceIds: Collection<String>, qty: Int = 1): CartChange {
        val change = _cart.value.add(item, choiceIds, qty)
        if (change.limit == null) {
            _cart.value = change.state
            this.storeId = storeId
        }
        return change
    }

    /** 매장을 바꿀 때 비운다 (1.1.2) */
    fun clear() {
        _cart.update { CartState() }
        storeId = null
    }

    override fun reset() = clear()
}
