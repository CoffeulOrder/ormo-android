package com.ormo.domain.common

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommonErrorsTest {
    @Test fun `네트워크 오류 문구`() {
        assertEquals("인터넷 연결을 확인해주세요", CommonError.Network.title)
        assertEquals("연결이 복구되면 다시 불러올게요", CommonError.Network.body)
    }

    @Test fun `로그인 만료 문구 - 장바구니는 유지된다는 안내`() {
        assertEquals("다시 로그인해주세요", CommonError.SessionExpired.title)
        assertEquals("보안을 위해 로그아웃됐어요. 장바구니에 담긴 메뉴는 그대로 유지돼요.", CommonError.SessionExpired.body)
    }

    @Test fun `점검 장애 문구 - 점검이면 끝나는 시각도`() {
        val m = CommonError.Maintenance(LocalDateTime.of(2026, 11, 19, 15, 0))
        assertEquals("서비스 이용이 원활하지 않아요", m.title)
        assertEquals("잠시 후 다시 시도해주세요", m.body)
        assertEquals("오후 3시에 점검이 끝나요", m.endsAtNotice)
        assertNull(CommonError.Maintenance().endsAtNotice)
    }

    @Test fun `시각 읽기`() {
        fun t(h: Int, m: Int = 0) = CommonErrorRules.koreanTime(LocalDateTime.of(2026, 11, 19, h, m))
        assertEquals("오전 12시", t(0))
        assertEquals("오전 9시", t(9))
        assertEquals("오후 12시", t(12))
        assertEquals("오후 3시 30분", t(15, 30))
        assertEquals("오후 11시 5분", t(23, 5))
    }

    @Test fun `스켈레톤은 0점3초 안에 불러오면 띄우지 않는다`() {
        assertFalse(CommonErrorRules.shouldShowSkeleton(299))
        assertTrue(CommonErrorRules.shouldShowSkeleton(300))
    }

    @Test fun `보여줄 내용이 있으면 스낵바 없으면 전체 화면`() {
        assertEquals(ErrorPresentation.SNACKBAR, CommonErrorRules.presentation(hasContent = true))
        assertEquals(ErrorPresentation.FULL_SCREEN, CommonErrorRules.presentation(hasContent = false))
        assertEquals("연결이 불안해요. 다시 시도해 주세요", CommonErrorRules.PARTIAL_FAILURE_SNACKBAR)
        assertEquals(3000L, CommonErrorRules.SNACKBAR_MS)
    }
}
