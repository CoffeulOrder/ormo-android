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
        // 앱을 켠 뒤 첫 확인은 요청 없이 '다름' → 저장해 둔 메뉴가 있어도 전부 새로 받는다
        assertEquals(-2L, api.fetchVersion("s"))
        assertTrue(seen.isEmpty())
        val v1 = api.fetchMenus("s").version
        assertEquals(v1, api.fetchVersion("s")) // 같은 목록 → 같은 버전
        val base = ::menuRoutes
        routes = { r -> if (path(r) == "/stores/1/menus") base(r).setBody(base(r).getBody()!!.readUtf8().replace(""""soldOut":true""", """"soldOut":false""")) else base(r) }
        assertEquals(v1, api.fetchVersion("s")) // 품절은 버전에 넣지 않는다
        routes = { r -> if (path(r) == "/stores/1/menus") base(r).setBody(base(r).getBody()!!.readUtf8().replace("3500", "3600")) else base(r) }
        assertTrue(api.fetchVersion("s") != v1)
    }

    @Test fun `메뉴 - 이름이 빈 카테고리의 메뉴는 기타로 묶는다`() = runBlocking {
        routes = { r ->
            if (path(r) == "/stores/1/categories") {
                ok("""[{"categoryId":10,"name":"커피","sortOrder":1},{"categoryId":11,"name":"","sortOrder":2}]""")
            } else {
                menuRoutes(r)
            }
        }
        val cafe = BackendMenuApi(client).fetchMenus("s").cafes.single()
        assertEquals("커피", cafe.menus.first { it.id == "100" }.category)
        assertEquals("기타", cafe.menus.first { it.id == "200" }.category)
        assertTrue(seen.none { path(it) == "/stores/1/menus?categoryId=11" })
    }

    /** 매장 1 은 정상, 매장 2 는 [store2] 가 정한 응답 */
    private fun twoStores(store2: (String) -> MockResponse): (RecordedRequest) -> MockResponse = { r ->
        val p = path(r)
        when {
            p == "/stores" -> ok("""[{"storeId":1,"name":"범석관점","status":"OPEN"},{"storeId":2,"name":"깨진 매장","status":"OPEN"}]""")
            p.startsWith("/stores/2/") -> store2(p)
            else -> menuRoutes(r)
        }
    }

    private val oneMenu = """[{"menuId":900,"name":"라떼","description":"","basePrice":4000,"imageUrl":null,"soldOut":false,"hasOptions":true}]"""

    @Test fun `메뉴 - 한 매장의 데이터가 잘못돼도 다른 매장은 보이고, 다음에 다시 받는다`() = runBlocking {
        val broken = listOf<(String) -> MockResponse>(
            // 메뉴 목록 형태 오류
            { ok("""[{"menuId":900}]""") },
            // 카테고리 서버 오류
            { p -> if (p == "/stores/2/menus") ok(oneMenu) else err(500, "C999") },
            // 옵션 상세 형태 오류 (매장 안의 병렬 요청)
            { p ->
                when {
                    p == "/stores/2/menus" -> ok(oneMenu)
                    p == "/stores/2/categories" -> ok("[]")
                    else -> ok("""{"optionGroups":[{"name":"id 없음"}]}""")
                }
            },
        )
        for (store2 in broken) {
            routes = twoStores(store2)
            val api = BackendMenuApi(client)
            val p = api.fetchMenus("s")
            assertEquals(listOf("1"), p.cafes.map { it.id })
            assertEquals(3, p.cafes.single().menus.size)
            assertTrue(api.fetchVersion("s") != p.version)
        }
    }

    @Test fun `메뉴 - 모든 매장이 실패하거나 로그인이 만료되면 전체 실패`() {
        routes = { r -> if (path(r) == "/stores") menuRoutes(r) else ok("""[{"menuId":1}]""") }
        assertFailsWith<AppError.Schema> { runBlocking { BackendMenuApi(client).fetchMenus("s") } }
        routes = twoStores { err(401, "AUTH_003") }
        assertFailsWith<AppError.Api> { runBlocking { BackendMenuApi(client).fetchMenus("s") } }
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

    @Test fun `주문 생성·결제 다시 시도 - 포트원 결제창 설정을 읽는다`() = runBlocking {
        val payment = """{"attemptNo":1,"pgProvider":"INICIS_V2","pgOrderId":"CF-1","orderName":"바닐라라떼","amount":8000,"clientKey":null,
            "portOne":{"storeId":"store-a","channelKey":"channel-b","paymentId":"CF-1","currency":"CURRENCY_KRW"}}"""
        routes = { ok("""{"orderId":77,"totalAmount":8000,"expiresAt":null,"payment":$payment}""", 201) }
        val api = RemoteOrderApi(client) { "1" }
        val placed = (api.placeOrder(draft, "k") as Outcome.Ok).value
        assertEquals(com.ormo.domain.payment.PgCheckout("store-a", "channel-b", "CF-1", "바닐라라떼"), placed.checkout)

        seen.clear()
        routes = { ok("""{"orderId":77,"status":"PENDING_PAYMENT","action":"OPEN_CHECKOUT","payment":$payment}""") }
        val retry = api.reopen("77") as com.ormo.data.payment.PaymentRetry.Open
        assertEquals("CF-1", retry.checkout!!.paymentId)
        assertEquals("POST", seen.single().method)
        assertEquals("/orders/77/payment-attempts", path(seen.single()))

        routes = { ok("""{"orderId":77,"status":"REQUESTED","action":"VIEW_ORDER","payment":null}""") }
        assertEquals(com.ormo.data.payment.PaymentRetry.AlreadyPlaced, api.reopen("77"))
        routes = { err(409, "PY012") }
        assertEquals("PY012", assertFailsWith<AppError.Api> { runBlocking { api.reopen("77") } }.code)
    }

    @Test fun `결제 승인 - 주문 번호와 결제번호를 보내고 오류 코드로 결과를 정한다`() = runBlocking {
        val api = RemoteOrderApi(client) { "1" }
        routes = { ok("""{"orderId":77,"orderCode":"CF-1","status":"REQUESTED","pickupNo":3,"payment":{"status":"APPROVED","amount":8000}}""") }
        assertEquals(PaymentStatus.Paid, api.confirm("77", "CF-1"))
        val req = seen.single()
        assertEquals("/payments/confirm", path(req))
        assertEquals("""{"orderId":77,"paymentId":"CF-1"}""", req.body.readUtf8())

        routes = { err(409, "PY009") }
        assertEquals(PaymentStatus.Pending, api.confirm("77", "CF-1"))
        routes = { err(503, "PY010") }
        assertEquals(PaymentStatus.Pending, api.confirm("77", "CF-1"))
        routes = { err(409, "OD009") }
        assertEquals(PaymentStatus.Expired, api.confirm("77", "CF-1"))
        // 그 밖의 오류는 저장된 상태를 읽는다
        routes = { r -> if (r.method == "POST") err(409, "PY008") else ok("""{"orderId":77,"orderStatus":"PENDING_PAYMENT","payment":{"status":"FAILED"}}""") }
        assertEquals(PaymentStatus.Failed("FAILED"), api.confirm("77", "CF-1"))
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
        assertTrue(seen.any { path(it) == "/orders?page=0&size=20&filter=HISTORY" })
        // 진행 중만
        assertEquals(listOf("77"), api.fetchActive().map { it.id })
        assertTrue(seen.any { path(it) == "/orders?page=0&size=50&filter=ACTIVE" })
        // 다음 페이지가 없으면 커서 null
        routes = { ok("""{"content":[],"page":1,"size":20,"hasNext":false}""") }
        assertNull(api.fetchHistoryPage("1", 20).nextCursor)
    }

    @Test fun `주문 목록 - 결제하지 않은 주문(placedAt 없음)은 빼고 나머지를 보여준다`() = runBlocking {
        routes = { r ->
            when {
                path(r).startsWith("/orders?") -> ok(
                    """{"content":[{"orderId":81,"status":"PENDING_PAYMENT","placedAt":null},
                        {"orderId":77,"status":"REQUESTED","placedAt":"2026-11-25T10:00:00+09:00"}],"page":0,"size":20,"hasNext":false}""",
                )
                path(r) == "/orders/77" -> ok(detail)
                else -> err(500, "C999")
            }
        }
        val api = RemoteMyOrdersApi(client)
        assertEquals(listOf("77"), api.fetchHistoryPage(null, 20).orders.map { it.id })
        assertEquals(listOf("77"), api.fetchActive().map { it.id })
        assertTrue(seen.none { path(it) == "/orders/81" })
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
