package com.ormo.data.backend

import com.ormo.data.remote.AppError
import com.ormo.data.remote.boolValue
import com.ormo.data.remote.id
import com.ormo.data.remote.intValue
import com.ormo.data.remote.longValue
import com.ormo.data.remote.str
import com.ormo.domain.model.MenuItem
import com.ormo.domain.model.OptionChoice
import com.ormo.domain.model.OptionGroup
import com.ormo.domain.model.OrderStatus
import com.ormo.domain.orders.CancelActor
import com.ormo.domain.orders.Order
import com.ormo.domain.orders.OrderLine
import com.ormo.domain.payment.PaymentStatus
import com.ormo.domain.payment.PgCheckout
import com.ormo.domain.store.DayHours
import com.ormo.domain.store.SellerInfo
import com.ormo.domain.store.Store
import com.ormo.domain.store.StoreInfo
import com.ormo.domain.store.StoreStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * 실제 BE(`api.orso.it.kr`, FE·BE 연동 가이드 2026-11-24) 응답을 앱 모델로 바꾼다. 모양이 틀리면 [AppError.Schema].
 * 가이드/OpenAPI 에서 **확인하지 못한 값**은 `TODO(BE)` 로 표시했다 (BE_GUIDE_MAPPING_M8.md).
 */
internal object BackendParsers {

    private fun obj(raw: JsonElement?, at: String) = raw as? JsonObject ?: throw AppError.Schema(at)
    private fun arr(raw: JsonElement?, at: String) = raw as? JsonArray ?: throw AppError.Schema(at)

