package com.ormo.domain

import com.ormo.domain.model.MenuItem
import com.ormo.domain.model.OptionChoice
import com.ormo.domain.model.OptionGroup

object Fixtures {
    val temp = OptionGroup(
        id = "temp", name = "온도", required = true, multi = false,
        choices = listOf(OptionChoice("hot", "HOT", 0), OptionChoice("ice", "ICE", 0)),
    )
    val size = OptionGroup(
        id = "size", name = "사이즈", required = false, multi = false,
        choices = listOf(OptionChoice("l", "Large", 700)),
    )
    val extra = OptionGroup(
        id = "extra", name = "추가", required = false, multi = true,
        choices = listOf(
            OptionChoice("shot", "샷 추가", 500),
            OptionChoice("syrup", "시럽 추가", 300),
            OptionChoice("vanilla", "바닐라 추가", 600),
        ),
    )

    val americano = MenuItem(
        id = "m1", name = "아메리카노", desc = "", price = 4000, category = "커피",
        optionGroups = listOf(temp, size, extra),
    )

    /** 옵션 없는 메뉴 */
    fun plain(id: String, name: String = "메뉴$id", price: Int = 3000) =
        MenuItem(id = id, name = name, desc = "", price = price, category = "디저트")

    fun withExtraMax(max: Int) =
        americano.copy(optionGroups = listOf(temp, size, extra.copy(maxSelect = max)))
}
