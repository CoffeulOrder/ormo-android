package com.ormo.core

import kotlin.test.Test
import kotlin.test.assertEquals

class KoreanTest {
    @Test fun `받침이 없으면 를, 있으면 을`() {
        assertEquals("를", Korean.objectParticle("아메리카노"))
        assertEquals("를", Korean.objectParticle("카페라떼"))
        assertEquals("을", Korean.objectParticle("레몬"))
        assertEquals("을", Korean.objectParticle("자몽"))
    }

    @Test fun `한글이 아니거나 비어 있으면 를`() {
        assertEquals("를", Korean.objectParticle("Latte"))
        assertEquals("를", Korean.objectParticle(""))
    }
}
