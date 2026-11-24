package com.ormo.data.backend

import com.ormo.core.Outcome
import com.ormo.data.payment.PgRequest
import com.ormo.data.payment.PgResult
import com.ormo.data.payment.PlaceOrderFailure
import com.ormo.data.remote.ApiClient
import com.ormo.data.remote.AppError
import com.ormo.data.remote.SessionManager
import com.ormo.domain.model.OrderStatus
import com.ormo.domain.order.OrderDraft
import com.ormo.domain.order.OrderLineRequest
import com.ormo.domain.order.PaymentMethod
import com.ormo.domain.order.PickupMethod
import com.ormo.domain.payment.PaymentStatus
import com.ormo.domain.store.StoreStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 실제 BE(FE·BE 연동 가이드 2026-11-24)와 같은 경로·봉투·필드로 확인 */
class BackendApisTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient
    private val seen = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
    private var routes: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    @BeforeTest fun setUp() {
        server = MockWebServer().also {
            it.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse { seen += request; return routes(request) }
            }
            it.start()
        }
        val sessions = SessionManager().also { it.set("tok", "r") }
        client = ApiClient(server.url("/api/v1").toString(), sessions)
    }

    @AfterTest fun tearDown() { server.shutdown() }

    private fun ok(data: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
        .setBody("""{"timestamp":"2026-11-25T10:00:00+09:00","status":200,"code":"SUCCESS","message":"ok","data":$data}""")

    private fun err(http: Int, code: String, msg: String = "오류") = MockResponse().setResponseCode(http).setHeader("Content-Type", "application/json")
        .setBody("""{"timestamp":"2026-11-25T10:00:00+09:00","status":$http,"code":"$code","message":"$msg"}""")

    private fun path(r: RecordedRequest) = r.path!!.removePrefix("/api/v1")

    // ───── 매장 ─────

    @Test fun `매장 목록 - 영업 상태 문자열을 앱 상태로`() = runBlocking {
        routes = { ok("""[{"storeId":1,"name":"범석관점","location":"범석관 1층","status":"OPEN"},{"storeId":2,"name":"뉴밀레니엄관점","location":"뉴밀 2층","status":"PAUSED"},{"storeId":3,"name":"x","location":null,"status":"CLOSED"}]""") }
        val stores = RemoteStoreDirectory(client).fetchStores()
        assertEquals(listOf("1", "2", "3"), stores.map { it.id })
        assertEquals(StoreStatus.Open, stores[0].status)
        assertEquals(StoreStatus.Break(null), stores[1].status)
        assertEquals(StoreStatus.Closed(null), stores[2].status)
        assertEquals("범석관 1층", stores[0].address)
        assertTrue(stores.all { it.menuCount > 0 }) // 목록에 메뉴 수가 없어도 빠지지 않게
        assertEquals("/stores", path(seen.single()))
        assertEquals("Bearer tok", seen.single().getHeader("Authorization"))
    }

    @Test fun `매장 정보 - 영업시간 공지 판매자`() = runBlocking {
        routes = {
            ok(
                """{"storeId":1,"name":"범석관점","location":"범석관 1층","status":"OPEN","notice":"행사로 14시까지",
                "businessHours":[{"dayOfWeek":1,"openTime":"08:30:00","closeTime":"18:00:00","closed":false},{"dayOfWeek":7,"openTime":null,"closeTime":null,"closed":true}],
                "seller":{"businessName":"ORMO 카페","representativeName":"김오르","businessRegNo":"123-45-67890","businessAddress":"성남시","contactPhone":"031","mailOrderRegNo":null}}""",
            )
        }
        val info = RemoteStoreDirectory(client).fetchInfo("1")
        assertEquals("행사로 14시까지", info.notice)
        assertEquals(2, info.weeklyHours.size)
        assertEquals(false, info.weeklyHours[0].closed)
        assertEquals(true, info.weeklyHours[1].closed)
        assertEquals("ORMO 카페", info.seller.businessName)
        assertNull(info.seller.mailOrderNumber)
    }

    @Test fun `매장 목록 - 필드가 빠지면 Schema 오류`() {
        routes = { ok("""[{"name":"x","status":"OPEN"}]""") }
        assertFailsWith<AppError.Schema> { runBlocking { RemoteStoreDirectory(client).fetchStores() } }
    }

    // ───── 메뉴 ─────

    private fun menuRoutes(r: RecordedRequest): MockResponse = when {
        path(r) == "/stores" -> ok("""[{"storeId":1,"name":"범석관점","location":"1층","status":"OPEN"}]""")
        path(r) == "/stores/1/categories" -> ok("""[{"categoryId":11,"name":"디저트","sortOrder":2},{"categoryId":10,"name":"커피","sortOrder":1}]""")
        path(r) == "/stores/1/menus?categoryId=10" -> ok("""[{"menuId":100,"name":"아메리카노","description":"기본","basePrice":3500,"imageUrl":null,"soldOut":false,"isNew":false,"isEvent":false,"hasOptions":true}]""")
        path(r) == "/stores/1/menus?categoryId=11" -> ok("""[{"menuId":200,"name":"토스트","description":"","basePrice":3000,"imageUrl":"https://img/t.png","soldOut":true,"isNew":true,"isEvent":false,"hasOptions":false}]""")
        path(r) == "/stores/1/menus" -> ok("""[{"menuId":200,"name":"토스트","description":"","basePrice":3000,"imageUrl":"https://img/t.png","soldOut":true,"isNew":true,"isEvent":false,"hasOptions":false},{"menuId":100,"name":"아메리카노","description":"기본","basePrice":3500,"imageUrl":null,"soldOut":false,"isNew":false,"isEvent":false,"hasOptions":true},{"menuId":300,"name":"분류 없음","description":"","basePrice":1000,"imageUrl":null,"soldOut":false,"isNew":false,"isEvent":false,"hasOptions":false}]""")
        path(r) == "/stores/1/menus/100" -> ok(
            """{"menuId":100,"name":"아메리카노","description":"기본","basePrice":3500,"imageUrl":null,"soldOut":false,"optionGroups":[
              {"optionGroupId":1,"name":"온도","type":"SINGLE","required":true,"minSelect":1,"maxSelect":1,"options":[{"optionItemId":11,"name":"HOT","priceDelta":0,"isDefault":true,"soldOut":false},{"optionItemId":12,"name":"ICE","priceDelta":0,"isDefault":false,"soldOut":false}]},
              {"optionGroupId":2,"name":"샷","type":"MULTI","required":false,"minSelect":0,"maxSelect":2,"options":[{"optionItemId":21,"name":"샷 추가","priceDelta":500,"isDefault":false,"soldOut":true}]}]}""",
        )
        else -> err(404, "C004")
    }

    @Test fun `메뉴 - 매장 카테고리 메뉴 옵션을 한 묶음으로 조합`() = runBlocking {
        routes = ::menuRoutes
        val p = BackendMenuApi(client).fetchMenus("any")
        val cafe = p.cafes.single()
        assertEquals("1", cafe.id)
        assertEquals("범석관점", cafe.name)
        // 카테고리 순서(커피 → 디저트 → 기타)
        assertEquals(listOf("커피", "디저트", "기타"), cafe.menus.map { it.category })
        val americano = cafe.menus.first { it.id == "100" }
        assertEquals(3500, americano.price)
        assertEquals(2, americano.optionGroups.size)
        val temp = americano.optionGroups[0]
        assertTrue(temp.required); assertEquals(false, temp.multi); assertNull(temp.maxSelect)
        val shot = americano.optionGroups[1]
        assertTrue(shot.multi); assertEquals(2, shot.maxSelect)
        assertEquals(500, shot.choices[0].priceDelta); assertTrue(shot.choices[0].soldOut)
        assertTrue(cafe.menus.first { it.id == "200" }.soldOut)
        // 옵션이 없는 메뉴는 상세를 부르지 않는다
        assertTrue(seen.none { path(it) == "/stores/1/menus/200" })
    }

    @Test fun `메뉴 버전 - 같은 목록이면 같고 품절만 바뀌어도 같다, 가격이 바뀌면 다르다`() = runBlocking {
        routes = ::menuRoutes
        val api = BackendMenuApi(client)
        val v1 = api.fetchVersion("s")
        assertEquals(v1, api.fetchMenus("s").version) // 같은 목록 → 같은 버전
        val base = ::menuRoutes
        routes = { r -> if (path(r) == "/stores/1/menus") base(r).setBody(base(r).getBody()!!.readUtf8().replace(""""soldOut":true""", """"soldOut":false""")) else base(r) }
        assertEquals(v1, api.fetchVersion("s")) // 품절은 버전에 넣지 않는다
        routes = { r -> if (path(r) == "/stores/1/menus") base(r).setBody(base(r).getBody()!!.readUtf8().replace("3500", "3600")) else base(r) }
        assertTrue(api.fetchVersion("s") != v1)
    }

    @Test fun `품절 목록`() = runBlocking {
        routes = ::menuRoutes
        assertEquals(listOf("200"), BackendMenuApi(client).fetchSoldOutIds("1"))
    }

    // ───── 주문 ─────

    private val draft = OrderDraft(
        lines = listOf(OrderLineRequest("100", listOf("11", "21"), 2)),
        pickup = PickupMethod.DINE_IN,
        expectedAmount = 8000,
    )

    @Test fun `주문 생성 - Bearer 와 멱등 키, 본문 필드, 매장 섭취 메모`() = runBlocking {
        routes = { ok("""{"orderId":77,"orderCode":"B-0125-001","status":"PENDING_PAYMENT","expiresAt":"2026-11-25T10:10:00+09:00","items":[],"subtotalAmount":8000,"discountAmount":0,"totalAmount":8000,"payment":{"attemptNo":1,"pgProvider":"FAKE","pgOrderId":"x","orderName":"아메리카노","amount":8000,"clientKey":"k"}}""", 201) }
        val r = RemoteOrderApi(client) { "1" }.placeOrder(draft, "key-1")
        val placed = (r as Outcome.Ok).value
        assertEquals("77", placed.orderId)
        assertEquals(8000, placed.amount)
        assertEquals(draft.fingerprint, placed.fingerprint)
        assertEquals(java.time.OffsetDateTime.parse("2026-11-25T10:10:00+09:00").toInstant().toEpochMilli(), placed.expiresAtMs)
        val req = seen.single()
        assertEquals("POST", req.method); assertEquals("/orders", path(req))
        assertEquals("key-1", req.getHeader("Idempotency-Key"))
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertNull(req.getHeader("X-Member-Id")) // 임시 헤더는 더 이상 쓰지 않는다
        val b = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("1", b["storeId"].toString())
        assertEquals("""[{"menuId":100,"quantity":2,"optionItemIds":[11,21]}]""", b["items"].toString())
        assertEquals("\"[매장 섭취]\"", b["requestMemo"].toString())
        assertEquals("true", b["withdrawalLimitAgreed"].toString())
    }

    @Test fun `주문 생성 - 영업 안 함, 품절, 그 밖의 오류`() = runBlocking {
        val api = RemoteOrderApi(client) { "1" }
        routes = { err(409, "OD001", "지금은 주문을 받지 않아요") }
        assertTrue((api.placeOrder(draft, "k") as Outcome.Fail).failure is PlaceOrderFailure.StoreUnavailable)
        routes = { err(409, "OD002") }
        assertEquals(PlaceOrderFailure.AllSoldOut, (api.placeOrder(draft, "k") as Outcome.Fail).failure)
        routes = { err(409, "OD010") }
        assertEquals(PlaceOrderFailure.Other, (api.placeOrder(draft, "k") as Outcome.Fail).failure)
        // 매장을 모르면 서버를 부르지 않는다
        seen.clear()
        assertEquals(PlaceOrderFailure.Other, (RemoteOrderApi(client) { null }.placeOrder(draft, "k") as Outcome.Fail).failure)
        assertTrue(seen.isEmpty())
    }

    @Test fun `결제 상태 - 주문 상태와 결제 상태로 결과를 정한다`() = runBlocking {
        val api = RemoteOrderApi(client) { "1" }
        fun status(order: String, pay: String?) = ok("""{"orderId":77,"orderStatus":"$order","expiresAt":null,"pickupNo":null,"retryAvailable":false,"payment":${if (pay == null) "null" else """{"provider":"FAKE","status":"$pay","amount":8000,"approvedAt":null}"""}}""")
        routes = { status("PENDING_PAYMENT", "READY") }; assertEquals(PaymentStatus.Pending, api.paymentStatus("77"))
        routes = { status("PENDING_PAYMENT", null) }; assertEquals(PaymentStatus.Pending, api.paymentStatus("77"))
        routes = { status("PENDING_PAYMENT", "FAILED") }; assertEquals(PaymentStatus.Failed("FAILED"), api.paymentStatus("77"))
        routes = { status("EXPIRED", null) }; assertEquals(PaymentStatus.Expired, api.paymentStatus("77"))
        routes = { status("REQUESTED", "SUCCEEDED") }; assertEquals(PaymentStatus.Paid, api.paymentStatus("77"))
        routes = { status("MAKING", "SUCCEEDED") }; assertEquals(PaymentStatus.Paid, api.paymentStatus("77"))
        assertEquals("/orders/77/payment", path(seen.first()))
    }

    // ───── 내 주문 ─────

    private val detail = """{"orderId":77,"orderCode":"B-0125-001","status":"ACCEPTED","store":{"storeId":1,"name":"범석관점"},"pickupNo":12,"aheadCount":3,"cancelable":false,
        "items":[{"menuName":"아메리카노","options":["HOT","샷 추가"],"unitPrice":4000,"quantity":2,"lineAmount":8000}],
        "subtotalAmount":8000,"discountAmount":0,"totalAmount":8000,"requestMemo":"[테이크아웃]","rejectReason":null,
        "timeline":{"placedAt":"2026-11-25T10:00:00+09:00","acceptedAt":"2026-11-25T10:02:00+09:00","makingAt":null,"readyAt":null,"completedAt":null,"canceledAt":null,"rejectedAt":null},
        "refundStatus":null,"rejectReasonDetail":null}"""

    @Test fun `주문 상세 - 수락은 만드는 중으로, 시각과 줄을 옮긴다`() = runBlocking {
        routes = { ok(detail) }
        val o = RemoteMyOrdersApi(client).fetchOrder("77")
        assertEquals("77", o.id); assertEquals(12, o.pickupNumber); assertEquals("범석관점", o.storeName)
        assertEquals(OrderStatus.MAKING, o.status)
        assertEquals(3, o.aheadCount)
        assertEquals("HOT | 샷 추가", o.lines.single().optionSummary)
        assertEquals(8000, o.lines.single().amount); assertEquals(2, o.lines.single().qty)
        assertEquals(java.time.OffsetDateTime.parse("2026-11-25T10:02:00+09:00").toInstant(), o.makingAt) // 수락 시각을 제조 시작으로
        assertEquals(8000, o.totalAmount)
    }

    @Test fun `주문 상세 - 완료 뒤 환불 성공이면 환불 완료`() = runBlocking {
        routes = { ok(detail.replace(""""status":"ACCEPTED"""", """"status":"COMPLETED"""").replace(""""refundStatus":null""", """"refundStatus":"SUCCEEDED"""")) }
        val o = RemoteMyOrdersApi(client).fetchOrder("77")
        assertEquals(OrderStatus.REFUNDED, o.status)
        assertEquals(8000, o.refundAmount)
    }

    @Test fun `주문 목록 - 항목이 상세 모양이 아니면 상세를 다시 받는다, 다음 페이지 커서`() = runBlocking {
        routes = { r ->
            when {
                path(r).startsWith("/orders?") -> ok("""{"content":[{"orderId":77,"status":"MAKING"},{"orderId":78,"status":"COMPLETED"}],"page":0,"size":20,"hasNext":true}""")
                path(r) == "/orders/77" -> ok(detail)
                path(r) == "/orders/78" -> ok(detail.replace("\"orderId\":77", "\"orderId\":78").replace("ACCEPTED", "COMPLETED"))
                else -> err(404, "C004")
            }
        }
        val api = RemoteMyOrdersApi(client)
        val page = api.fetchHistoryPage(null, 20)
        assertEquals(listOf("77", "78"), page.orders.map { it.id })
        assertEquals("1", page.nextCursor)
        assertTrue(seen.any { path(it) == "/orders?page=0&size=20" })
        // 진행 중만
        assertEquals(listOf("77"), api.fetchActive().map { it.id })
        // 다음 페이지가 없으면 커서 null
        routes = { ok("""{"content":[],"page":1,"size":20,"hasNext":false}""") }
        assertNull(api.fetchHistoryPage("1", 20).nextCursor)
    }

    @Test fun `주문 취소 - 요청 뒤 상세를 다시 받는다, 이미 수락됐으면 409 그대로`() = runBlocking {
        routes = { r -> if (r.method == "POST") ok("{}") else ok(detail.replace("ACCEPTED", "CANCELED")) }
        val o = RemoteMyOrdersApi(client).cancel("77")
        assertEquals(OrderStatus.CANCELED, o.status)
        assertTrue(seen.any { it.method == "POST" && path(it) == "/orders/77/cancel" })
        routes = { err(409, "OD006", "지금 상태에서는 할 수 없는 요청이에요.") }
        val e = assertFailsWith<AppError.Api> { runBlocking { RemoteMyOrdersApi(client).cancel("77") } }
        assertEquals(409, e.status)
    }

    // ───── 결제 게이트웨이 (개발 서버) ─────

    @Test fun `개발용 결제 확정 - DEV-1 호출`() = runBlocking {
        routes = { ok("{}") }
        val r = DevPaymentGateway(client).pay(PgRequest("77", 8000, PaymentMethod("card", "카드")))
        assertEquals(PgResult.Approved(), r)
        assertEquals("/dev/orders/77/confirm-payment", path(seen.single()))
        routes = { err(409, "PY001") }
        assertEquals(PgResult.Failed("PY001", null), DevPaymentGateway(client).pay(PgRequest("77", 8000, PaymentMethod("card", "카드"))))
    }
}
