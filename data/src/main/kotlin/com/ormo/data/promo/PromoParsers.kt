package com.ormo.data.promo

import com.ormo.data.remote.AppError
import com.ormo.data.remote.intValue
import com.ormo.data.remote.str
import com.ormo.domain.promo.AppEvent
import com.ormo.domain.promo.Banner
import com.ormo.domain.promo.BannerRules
import com.ormo.domain.promo.MyMenuItem
import com.ormo.domain.promo.MyMenuSet
import com.ormo.domain.promo.Voucher
import com.ormo.domain.promo.VoucherStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * 서버 응답 검증. 알 수 없는 종류의 쿠폰은 에러가 아니라 건너뛰고(서버가 새 종류를 먼저 내려줘도 구버전 앱이 목록을 통째로 못 보여주는 일이 없게),
 * 배너·이벤트 하나가 깨져 있어도 나머지는 살린다. 목록 자체의 모양이 틀리면 [AppError.Schema].
 */
internal object PromoParsers {
    private fun obj(e: JsonElement?, where: String): JsonObject = e as? JsonObject ?: throw AppError.Schema(where)
    private fun arr(e: JsonElement?, where: String): JsonArray = e as? JsonArray ?: throw AppError.Schema(where)
    private fun instant(e: JsonElement?): Instant? = e.str()?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun voucherList(raw: JsonElement?): VoucherList {
        val o = obj(raw, "vouchers")
        val list = arr(o["vouchers"], "vouchers").mapNotNull { voucher(it) }
        return VoucherList(list, o["notice"].str())
    }

    private fun voucher(raw: JsonElement): Voucher? {
        val o = raw as? JsonObject ?: throw AppError.Schema("voucher")
        val kind = o["kind"].str()
        if (kind != "MENU_EXCHANGE" && kind != "AMOUNT") return null // 모르는 종류는 건너뜀
        val id = o["id"].str()
        val title = o["title"].str()
        if (id == null || title == null) throw AppError.Schema("voucher.fields")
        val status = runCatching { VoucherStatus.valueOf(o["status"].str().orEmpty()) }.getOrNull() ?: throw AppError.Schema("voucher.status")
        val expires = instant(o["expiresAt"]) ?: throw AppError.Schema("voucher.expiresAt")
        if (kind == "AMOUNT") {
            val amount = o["amount"].intValue()?.takeIf { it > 0 } ?: throw AppError.Schema("voucher.amount")
            return Voucher.Amount(
                id, title, status, expires, amount,
                minOrderAmount = o["minOrderAmount"].intValue()?.takeIf { it > 0 },
                cafeId = o["cafeId"].str(),
                cafeName = o["cafeName"].str(),
            )
        }
        val cafeId = o["cafeId"].str()
        val menuId = o["menuId"].str()
        if (cafeId == null || menuId == null) throw AppError.Schema("voucher.fields")
        return Voucher.MenuExchange(id, title, status, expires, cafeId, o["cafeName"].str().orEmpty(), menuId, o["menuName"].str().orEmpty())
    }

    fun banners(raw: JsonElement?): List<Banner> {
        val list = arr(obj(raw, "banners")["banners"], "banners")
        return list.mapNotNull { e ->
            val b = e as? JsonObject ?: return@mapNotNull null
            val id = b["id"].str() ?: return@mapNotNull null
            val image = b["imageUrl"].str() ?: return@mapNotNull null
            val link = (b["link"] as? JsonObject)?.let { l ->
                BannerRules.parseLink(l["type"].str(), if (l["type"].str() == "route") l["path"].str() else l["url"].str())
            }
            Banner(id, b["title"].str() ?: "이벤트", image, b["priority"].intValue() ?: 0, link)
        }
    }

    fun events(raw: JsonElement?): List<AppEvent> {
        val list = arr(obj(raw, "events")["events"], "events")
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o["id"].str() ?: return@mapNotNull null
            val title = o["title"].str() ?: return@mapNotNull null
            val starts = instant(o["startsAt"]) ?: return@mapNotNull null
            val ends = instant(o["endsAt"]) ?: return@mapNotNull null
            AppEvent(id, title, o["summary"].str(), o["body"].str(), o["imageUrl"].str(), starts, ends)
        }
    }

    fun myMenuSets(raw: JsonElement?): List<MyMenuSet> {
        val list = arr(obj(raw, "myMenu")["sets"], "myMenu.sets")
        return list.map { e ->
            val o = e as? JsonObject ?: throw AppError.Schema("myMenu.set")
            val cafeId = o["cafeId"].str() ?: throw AppError.Schema("myMenu.cafeId")
            val items = arr(o["items"], "myMenu.items").map { i ->
                val it = i as? JsonObject ?: throw AppError.Schema("myMenu.item")
                val menuId = it["menuId"].str()
                val qty = it["qty"].intValue()
                val opts = it["options"] as? JsonArray
                if (menuId == null || qty == null || qty < 1 || opts == null || opts.any { o -> o.str() == null }) throw AppError.Schema("myMenu.item")
                MyMenuItem(menuId, qty, opts.map { o -> o.str()!! })
            }
            MyMenuSet(cafeId, items)
        }
    }
}
