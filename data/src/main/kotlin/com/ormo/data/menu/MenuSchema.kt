package com.ormo.data.menu

import com.ormo.data.remote.AppError
import com.ormo.data.remote.boolValue
import com.ormo.data.remote.id
import com.ormo.data.remote.intValue
import com.ormo.data.remote.isAbsent
import com.ormo.data.remote.longValue
import com.ormo.data.remote.str
import com.ormo.domain.model.Cafe
import com.ormo.domain.model.MenuItem
import com.ormo.domain.model.MenuPayload
import com.ormo.domain.model.OptionChoice
import com.ormo.domain.model.OptionGroup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 서버 응답/로컬 캐시의 메뉴 JSON 검증 (RN `api/menu-schema.ts`).
 * 잘못된 데이터가 정상 캐시를 덮어쓰지 않도록 저장 전에 반드시 통과시킨다.
 *
 * 규칙: **메뉴 JSON 이 깨져 있으면 전체 거부** ([AppError.Schema]). 옵션 그룹은 "없으면 없음, 있는데 형태가 틀리면
 * 메뉴 전체 거부", 선택지 id 는 메뉴 안에서 중복 금지.
 * id 는 문자열 또는 정수로 받아 String 으로 통일한다 (BE 가 숫자 id 를 써도 되도록).
 * 이미지 URL 이 빈 문자열이면 "없음"으로 본다 (RN 은 거부했음 — 디자인 2.1.1: imageUrl 은 비워 둘 수 있음).
 *
 * 품절 상태는 여기서 읽지 않는다 — 품절은 캐시하지 않고 `menu-status` 로 따로 받아 메모리에만 둔다.
 */
object MenuSchema {

    fun parsePayload(raw: JsonElement?): MenuPayload {
        val root = raw as? JsonObject ?: throw AppError.Schema("root")
        val version = root["version"].longValue() ?: throw AppError.Schema("version")
        val cafes = root["cafes"] as? JsonArray ?: throw AppError.Schema("cafes")
        return MenuPayload(version, cafes.mapIndexed { i, c -> parseCafe(c, "cafes[$i]") })
    }

    fun parseVersion(raw: JsonElement?): Long =
        (raw as? JsonObject)?.get("version").longValue() ?: throw AppError.Schema("version")

    fun parseSoldOutIds(raw: JsonElement?): List<String> {
        val ids = (raw as? JsonObject)?.get("soldOutMenuIds") as? JsonArray ?: throw AppError.Schema("soldOutMenuIds")
        return ids.mapNotNull { it.id() }
    }

    private fun parseCafe(raw: JsonElement, at: String): Cafe {
        val o = raw as? JsonObject ?: throw AppError.Schema(at)
        val id = o["id"].id()
        val name = o["name"].str()
        val menus = o["menus"] as? JsonArray
        if (id == null || name == null || menus == null) throw AppError.Schema("$at.id/name/menus")
        return Cafe(
            id = id,
            name = name,
            code = o["code"].str() ?: id.take(2).uppercase(),
            menus = menus.mapIndexed { i, m -> parseMenu(m, "$at.menus[$i]") },
        )
    }

