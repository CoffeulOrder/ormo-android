package com.ormo.domain.profile

import com.ormo.domain.payment.DialogText

/** 내 정보 행 (5.1.1) */
enum class MyInfoRow { ORDER_HISTORY, CHANGE_PASSWORD, NOTIFICATIONS, TERMS_AND_POLICY, LOGOUT, WITHDRAW }

/** 알림 설정 행: OS 권한이 꺼져 있으면 설명을 붙인다 (5.1.1) */
data class NotificationRowView(val description: String?)

/** 내 정보 화면에 그릴 내용. 닉네임이 없으므로 이름만. */
data class MyInfoView(
    val name: String,
    val email: String?,
    val rows: List<MyInfoRow>,
    val notificationRow: NotificationRowView,
)

/** 약관·정책 한 항목 (5.1.3). 문서 본문은 서버/웹 주소이고, 0.2.5 약관 전문과 같은 화면을 쓴다. */
enum class LegalDoc { TERMS, PRIVACY }

object MyInfoRules {

    val LOGOUT_CONFIRM = DialogText("로그아웃할까요?", "학교 이메일로 다시 로그인해주세요")

    /** 이름이 비어 있으면(프로필을 아직 못 받음) 빈 문자열 — 화면은 스켈레톤/자리 표시 */
    fun view(name: String?, email: String?, osNotificationsGranted: Boolean): MyInfoView = MyInfoView(
        name = name.orEmpty(),
        email = email,
        rows = MyInfoRow.entries,
        notificationRow = NotificationRowView(null), // Figma 5.1.1: 권한 설정 행은 메뉴 이름만 — 값(켜짐/꺼짐)이 어떻든 안내 문구를 붙이지 않는다
    )

    /**
     * 개인정보 처리방침은 앱 안에서 언제든 볼 수 있어야 한다 (앱스토어 심사 5.1.1). 로그인 전(약관 동의 화면)·내 정보 양쪽에서 열린다.
     * 문서 표시 이름.
     */
    fun legalTitle(doc: LegalDoc): String = when (doc) {
        LegalDoc.TERMS -> "이용약관"
        LegalDoc.PRIVACY -> "개인정보 처리방침"
    }
}
