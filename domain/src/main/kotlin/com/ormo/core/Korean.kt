package com.ormo.core

object Korean {
    /** 받침 유무로 "을/를" 을 고른다: 아메리카노 → 를, 콜드브루 → 를, 딸기라떼 → 를, 잣차 → 을. 한글이 아니면 "를". */
    fun objectParticle(word: String): String {
        val last = word.trimEnd().lastOrNull() ?: return "를"
        if (last !in '가'..'힣') return "를"
        return if ((last.code - 0xAC00) % 28 == 0) "를" else "을"
    }
}
