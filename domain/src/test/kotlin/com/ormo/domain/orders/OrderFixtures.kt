package com.ormo.domain.orders

import com.ormo.domain.model.OrderStatus
import java.time.Instant
import java.time.ZoneOffset

/** 테스트 기준 시각: 2026-11-19 13:00 (Asia/Seoul = UTC+9 → 04:00Z) */
internal val T0: Instant = java.time.LocalDateTime.of(2026, 11, 19, 13, 0).toInstant(ZoneOffset.ofHours(9))
internal fun at(min: Long): Instant = T0.plusSeconds(min * 60)

internal fun line(menuId: String = "1", name: String = "아메리카노", qty: Int = 1, choices: List<String> = emptyList(), amount: Int = 4000) =
    OrderLine(menuId, name, choices, "", qty, amount)

internal fun order(id: String, status: OrderStatus, placedAt: Instant = at(0), readyAt: Instant? = null): Order =
    order(status = status, id = id, placedAt = placedAt, readyAt = readyAt, lines = listOf(line()))

internal fun order(
    status: OrderStatus = OrderStatus.REQUESTED,
    id: String = "o1",
    number: Int? = 27,
    lines: List<OrderLine> = listOf(line()),
    total: Int = 4000,
    placedAt: Instant = at(0),
    store: String = "범석관 카페",
    ahead: Int? = 3,
    makingAt: Instant? = null,
    readyAt: Instant? = null,
    completedAt: Instant? = null,
    closedAt: Instant? = null,
    canceledBy: CancelActor? = null,
    reason: String? = null,
    auto: Boolean = false,
    refund: Int? = null,
    discard: Int? = null,
    deadline: Instant? = null,
) = Order(
    id = id, pickupNumber = number, storeId = "s1", storeName = store, status = status, lines = lines, totalAmount = total,
    refundAmount = refund, placedAt = placedAt, makingAt = makingAt, readyAt = readyAt, completedAt = completedAt,
    closedAt = closedAt, pickupDeadline = deadline, discardMinutes = discard, aheadCount = ahead, canceledBy = canceledBy,
    reason = reason, autoRejected = auto,
)
