package com.ormo.data.store

import com.ormo.core.Outcome
import com.ormo.data.remote.AppError
import com.ormo.domain.store.DayHours
import com.ormo.domain.store.SellerInfo
import com.ormo.domain.store.Store
import com.ormo.domain.store.StoreEntry
import com.ormo.domain.store.StoreInfo
import com.ormo.domain.store.StoreStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private class FakeDirectory(var stores: List<Store> = emptyList(), var error: Exception? = null) : StoreDirectory {
    override suspend fun fetchStores(): List<Store> { error?.let { throw it }; return stores }
    override suspend fun fetchInfo(storeId: String): StoreInfo {
        error?.let { throw it }
        return StoreInfo(storeId, "n", null, emptyList<DayHours>(), null, SellerInfo(null, null, null, null, null, null))
    }
}

class StoreRepositoryTest {
    private fun store(id: String, menus: Int = 3) = Store(id, "매장$id", StoreStatus.Open, menus)
    private fun blocking(block: suspend () -> Unit): Unit = runBlocking { block() }

    @Test fun `목록은 전체와 메뉴가 있는 매장으로 나뉜다`() = blocking {
        val repo = StoreRepository(FakeDirectory(listOf(store("a"), store("b", 0), store("c"))), InMemorySelectedStoreStore())
        val ok = assertIs<Outcome.Ok<StoreList>>(repo.loadStores())
        assertEquals(listOf("a", "b", "c"), ok.value.all.map { it.id })
        assertEquals(listOf("a", "c"), ok.value.listable.map { it.id })
    }

    @Test fun `네트워크 오류와 그 밖의 오류를 구분`() = blocking {
        val dir = FakeDirectory(error = AppError.Network())
        val repo = StoreRepository(dir, InMemorySelectedStoreStore())
        assertEquals(Outcome.Fail(StoreLoadFailure.NETWORK), repo.loadStores())
        dir.error = AppError.Api(500)
        assertEquals(Outcome.Fail(StoreLoadFailure.OTHER), repo.loadStores())
        dir.error = AppError.Schema("stores")
        assertEquals(Outcome.Fail(StoreLoadFailure.OTHER), repo.loadInfo("a"))
    }

    @Test fun `코루틴 취소는 삼키지 않는다`() = blocking {
        val repo = StoreRepository(FakeDirectory(error = CancellationException("x")), InMemorySelectedStoreStore())
        assertFailsWith<CancellationException> { repo.loadStores() }
    }

    @Test fun `고른 매장은 계정별로 기억한다`() = blocking {
        val repo = StoreRepository(FakeDirectory(), InMemorySelectedStoreStore())
        repo.select("u1", "a")
        repo.select("u2", "b")
        assertEquals("a", repo.lastStoreId("u1"))
        assertEquals("b", repo.lastStoreId("u2"))
        assertEquals(null, repo.lastStoreId("u3"))
    }

    @Test fun `기억한 매장이 있으면 바로 메뉴 홈`() = blocking {
        val repo = StoreRepository(FakeDirectory(listOf(store("a"), store("b"))), InMemorySelectedStoreStore())
        repo.select("u1", "b")
        assertEquals(Outcome.Ok(StoreEntry.MenuHome("b")), repo.resolveEntry("u1"))
    }

    @Test fun `기억한 매장이 없으면 매장 선택 - 가입 직후면 뒤로가기 숨김`() = blocking {
        val repo = StoreRepository(FakeDirectory(listOf(store("a"))), InMemorySelectedStoreStore())
        assertEquals(Outcome.Ok(StoreEntry.StoreSelect(false)), repo.resolveEntry("u1"))
        assertEquals(Outcome.Ok(StoreEntry.StoreSelect(true)), repo.resolveEntry("u1", firstEntryAfterSignup = true))
    }

    @Test fun `목록을 못 받아도 기억한 매장이 있으면 메뉴 캐시로 열 수 있게 메뉴 홈`() = blocking {
        val dir = FakeDirectory(listOf(store("a")))
        val repo = StoreRepository(dir, InMemorySelectedStoreStore())
        repo.select("u1", "a")
        dir.error = AppError.Network()
        assertEquals(Outcome.Ok(StoreEntry.MenuHome("a")), repo.resolveEntry("u1"))
    }

    @Test fun `목록도 못 받고 기억한 매장도 없으면 실패`() = blocking {
        val repo = StoreRepository(FakeDirectory(error = AppError.Network()), InMemorySelectedStoreStore())
        assertEquals(Outcome.Fail(StoreLoadFailure.NETWORK), repo.resolveEntry("u1"))
    }
}
