package com.ormo.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ORMO 백엔드 HTTP 클라이언트 (OkHttp). RN `api/client.ts` 의 동작을 옮겼다.
 *
 * - 모든 요청에 `Authorization: Bearer <액세스 토큰>` (세션이 있으면), `Accept: application/json`.
 * - 401 을 받으면 (로그인·갱신 요청 제외, 리프레시 토큰이 있을 때) [SessionManager.refresh] 로 **한 번** 갱신하고
 *   **같은 요청**(같은 헤더, 예: Idempotency-Key)을 다시 보낸다. 갱신이 거절되면 원래 401 을 던진다.
 * - 오류: [AppError.Network] / [AppError.Api] / (파싱은 호출하는 쪽이 [AppError.Schema]).
 * - BE 응답 봉투 `{timestamp, status, code, message, data}` 가 오면 `data` 만 돌려준다 (BE_COMPARISON §0).
 *   봉투 판정: 최상위 객체에 `data` 키가 있고 `timestamp/status/code/message` 중 하나가 같이 있을 때.
 *   (BE 계약이 확정되면 이 한 곳만 고친다.)
 *
 * 인터셉터 `Authenticator` 대신 코루틴에서 직접 재시도하는 이유: OkHttp 스레드에서 `runBlocking` 을 하지 않고,
 * 갱신 결과(네트워크 오류 vs 거절)를 호출한 쪽까지 그대로 전달하기 위해서다.
 */
class ApiClient(
    baseUrl: String,
    val sessions: SessionManager,
    private val http: OkHttpClient = defaultHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val base = baseUrl.trimEnd('/')

    suspend fun get(path: String, headers: Map<String, String> = emptyMap()): JsonElement? =
        request("GET", path, null, headers)

    suspend fun post(path: String, body: JsonElement? = null, headers: Map<String, String> = emptyMap()): JsonElement? =
        request("POST", path, body, headers)

    suspend fun put(path: String, body: JsonElement? = null, headers: Map<String, String> = emptyMap()): JsonElement? =
        request("PUT", path, body, headers)

    suspend fun delete(path: String, headers: Map<String, String> = emptyMap()): JsonElement? =
        request("DELETE", path, null, headers)

    suspend fun request(
        method: String,
        path: String,
        body: JsonElement? = null,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement? {
        val token = sessions.accessToken
        try {
            return send(method, path, body, token, headers)
        } catch (e: AppError.Api) {
            val canRefresh = e.status == 401 && path !in NO_REFRESH && sessions.hasRefreshToken
            if (!canRefresh) throw e
            val fresh = sessions.refresh(token) { rt -> refreshCall(rt) } ?: throw e
            return send(method, path, body, fresh, headers)
        }
    }

    private suspend fun refreshCall(refreshToken: String): RefreshedTokens {
        val res = send("POST", "/auth/refresh", buildJsonObject { put("refreshToken", refreshToken) }, null, emptyMap())
        val obj = res as? JsonObject
        val access = obj?.get("accessToken").str() ?: throw AppError.Api(200, "BAD_RESPONSE")
        return RefreshedTokens(access, obj?.get("refreshToken").str())
    }

    /** 토큰 하나로 요청 한 번 (재시도 없음) */
    private suspend fun send(
        method: String,
        path: String,
        body: JsonElement?,
        token: String?,
        headers: Map<String, String>,
    ): JsonElement? {
        val builder = Request.Builder().url(base + path).header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val requestBody = when {
            body != null -> json.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON_TYPE)
            method == "POST" || method == "PUT" -> "".toRequestBody(JSON_TYPE)
            else -> null
        }
        builder.method(method, requestBody)

        val (status, text) = http.newCall(builder.build()).await()

        val parsed: JsonElement? = if (text.isBlank()) {
            null
        } else {
            try {
                json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                // JSON 이 아닌 응답은 잘못된 응답으로 취급 (캐시를 덮어쓰지 않도록 실패 처리)
                throw AppError.Api(status, "BAD_RESPONSE")
            }
        }

        // kotlinx 는 따옴표 없는 최상위 글자("<html>oops</html>")도 값으로 받아 주므로, 객체/배열이 아니면 잘못된 응답
        if (parsed != null && parsed !is JsonObject && parsed !is JsonArray && parsed !is JsonNull) {
            throw AppError.Api(status, "BAD_RESPONSE")
        }

        if (status !in 200..299) {
            val code = (parsed as? JsonObject)?.get("code").str()
            throw AppError.Api(status, code, parsed)
        }
        return unwrap(parsed)
    }

    private fun unwrap(el: JsonElement?): JsonElement? {
        if (el is JsonObject && "data" in el && ENVELOPE_KEYS.any { it in el }) {
            return el["data"].takeUnless { it is JsonNull }
        }
        return el
    }

    private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(AppError.Network(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { it.code to (it.body?.string().orEmpty()) }
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(AppError.Network(e))
                    return
                }
                if (cont.isActive) cont.resume(result)
            }
        })
    }

    companion object {
        /** 401 이 와도 갱신을 시도하지 않는 경로 (로그인 실패/갱신 자체의 거절은 "세션 만료" 가 아니다) */
        val NO_REFRESH = setOf("/auth/login", "/auth/refresh")
        private val ENVELOPE_KEYS = listOf("timestamp", "status", "code", "message")
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        const val TIMEOUT_SECONDS = 10L

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
