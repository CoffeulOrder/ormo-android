package com.ormo.data.remote

import kotlinx.serialization.json.JsonElement

/**
 * 오류는 세 종류로 구분한다 (RN `api/errors.ts` 와 같은 구분):
 * - [Network]: 연결 실패/타임아웃 — 캐시를 계속 써도 되는 상황, 세션을 지우면 안 되는 상황
 * - [Api]: 서버가 응답했지만 2xx 가 아님 (status, code, body 보존). 2xx 인데 JSON 이 아니면 code = "BAD_RESPONSE"
 * - [Schema]: 응답은 받았지만 데이터 형태가 틀림 (캐시를 덮어쓰지 않도록 저장 전에 던진다)
 */
sealed class AppError(message: String?, cause: Throwable? = null) : Exception(message, cause) {

    class Network(cause: Throwable? = null) : AppError("network error", cause)

    class Api(val status: Int, val code: String? = null, val body: JsonElement? = null) :
        AppError("HTTP $status${code?.let { " $it" } ?: ""}")

    class Schema(val where: String) : AppError("잘못된 데이터: $where")
}
