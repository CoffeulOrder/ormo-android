package com.ormo.domain.orders

import com.ormo.domain.model.OrderStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderHistoryRulesTest {
    private fun done(id: String, min: Long, status: OrderStatus = OrderStatus.COMPLETED) = order(id, status, placedAt = at(min))

    @Test fun `지난 주문만 - 진행 중 결제 대기 만료는 빠진다`() {
        val s = HistoryState().loaded(
            listOf(
                done("a", 1), done("b", 2, OrderStatus.REQUESTED), done("c", 3, OrderStatus.MAKING), done("d", 4, OrderStatus.READY),
                done("e", 5, OrderStatus.PENDING_PAYMENT), done("f", 6, OrderStatus.EXPIRED), done("g", 7, OrderStatus.UNCLAIMED),
            ),
            next = null, hasMore = false,
        )
        assertEquals(listOf("g", "a"), s.orders.map { it.id }) // 최신순
    }

    @Test fun `거절 취소 환불 미수령은 내역에 보인다`() {
        val s = HistoryState().loaded(
            listOf(OrderStatus.REJECTED, OrderStatus.CANCELED, OrderStatus.REFUNDED, OrderStatus.UNCLAIMED).mapIndexed { i, st -> done("o$i", i.toLong(), st) },
            null, false,
        )
        assertEquals(4, s.orders.size)
    }

    @Test fun `페이지를 이어 붙이고 중복은 새 값으로 바꾼다`() {
        var s = HistoryState().loaded(listOf(done("a", 5), done("b", 4)), next = "c1", hasMore = true)
        assertTrue(s.hasMore)
        s = s.loaded(listOf(done("b", 4, OrderStatus.REFUNDED), done("c", 3)), next = null, hasMore = false)
        assertEquals(listOf("a", "b", "c"), s.orders.map { it.id })
        assertEquals(OrderStatus.REFUNDED, s.orders[1].status)
        assertFalse(s.hasMore)
    }

    @Test fun `다음 커서가 없으면 더 없음`() {
        assertFalse(HistoryState().loaded(listOf(done("a", 1)), next = null, hasMore = true).hasMore)
    }

    @Test fun `주문 내역이 없으면 빈 상태 - 첫 로딩 중에는 아님`() {
        assertFalse(HistoryState().isEmpty)
        assertFalse(HistoryState().started().isEmpty)
        assertTrue(HistoryState().loaded(emptyList(), null, false).isEmpty)
        assertEquals("주문 내역이 없어요", OrderHistoryRules.EMPTY_TITLE)
    }

    @Test fun `한 페이지가 통째로 걸러졌는데 더 있으면 자동으로 이어 불러온다`() {
        val s = HistoryState().loaded(listOf(done("a", 1, OrderStatus.REQUESTED)), next = "c1", hasMore = true)
        assertTrue(s.orders.isEmpty())
        assertTrue(s.needsAutoLoad)
        assertFalse(s.isEmpty) // 아직 더 있으니 빈 상태가 아니다
        assertFalse(s.started().needsAutoLoad)
    }

    @Test fun `로딩 중에는 더 불러오지 않는다 - 실패하면 다시 시도할 수 있다`() {
        val s = HistoryState().loaded(listOf(done("a", 1)), "c1", true)
        assertTrue(s.canLoadMore)
        assertFalse(s.started().canLoadMore)
        val f = s.started().failedToLoad()
        assertTrue(f.failed)
        assertTrue(f.canLoadMore)
        assertFalse(f.started().failed)
    }

    @Test fun `주문이 바뀌면 목록에 반영하고 진행 중이 되면 빠진다`() {
        val s = HistoryState().loaded(listOf(done("a", 1), done("b", 2)), null, false)
        val refunded = s.updated(done("a", 1, OrderStatus.REFUNDED))
        assertEquals(OrderStatus.REFUNDED, refunded.orders.first { it.id == "a" }.status)
        assertEquals(listOf("b"), s.updated(done("a", 1, OrderStatus.REQUESTED)).orders.map { it.id })
        assertEquals(s, s.updated(done("zzz", 1)))
    }

    @Test fun `상태 문구`() {
        assertEquals("픽업 완료", OrderHistoryRules.statusLabel(done("a", 1)))
        assertEquals("주문 거절", OrderHistoryRules.statusLabel(done("a", 1, OrderStatus.REJECTED)))
        assertEquals("주문 취소", OrderHistoryRules.statusLabel(order(status = OrderStatus.CANCELED, canceledBy = CancelActor.CUSTOMER)))
        assertEquals("매장 주문 취소", OrderHistoryRules.statusLabel(order(status = OrderStatus.CANCELED, canceledBy = CancelActor.STORE)))
        assertEquals("환불 완료", OrderHistoryRules.statusLabel(done("a", 1, OrderStatus.REFUNDED)))
        assertEquals("미수령", OrderHistoryRules.statusLabel(done("a", 1, OrderStatus.UNCLAIMED)))
    }

    @Test fun `취소 거절 환불은 금액에 취소선과 메뉴 보러 가기 - 나머지는 재주문`() {
        val ok = OrderHistoryRules.card(done("a", 1))
        assertFalse(ok.strikeAmount)
        assertEquals(HistoryAction.REORDER, ok.primary)
        assertEquals("4,000원", ok.amountLabel)
        assertEquals("11월 19일 13:01", ok.dateLabel)

        listOf(OrderStatus.CANCELED, OrderStatus.REJECTED, OrderStatus.REFUNDED).forEach {
            val c = OrderHistoryRules.card(done("a", 1, it))
            assertTrue(c.strikeAmount, "$it")
            assertEquals(HistoryAction.BROWSE_MENU, c.primary)
        }
        assertEquals(HistoryAction.REORDER, OrderHistoryRules.card(done("a", 1, OrderStatus.UNCLAIMED)).primary)
    }
}
