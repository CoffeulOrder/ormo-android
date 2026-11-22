package com.ormo.data.store

import com.ormo.core.Outcome
import com.ormo.data.remote.AppError
import com.ormo.domain.store.Store
import com.ormo.domain.store.StoreEntry
import com.ormo.domain.store.StoreInfo
import com.ormo.domain.store.StoreRules
import kotlinx.coroutines.CancellationException

/**
 * 매장 목록/정보를 서버에서 받는 곳.
 *
 * ⚠️ 서버 계약(경로·필드·영업 상태 표현)은 아직 없다 — 이 인터페이스 뒤에 숨겨 두고 Fake 로 화면을 먼저 만든다.
 * 확정되면 `RemoteStoreDirectory` 하나만 추가하면 된다 (실패는 [AppError] 로 던진다).
 */
interface StoreDirectory {
    /** 이 학교의 모든 매장 (메뉴 유무와 상관없이). 서버 순서 그대로. */
    suspend fun fetchStores(): List<Store>

    suspend fun fetchInfo(storeId: String): StoreInfo
}

/** 마지막에 고른 매장을 계정별로 기억한다 (1.1.1: "다음엔 바로 메뉴로"). 로그아웃해도 남긴다. 실제 구현은 :app 의 DataStore. */
interface SelectedStoreStore {
    suspend fun load(accountKey: String): String?
    suspend fun save(accountKey: String, storeId: String)
    suspend fun clear(accountKey: String)
}

class InMemorySelectedStoreStore : SelectedStoreStore {
    private val map = HashMap<String, String>()
    override suspend fun load(accountKey: String): String? = map[accountKey]
    override suspend fun save(accountKey: String, storeId: String) { map[accountKey] = storeId }
    override suspend fun clear(accountKey: String) { map.remove(accountKey) }
}

enum class StoreLoadFailure { NETWORK, OTHER }

/** 매장 선택 화면(1.1.1) 데이터: [all] 은 전체, [listable] 은 메뉴가 있어 목록에 보일 매장 */
data class StoreList(val all: List<Store>, val listable: List<Store>)

class StoreRepository(
    private val directory: StoreDirectory,
    private val selected: SelectedStoreStore,
) {
    suspend fun loadStores(): Outcome<StoreList, StoreLoadFailure> = try {
        val all = directory.fetchStores()
        Outcome.Ok(StoreList(all, StoreRules.listable(all)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Fail(e.toFailure())
    }

    suspend fun loadInfo(storeId: String): Outcome<StoreInfo, StoreLoadFailure> = try {
        Outcome.Ok(directory.fetchInfo(storeId))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Fail(e.toFailure())
    }

    suspend fun lastStoreId(accountKey: String): String? = selected.load(accountKey)

    /** 매장을 고르면 기억한다. 장바구니 경고(1.1.2)를 통과한 뒤에 호출할 것. */
    suspend fun select(accountKey: String, storeId: String) = selected.save(accountKey, storeId)

    /** 탈퇴 때 이 계정의 마지막 매장 기억을 지운다 */
    suspend fun forget(accountKey: String) = selected.clear(accountKey)

    /**
     * 로그인 직후/앱 시작 때 어디로 갈지. 기억한 매장이 있는데 목록을 못 받았다면(네트워크 등) 메뉴 캐시로 열 수 있게 그 매장으로 보낸다.
     * 기억한 매장이 없는데 목록도 못 받으면 실패(→ 1.1.1 불러오기 실패 화면).
     */
    suspend fun resolveEntry(accountKey: String, firstEntryAfterSignup: Boolean = false): Outcome<StoreEntry, StoreLoadFailure> {
        val last = selected.load(accountKey)
        return when (val r = loadStores()) {
            is Outcome.Ok -> Outcome.Ok(StoreRules.resolveEntry(last, r.value.all, firstEntryAfterSignup))
            is Outcome.Fail ->
                if (last != null && !firstEntryAfterSignup) Outcome.Ok(StoreEntry.MenuHome(last)) else Outcome.Fail(r.failure)
        }
    }

    private fun Exception.toFailure(): StoreLoadFailure =
        if (this is AppError.Network) StoreLoadFailure.NETWORK else StoreLoadFailure.OTHER
}
