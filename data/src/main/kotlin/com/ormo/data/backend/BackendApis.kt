package com.ormo.data.backend

import com.ormo.core.Outcome
import com.ormo.data.menu.MenuApi
import com.ormo.data.orders.MyOrdersApi
import com.ormo.data.orders.OrderPage
import com.ormo.data.payment.OrderApi
import com.ormo.data.payment.PaymentGateway
import com.ormo.data.payment.PgRequest
import com.ormo.data.payment.PgResult
import com.ormo.data.payment.PlaceOrderFailure
import com.ormo.data.remote.ApiClient
import com.ormo.data.remote.AppError
import com.ormo.data.remote.id
import com.ormo.data.remote.intValue
import com.ormo.domain.model.MenuItem
import com.ormo.domain.model.MenuPayload
import com.ormo.domain.model.Cafe
import com.ormo.domain.model.OptionGroup
import com.ormo.domain.model.OrderStatus
import com.ormo.domain.order.OrderDraft
import com.ormo.domain.order.PickupMethod
import com.ormo.domain.orders.Order
import com.ormo.domain.payment.PaymentStatus
import com.ormo.domain.payment.PlacedOrder
import com.ormo.domain.store.Store
import com.ormo.data.store.StoreDirectory
import com.ormo.domain.store.StoreInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/** MS-5 · MS-6 (학교는 토큰이 정한다 — 목록에 학교 파라미터가 없다) */
class RemoteStoreDirectory(private val client: ApiClient) : StoreDirectory {
    override suspend fun fetchStores(): List<Store> = BackendParsers.stores(client.get("/stores"))
    override suspend fun fetchInfo(storeId: String): StoreInfo = BackendParsers.storeInfo(client.get("/stores/$storeId"))
}

/**
 * 메뉴: 앱은 학교 단위 한 번에 받는 구조라, BE 의 매장별 API 를 조합해 [MenuPayload] 를 만든다.
 *  - 매장 목록(MS-5) → 매장마다 카테고리(MS-9) · 메뉴(MS-10, 카테고리별) · 옵션이 있는 메뉴의 상세(MS-11)
 *  - 버전: BE 에 메뉴 버전이 없어 **메뉴 목록의 (id·이름·가격·설명·옵션 유무) 해시**로 대신한다. 품절은 버전에 넣지 않는다(따로 받음).
 *    → 앱이 켜져 있는 동안 옵션 안의 가격·품절 변경은 메뉴 목록이 바뀌거나 당겨서 새로고침할 때까지 반영되지 않는다 (TODO(BE): 메뉴 버전).
 *  - 앱을 켠 뒤 **첫 버전 확인은 항상 '다름'** 으로 답해 전부 새로 받는다 (저장해 둔 옵션 품절·가격이 계속 남지 않게).
 *  - **한 매장의 데이터 오류는 그 매장만 뺀다** (형태 오류·그 매장 요청의 서버 오류). 네트워크 끊김·로그인 만료는 전체 실패,
 *    모든 매장이 실패해도 전체 실패. 일부가 빠진 결과는 [PARTIAL_VERSION] 이라 다음 확인 때 다시 받는다.
 */
class BackendMenuApi(private val client: ApiClient, private val parallelism: Int = 6) : MenuApi {

    private class StoreMenus(val store: Store, val all: List<BackendParsers.MenuListItem>)

    /** 매장 하나의 결과: 값 아니면 그 매장만의 오류 */
    private class PerStore<T>(val value: T? = null, val error: AppError? = null)

    private suspend fun <T> isolate(store: Store, block: suspend () -> T): PerStore<T> = try {
        PerStore(value = block())
    } catch (e: AppError) {
        val onlyThisStore = e is AppError.Schema || (e is AppError.Api && e.status != 401)
        if (!onlyThisStore) throw e
        client.note("xx 매장 ${store.id} 메뉴를 빼고 진행: ${e.message}")
        PerStore(error = e)
    }

    /** 성공한 매장들. 매장이 있는데 모두 실패했으면 첫 오류를 던진다 */
    private fun <T> List<PerStore<T>>.successes(): List<T> {
        val ok = mapNotNull { it.value }
        if (ok.isEmpty()) firstNotNullOfOrNull { it.error }?.let { throw it }
        return ok
    }

    private suspend fun loadAll(): List<PerStore<StoreMenus>> = coroutineScope {
        val stores = BackendParsers.stores(client.get("/stores"))
        stores.map { s ->
            async { isolate(s) { StoreMenus(s, BackendParsers.menuList(client.get("/stores/${s.id}/menus"))) } }
        }.map { it.await() }
    }

    private fun version(all: List<StoreMenus>): Long {
        val sig = all.sortedBy { it.store.id }.joinToString("|") { sm ->
            sm.store.id + ":" + sm.all.sortedBy { it.menuId }.joinToString(",") { "${it.menuId}/${it.name}/${it.price}/${it.desc}/${it.hasOptions}" }
        }
        return sig.hashCode().toLong() and 0x7fffffffL
    }

