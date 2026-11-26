package com.ormo.domain.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MyInfoRulesTest {
    @Test fun `이름만 보여주고 모든 행이 있다`() {
        val v = MyInfoRules.view("가을", "kim@eulji.ac.kr", osNotificationsGranted = true)
        assertEquals("가을", v.name)
        assertEquals(
            listOf(MyInfoRow.ORDER_HISTORY, MyInfoRow.CHANGE_PASSWORD, MyInfoRow.NOTIFICATIONS, MyInfoRow.TERMS_AND_POLICY, MyInfoRow.LOGOUT, MyInfoRow.WITHDRAW),
            v.rows,
        )
        assertEquals("", MyInfoRules.view(null, null, true).name)
    }

    @Test fun `권한 설정 행에는 켜짐 여부와 상관없이 설명이 붙지 않는다`() {
        assertNull(MyInfoRules.view("가을", null, true).notificationRow.description)
        assertNull(MyInfoRules.view("가을", null, false).notificationRow.description) // 꺼져 있어도 안내 문구 없음
    }

    @Test fun `로그아웃 다이얼로그 문구`() {
        assertEquals("로그아웃할까요?", MyInfoRules.LOGOUT_CONFIRM.title)
        assertEquals("학교 이메일로 다시 로그인해주세요", MyInfoRules.LOGOUT_CONFIRM.body)
    }

    @Test fun `개인정보 처리방침은 항상 열 수 있다`() {
        assertEquals("개인정보 처리방침", MyInfoRules.legalTitle(LegalDoc.PRIVACY))
        assertEquals("이용약관", MyInfoRules.legalTitle(LegalDoc.TERMS))
    }
}

class WithdrawRulesTest {
    @Test fun `진행 중 주문이 있으면 탈퇴를 막는다`() {
        val g = WithdrawRules.gate(2) as WithdrawGate.Blocked
        assertEquals("진행 중인 주문이 2건 있어요", g.title)
        assertEquals("주문이 끝난 뒤에 탈퇴할 수 있어요", g.body)
        assertEquals(WithdrawGate.Allowed, WithdrawRules.gate(0))
    }

    @Test fun `비밀번호를 입력해야 탈퇴할 수 있고 전송 중에는 다시 못 누른다`() {
        assertFalse(WithdrawFormState().canSubmit)
        val s = WithdrawFormState().onPasswordChanged("pw")
        assertTrue(s.canSubmit)
        assertFalse(s.onSubmitStarted().canSubmit)
    }

    @Test fun `비밀번호가 틀리면 칸을 비우고 다른 오류는 입력을 유지한다`() {
        val s = WithdrawFormState("pw").onSubmitStarted()
        val wrong = s.onFailure(WithdrawFailure.WRONG_PASSWORD)
        assertEquals("", wrong.password)
        assertEquals(WithdrawFailure.WRONG_PASSWORD, wrong.failure)
        assertFalse(wrong.submitting)
        assertEquals("pw", s.onFailure(WithdrawFailure.NETWORK).password)
        assertNull(wrong.onPasswordChanged("a").failure)
    }

    @Test fun `안내 문구`() {
        assertEquals("탈퇴하기 전에 확인해주세요", WithdrawRules.TITLE)
        assertTrue(WithdrawRules.RETENTION_NOTICE.contains("5년"))
        assertEquals("탈퇴가 완료됐어요. 그동안 ORMO를 이용해 주셔서 감사해요", WithdrawRules.DONE)
    }
}

class NotificationSettingsStateTest {
    private val s0 = NotificationSettingsState()

    @Test fun `기본은 모두 켜짐`() {
        assertTrue(s0.isOn(NotificationSwitch.ORDER_PUSH))
        assertTrue(s0.isOn(NotificationSwitch.LIVE))
        assertNull(s0.osOffNotice)
    }

    @Test fun `누르면 즉시 보이고 저장되면 확정`() {
        val t = s0.onToggle(NotificationSwitch.LIVE, false)
        assertFalse(t.isOn(NotificationSwitch.LIVE))
        assertTrue(t.confirmed.live) // 아직 서버 확인 전
        assertEquals(setOf(NotificationSwitch.LIVE), t.saving)
        val saved = t.onSaved(NotificationSwitch.LIVE, false)
        assertFalse(saved.confirmed.live)
        assertTrue(saved.saving.isEmpty())
    }

    @Test fun `저장 실패는 그 스위치만 되돌린다`() {
        val t = s0.onToggle(NotificationSwitch.LIVE, false).onToggle(NotificationSwitch.ORDER_PUSH, false).onSaveFailed(NotificationSwitch.LIVE)
        assertTrue(t.isOn(NotificationSwitch.LIVE))
        assertFalse(t.isOn(NotificationSwitch.ORDER_PUSH)) // 다른 스위치는 그대로
        assertEquals(NotificationSwitch.LIVE, t.failed)
    }

    @Test fun `저장 중에 다시 눌렀으면 먼저 온 저장 결과로 저장 중 표시를 끄지 않는다`() {
        val t = s0.onToggle(NotificationSwitch.LIVE, false).onToggle(NotificationSwitch.LIVE, true)
        val afterFirst = t.onSaved(NotificationSwitch.LIVE, false) // 첫 저장(꺼짐) 완료, 화면은 켜짐
        assertEquals(setOf(NotificationSwitch.LIVE), afterFirst.saving)
        assertTrue(afterFirst.isOn(NotificationSwitch.LIVE))
        assertTrue(afterFirst.onSaved(NotificationSwitch.LIVE, true).saving.isEmpty())
    }

    @Test fun `OS 알림이 꺼져 있으면 스위치는 꺼진 채 비활성이고 눌러도 변화 없음`() {
        val off = s0.withOsPermission(false)
        assertFalse(off.isOn(NotificationSwitch.ORDER_PUSH))
        assertFalse(off.isEnabled(NotificationSwitch.ORDER_PUSH))
        assertEquals(off, off.onToggle(NotificationSwitch.ORDER_PUSH, false))
        assertEquals("주문 알림을 받으려면 휴대폰 설정에서 알림을 켜주세요", off.osOffNotice?.body)
    }

    @Test fun `동작 영향 - 주문 알림을 끄면 푸시 없음 실시간 현황을 끄면 진행 중 알림 없음`() {
        val all = NotificationSettings()
        assertTrue(NotificationBehavior.pushAllowed(all, true))
        assertFalse(NotificationBehavior.pushAllowed(all.copy(orderPush = false), true))
        assertFalse(NotificationBehavior.pushAllowed(all, false))
        assertTrue(NotificationBehavior.ongoingAllowed(all, true))
        assertFalse(NotificationBehavior.ongoingAllowed(all.copy(live = false), true))
        assertFalse(NotificationBehavior.ongoingAllowed(all, false))
    }

    @Test fun `불러온 값으로 모두 교체`() {
        val t = s0.onToggle(NotificationSwitch.LIVE, false).withLoaded(NotificationSettings(orderPush = false, live = true))
        assertFalse(t.isOn(NotificationSwitch.ORDER_PUSH))
        assertTrue(t.isOn(NotificationSwitch.LIVE))
        assertTrue(t.saving.isEmpty())
    }
}