    private fun parseMenu(raw: JsonElement, at: String): MenuItem {
        val o = raw as? JsonObject ?: throw AppError.Schema(at)
        val id = o["id"].id()
        val name = o["name"].str()
        val category = o["category"].str()
        if (id == null || name == null || category == null) throw AppError.Schema("$at.id/name/category")
        val price = o["price"].intValue()
        if (price == null || price < 0) throw AppError.Schema("$at.price")
        return MenuItem(
            id = id,
            name = name,
            desc = (o["desc"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
            price = price,
            category = category,
            thumbnailUrl = optUrl(o["thumbnailUrl"], "$at.thumbnailUrl"),
            imageUrl = optUrl(o["imageUrl"], "$at.imageUrl"),
            optionGroups = parseOptionGroups(o["optionGroups"], "$at.optionGroups"),
        )
    }

    /** 없음/null/빈 문자열 → null, 그 밖에 문자열이 아니면 거부 */
    private fun optUrl(v: JsonElement?, at: String): String? {
        if (v.isAbsent()) return null
        val p = v as? JsonPrimitive
        if (p == null || !p.isString) throw AppError.Schema(at)
        return p.content.ifEmpty { null }
    }

    private fun parseOptionGroups(raw: JsonElement?, at: String): List<OptionGroup> {
        if (raw.isAbsent()) return emptyList()
        val arr = raw as? JsonArray ?: throw AppError.Schema(at)
        val seen = HashSet<String>()
        return arr.mapIndexed { gi, g ->
            val gAt = "$at[$gi]"
            val go = g as? JsonObject ?: throw AppError.Schema(gAt)
            val gid = go["id"].id()
            val gname = go["name"].str()
            val choicesRaw = go["choices"] as? JsonArray
            if (gid == null || gname == null || choicesRaw == null || choicesRaw.isEmpty()) throw AppError.Schema(gAt)

            val choices = choicesRaw.mapIndexed { ci, c ->
                val cAt = "$gAt.choices[$ci]"
                val co = c as? JsonObject ?: throw AppError.Schema(cAt)
                val cid = co["id"].id()
                val cname = co["name"].str()
                if (cid == null || cname == null) throw AppError.Schema(cAt)
                val deltaRaw = co["priceDelta"]
                val delta = if (deltaRaw == null) 0 else deltaRaw.intValue() ?: throw AppError.Schema("$cAt.priceDelta")
                if (!seen.add(cid)) throw AppError.Schema("$cAt.id 중복") // 선택지 id 는 메뉴 안에서 고유
                OptionChoice(cid, cname, delta)
            }

            val maxRaw = go["maxSelect"]
            val maxSelect = if (maxRaw.isAbsent()) null else {
                maxRaw.intValue()?.takeIf { it >= 1 } ?: throw AppError.Schema("$gAt.maxSelect")
            }
            OptionGroup(
                id = gid,
                name = gname,
                required = go["required"].boolValue() == true,
                multi = go["multi"].boolValue() == true,
                choices = choices,
                maxSelect = maxSelect,
            )
        }
    }

    // ---- 캐시 저장용 인코딩 (parsePayload 로 다시 읽을 수 있는 형태) ----

    fun encodePayload(p: MenuPayload): JsonObject = buildJsonObject {
        put("version", p.version)
        put("cafes", buildJsonArray { p.cafes.forEach { add(encodeCafe(it)) } })
    }

    private fun encodeCafe(c: Cafe): JsonObject = buildJsonObject {
        put("id", c.id)
        put("name", c.name)
        put("code", c.code)
        put("menus", buildJsonArray { c.menus.forEach { add(encodeMenu(it)) } })
    }

    private fun encodeMenu(m: MenuItem): JsonObject = buildJsonObject {
        put("id", m.id)
        put("name", m.name)
        put("desc", m.desc)
        put("price", m.price)
        put("category", m.category)
        m.thumbnailUrl?.let { put("thumbnailUrl", it) }
        m.imageUrl?.let { put("imageUrl", it) }
        if (m.optionGroups.isNotEmpty()) {
            put("optionGroups", buildJsonArray {
                m.optionGroups.forEach { g ->
                    add(buildJsonObject {
                        put("id", g.id)
                        put("name", g.name)
                        put("required", g.required)
                        put("multi", g.multi)
                        g.maxSelect?.let { put("maxSelect", it) }
                        put("choices", buildJsonArray {
                            g.choices.forEach { c ->
                                add(buildJsonObject {
                                    put("id", c.id)
                                    put("name", c.name)
                                    put("priceDelta", c.priceDelta)
                                })
                            }
                        })
                    })
                }
            })
        }
    }
}
