package com.ormo.data.promo

import com.ormo.data.remote.ApiClient
import com.ormo.data.remote.AppError
import com.ormo.domain.promo.AppEvent
import com.ormo.domain.promo.Banner
import com.ormo.domain.promo.BannerEvent
import com.ormo.domain.promo.MyMenuItem
import com.ormo.domain.promo.MyMenuSet
import com.ormo.domain.promo.Voucher
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * 베타 제외 기능(쿠폰·이벤트·배너·내 메뉴)의 서버 API. ⚠️ **경로·형태는 협의된 스펙이 없어 RN 앱이 가정한 것** — BE 와 맞춰야 한다.
 * 플래그가 꺼져 있으면 저장소가 이 API 를 부르지 않는다.
 */

data class VoucherList(val vouchers: List<Voucher>, val notice: String? = null)

/** GET /vouchers -> { vouchers: [...], notice? } (개인 데이터: 캐시하지 않고 화면에 들어올 때마다 조회) */
interface VoucherApi {
    suspend fun fetchVouchers(): VoucherList
}

/**
 * GET /banners -> { banners: [{ id, title, imageUrl, priority, link? }] }
 * POST /banners/{id}/events { event } (성과 측정용, 실패해도 무시)
 */
interface BannerApi {
    suspend fun fetchBanners(): List<Banner>
    suspend fun reportEvent(bannerId: String, event: BannerEvent)
}

/** GET /events -> { events: [{ id, title, summary?, body?, imageUrl?, startsAt, endsAt }] } */
interface EventApi {
    suspend fun fetchEvents(): List<AppEvent>
}

/**
 * GET /my-menus -> { sets: [{ cafeId, items: [{ menuId, qty, options }] }] }
 * PUT /my-menus/{cafeId} { items } (통째로 교체, 멱등) / DELETE /my-menus/{cafeId} (없어도 성공)
 */
interface MyMenuApi {
    suspend fun fetch(): List<MyMenuSet>
    suspend fun save(cafeId: String, items: List<MyMenuItem>)
    suspend fun delete(cafeId: String)
}

class RemoteVoucherApi(private val client: ApiClient) : VoucherApi {
    override suspend fun fetchVouchers(): VoucherList = PromoParsers.voucherList(client.get("/vouchers"))
}

class RemoteBannerApi(private val client: ApiClient) : BannerApi {
    override suspend fun fetchBanners(): List<Banner> = PromoParsers.banners(client.get("/banners"))
    override suspend fun reportEvent(bannerId: String, event: BannerEvent) {
        client.post("/banners/${java.net.URLEncoder.encode(bannerId, "UTF-8")}/events", buildJsonObject { put("event", event.wire) })
    }
}

class RemoteEventApi(private val client: ApiClient) : EventApi {
    override suspend fun fetchEvents(): List<AppEvent> = PromoParsers.events(client.get("/events"))
}

class RemoteMyMenuApi(private val client: ApiClient) : MyMenuApi {
    override suspend fun fetch(): List<MyMenuSet> = PromoParsers.myMenuSets(client.get("/my-menus"))

    override suspend fun save(cafeId: String, items: List<MyMenuItem>) {
        client.put(
            "/my-menus/${java.net.URLEncoder.encode(cafeId, "UTF-8")}",
            buildJsonObject {
                put(
                    "items",
                    buildJsonArray {
                        items.forEach {
                            add(
                                buildJsonObject {
                                    put("menuId", it.menuId)
                                    put("qty", it.qty)
                                    put("options", buildJsonArray { it.options.forEach { o -> add(kotlinx.serialization.json.JsonPrimitive(o)) } })
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    override suspend fun delete(cafeId: String) {
        client.delete("/my-menus/${java.net.URLEncoder.encode(cafeId, "UTF-8")}")
    }
}

/** 베타에서 서버가 아직 없는 기능용 자리: 호출되면 실패한다 (플래그가 꺼져 있어 호출되지 않아야 한다) */
object UnavailablePromoApi : VoucherApi, BannerApi, EventApi, MyMenuApi {
    private fun nope(): Nothing = throw AppError.Api(404, "FEATURE_OFF")
    override suspend fun fetchVouchers(): VoucherList = nope()
    override suspend fun fetchBanners(): List<Banner> = nope()
    override suspend fun reportEvent(bannerId: String, event: BannerEvent) = nope()
    override suspend fun fetchEvents(): List<AppEvent> = nope()
    override suspend fun fetch(): List<MyMenuSet> = nope()
    override suspend fun save(cafeId: String, items: List<MyMenuItem>) = nope()
    override suspend fun delete(cafeId: String) = nope()
}
