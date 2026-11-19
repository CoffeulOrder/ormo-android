package com.ormo.data.menu

import com.ormo.domain.model.Cafe
import com.ormo.domain.model.MenuItem
import com.ormo.domain.model.MenuPayload

/** 학교/버전/이름으로 구분되는 간단한 payload */
fun testPayload(version: Long, menuName: String = "아메리카노"): MenuPayload = MenuPayload(
    version,
    listOf(
        Cafe(
            "c1", "범석관 카페", "BS",
            listOf(MenuItem("m1", menuName, "", 4000, "커피")),
        ),
    ),
)

class FakeMenuApi : MenuApi {
    var version = 1L
    var menusFor: (schoolId: String) -> MenuPayload = { testPayload(version, "menu-of-$it") }
    var soldOut: List<String> = emptyList()

    var versionCalls = 0
    var menuCalls = 0
    var statusCalls = 0

    var failVersion = false
    var failMenus = false
    var failStatus = false

    /** 응답을 보내기 전에 실행 (게이트로 응답 시점을 제어) */
    var versionHook: suspend (schoolId: String) -> Unit = {}
    var menusHook: suspend (schoolId: String) -> Unit = {}

    override suspend fun fetchVersion(schoolId: String): Long {
        versionCalls++
        versionHook(schoolId)
        if (failVersion) throw com.ormo.data.remote.AppError.Network()
        return version
    }

    override suspend fun fetchMenus(schoolId: String): MenuPayload {
        menuCalls++
        menusHook(schoolId)
        if (failMenus) throw com.ormo.data.remote.AppError.Network()
        return menusFor(schoolId)
    }

    override suspend fun fetchSoldOutIds(cafeId: String): List<String> {
        statusCalls++
        if (failStatus) throw com.ormo.data.remote.AppError.Network()
        return soldOut
    }
}

class InMemoryMenuCache : MenuCache {
    val store = HashMap<String, MenuPayload>()
    var writes = 0
    var purges = mutableListOf<String>()
    var failWrites = false
    var failReads = false

    override suspend fun read(schoolId: String): MenuPayload? {
        if (failReads) throw java.io.IOException("read failed")
        return store[schoolId]
    }

    override suspend fun write(schoolId: String, payload: MenuPayload) {
        if (failWrites) throw java.io.IOException("write failed")
        writes++
        store[schoolId] = payload
    }

    override suspend fun purgeOthers(keepSchoolId: String) {
        purges += keepSchoolId
        store.keys.retainAll(setOf(keepSchoolId))
    }
}
