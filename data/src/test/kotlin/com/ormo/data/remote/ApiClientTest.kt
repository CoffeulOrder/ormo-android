package com.ormo.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: SessionManager
    private lateinit var client: ApiClient

    private val refreshCalls = AtomicInteger()
    private val refreshBodies = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val seenAuth = java.util.concurrent.CopyOnWriteArrayList<String?>()
    private val seenIdem = java.util.concurrent.CopyOnWriteArrayList<String?>()

    @BeforeTest fun setUp() {
        server = MockWebServer()
        server.start()
        sessions = SessionManager()
        client = ApiClient(server.url("/api/v1").toString(), sessions)
    }

    @AfterTest fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun route(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handler(request)
        }
    }

    /** "/x" 는 Bearer old 면 401, Bearer new 면 200. "/auth/refresh" 는 새 토큰 발급. */
    private fun routeRefreshable(refresh: () -> MockResponse = { json("""{"accessToken":"new","refreshToken":"r2"}""") }) {
        route { req ->
            when (req.path) {
                "/api/v1/auth/refresh" -> {
                    refreshCalls.incrementAndGet()
                    refreshBodies += req.body.readUtf8()
                    refresh()
                }
                else -> {
                    seenAuth += req.getHeader("Authorization")
                    seenIdem += req.getHeader("Idempotency-Key")
                    if (req.getHeader("Authorization") == "Bearer new") json("""{"ok":true}""")
                    else json("""{"code":"C002"}""", 401)
                }
            }
        }
    }

    // ---- 기본 요청 ----

    @Test fun `토큰이 있으면 Authorization 을 붙이고 JSON 을 돌려준다`() = blocking {
        sessions.set("tok")
        server.enqueue(json("""{"a":1}"""))

        val res = client.get("/x")

        assertEquals(1, (res as JsonObject)["a"]?.jsonPrimitive?.content?.toInt())
        val req = server.takeRequest()
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("application/json", req.getHeader("Accept"))
        assertEquals("/api/v1/x", req.path)
    }

    @Test fun `세션이 없으면 Authorization 이 없다`() = blocking {
        server.enqueue(json("{}"))
        client.get("/x")
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `POST 는 JSON 본문과 헤더를 보낸다`() = blocking {
        server.enqueue(json("{}"))
        client.post("/orders", buildJsonObject { put("qty", 2) }, mapOf("Idempotency-Key" to "k-1"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("""{"qty":2}""", req.body.readUtf8())
        assertEquals("k-1", req.getHeader("Idempotency-Key"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test fun `본문 없는 POST 와 PUT 과 DELETE`() = blocking {
        repeat(3) { server.enqueue(json("{}")) }
        client.post("/a")
        client.put("/b")
        client.delete("/c")
        assertEquals(listOf("POST /api/v1/a", "PUT /api/v1/b", "DELETE /api/v1/c"), List(3) { server.takeRequest().requestLine.substringBeforeLast(" ").trim() })
    }

    @Test fun `빈 본문은 null`() = blocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertNull(client.delete("/x"))
    }

    // ---- 오류 ----

    @Test fun `2xx 가 아니면 상태와 code 와 본문을 보존한 ApiError`() = blocking {
        server.enqueue(json("""{"code":"MENU_SOLD_OUT","menuIds":["1","2"]}""", 409))
        val e = assertFailsWith<AppError.Api> { client.post("/orders") }
        assertEquals(409, e.status)
        assertEquals("MENU_SOLD_OUT", e.code)
        assertEquals("""["1","2"]""", e.body!!.jsonObject["menuIds"].toString())
    }

    @Test fun `JSON 이 아닌 응답은 BAD_RESPONSE`() = blocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>oops</html>"))
        val e = assertFailsWith<AppError.Api> { client.get("/x") }
        assertEquals(200, e.status)
        assertEquals("BAD_RESPONSE", e.code)
    }

    @Test fun `서버 오류 페이지도 BAD_RESPONSE 이면서 상태를 보존한다`() = blocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        val e = assertFailsWith<AppError.Api> { client.get("/x") }
        assertEquals(502, e.status)
        assertEquals("BAD_RESPONSE", e.code)
    }

    @Test fun `연결 실패는 NetworkError`() = blocking {
        val dead = MockWebServer().also { it.start() }
        val url = dead.url("/api/v1").toString()
        dead.shutdown()
        val c = ApiClient(url, SessionManager())
        assertFailsWith<AppError.Network> { c.get("/x") }
        Unit
    }

    @Test fun `시간 초과는 NetworkError`() = blocking {
        val fast = ApiClient(
            server.url("/api/v1").toString(), sessions,
            OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build(),
        )
        server.enqueue(json("{}").setHeadersDelay(2, TimeUnit.SECONDS))
        assertFailsWith<AppError.Network> { fast.get("/x") }
        Unit
    }

    // ---- BE 응답 봉투 ----

    @Test fun `봉투가 오면 data 만 돌려준다`() = blocking {
        server.enqueue(json("""{"timestamp":"2026-11-19T00:00:00+09:00","status":200,"code":"OK","message":"ok","data":{"version":3}}"""))
        val res = client.get("/x")
        assertEquals("""{"version":3}""", res.toString())
    }

    @Test fun `봉투의 data 가 null 이면 null`() = blocking {
        server.enqueue(json("""{"status":200,"code":"OK","message":"ok","data":null}"""))
        assertNull(client.get("/x"))
    }

    @Test fun `data 키만 있는 일반 객체는 봉투로 보지 않는다`() = blocking {
        server.enqueue(json("""{"data":[1,2]}"""))
        assertEquals("""{"data":[1,2]}""", client.get("/x").toString())
    }

    @Test fun `봉투 오류 응답에서도 code 를 읽는다`() = blocking {
        server.enqueue(json("""{"status":409,"code":"OD002","message":"품절","data":{"soldOutItems":[1]}}""", 409))
        val e = assertFailsWith<AppError.Api> { client.post("/orders") }
        assertEquals("OD002", e.code)
        assertNotNull(e.body)
    }

    // ---- 401 → 갱신 → 재시도 ----

    @Test fun `401 이면 한 번 갱신하고 같은 요청을 다시 보낸다`() = blocking {
        sessions.set("old", "r1")
        val rotated = mutableListOf<String>()
        sessions.onRefreshTokenRotated = { rotated += it }
        routeRefreshable()

        val res = client.post("/orders", buildJsonObject { put("q", 1) }, mapOf("Idempotency-Key" to "K"))

        assertEquals("""{"ok":true}""", res.toString())
        assertEquals(1, refreshCalls.get())
        assertTrue(refreshBodies.single().contains("r1")) // 리프레시 토큰으로 요청
        assertEquals("new", sessions.accessToken)
        assertEquals("r2", sessions.refreshToken) // 회전된 리프레시 토큰
        assertEquals(listOf("r2"), rotated)
        assertEquals(listOf<String?>("Bearer old", "Bearer new"), seenAuth.toList())
        assertEquals(listOf<String?>("K", "K"), seenIdem.toList()) // 재시도에도 같은 Idempotency-Key
    }

    @Test fun `서버가 새 리프레시 토큰을 안 주면 기존 것을 유지한다`() = blocking {
        sessions.set("old", "r1")
        routeRefreshable { json("""{"accessToken":"new"}""") }
        client.get("/x")
        assertEquals("r1", sessions.refreshToken)
    }

    @Test fun `동시에 여러 요청이 401 을 받아도 갱신은 한 번`() = blocking {
        sessions.set("old", "r1")
        routeRefreshable()

        val results = List(6) { async(Dispatchers.IO) { client.get("/x").toString() } }.awaitAll()

        assertTrue(results.all { it == """{"ok":true}""" })
        assertEquals(1, refreshCalls.get())
    }

    @Test fun `갱신이 서버에게 거절되면 세션을 지우고 expired 를 알리고 원래 401 을 던진다`() = blocking {
        sessions.set("old", "r1")
        routeRefreshable { json("""{"code":"AUTH_003"}""", 401) }
        val expired = async(start = CoroutineStart.UNDISPATCHED) { sessions.expired.first() }

        val e = assertFailsWith<AppError.Api> { client.get("/x") }

        assertEquals(401, e.status)
        withTimeout(2_000) { expired.await() }
        assertNull(sessions.accessToken)
        assertNull(sessions.refreshToken)
    }

    @Test fun `갱신 중 네트워크 오류면 세션을 유지하고 NetworkError`() = blocking {
        sessions.set("old", "r1")
        route { req ->
            if (req.path == "/api/v1/auth/refresh") MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            else json("""{"code":"C002"}""", 401)
        }
        val expired = async(start = CoroutineStart.UNDISPATCHED) { sessions.expired.first() }

        assertFailsWith<AppError.Network> { client.get("/x") }

        assertEquals("r1", sessions.refreshToken)
        assertEquals("old", sessions.accessToken)
        assertTrue(expired.isActive) // 만료 알림 없음
        expired.cancel()
    }

    @Test fun `갱신 중 서버 장애(5xx)면 세션을 유지한다`() = blocking {
        sessions.set("old", "r1")
        routeRefreshable { json("{}", 503) }
        val e = assertFailsWith<AppError.Api> { client.get("/x") }
        assertEquals(503, e.status)
        assertEquals("r1", sessions.refreshToken)
    }

    @Test fun `갱신 응답에 accessToken 이 없으면 세션은 유지하고 실패`() = blocking {
        sessions.set("old", "r1")
        routeRefreshable { json("""{"nope":1}""") }
        val e = assertFailsWith<AppError.Api> { client.get("/x") }
        assertEquals("BAD_RESPONSE", e.code)
        assertEquals("r1", sessions.refreshToken)
    }

    @Test fun `로그인 요청의 401 은 갱신하지 않는다`() = blocking {
        sessions.set("old", "r1")
        routeRefreshable()
        val e = assertFailsWith<AppError.Api> { client.post("/auth/login") }
        assertEquals(401, e.status)
        assertEquals(0, refreshCalls.get())
        assertEquals("old", sessions.accessToken)
    }

    @Test fun `토큰 문제가 아닌 401(현재 비밀번호 틀림 U004)은 갱신하지 않고 그대로 던진다`() = blocking {
        sessions.set("old", "r1")
        route { if (it.path == "/api/v1/members/me") json("""{"status":401,"code":"U004","message":"현재 비밀번호가 올바르지 않아요."}""", 401) else json("{}") }
        val e = assertFailsWith<AppError.Api> { client.delete("/members/me", buildJsonObject { put("password", "x") }) }
        assertEquals(401, e.status)
        assertEquals("U004", e.code)
        assertEquals(0, refreshCalls.get())
        assertEquals("old", sessions.accessToken) // 세션도 그대로
    }

    @Test fun `DELETE 와 PATCH 도 본문과 인증 헤더를 싣는다`() = blocking {
        sessions.set("tok", "r1")
        server.enqueue(json("{}"))
        server.enqueue(json("{}"))
        client.delete("/members/me", buildJsonObject { put("password", "pw") })
        client.patch("/members/me/password", buildJsonObject { put("newPassword", "n") })
        val del = server.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("Bearer tok", del.getHeader("Authorization"))
        assertEquals("""{"password":"pw"}""", del.body.readUtf8())
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("""{"newPassword":"n"}""", patch.body.readUtf8())
    }

    @Test fun `리프레시 토큰이 없으면 401 을 그대로 던진다`() = blocking {
        sessions.set("old") // 리프레시 토큰 없음
        routeRefreshable()
        val e = assertFailsWith<AppError.Api> { client.get("/x") }
        assertEquals(401, e.status)
        assertEquals(0, refreshCalls.get())
    }

    @Test fun `재시도한 요청이 또 401 이면 더 갱신하지 않고 던진다`() = blocking {
        sessions.set("old", "r1")
        route { req ->
            if (req.path == "/api/v1/auth/refresh") {
                refreshCalls.incrementAndGet()
                json("""{"accessToken":"new","refreshToken":"r2"}""")
            } else json("""{"code":"C002"}""", 401)
        }
        val e = assertFailsWith<AppError.Api> { client.get("/x") }
        assertEquals(401, e.status)
        assertEquals(1, refreshCalls.get())
    }

    @Test fun `이미 다른 요청이 갱신했으면 갱신 호출 없이 새 토큰을 돌려준다`() = blocking {
        // 이 요청이 "old" 로 나가 401 을 받는 사이 다른 쪽이 먼저 갱신한 상황
        sessions.set("new", "r2")
        val token = sessions.refresh(staleAccessToken = "old") { error("갱신 호출이 일어나면 안 된다") }
        assertEquals("new", token)
    }

    @Test fun `리프레시 토큰이 없으면 갱신은 null`() = blocking {
        sessions.set("old")
        assertNull(sessions.refresh(staleAccessToken = "old") { error("호출되면 안 된다") })
    }

    // ---- SessionManager 단독 ----

    @Test fun `세션을 지우면 두 토큰이 모두 사라진다`() {
        sessions.set("a", "r")
        sessions.clear()
        assertNull(sessions.accessToken)
        assertNull(sessions.refreshToken)
        assertEquals(false, sessions.hasRefreshToken)
    }

    @Test fun `set 에서 리프레시 토큰을 생략하면 기존 값을 유지한다`() {
        sessions.set("a", "r")
        sessions.set("b")
        assertEquals("b", sessions.accessToken)
        assertEquals("r", sessions.refreshToken)
    }

    @Test fun `응답의 임의의 구조를 그대로 돌려준다`() = blocking {
        server.enqueue(json("""{"a":1,"unknown":{"x":[1,2,3]}}"""))
        val res = client.get("/x")
        assertEquals(JsonPrimitive(1), (res as JsonObject)["a"])
        assertEquals("""[1,2,3]""", res["unknown"]!!.jsonObject["x"].toString())
    }

    private fun blocking(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking { block() }
}
