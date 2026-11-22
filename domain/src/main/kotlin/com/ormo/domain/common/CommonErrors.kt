package com.ormo.domain.common

import java.time.LocalDateTime

/** 공통 오류·안내 화면 (디자인 6.1, 고객 앱·사장님 앱 공통) */
sealed interface CommonError {
    val title: String
    val body: String

    /** 6.1.1 인터넷 끊김: [다시 시도] → 보던 화면 재로드. (결제 중 끊기면 3.2.2 로 — 결제 쪽이 처리) */
    data object Network : CommonError {
        override val title get() = "인터넷 연결을 확인해주세요"
        override val body get() = "연결이 복구되면 다시 불러올게요"
    }

    /** 6.1.2 로그인 만료: [로그인하기] → 0.1.1(이메일 채움), 로그인하면 보던 화면으로. 다른 기기에서 비밀번호를 바꿨을 때도 같은 안내 */
    data object SessionExpired : CommonError {
        override val title get() = "다시 로그인해주세요"
        override val body get() = "보안을 위해 로그아웃됐어요. 장바구니에 담긴 메뉴는 그대로 유지돼요."
    }

    /** 6.1.3 서비스 점검·장애: [다시 시도] → 앱을 처음부터 다시 불러옴. 점검이면 끝나는 시각도 함께 */
    data class Maintenance(val endsAt: LocalDateTime? = null) : CommonError {
        override val title get() = "서비스 이용이 원활하지 않아요"
        override val body get() = "잠시 후 다시 시도해주세요"

        /** "오후 3시에 점검이 끝나요" — 점검 시각을 모르면(장애) null */
        val endsAtNotice: String? get() = endsAt?.let { "${CommonErrorRules.koreanTime(it)}에 점검이 끝나요" }
    }
}

object CommonErrorRules {
    /** 6.1.1 "일부만 실패": 전체 오류 화면 대신 스낵바 3초 (예: 메뉴 홈에서 담기·새로고침 실패) */
    const val PARTIAL_FAILURE_SNACKBAR = "연결이 불안해요. 다시 시도해 주세요"
    const val SNACKBAR_MS = 3_000L

    /** 6.1.4 스켈레톤: 0.3초 안에 불러오면 띄우지 않는다 (깜빡임 방지) */
    const val SKELETON_DELAY_MS = 300L

    fun shouldShowSkeleton(loadingForMs: Long): Boolean = loadingForMs >= SKELETON_DELAY_MS

    /**
     * 오류를 전체 화면으로 보일지 스낵바로 보일지 (6.1.1): **이미 보여줄 내용이 있으면 스낵바**, 아무것도 없으면 전체 화면.
     */
    fun presentation(hasContent: Boolean): ErrorPresentation = if (hasContent) ErrorPresentation.SNACKBAR else ErrorPresentation.FULL_SCREEN

    /** "오후 3시", "오후 3시 30분", "오전 12시"(자정), "오후 12시"(정오) */
    fun koreanTime(t: LocalDateTime): String {
        val h = t.hour
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = when (val x = h % 12) { 0 -> 12; else -> x }
        return if (t.minute == 0) "$ampm ${h12}시" else "$ampm ${h12}시 ${t.minute}분"
    }
}

enum class ErrorPresentation { FULL_SCREEN, SNACKBAR }
