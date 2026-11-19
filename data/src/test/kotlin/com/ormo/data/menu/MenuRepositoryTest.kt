package com.ormo.data.menu

import com.ormo.domain.model.MenuPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MenuRepositoryTest {

    private val api = FakeMenuApi()
    private val cache = InMemoryMenuCache()
    private var now = 0L
    private val updates = mutableListOf<Pair<MenuPayload?, MenuPayload>>()

    private fun TestScope.repo(minInterval: Long = 30_000) = MenuRepository(
        api = api,
        cache = cache,
        scope = this,
        clock = { now },
        minCheckIntervalMs = minInterval,
        onUpdated = { prev, fresh -> updates += prev to fresh },
    )

    // ---- 첫 로드 / 캐시 ----

    @Test fun `캐시가 없으면 menus 를 바로 받아 저장하고 표시한다`() = runTest {
        val r = repo()
        r.setSchool("s1")

        assertEquals(1, api.menuCalls)
        assertEquals(0, api.versionCalls)
        assertEquals(MenuStatus.READY, r.state.value.status)
        assertEquals(1L, r.state.value.payload?.version)
        assertEquals(r.state.value.payload, cache.store["s1"])
        assertEquals(listOf<Pair<MenuPayload?, MenuPayload>>(null to r.state.value.payload!!), updates) // 첫 로드는 prev == null
    }

    @Test fun `캐시가 있으면 먼저 표시하고 버전이 같으면 menus 를 받지 않는다`() = runTest {
        cache.store["s1"] = testPayload(1, "cached")
        api.version = 1
        var shownDuringCheck: MenuPayload? = null
        val r = repo()
        api.versionHook = { shownDuringCheck = r.state.value.payload }

        r.setSchool("s1")

        assertEquals("cached", shownDuringCheck?.cafes?.single()?.menus?.single()?.name) // 버전 확인 중에도 이미 표시됨
        assertEquals(0, api.menuCalls)
        assertEquals(1, api.versionCalls)
        assertEquals(0, cache.writes)
        assertTrue(updates.isEmpty())
    }

    @Test fun `버전이 다르면 menus 를 받아 캐시를 교체하고 이전 값과 함께 알린다`() = runTest {
        val old = testPayload(1, "old")
        cache.store["s1"] = old
        api.version = 2
        api.menusFor = { testPayload(2, "new") }
        val r = repo()

        r.setSchool("s1")

        assertEquals(1, api.menuCalls)
        assertEquals(2L, r.state.value.payload?.version)
        assertEquals(2L, cache.store["s1"]?.version)
        assertEquals(listOf<Pair<MenuPayload?, MenuPayload>>(old to testPayload(2, "new")), updates)
    }

    @Test fun `깨진 캐시를 읽다 실패하면 캐시 없음으로 처리한다`() = runTest {
        cache.failReads = true
        val r = repo()
        r.setSchool("s1")
        assertEquals(1, api.menuCalls)
        assertEquals(MenuStatus.READY, r.state.value.status)
    }

    @Test fun `캐시 저장이 실패해도 이번 실행에서는 새 메뉴를 쓴다`() = runTest {
        cache.failWrites = true
        val r = repo()
        r.setSchool("s1")
        assertEquals(MenuStatus.READY, r.state.value.status)
        assertEquals(0, cache.writes)
    }

    // ---- 30초 규칙 ----

    @Test fun `마지막 체크 후 30초 이내면 생략하고 30초가 지나면 버전을 확인한다`() = runTest {
        val r = repo()
        now = 0
        r.setSchool("s1") // menus 1회 (체크 시각 0)

        now = 10_000
        assertSame(r.state.value.payload, r.sync(SyncReason.SCREEN))
        assertEquals(0, api.versionCalls)

        now = 29_999
        r.sync(SyncReason.FOREGROUND)
        assertEquals(0, api.versionCalls)

        now = 30_000
        r.sync(SyncReason.FOREGROUND)
        assertEquals(1, api.versionCalls)
        assertEquals(1, api.menuCalls) // 버전이 같으니 menus 는 그대로
    }

    @Test fun `force 는 30초 규칙과 버전 비교를 건너뛴다`() = runTest {
        val r = repo()
        r.setSchool("s1")
        now = 1_000
        r.sync(SyncReason.ORDER_CONFLICT, force = true)
        assertEquals(2, api.menuCalls)
        assertEquals(0, api.versionCalls)
    }

    @Test fun `표시 중인 메뉴가 없으면 30초 안이어도 다시 받는다`() = runTest {
        api.failMenus = true
        val r = repo()
        r.setSchool("s1") // 실패 → 표시할 메뉴 없음
        assertEquals(MenuStatus.ERROR, r.state.value.status)

        api.failMenus = false
        now = 1_000
        r.sync(SyncReason.SCREEN)
        assertEquals(2, api.menuCalls)
        assertEquals(MenuStatus.READY, r.state.value.status)
    }

    // ---- 진행 중 합치기 / force 직렬화 ----

    @Test fun `진행 중인 sync 에는 합류하고 요청을 한 번만 보낸다`() = runTest {
        val gate = CompletableDeferred<Unit>()
        api.menusHook = { gate.await() }
        val r = repo()

        val first = async { r.setSchool("s1") }
        runCurrent()
        val second = async { r.sync(SyncReason.SCREEN) }
        val third = async { r.sync(SyncReason.FOREGROUND) }
        runCurrent()
        assertEquals(1, api.menuCalls)

        gate.complete(Unit)
        advanceUntilIdle()
        first.await()
        assertEquals(1, api.menuCalls)
        assertSame(second.await(), third.await())
        assertEquals(1L, second.await()?.version)
    }

    @Test fun `진행 중에 들어온 force 는 끝난 뒤 한 번 더 수행한다`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var call = 0
        api.menusHook = { if (++call == 1) gate.await() }
        api.menusFor = { testPayload(call.toLong(), "call-$call") }
        val r = repo()

        val first = async { r.setSchool("s1") }
        runCurrent()
        val forced = async { r.sync(SyncReason.ORDER_CONFLICT, force = true) }
        runCurrent()
        assertEquals(1, api.menuCalls) // 아직 첫 요청이 끝나지 않음

        gate.complete(Unit)
        advanceUntilIdle()
        first.await()
        assertEquals(2, api.menuCalls)
        assertEquals("call-2", forced.await()?.cafes?.single()?.menus?.single()?.name)
        assertEquals("call-2", r.state.value.payload?.cafes?.single()?.menus?.single()?.name)
    }

    // ---- 학교 변경 ----

    @Test fun `학교가 바뀌는 동안 도착한 낡은 응답은 버린다`() = runTest {
        val gate = CompletableDeferred<Unit>()
        // 취소도 무시하고 응답이 도착하는 최악의 경우 (세대 번호 검사로 막아야 함)
        api.menusHook = { school -> if (school == "s1") withContext(NonCancellable) { gate.await() } }
        val r = repo()

        val first = launch { r.setSchool("s1") }
        runCurrent()
        r.setSchool("s2")
        assertEquals("menu-of-s2", r.state.value.payload?.cafes?.single()?.menus?.single()?.name)

        gate.complete(Unit)
        advanceUntilIdle()
        first.join()

        assertEquals("menu-of-s2", r.state.value.payload?.cafes?.single()?.menus?.single()?.name)
        assertNull(cache.store["s1"]) // 낡은 학교의 캐시는 쓰지 않는다
        assertEquals(1, cache.writes)
    }

    @Test fun `학교를 바꾸면 이전 학교 캐시를 지우고 상태와 품절 표시를 초기화한다`() = runTest {
        val r = repo()
        r.setSchool("s1")
        r.markSoldOut("c1", listOf("m1"))
        assertTrue(r.soldOut.value.isNotEmpty())

        api.menusHook = { assertEquals(emptyMap(), r.soldOut.value) }
        r.setSchool("s2")

        assertEquals(listOf("s1", "s2"), cache.purges)
        assertEquals(setOf("s2"), cache.store.keys)
        assertEquals(emptyMap(), r.soldOut.value)
    }

    @Test fun `같은 학교를 다시 정해도 아무것도 하지 않는다`() = runTest {
        val r = repo()
        r.setSchool("s1")
        r.setSchool("s1")
        assertEquals(1, api.menuCalls)
        assertEquals(listOf("s1"), cache.purges)
    }

    @Test fun `학교가 없으면 sync 는 null`() = runTest {
        val r = repo()
        assertNull(r.sync(SyncReason.SCREEN))
        r.setSchool("s1")
        r.setSchool(null)
        assertNull(r.sync(SyncReason.SCREEN))
        assertNull(r.state.value.payload)
    }

    // ---- 실패 ----

    @Test fun `실패해도 표시 중인 메뉴는 조용히 유지한다`() = runTest {
        cache.store["s1"] = testPayload(1, "cached")
        api.version = 2
        api.failMenus = true
        val r = repo()

        r.setSchool("s1")

        assertEquals(MenuStatus.READY, r.state.value.status)
        assertEquals("cached", r.state.value.payload?.cafes?.single()?.menus?.single()?.name)
        assertEquals(false, r.state.value.failed)
    }

    @Test fun `버전 확인이 실패해도 기존 메뉴를 유지한다`() = runTest {
        cache.store["s1"] = testPayload(1, "cached")
        api.failVersion = true
        val r = repo()
        r.setSchool("s1")
        assertEquals(1L, r.state.value.payload?.version)
        assertEquals(false, r.state.value.failed)
    }

    @Test fun `캐시도 없고 실패하면 ERROR 이고 retry 로 복구된다`() = runTest {
        api.failMenus = true
        val r = repo()
        r.setSchool("s1")
        assertEquals(MenuStatus.ERROR, r.state.value.status)

        api.failMenus = false
        r.retry()
        assertEquals(MenuStatus.READY, r.state.value.status)
        assertEquals(false, r.state.value.failed)
    }

    // ---- 품절 (메모리 전용) ----

    @Test fun `품절 목록은 메모리에만 두고 캐시에 쓰지 않는다`() = runTest {
        api.soldOut = listOf("m1", "m2")
        val r = repo()
        r.setSchool("s1")
        val writesBefore = cache.writes

        r.refreshSoldOut("c1")

        assertEquals(mapOf("c1" to setOf("m1", "m2")), r.soldOut.value)
        assertEquals(writesBefore, cache.writes)
    }

    @Test fun `품절 조회가 실패하면 직전 표시를 유지한다`() = runTest {
        api.soldOut = listOf("m1")
        val r = repo()
        r.setSchool("s1")
        r.refreshSoldOut("c1")

        api.failStatus = true
        r.refreshSoldOut("c1")

        assertEquals(mapOf("c1" to setOf("m1")), r.soldOut.value)
    }

    @Test fun `주문 충돌로 받은 품절은 기존 표시에 합쳐진다`() = runTest {
        api.soldOut = listOf("m1")
        val r = repo()
        r.setSchool("s1")
        r.refreshSoldOut("c1")

        r.markSoldOut("c1", listOf("m2", "m1"))
        r.markSoldOut("c2", listOf("m9"))

        assertEquals(mapOf("c1" to setOf("m1", "m2"), "c2" to setOf("m9")), r.soldOut.value)
    }
}