    fun instant(raw: JsonElement?): Instant? =
        raw.str()?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { Instant.parse(it) }.getOrNull() }

    // ───── 매장 ─────

    /** MS-5: `[ {storeId,name,location,status} ]`. 영업 상태는 OPEN / PAUSED / CLOSED 문자열 (TODO(BE): 재개·다음 영업 시각이 없어 null). */
    fun stores(raw: JsonElement?): List<Store> = arr(raw, "stores").mapIndexed { i, e ->
        val o = obj(e, "stores[$i]")
        Store(
            id = o["storeId"].id() ?: throw AppError.Schema("stores[$i].storeId"),
            name = o["name"].str() ?: throw AppError.Schema("stores[$i].name"),
            status = storeStatus(o["status"].str()),
            // TODO(BE): 메뉴 수·대기 건수가 목록에 없다. 0 이면 목록에서 빠지므로 '있다'고 가정한다.
            menuCount = 1,
            address = o["location"].str(),
        )
    }

    fun storeStatus(s: String?): StoreStatus = when (s?.uppercase()) {
        "OPEN" -> StoreStatus.Open
        "PAUSED", "BREAK" -> StoreStatus.Break(null)
        else -> StoreStatus.Closed(null)
    }

    /** MS-6 */
    fun storeInfo(raw: JsonElement?): StoreInfo {
        val o = obj(raw, "store")
        val seller = o["seller"] as? JsonObject
        return StoreInfo(
            storeId = o["storeId"].id() ?: throw AppError.Schema("store.storeId"),
            name = o["name"].str() ?: throw AppError.Schema("store.name"),
            location = o["location"].str(),
            weeklyHours = (o["businessHours"] as? JsonArray).orEmpty().mapNotNull { hours(it) },
            notice = o["notice"].str(),
            seller = SellerInfo(
                businessName = seller?.get("businessName").str(),
                representative = seller?.get("representativeName").str(),
                businessRegistrationNumber = seller?.get("businessRegNo").str(),
                mailOrderNumber = seller?.get("mailOrderRegNo").str(),
                address = seller?.get("businessAddress").str(),
                phone = seller?.get("contactPhone").str(),
            ),
        )
    }

    /** TODO(BE): dayOfWeek 값 범위 확인 — 1=월 … 7=일 (java.time) 로 가정, 0 이면 일요일로 본다. */
    private fun hours(e: JsonElement): DayHours? {
        val o = e as? JsonObject ?: return null
        val n = o["dayOfWeek"].intValue() ?: return null
        val day = when (n) {
            in 1..7 -> DayOfWeek.of(n)
            0 -> DayOfWeek.SUNDAY
            else -> return null
        }
        if (o["closed"].boolValue() == true) return DayHours(day, null, null)
        return DayHours(day, time(o["openTime"]), time(o["closeTime"]))
    }

    private fun time(raw: JsonElement?): LocalTime? = raw.str()?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

    // ───── 메뉴 ─────

    class MenuListItem(val menuId: String, val name: String, val desc: String, val price: Int, val imageUrl: String?, val soldOut: Boolean, val hasOptions: Boolean)

    /** MS-10 */
    fun menuList(raw: JsonElement?): List<MenuListItem> = arr(raw, "menus").mapIndexed { i, e ->
        val o = obj(e, "menus[$i]")
        val price = o["basePrice"].intValue()
        if (price == null || price < 0) throw AppError.Schema("menus[$i].basePrice")
        MenuListItem(
            menuId = o["menuId"].id() ?: throw AppError.Schema("menus[$i].menuId"),
            name = o["name"].str() ?: throw AppError.Schema("menus[$i].name"),
            desc = o["description"].str().orEmpty(),
            price = price,
            imageUrl = o["imageUrl"].str(),
            soldOut = o["soldOut"].boolValue() == true,
            hasOptions = o["hasOptions"].boolValue() == true,
        )
    }

    class Category(val id: String, val name: String, val sortOrder: Int)

    /** MS-9. 이름이 빈 카테고리가 실제로 온다 → 받아 두고, 그 메뉴는 앱에서 '기타' 로 묶는다. 이름 필드가 없거나 문자열이 아니면 오류. */
    fun categories(raw: JsonElement?): List<Category> = arr(raw, "categories").mapIndexed { i, e ->
        val o = obj(e, "categories[$i]")
        Category(
            id = o["categoryId"].id() ?: throw AppError.Schema("categories[$i].categoryId"),
            name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw AppError.Schema("categories[$i].name"),
            sortOrder = o["sortOrder"].intValue() ?: i,
        )
    }

    fun menuItem(m: MenuListItem, category: String, groups: List<OptionGroup>) = MenuItem(
        id = m.menuId, name = m.name, desc = m.desc, price = m.price, category = category,
        soldOut = m.soldOut, thumbnailUrl = m.imageUrl, imageUrl = m.imageUrl, optionGroups = groups,
    )

    /**
     * MS-11 메뉴 상세의 옵션 그룹.
     * TODO(BE): `type` 값 확인 — "MULTI"/"MULTIPLE"/"CHECKBOX" 이거나 `maxSelect > 1` 이면 복수 선택으로 본다.
     */
    fun optionGroups(raw: JsonElement?): List<OptionGroup> {
        val o = obj(raw, "menuDetail")
        return (o["optionGroups"] as? JsonArray).orEmpty().mapIndexed { i, g ->
            val go = obj(g, "optionGroups[$i]")
            val type = go["type"].str()?.uppercase().orEmpty()
            val max = go["maxSelect"].intValue()
            val multi = type in setOf("MULTI", "MULTIPLE", "CHECKBOX") || (max != null && max > 1)
            OptionGroup(
                id = go["optionGroupId"].id() ?: throw AppError.Schema("optionGroups[$i].id"),
                name = go["name"].str() ?: throw AppError.Schema("optionGroups[$i].name"),
                required = go["required"].boolValue() == true,
                multi = multi,
                choices = arr(go["options"], "optionGroups[$i].options").mapIndexed { j, c ->
                    val co = obj(c, "options[$j]")
                    OptionChoice(
                        id = co["optionItemId"].id() ?: throw AppError.Schema("options[$j].id"),
                        name = co["name"].str() ?: throw AppError.Schema("options[$j].name"),
                        priceDelta = co["priceDelta"].intValue() ?: 0,
                        soldOut = co["soldOut"].boolValue() == true,
                    )
                },
                maxSelect = if (multi && max != null && max > 0) max else null,
            )
        }
    }

    // ───── 주문 ─────

    /**
     * 주문 상태. 앱에는 ACCEPTED(매장 수락, 제조 전)가 없어 '만드는 중'으로 묶는다 (BE_GUIDE_MAPPING §2, 디자인 확인 필요).
     * TODO(BE): 미수령(UNCLAIMED) 에 해당하는 상태를 확인.
     */
    fun orderStatus(s: String?): OrderStatus = when (s?.uppercase()) {
        "PENDING_PAYMENT" -> OrderStatus.PENDING_PAYMENT
        "REQUESTED" -> OrderStatus.REQUESTED
        "ACCEPTED", "MAKING" -> OrderStatus.MAKING
        "READY" -> OrderStatus.READY
        "COMPLETED" -> OrderStatus.COMPLETED
        "REJECTED" -> OrderStatus.REJECTED
        "CANCELED", "CANCELLED" -> OrderStatus.CANCELED
        "EXPIRED" -> OrderStatus.EXPIRED
        "REFUNDED" -> OrderStatus.REFUNDED
        else -> throw AppError.Schema("order.status=$s")
    }

    /** 목록 항목이 상세와 같은 모양(줄 목록 포함)인지 */
    fun hasDetail(raw: JsonElement?): Boolean = (raw as? JsonObject)?.let { it["items"] is JsonArray && it["store"] is JsonObject } == true

    /** MS-18 (목록 항목이 같은 모양이면 그대로 쓴다) */
    fun order(raw: JsonElement?): Order {
        val o = obj(raw, "order")
        val status = orderStatus(o["status"].str())
        val store = o["store"] as? JsonObject
        val tl = o["timeline"] as? JsonObject
        val placed = instant(tl?.get("placedAt")) ?: throw AppError.Schema("order.timeline.placedAt")
        val lines = (o["items"] as? JsonArray).orEmpty().mapIndexed { i, e ->
            val l = obj(e, "items[$i]")
            val qty = l["quantity"].intValue() ?: 1
            OrderLine(
                menuId = "", // TODO(BE): 상세 줄에 menuId 가 없어 재주문은 이름으로 찾아야 한다
                name = l["menuName"].str() ?: throw AppError.Schema("items[$i].menuName"),
                choiceIds = emptyList(),
                optionSummary = (l["options"] as? JsonArray).orEmpty().mapNotNull { it.str() }.joinToString(" | "),
                qty = qty,
                amount = l["lineAmount"].intValue() ?: ((l["unitPrice"].intValue() ?: 0) * qty),
            )
        }
        val refund = o["refundStatus"].str()?.uppercase()
        val finalStatus = if (status == OrderStatus.COMPLETED && refund in REFUND_DONE) OrderStatus.REFUNDED else status
        val closed = instant(tl?.get("canceledAt")) ?: instant(tl?.get("rejectedAt"))
        return Order(
            id = o["orderId"].id() ?: throw AppError.Schema("order.orderId"),
            pickupNumber = o["pickupNo"].intValue(),
            storeId = store?.get("storeId").id().orEmpty(),
            storeName = store?.get("name").str().orEmpty(),
            status = finalStatus,
            lines = lines,
            totalAmount = o["totalAmount"].intValue() ?: lines.sumOf { it.amount },
            refundAmount = if (finalStatus == OrderStatus.REFUNDED) o["totalAmount"].intValue() else null,
            placedAt = placed,
            makingAt = instant(tl?.get("makingAt")) ?: instant(tl?.get("acceptedAt")),
            readyAt = instant(tl?.get("readyAt")),
            completedAt = instant(tl?.get("completedAt")),
            closedAt = closed,
            aheadCount = o["aheadCount"].longValue()?.toInt(),
            // TODO(BE): 취소 주체(고객/매장) 필드가 없어, 거절 사유가 있으면 매장, 아니면 고객 취소로 본다
            canceledBy = if (status == OrderStatus.CANCELED) (if (o["rejectReason"].str() != null) CancelActor.STORE else CancelActor.CUSTOMER) else null,
            reason = o["rejectReasonDetail"].str() ?: o["rejectReason"].str(),
        )
    }

    /** MS-16 · MS-29 의 `payment` 에서 결제창 설정. 포트원 값이 없으면(가짜 결제) null */
    fun checkout(payment: JsonElement?): PgCheckout? {
        val pay = payment as? JsonObject ?: return null
        val portOne = pay["portOne"] as? JsonObject ?: return null
        return PgCheckout(
            storeId = portOne["storeId"].str() ?: return null,
            channelKey = portOne["channelKey"].str() ?: return null,
            paymentId = portOne["paymentId"].str() ?: return null,
            orderName = pay["orderName"].str() ?: "ORMO 주문",
        )
    }

    private val REFUND_DONE = setOf("SUCCEEDED", "COMPLETED", "REFUNDED", "DONE")
    private val PAYMENT_FAILED = setOf("FAILED", "CANCELED", "CANCELLED", "ABORTED")

    /**
     * MS-28 `{orderStatus, expiresAt, pickupNo, retryAvailable, payment{status}, refund{...}}` → 앱의 결제 결과.
     * 결제 상태 문자열(payment.status) 값은 확인되지 않았다 (TODO(BE)) — 실패 계열은 FAILED/CANCELED 로 가정하고,
     * 주문 상태가 PENDING_PAYMENT 를 벗어났으면(접수·진행·완료) 결제된 것으로 본다.
     */
    fun paymentStatus(raw: JsonElement?): PaymentStatus {
        val o = obj(raw, "payment")
        val st = o["orderStatus"].str()?.uppercase()
        val pay = (o["payment"] as? JsonObject)?.get("status").str()?.uppercase()
        return when {
            st == "EXPIRED" -> PaymentStatus.Expired
            st == "PENDING_PAYMENT" || st == null ->
                if (pay != null && pay in PAYMENT_FAILED) PaymentStatus.Failed(pay) else PaymentStatus.Pending
            else -> PaymentStatus.Paid
        }
    }
}
