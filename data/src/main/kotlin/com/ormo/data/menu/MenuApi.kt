package com.ormo.data.menu

import com.ormo.data.remote.ApiClient
import com.ormo.domain.model.MenuPayload
import java.net.URLEncoder

/**
 * 메뉴 API (경로·형태는 앱이 가정한 것 — BE 와 맞추기 전. FEATURE_SPEC §3):
 *   GET /schools/{schoolId}/menu-version -> { version }
 *   GET /schools/{schoolId}/menus        -> { version, cafes: [...] }
 *   GET /cafes/{cafeId}/menu-status      -> { soldOutMenuIds: [] }
 * 구현은 검증(parse)까지 통과한 데이터만 돌려준다 → 호출한 쪽은 믿고 저장해도 된다.
 */
interface MenuApi {
    suspend fun fetchVersion(schoolId: String): Long
    suspend fun fetchMenus(schoolId: String): MenuPayload
    suspend fun fetchSoldOutIds(cafeId: String): List<String>
}

class RemoteMenuApi(private val client: ApiClient) : MenuApi {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    override suspend fun fetchVersion(schoolId: String): Long =
        MenuSchema.parseVersion(client.get("/schools/${enc(schoolId)}/menu-version"))

    override suspend fun fetchMenus(schoolId: String): MenuPayload =
        MenuSchema.parsePayload(client.get("/schools/${enc(schoolId)}/menus"))

    override suspend fun fetchSoldOutIds(cafeId: String): List<String> =
        MenuSchema.parseSoldOutIds(client.get("/cafes/${enc(cafeId)}/menu-status"))
}
