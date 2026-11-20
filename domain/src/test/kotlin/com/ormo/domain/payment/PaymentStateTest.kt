package com.ormo.domain.payment

import com.ormo.domain.store.StoreStatus
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentStateTest {
    private val order = PlacedOrder("o1", 8000, "fp", expiresAtMs = 100_000)

    private fun begun(): PaymentState = PaymentState().begin(keepOrder = false) { "key1" }!!
    private fun atPg(): PaymentState = begun().placed(order)
    private fun confirming(): PaymentState = atPg().pgReturned()

    @Test fun `처음 상태는 Idle 이고 시작할 수 있다`() {
        val s = PaymentState()
        assertTrue(s.canBegin)
        assertFalse(s.backBlocked)
        assertFalse(s.busy)
    }

    @Test fun `시작하면 준비 중 - 같은 상태에서 다시 시작은 무시된다 (연타)`() {
        val s = begun()
        assertEquals(PaymentPhase.Preparing, s.phase)
        assertEquals("key1", s.key)
        assertNull(s.begin(keepOrder = false) { "key2" })
        assertTrue(s.busy)
        assertTrue(s.backBlocked)
    }

    @Test fun `정상 흐름 - 결제창 - 확인 중 - 성공`() {
        val pg = atPg()
        assertEquals(PaymentPhase.AwaitingPg, pg.phase)
        assertEquals(order, pg.order)
        val c = pg.pgReturned()
        assertEquals(PaymentPhase.Confirming(), c.phase)
        assertTrue(c.backBlocked) // 3.2.2 뒤로가기 막음
        assertEquals(PaymentPhase.Succeeded, c.paid().phase)
        assertFalse(c.paid().backBlocked)
    }

    @Test fun `0원 주문은 결제창 없이 바로 확인 중`() {
        val s = begun().placed(order.copy(amount = 0))
        assertEquals(PaymentPhase.Confirming(), s.phase)
    }

    @Test fun `영업이 멈춰 있으면 결제창 전에 막고 확인을 누르면 주문서로`() {
        val s = begun().blocked(StoreStatus.Closed(null))
        assertIs<PaymentPhase.Blocked>(s.phase)
        assertEquals(PaymentPhase.Idle, s.dismissBlocked().phase)
        assertTrue(s.dismissBlocked().canBegin)
    }

    @Test fun `네트워크 오류나 전부 품절이면 주문서로 돌아가 오류 종류를 남긴다`() {
        val s = begun().prepareFailed(PrepareError.NETWORK)
        assertEquals(PaymentPhase.Idle, s.phase)
        assertEquals(PrepareError.NETWORK, s.prepareError)
        assertNull(s.clearPrepareError().prepareError)
        assertNull(s.begin(keepOrder = false) { "k" }!!.prepareError) // 다시 시작하면 지워진다
    }

    @Test fun `결제창을 닫으면 주문서로 - 주문은 유지 실패 횟수는 그대로`() {
        val s = atPg().pgCanceled()
        assertEquals(PaymentPhase.Idle, s.phase)
        assertEquals(order, s.order)
        assertEquals(0, s.failedCount)
    }

    @Test fun `같은 주문으로 다시 시작하면 주문과 키를 유지한다`() {
        val s = atPg().pgCanceled().begin(keepOrder = true) { "otherKey" }!!
        assertEquals("key1", s.key)
        assertEquals(order, s.order)
    }

    @Test fun `다른 주문이면 새 키로 시작하고 실패 횟수도 초기화`() {
        val failed = confirming().failed("X")
        val s = failed.begin(keepOrder = false) { "key2" }!!
        assertEquals("key2", s.key)
        assertNull(s.order)
        assertEquals(0, s.failedCount)
    }

    @Test fun `실패 1~4번은 다시 결제할 수 있다`() {
        var s = confirming()
        repeat(4) { i ->
            s = s.failed("C$i")
            assertEquals(PaymentPhase.Failed("C$i"), s.phase)
            assertEquals(i + 1, s.failedCount)
            assertTrue(s.canBegin)
            s = s.begin(keepOrder = true) { "x" }!!.openPg().pgReturned()
        }
    }

    @Test fun `5번째 실패는 더 이상 결제할 수 없다`() {
        var s = confirming()
        repeat(4) { s = s.failed(null).begin(keepOrder = true) { "x" }!!.openPg().pgReturned() }
        s = s.failed(null)
        assertEquals(PaymentPhase.Exhausted, s.phase)
        assertEquals(5, s.failedCount)
        assertFalse(s.canBegin)
        assertNull(s.begin(keepOrder = true) { "x" })
        assertEquals(PaymentState(), s.leaveTerminal()) // [장바구니로 이동]
    }

    @Test fun `만료되면 3_3_2 - 주문은 버리고 처음부터`() {
        val s = confirming().expired()
        assertEquals(PaymentPhase.Expired, s.phase)
        assertFalse(s.canBegin)
        assertEquals(PaymentState(), s.leaveTerminal())
    }

    @Test fun `주문서에 돌아와 있는 동안 만료 시각이 지나면 만료`() {
        val idle = atPg().pgCanceled()
        assertEquals(PaymentPhase.Idle, idle.checkExpiry(99_999).phase)
        assertEquals(PaymentPhase.Expired, idle.checkExpiry(100_000).phase)
        // 확인 중에는 서버가 정한다
        assertEquals(PaymentPhase.Confirming(), confirming().checkExpiry(200_000).phase)
        // 만료 시각을 모르면 그대로
        assertEquals(PaymentPhase.Idle, begun().placed(order.copy(expiresAtMs = null)).pgCanceled().checkExpiry(Long.MAX_VALUE).phase)
    }

    @Test fun `확인 중 응답을 못 받으면 표시하고 다시 확인할 수 있다`() {
        val s = confirming().confirmUnresolved()
        assertEquals(PaymentPhase.Confirming(checkFailed = true), s.phase)
        assertEquals(PaymentPhase.Confirming(checkFailed = false), s.confirmRetrying().phase)
        assertTrue(s.backBlocked)
    }

    @Test fun `계속 확인이 안 될 때만 주문서로 나갈 수 있다 - 주문은 유지`() {
        assertEquals(PaymentPhase.Confirming(), confirming().giveUpWaiting().phase) // 정상 확인 중엔 못 나감
        val out = confirming().confirmUnresolved().giveUpWaiting()
        assertEquals(PaymentPhase.Idle, out.phase)
        assertEquals(order, out.order)
    }

    @Test fun `앱을 다시 켰을 때 저장된 주문으로 확인 중에서 시작`() {
        val s = PaymentState().resumed(order, "k", failedCount = 2)
        assertEquals(PaymentPhase.Confirming(), s.phase)
        assertEquals(2, s.failedCount)
        assertEquals("k", s.key)
        // 이미 진행 중이면 덮어쓰지 않는다
        assertEquals(begun(), begun().resumed(order, "k", 0))
    }

    @Test fun `주문서가 바뀌면 진행 중이 아닐 때만 주문을 버린다`() {
        assertEquals(PaymentState(), atPg().pgCanceled().invalidateOrder())
        assertEquals(PaymentState(), confirming().failed(null).invalidateOrder())
        assertEquals(confirming(), confirming().invalidateOrder()) // 확인 중엔 그대로
        assertEquals(atPg(), atPg().invalidateOrder())
    }

    @Test fun `순서에 맞지 않는 이벤트는 무시된다`() {
        val idle = PaymentState()
        assertEquals(idle, idle.placed(order))
        assertEquals(idle, idle.pgReturned())
        assertEquals(idle, idle.paid())
        assertEquals(idle, idle.failed("x"))
        assertEquals(idle, idle.blocked(StoreStatus.Open))
        assertEquals(idle, idle.leaveTerminal())
    }

    @Test fun `주문 중지 다이얼로그 문구`() {
        val now = LocalDateTime.of(2026, 11, 19, 13, 0)
        assertEquals(
            DialogText("오늘 영업이 끝났어요", "내일 08:30부터 주문할 수 있어요"),
            PaymentMessages.blocked(StoreStatus.Closed(now.plusDays(1).withHour(8).withMinute(30)), now),
        )
        assertEquals(
            DialogText("매장이 잠시 주문을 받지 않아요", "14:30부터 다시 주문할 수 있어요"),
            PaymentMessages.blocked(StoreStatus.Break(now.withHour(14).withMinute(30)), now),
        )
        assertEquals(
            DialogText("매장이 잠시 주문을 받지 않아요", "잠시 후 다시 주문할 수 있어요"),
            PaymentMessages.blocked(StoreStatus.Break(null), now),
        )
    }

    @Test fun `실패 문구는 코드 표가 없으면 기본 문구`() {
        assertEquals("결제하지 못했어요", PaymentMessages.failed("ANY").title)
        assertEquals("카드 상태를 확인하거나 다른 결제수단을 선택해주세요", PaymentMessages.failed(null).body)
    }
}
