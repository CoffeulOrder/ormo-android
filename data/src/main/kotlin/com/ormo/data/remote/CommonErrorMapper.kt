package com.ormo.data.remote

import com.ormo.domain.common.CommonError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * 예외 → 공통 오류 화면(6.1) 종류. **화면 전체를 대체할 만한 오류만** 돌려주고, 그 밖의 오류는 null → 각 화면이 자기 방식으로 다룬다.
 *
 * - 네트워크 → [CommonError.Network]
 * - 서비스 점검·장애 → [CommonError.Maintenance]: 503 또는 코드가 MAINTENANCE 류. 점검 종료 시각을 서버가 주면 함께 (`maintenanceEndsAt`: ISO-8601).
 *   502/504 도 같은 화면 (게이트웨이 장애). 그 밖의 5xx 는 화면별 처리(일시적 서버 오류 토스트 등)에 맡긴다.
 * - 로그인 만료는 [com.ormo.data.auth.AuthRepository] 의 `expired` 이벤트가 정한다 (여기서 만들지 않음).
 *
 * ⚠️ 점검 응답 형식은 앱이 가정한 것 (BE 미확정): 코드 `MAINTENANCE`/`SERVICE_UNAVAILABLE`, 필드 `maintenanceEndsAt`.
 */
object CommonErrorMapper {
    private val MAINTENANCE_CODES = setOf("MAINTENANCE", "SERVICE_MAINTENANCE", "SERVICE_UNAVAILABLE")

    fun map(e: Throwable, zone: ZoneId = ZoneId.of("Asia/Seoul")): CommonError? = when (e) {
        is AppError.Network -> CommonError.Network
        is AppError.Api ->
            if (e.status == 503 || e.status == 502 || e.status == 504 || e.code in MAINTENANCE_CODES) {
                CommonError.Maintenance(endsAt(e.body, zone))
            } else {
                null
            }
        else -> null
    }

    private fun endsAt(body: JsonElement?, zone: ZoneId): LocalDateTime? {
        val o = body as? JsonObject ?: return null
        val raw = o["maintenanceEndsAt"].str() ?: (o["data"] as? JsonObject)?.get("maintenanceEndsAt").str() ?: return null
        return try {
            Instant.parse(raw).atZone(zone).toLocalDateTime()
        } catch (e: DateTimeParseException) {
            try {
                java.time.OffsetDateTime.parse(raw).atZoneSameInstant(zone).toLocalDateTime()
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }
}
