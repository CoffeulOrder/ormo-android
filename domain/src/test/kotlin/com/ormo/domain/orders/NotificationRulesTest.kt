package com.ormo.domain.orders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationPromptRulesTest {
    @Test fun `OS 권한이 있으면 묻지 않는다`() {
        assertFalse(NotificationPromptRules.shouldShow(true, 0, true))
    }

    @Test fun `첫 주문에서 처음 묻는다 - 첫 주문이 아니면 아직 안 묻는다`() {
        assertTrue(NotificationPromptRules.shouldShow(false, 0, isFirstOrder = true))
        assertFalse(NotificationPromptRules.shouldShow(false, 0, isFirstOrder = false))
    }

    @Test fun `다음에 한 번 누르면 다음 주문에서 한 번 더 묻고 두 번 누르면 그만`() {
        assertTrue(NotificationPromptRules.shouldShow(false, 1, isFirstOrder = false))
        assertFalse(NotificationPromptRules.shouldShow(false, 2, isFirstOrder = false))
        assertFalse(NotificationPromptRules.shouldShow(false, 3, isFirstOrder = true))
    }

    @Test fun `시트 문구`() {
        assertEquals("음료가 준비되면 바로 알려드려요", NotificationPromptRules.TITLE)
        assertEquals("알림을 켜면 주문 진행 상황을 바로 알 수 있어요", NotificationPromptRules.BODY)
    }
}

class PushRulesTest {
    private fun parse(vararg kv: Pair<String, String>) = PushRules.parse(mapOf(*kv))

    @Test fun `음료 준비 푸시`() {
        val m = parse("type" to "READY", "orderId" to "o1", "storeName" to "범석관 카페", "pickupNumber" to "27", "deadline" to "13:30")!!
        assertEquals(PushType.READY, m.type)
        assertEquals("음료가 준비됐어요", m.title)
        assertEquals("범석관 카페에서 27번을 찾아가세요. 13:30까지 받아주세요.", m.body)
        assertEquals(OrderDeepLink("o1"), m.link)
    }

    @Test fun `제목에는 앱 이름이 없다`() {
        listOf(
            parse("type" to "READY", "orderId" to "o"), parse("type" to "REJECTED", "orderId" to "o"),
            parse("type" to "CANCELED_BY_STORE", "orderId" to "o"), parse("type" to "REFUNDED", "orderId" to "o", "amount" to "3000"),
        ).forEach { assertFalse(it!!.title.contains("ORMO", ignoreCase = true)) }
    }

    @Test fun `음료 준비 - 정보가 부족해도 어색하지 않은 문구`() {
        assertEquals("음료를 찾아가세요.", parse("type" to "READY", "orderId" to "o")!!.body)
        assertEquals("범석관 카페에서 27번을 찾아가세요.", parse("type" to "READY", "orderId" to "o", "storeName" to "범석관 카페", "pickupNumber" to "27")!!.body)
    }

    @Test fun `거절과 매장 취소 - 사유는 데이터`() {
        val r = parse("type" to "REJECTED", "orderId" to "o", "reason" to "메뉴가 품절됐어요")!!
        assertEquals("매장에서 주문을 받지 못했어요", r.title)
        assertEquals("메뉴가 품절됐어요", r.body)
        val c = parse("type" to "STORE_CANCELED", "orderId" to "o")!!
        assertEquals(PushType.CANCELED_BY_STORE, c.type)
        assertEquals("매장 사정으로 주문이 취소됐어요", c.title)
        assertEquals("결제한 금액은 전액 환불해 드려요", c.body)
    }

    @Test fun `환불 푸시 금액`() {
        assertEquals("3,000원을 환불했어요", parse("type" to "REFUNDED", "orderId" to "o", "amount" to "3000")!!.title)
        assertEquals("환불이 완료됐어요", parse("type" to "REFUNDED", "orderId" to "o")!!.title)
    }

    @Test fun `모르는 종류나 주문 번호가 없으면 무시`() {
        assertNull(parse("type" to "MARKETING", "orderId" to "o"))
        assertNull(parse("type" to "READY"))
        assertNull(parse("type" to "READY", "orderId" to "  "))
        assertNull(PushRules.parse(emptyMap()))
    }

    @Test fun `종류 이름은 대소문자와 별칭을 받아준다`() {
        assertEquals(PushType.READY, parse("type" to "order_ready", "orderId" to "o")!!.type)
    }

    @Test fun `실시간 현황을 켠 사람에게 음료 준비 푸시는 띄우지 않고 나머지는 띄운다`() {
        assertFalse(PushRules.shouldDisplay(PushType.READY, orderPushEnabled = true, liveEnabled = true))
        assertTrue(PushRules.shouldDisplay(PushType.READY, orderPushEnabled = true, liveEnabled = false))
        assertTrue(PushRules.shouldDisplay(PushType.REJECTED, true, true))
        assertTrue(PushRules.shouldDisplay(PushType.REFUNDED, true, true))
        assertFalse(PushRules.shouldDisplay(PushType.REJECTED, orderPushEnabled = false, liveEnabled = false))
    }
}
