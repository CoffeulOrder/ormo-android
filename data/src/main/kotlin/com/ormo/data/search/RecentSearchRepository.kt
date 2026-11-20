package com.ormo.data.search

import com.ormo.domain.search.RecentSearches
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 최근 검색어 저장소 (디자인 2.1.3: "계정별로 기기에 저장", 이 매장 기준 최대 10개).
 * 로그아웃해도 지우지 않는다 (계정별이라 섞이지 않음). 회원 탈퇴 때는 [deleteAccount].
 */
interface RecentSearchStore {
    suspend fun load(accountKey: String, storeId: String): List<String>
    suspend fun save(accountKey: String, storeId: String, terms: List<String>)
    suspend fun deleteAccount(accountKey: String)
}

class InMemoryRecentSearchStore : RecentSearchStore {
    private val map = HashMap<Pair<String, String>, List<String>>()
    override suspend fun load(accountKey: String, storeId: String) = map[accountKey to storeId].orEmpty()
    override suspend fun save(accountKey: String, storeId: String, terms: List<String>) {
        if (terms.isEmpty()) map.remove(accountKey to storeId) else map[accountKey to storeId] = terms
    }
    override suspend fun deleteAccount(accountKey: String) {
        map.keys.removeAll { it.first == accountKey }
    }
}

/** 읽고-고치고-쓰기를 한 번에 하나씩 하도록 Mutex 로 직렬화한다 (빠르게 연속 탭해도 항목이 사라지지 않게). */
class RecentSearchRepository(private val store: RecentSearchStore) {
    private val lock = Mutex()

    suspend fun load(accountKey: String, storeId: String): List<String> =
        lock.withLock { store.load(accountKey, storeId) }

    /** 검색 결과를 눌렀거나 검색 키를 눌렀을 때 호출 (글자 하나 칠 때마다 저장하지 않는다) */
    suspend fun add(accountKey: String, storeId: String, term: String): List<String> = update(accountKey, storeId) {
        RecentSearches.add(it, term)
    }

    suspend fun remove(accountKey: String, storeId: String, term: String): List<String> = update(accountKey, storeId) {
        RecentSearches.remove(it, term)
    }

    suspend fun clear(accountKey: String, storeId: String): List<String> = update(accountKey, storeId) {
        RecentSearches.clear()
    }

    suspend fun deleteAccount(accountKey: String) = lock.withLock { store.deleteAccount(accountKey) }

    private suspend fun update(accountKey: String, storeId: String, change: (List<String>) -> List<String>): List<String> =
        lock.withLock {
            val before = store.load(accountKey, storeId)
            val after = change(before)
            if (after != before) store.save(accountKey, storeId, after)
            after
        }
}