    /** 이번 실행에서 메뉴를 전부 받아 본 적이 있는지 */
    @Volatile private var fetchedThisRun = false

    override suspend fun fetchVersion(schoolId: String): Long =
        if (fetchedThisRun) version(loadAll().successes()) else STALE_VERSION

    override suspend fun fetchMenus(schoolId: String): MenuPayload = coroutineScope {
        val loaded = loadAll()
        val all = loaded.successes()
        val gate = Semaphore(parallelism)
        val results = all.map { sm ->
            // 안쪽 coroutineScope: 옵션 상세 하나가 실패해도 이 매장 안에서 끝나고 다른 매장은 취소되지 않는다
            async { isolate(sm.store) { coroutineScope { cafe(sm, gate) } } }
        }.map { it.await() }
        val cafes = results.successes()
        val partial = loaded.any { it.error != null } || results.any { it.error != null }
        fetchedThisRun = true
        MenuPayload(if (partial) PARTIAL_VERSION else version(all), cafes)
    }

    private suspend fun CoroutineScope.cafe(sm: StoreMenus, gate: Semaphore): Cafe {
        val cats = BackendParsers.categories(client.get("/stores/${sm.store.id}/categories")).sortedBy { it.sortOrder }
        // 카테고리별 목록으로 메뉴의 카테고리 이름을 알아낸다 (메뉴 응답에는 카테고리가 없다)
        val categoryOf = HashMap<String, String>()
        val order = ArrayList<String>()
        for (cat in cats.filter { it.name.isNotBlank() }) { // 이름이 빈 카테고리의 메뉴는 '기타' 로 남는다
            val inCat = BackendParsers.menuList(client.get("/stores/${sm.store.id}/menus?categoryId=${cat.id}"))
            if (inCat.isNotEmpty()) order += cat.name
            inCat.forEach { categoryOf.putIfAbsent(it.menuId, cat.name) }
        }
        val details = sm.all.filter { it.hasOptions }.map { m ->
            m.menuId to async { gate.withPermit { BackendParsers.optionGroups(client.get("/stores/${sm.store.id}/menus/${m.menuId}")) } }
        }.associate { (id, d) -> id to d }
        val items = sm.all.map { m ->
            BackendParsers.menuItem(m, categoryOf[m.menuId] ?: OTHER_CATEGORY, details[m.menuId]?.await().orEmpty())
        }
        // 카테고리 순서대로 (목록에 없는 메뉴는 '기타' 로 마지막)
        val rank = (order + OTHER_CATEGORY).withIndex().associate { it.value to it.index }
        return Cafe(id = sm.store.id, name = sm.store.name, code = sm.store.id, menus = items.sortedBy { rank[it.category] ?: Int.MAX_VALUE })
    }

    override suspend fun fetchSoldOutIds(cafeId: String): List<String> =
        BackendParsers.menuList(client.get("/stores/$cafeId/menus")).filter { it.soldOut }.map { it.menuId }

    private companion object {
        const val OTHER_CATEGORY = "기타"

        /** 일부 매장이 빠진 결과의 버전. 정상 버전(0 이상)과 절대 같지 않다 */
        const val PARTIAL_VERSION = -1L

        /** 아직 확인하지 못한 상태의 버전. 저장된 어떤 버전과도 같지 않다 */
        const val STALE_VERSION = -2L
    }
}

/**
 * 주문 생성(MS-16)·결제 상태(MS-28). 주문 초안에 매장이 없어 [currentStoreId] 로 받는다.
 * - 같은 [idempotencyKey] 로 다시 부르면 BE 가 같은 주문을 돌려준다 (HTTP 200 "이미 만든 주문").
 * - **매장 섭취/테이크아웃 필드가 BE 에 없어** `requestMemo` 에 `[매장 섭취]` / `[테이크아웃]` 으로 넣어 매장이 알 수 있게 한다 (임시, TODO(BE)).
 * - 영업 중이 아님(OD001) → 결제창 전에 막음, 품절(OD002) → 담은 메뉴가 품절.
 */
class RemoteOrderApi(private val client: ApiClient, private val currentStoreId: () -> String?) : OrderApi {

