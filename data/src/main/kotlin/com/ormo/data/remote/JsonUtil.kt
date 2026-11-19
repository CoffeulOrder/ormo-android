package com.ormo.data.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** 비어 있지 않은 문자열이면 그 값, 아니면 null */
internal fun JsonElement?.str(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content

/** id: 비어 있지 않은 문자열 또는 정수 (BE 가 숫자 id 를 써도 받을 수 있게). 항상 String 으로 돌려준다. */
internal fun JsonElement?.id(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    if (p.isString) return p.content.takeIf { it.isNotEmpty() }
    return p.content.toLongOrNull()?.toString()
}

/** 유한한 정수값 (4000, 4000.0 허용 / 4000.5, 문자열, null 은 null) */
internal fun JsonElement?.intValue(): Int? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    val d = p.content.toDoubleOrNull() ?: return null
    if (d.isNaN() || d.isInfinite() || d != Math.floor(d) || d > Int.MAX_VALUE || d < Int.MIN_VALUE) return null
    return d.toInt()
}

internal fun JsonElement?.longValue(): Long? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    return p.content.toLongOrNull()
}

/** 진짜 JSON boolean 일 때만 (문자열 "true" 는 아님) */
internal fun JsonElement?.boolValue(): Boolean? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    return when (p.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

internal fun JsonElement?.isAbsent(): Boolean = this == null || this is JsonNull
