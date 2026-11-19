package com.ormo.data.auth

import com.ormo.data.remote.ApiClient
import com.ormo.data.remote.AppError
import com.ormo.data.remote.id
import com.ormo.data.remote.str
import com.ormo.domain.auth.EmailDomains
import com.ormo.domain.auth.SchoolDomain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 학교 메일 도메인 목록 (D-23: 도메인 목록은 서버가 준다). 로그인 도메인 선택(0.1.4)과 가입 이메일(0.2.1)에 쓴다.
 *
 *   GET /schools -> [ { id, name, emailDomains: ["eulji.ac.kr", ...] }, ... ]    (또는 { schools: [...] })
 *
 * ⚠️ 경로/형태는 앱이 가정한 것 (BE `GET /schools` 제안 — 확정 전). 깨진 항목은 건너뛰고, 쓸 수 있는 도메인이 하나도 없으면 실패.
 */
interface SchoolDirectoryApi {
    suspend fun fetchEmailDomains(): EmailDomains
}

class RemoteSchoolDirectoryApi(private val client: ApiClient) : SchoolDirectoryApi {
    override suspend fun fetchEmailDomains(): EmailDomains = parse(client.get("/schools"))

    internal companion object {
        fun parse(raw: JsonElement?): EmailDomains {
            val list = (raw as? JsonArray) ?: ((raw as? JsonObject)?.get("schools") as? JsonArray)
                ?: throw AppError.Schema("schools")
            val items = list.flatMap { school ->
                val o = school as? JsonObject ?: return@flatMap emptyList()
                val id = o["id"].id() ?: return@flatMap emptyList()
                val name = o["name"].str() ?: return@flatMap emptyList()
                val domains = o["emailDomains"] as? JsonArray ?: return@flatMap emptyList()
                domains.mapNotNull { it.str() }.map { SchoolDomain(it.trim().lowercase(), id, name) }
            }.distinctBy { it.domain }
            if (items.isEmpty()) throw AppError.Schema("schools.emailDomains")
            return EmailDomains(items)
        }
    }
}
