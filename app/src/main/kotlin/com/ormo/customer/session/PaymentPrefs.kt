package com.ormo.customer.session

import android.content.Context
import com.ormo.data.payment.PaymentMethodStore
import com.ormo.data.payment.PendingPayment
import com.ormo.data.payment.PendingPaymentStore
import com.ormo.domain.order.PaymentMethod
import com.ormo.domain.payment.PlacedOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 결제 확인 중에 앱이 꺼져도 이어갈 수 있도록 진행 중인 주문을 저장해 둔다 (3.2.2: "앱을 나갔다 들어와도 서버에 다시 묻고 상태 맞춤").
 * 결제 금액·주문 번호 정도라 민감 정보는 아니지만 계정 데이터라 로그아웃·탈퇴 때 지운다. 깨진 값은 '없음'.
 */
class PrefsPendingPaymentStore(context: Context) : PendingPaymentStore {
    private val prefs = context.getSharedPreferences("ormo_pending_payment", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): PendingPayment? = withContext(Dispatchers.IO) {
        val raw = prefs.getString("v", null) ?: return@withContext null
        try {
            val o = json.parseToJsonElement(raw) as JsonObject
            PendingPayment(
                order = PlacedOrder(
                    orderId = (o["orderId"] as JsonPrimitive).content,
                    amount = (o["amount"] as JsonPrimitive).intOrNull ?: return@withContext null,
                    fingerprint = (o["fingerprint"] as JsonPrimitive).content,
                    expiresAtMs = (o["expiresAtMs"] as? JsonPrimitive)?.longOrNull,
                    removedMenuIds = (o["removed"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                ),
                key = (o["key"] as JsonPrimitive).content,
                failedCount = (o["failedCount"] as JsonPrimitive).intOrNull ?: 0,
            )
        } catch (e: Exception) {
            prefs.edit().remove("v").apply()
            null
        }
    }

    override suspend fun save(pending: PendingPayment): Unit = withContext(Dispatchers.IO) {
        val o = buildJsonObject {
            put("orderId", pending.order.orderId)
            put("amount", pending.order.amount)
            put("fingerprint", pending.order.fingerprint)
            pending.order.expiresAtMs?.let { put("expiresAtMs", it) }
            put("removed", JsonArray(pending.order.removedMenuIds.map { JsonPrimitive(it) }))
            put("key", pending.key)
            put("failedCount", pending.failedCount)
        }
        prefs.edit().putString("v", o.toString()).apply()
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) { prefs.edit().remove("v").apply() }
}

/** 마지막에 쓴 결제수단을 계정별로 기억한다 (2.3) */
class PrefsPaymentMethodStore(context: Context) : PaymentMethodStore {
    private val prefs = context.getSharedPreferences("ormo_payment_method", Context.MODE_PRIVATE)

    override suspend fun load(accountKey: String): PaymentMethod? = withContext(Dispatchers.IO) {
        val id = prefs.getString("$accountKey.id", null) ?: return@withContext null
        val label = prefs.getString("$accountKey.label", null) ?: return@withContext null
        PaymentMethod(id, label)
    }

    override suspend fun save(accountKey: String, method: PaymentMethod): Unit = withContext(Dispatchers.IO) {
        prefs.edit().putString("$accountKey.id", method.id).putString("$accountKey.label", method.label).apply()
    }
}
