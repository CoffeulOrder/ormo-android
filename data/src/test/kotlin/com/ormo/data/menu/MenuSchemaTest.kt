package com.ormo.data.menu

import com.ormo.data.remote.AppError
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MenuSchemaTest {

    private fun parse(s: String) = MenuSchema.parsePayload(Json.parseToJsonElement(s))

    /** 메뉴 하나짜리 최소 payload. `extra` 는 메뉴 객체 끝에 덧붙일 필드 (앞에 쉼표 포함). */
    private fun menuJson(extra: String = "", price: String = "1") =
        """{"version":1,"cafes":[{"id":"c","name":"c","menus":[{"id":"m","name":"n","price":$price,"category":"k"$extra}]}]}"""

    private val valid = """
        {"version":3,"cafes":[{"id":"c1","name":"범석관 카페","code":"BS","menus":[
          {"id":"m1","name":"아메리카노","desc":"깔끔","price":4000,"category":"커피","thumbnailUrl":"https://x/t.png",
           "optionGroups":[
             {"id":"temp","name":"온도","required":true,"multi":false,
              "choices":[{"id":"hot","name":"HOT"},{"id":"ice","name":"ICE","priceDelta":0}]},
             {"id":"extra","name":"추가","multi":true,"maxSelect":2,
              "choices":[{"id":"shot","name":"샷 추가","priceDelta":500}]}]},
          {"id":"m2","name":"치즈케이크","price":6500,"category":"디저트"}]}]}
    """.trimIndent()

    @Test fun `정상 payload 를 해석한다`() {
        val p = parse(valid)
        assertEquals(3L, p.version)
        val cafe = p.cafes.single()
        assertEquals("BS", cafe.code)
        val (americano, cake) = cafe.menus
        assertEquals("아메리카노", americano.name)
        assertEquals(4000, americano.price)
        assertEquals("https://x/t.png", americano.thumbnailUrl)
        assertEquals(2, americano.optionGroups.size)
        val temp = americano.optionGroups[0]
        assertTrue(temp.required)
        assertEquals(false, temp.multi)
        assertEquals(0, temp.choices[0].priceDelta) // priceDelta 생략은 0
        val extra = americano.optionGroups[1]
        assertTrue(extra.multi)
        assertEquals(false, extra.required)
        assertEquals(2, extra.maxSelect)
        assertEquals(emptyList(), cake.optionGroups)
        assertEquals("", cake.desc)
    }

    @Test fun `숫자 id 도 문자열로 받는다`() {
        val p = parse("""{"version":1,"cafes":[{"id":1,"name":"c","menus":[{"id":7,"name":"n","price":1,"category":"k"}]}]}""")
        assertEquals("1", p.cafes[0].id)
        assertEquals("7", p.cafes[0].menus[0].id)
        assertEquals("1".take(2).uppercase(), p.cafes[0].code) // code 없으면 id 앞 2자
    }

    @Test fun `인코딩 후 다시 해석하면 같은 데이터`() {
        val p = parse(valid)
        assertEquals(p, MenuSchema.parsePayload(MenuSchema.encodePayload(p)))
    }

    @Test fun `필수 필드가 틀리면 전체 거부`() {
        assertFailsWith<AppError.Schema> { parse("""{"cafes":[]}""") } // version 없음
        assertFailsWith<AppError.Schema> { parse(valid.replace("\"version\":3", "\"version\":3.5")) }
        assertFailsWith<AppError.Schema> { parse("""{"version":1}""") } // cafes 없음
        assertFailsWith<AppError.Schema> { parse("[]") }
        assertFailsWith<AppError.Schema> { parse(menuJson(price = "-1")) }
        assertFailsWith<AppError.Schema> { parse(menuJson(price = "\"4000\"")) }
        assertFailsWith<AppError.Schema> { parse(menuJson(price = "4000.5")) }
        assertFailsWith<AppError.Schema> { parse(valid.replace("\"name\":\"범석관 카페\",", "")) }
    }

    @Test fun `메뉴 하나만 깨져도 전체를 거부한다`() {
        val broken = """{"version":1,"cafes":[{"id":"c","name":"c","menus":[
            {"id":"a","name":"ok","price":1,"category":"k"},
            {"id":"b","name":"bad","price":-5,"category":"k"}]}]}"""
        assertFailsWith<AppError.Schema> { parse(broken) }
    }

    @Test fun `옵션 그룹이 없으면 없음 있는데 틀리면 거부`() {
        assertEquals(emptyList(), parse(menuJson()).cafes[0].menus[0].optionGroups)
        assertEquals(emptyList(), parse(menuJson(""","optionGroups":null""")).cafes[0].menus[0].optionGroups)
        assertEquals(emptyList(), parse(menuJson(""","optionGroups":[]""")).cafes[0].menus[0].optionGroups)
        assertFailsWith<AppError.Schema> { parse(menuJson(""","optionGroups":"oops"""")) }
        assertFailsWith<AppError.Schema> { parse(menuJson(""","optionGroups":[1]""")) }
    }

    @Test fun `선택지가 비었거나 필드가 틀린 그룹은 거부`() {
        assertFailsWith<AppError.Schema> {
            parse(menuJson(""","optionGroups":[{"id":"g","name":"G","choices":[]}]"""))
        }
        assertFailsWith<AppError.Schema> {
            parse(menuJson(""","optionGroups":[{"id":"g","name":"G","choices":[{"id":"a"}]}]"""))
        }
        assertFailsWith<AppError.Schema> {
            parse(menuJson(""","optionGroups":[{"id":"g","name":"G","choices":[{"id":"a","name":"A","priceDelta":"x"}]}]"""))
        }
    }

    @Test fun `선택지 id 는 메뉴 안에서 중복 금지`() {
        // 그룹이 달라도 같은 id 는 안 된다 (주문에는 선택지 id 만 저장하므로)
        assertFailsWith<AppError.Schema> {
            parse(
                menuJson(
                    ""","optionGroups":[
                    {"id":"g1","name":"G1","choices":[{"id":"a","name":"A"}]},
                    {"id":"g2","name":"G2","choices":[{"id":"a","name":"A2"}]}]""",
                ),
            )
        }
        // 같은 id 가 다른 메뉴에 있는 것은 괜찮다
        parse(
            """{"version":1,"cafes":[{"id":"c","name":"c","menus":[
            {"id":"m1","name":"n","price":1,"category":"k","optionGroups":[{"id":"g","name":"G","choices":[{"id":"a","name":"A"}]}]},
            {"id":"m2","name":"n","price":1,"category":"k","optionGroups":[{"id":"g","name":"G","choices":[{"id":"a","name":"A"}]}]}]}]}""",
        )
    }

    @Test fun `required 와 multi 는 진짜 true 일 때만`() {
        val g = parse(
            menuJson(""","optionGroups":[{"id":"g","name":"G","required":"true","multi":1,"choices":[{"id":"a","name":"A"}]}]"""),
        ).cafes[0].menus[0].optionGroups[0]
        assertEquals(false, g.required)
        assertEquals(false, g.multi)
    }

    @Test fun `maxSelect 는 1 이상 정수`() {
        fun maxOf(v: String) = parse(
            menuJson(""","optionGroups":[{"id":"g","name":"G","multi":true,"maxSelect":$v,"choices":[{"id":"a","name":"A"}]}]"""),
        ).cafes[0].menus[0].optionGroups[0].maxSelect
        assertEquals(3, maxOf("3"))
        assertNull(maxOf("null"))
        assertFailsWith<AppError.Schema> { maxOf("0") }
        assertFailsWith<AppError.Schema> { maxOf("\"2\"") }
    }

    @Test fun `이미지 URL 은 비어 있으면 없음 문자열이 아니면 거부`() {
        assertNull(parse(menuJson(",\"thumbnailUrl\":\"\"")).cafes[0].menus[0].thumbnailUrl)
        assertNull(parse(menuJson(""","imageUrl":null""")).cafes[0].menus[0].imageUrl)
        assertFailsWith<AppError.Schema> { parse(menuJson(""","thumbnailUrl":5""")) }
    }

    @Test fun `버전과 품절 목록`() {
        assertEquals(9L, MenuSchema.parseVersion(Json.parseToJsonElement("""{"version":9}""")))
        assertFailsWith<AppError.Schema> { MenuSchema.parseVersion(Json.parseToJsonElement("""{"version":"9"}""")) }
        assertFailsWith<AppError.Schema> { MenuSchema.parseVersion(null) }

        assertEquals(
            listOf("1", "m2"),
            MenuSchema.parseSoldOutIds(Json.parseToJsonElement("""{"soldOutMenuIds":[1,"m2",null,"",1.5]}""")),
        )
        assertFailsWith<AppError.Schema> { MenuSchema.parseSoldOutIds(Json.parseToJsonElement("""{}""")) }
    }
}
