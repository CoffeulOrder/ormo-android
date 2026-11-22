package com.ormo.data.remote

import com.ormo.domain.common.CommonError
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommonErrorMapperTest {
    @Test fun `네트워크 오류`() {
        assertEquals(CommonError.Network, CommonErrorMapper.map(AppError.Network()))
    }

    @Test fun `503 과 게이트웨이 오류는 점검 장애 화면`() {
        listOf(502, 503, 504).forEach {
            assertEquals(CommonError.Maintenance(null), CommonErrorMapper.map(AppError.Api(it)), "$it")
        }
        assertEquals(CommonError.Maintenance(null), CommonErrorMapper.map(AppError.Api(500, "MAINTENANCE")))
    }

    @Test fun `점검 종료 시각을 서버가 주면 한국 시각으로 바꿔 함께 보여준다`() {
        val body = buildJsonObject { put("maintenanceEndsAt", "2026-11-19T06:00:00Z") } // = 15:00 KST
        val e = CommonErrorMapper.map(AppError.Api(503, "MAINTENANCE", body)) as CommonError.Maintenance
        assertEquals(LocalDateTime.of(2026, 11, 19, 15, 0), e.endsAt)
        assertEquals("오후 3시에 점검이 끝나요", e.endsAtNotice)
    }

    @Test fun `봉투 안의 data 와 오프셋 표기도 읽는다 - 이상한 값은 무시`() {
        val nested = buildJsonObject { put("data", buildJsonObject { put("maintenanceEndsAt", "2026-11-19T15:30:00+09:00") }) }
        assertEquals(LocalDateTime.of(2026, 11, 19, 15, 30), (CommonErrorMapper.map(AppError.Api(503, null, nested)) as CommonError.Maintenance).endsAt)
        val bad = buildJsonObject { put("maintenanceEndsAt", "곧") }
        assertNull((CommonErrorMapper.map(AppError.Api(503, null, bad)) as CommonError.Maintenance).endsAt)
    }

    @Test fun `그 밖의 오류는 화면별 처리에 맡긴다`() {
        assertNull(CommonErrorMapper.map(AppError.Api(500)))
        assertNull(CommonErrorMapper.map(AppError.Api(400, "X")))
        assertNull(CommonErrorMapper.map(AppError.Api(401)))
        assertNull(CommonErrorMapper.map(AppError.Schema("x")))
        assertNull(CommonErrorMapper.map(IllegalStateException()))
    }
}