    override suspend fun placeOrder(draft: OrderDraft, idempotencyKey: String): Outcome<PlacedOrder, PlaceOrderFailure> {
        val storeId = currentStoreId() ?: return Outcome.Fail(PlaceOrderFailure.Other)
        val body = buildJsonObject {
            put("storeId", storeId.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(storeId))
            putJsonArray("items") {
                draft.lines.forEach { l ->
                    add(
                        buildJsonObject {
                            put("menuId", l.menuId.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(l.menuId))
                            put("quantity", l.qty)
                            put(
                                "optionItemIds",
                                buildJsonArray { l.choiceIds.forEach { c -> add(c.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(c)) } },
                            )
                        },
                    )
                }
            }
            put("requestMemo", if (draft.pickup == PickupMethod.DINE_IN) "[매장 섭취]" else "[테이크아웃]")
            put("withdrawalLimitAgreed", true) // 주문서의 '취소할 수 없어요' 동의를 확인한 뒤에만 결제로 오므로 항상 true
        }
        val raw = try {
            client.post("/orders", body, mapOf("Idempotency-Key" to idempotencyKey))
        } catch (e: AppError.Api) {
            return Outcome.Fail(
                when (e.code) {
                    "OD001" -> PlaceOrderFailure.StoreUnavailable(BackendParsers.storeStatus(null)) // TODO(BE): 응답에 실제 영업 상태가 없다 → 영업 종료로 안내
                    "OD002" -> PlaceOrderFailure.AllSoldOut
                    else -> PlaceOrderFailure.Other
                },
            )
        }
        val o = raw as? JsonObject ?: throw AppError.Schema("order.create")
        val orderId = o["orderId"].id() ?: throw AppError.Schema("order.create.orderId")
        val total = o["totalAmount"].intValue() ?: throw AppError.Schema("order.create.totalAmount")
        return Outcome.Ok(
            PlacedOrder(
                orderId = orderId,
                amount = total,
                fingerprint = draft.fingerprint,
                expiresAtMs = BackendParsers.instant(o["expiresAt"])?.toEpochMilli(),
            ),
        )
    }

    override suspend fun paymentStatus(orderId: String): PaymentStatus =
        BackendParsers.paymentStatus(client.get("/orders/$orderId/payment"))
}

/**
 * 내 주문(MS-17·18·19). 목록 항목의 모양이 문서에서 확인되지 않아(TODO(BE)) **상세와 같은 모양이면 그대로**, 아니면 상세(MS-18)로 다시 받는다.
 * 목록은 페이지 방식 → 커서는 다음 페이지 번호. `filter` 값을 몰라 보내지 않고 앱에서 거른다.
 */
class RemoteMyOrdersApi(private val client: ApiClient, private val activePageSize: Int = 50) : MyOrdersApi {

    override suspend fun fetchOrder(orderId: String): Order = BackendParsers.order(client.get("/orders/$orderId"))

    private suspend fun page(page: Int, size: Int): Pair<List<Order>, Boolean> {
        val o = client.get("/orders?page=$page&size=$size") as? JsonObject ?: throw AppError.Schema("orders")
        val content = o["content"] as? JsonArray ?: throw AppError.Schema("orders.content")
        val orders = content.map { item ->
            if (BackendParsers.hasDetail(item)) BackendParsers.order(item)
            else fetchOrder((item as? JsonObject)?.get("orderId").id() ?: throw AppError.Schema("orders.content.orderId"))
        }
        val hasNext = (o["hasNext"] as? JsonPrimitive)?.content == "true"
        return orders to hasNext
    }

    override suspend fun fetchActive(): List<Order> = page(0, activePageSize).first.filter { it.status.isActive }

    override suspend fun fetchHistoryPage(cursor: String?, limit: Int): OrderPage {
        val p = cursor?.toIntOrNull() ?: 0
        val (orders, hasNext) = page(p, limit)
        return OrderPage(orders, if (hasNext) (p + 1).toString() else null)
    }

    /**
     * 고객 취소는 접수 대기에서만 (이미 수락됐으면 OD006 409). 요청 본문의 필수 값은 확인되지 않아 빈 본문을 보낸다 (TODO(BE)).
     * 응답 모양도 문서에서 확인되지 않아, 취소 뒤 상세를 다시 받아 돌려준다.
     */
    override suspend fun cancel(orderId: String): Order {
        client.post("/orders/$orderId/cancel", buildJsonObject {})
        return fetchOrder(orderId)
    }
}

/**
 * **개발 서버 전용** 결제창 대신 DEV-1(`POST /dev/orders/{id}/confirm-payment`)로 결제를 확정한다.
 * 실제 PG(PortOne)는 환경 전환 뒤 Activity 기반 게이트웨이로 바꾼다 (릴리스에는 쓰지 않는다).
 */
class DevPaymentGateway(private val client: ApiClient) : PaymentGateway {
    override suspend fun pay(request: PgRequest): PgResult = try {
        client.post("/dev/orders/${request.orderId}/confirm-payment", buildJsonObject {})
        PgResult.Approved()
    } catch (e: AppError.Api) {
        PgResult.Failed(e.code, null)
    }
}

/** 실제 PG(PortOne) 연결 전의 릴리스용: 결제를 시작하지 못한다 (가짜 결제로 주문이 접수되지 않게). */
object UnavailablePaymentGateway : PaymentGateway {
    override suspend fun pay(request: PgRequest): PgResult = PgResult.Failed("PG_NOT_READY", null)
}
