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
 * ⚠️ 실제 BE 에는 이 API 가 **아직 없다** (학교·도메인은 DB 기준 데이터 `school`·`school_email_domain` 이고, 지원하지 않는
 * 도메인은 인증코드 발송 때 EV001 로 거절된다). BE 가 `GET /schools` 를 열기 전까지는 [BundledSchoolDirectory] 를 쓴다.
 * 이 클래스는 제안한 형태(`GET /schools`)용이다. 깨진 항목은 건너뛰고, 쓸 수 있는 도메인이 하나도 없으면 실패.
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

/**
 * 앱에 넣어 둔 학교 메일 도메인 목록 (BE 에 목록 API 가 생기기 전까지의 임시 방편).
 * 1차 스코프는 을지대학교 성남캠퍼스 하나 (BE 마이그레이션 `V2__seed_eulji_school.sql`: 도메인 `g.eulji.ac.kr`).
 * 학교를 늘릴 때는 BE 의 `school_email_domain` 과 **같이** 늘려야 한다 — 어긋나면 서버(EV001)가 최종 판단한다.
 * TODO(BE): `GET /schools` 가 생기면 [RemoteSchoolDirectoryApi] 로 바꾸고 이 목록은 지운다.
 */
class BundledSchoolDirectory(
    private val items: List<SchoolDomain> = DEFAULT,
) : SchoolDirectoryApi {
    override suspend fun fetchEmailDomains(): EmailDomains = EmailDomains(items)

    companion object {
        val DEFAULT = listOf(SchoolDomain("g.eulji.ac.kr", "1", "을지대학교"))
    }
}
