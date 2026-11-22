package com.ormo.data.promo

import com.ormo.core.FeatureFlags
import com.ormo.core.Outcome
import com.ormo.data.remote.AppError
import com.ormo.domain.promo.AppEvent
import com.ormo.domain.promo.Banner
import com.ormo.domain.promo.BannerEvent
import com.ormo.domain.promo.BannerRules
import com.ormo.domain.promo.MyMenuItem
import com.ormo.domain.promo.MyMenuSet
import com.ormo.domain.promo.Voucher
import com.ormo.domain.promo.VoucherStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ON = FeatureFlags(coupons = true, events = true, myMenu = true, referral = true)
private val EXP = Instant.parse("2027-01-01T00:00:00Z")
private fun voucher(id: String) = Voucher.Amount(id, "금액권", VoucherStatus.AVAILABLE, EXP, 1000)

/** 호출 횟수를 세는 가짜 서버 */
private class FakePromoApi : VoucherApi, BannerApi, EventApi, MyMenuApi {
    var voucherCalls = 0; var bannerCalls = 0; var eventCalls = 0; var myMenuCalls = 0
    var vouchers: suspend () -> VoucherList = { VoucherList(emptyList()) }
    var banners: suspend () -> List<Banner> = { emptyList() }
    var events: List<AppEvent> = emptyList()
    var sets: List<MyMenuSet> = emptyList()
    var failSave: Exception? = null
    val reported = mutableListOf<Pair<String, BannerEvent>>()
    var failReport = false
    val saved = mutableMapOf<String, List<MyMenuItem>>()
    val deleted = mutableListOf<String>()

    override suspend fun fetchVouchers(): VoucherList { voucherCalls++; return vouchers() }
    override suspend fun fetchBanners(): List<Banner> { bannerCalls++; return banners() }
    override suspend fun reportEvent(bannerId: String, event: BannerEvent) { reported += bannerId to event; if (failReport) throw AppError.Network() }
    override suspend fun fetchEvents(): List<AppEvent> { eventCalls++; return events }
    override suspend fun fetch(): List<MyMenuSet> { myMenuCalls++; return sets }
    override suspend fun save(cafeId: String, items: List<MyMenuItem>) { failSave?.let { throw it }; saved[cafeId] = items }
    override suspend fun delete(cafeId: String) { failSave?.let { throw it }; deleted += cafeId }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureOffTest {
    @Test fun `베타 - 플래그가 꺼져 있으면 서버에 묻지 않는다`() = runTest {
        val api = FakePromoApi()
        val flags = FeatureFlags.BETA
        VoucherRepository(api, flags).refresh()
        MyMenuRepository(api, flags).refresh()
        assertIs<Outcome.Ok<List<AppEvent>>>(EventRepository(api, flags).load())
        BannerManager(api, InMemoryBannerHideStore(), backgroundScope, { true }, { "u" }, flags).prepare()
        runCurrent()
        assertEquals(0, api.voucherCalls + api.bannerCalls + api.eventCalls + api.myMenuCalls)
    }

    @Test fun `베타 - 내 메뉴 저장도 막힌다`() = runTest {
        val api = FakePromoApi()
        val r = MyMenuRepository(api, FeatureFlags.BETA).save("c1", listOf(MyMenuItem("m1", 1, emptyList())))
        assertIs<Outcome.Fail<*>>(r)
        assertTrue(api.saved.isEmpty())
    }

    @Test fun `서버가 없는 자리는 호출되면 실패한다`() = runTest {
        var failed = false
        try { UnavailablePromoApi.fetchVouchers() } catch (e: AppError.Api) { failed = true }
        assertTrue(failed)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoucherRepositoryTest {
    @Test fun `불러오면 목록과 안내가 채워진다 - 로딩 → 준비`() = runTest {
        val api = FakePromoApi().apply { vouchers = { VoucherList(listOf(voucher("v1")), "유의사항") } }
        val repo = VoucherRepository(api, ON)
        repo.refresh()
        assertEquals(LoadStatus.READY, repo.state.value.status)
        assertEquals(listOf("v1"), repo.state.value.vouchers.map { it.id })
        assertEquals("유의사항", repo.state.value.notice)
    }

    @Test fun `처음 실패하면 오류 - 한 번 받은 뒤 실패하면 이전 목록을 유지한다`() = runTest {
        val api = FakePromoApi().apply { vouchers = { throw AppError.Network() } }
        val repo = VoucherRepository(api, ON)
        repo.refresh()
        assertEquals(LoadStatus.ERROR, repo.state.value.status)

        api.vouchers = { VoucherList(listOf(voucher("v1"))) }
        repo.refresh()
        api.vouchers = { throw AppError.Network() }
        repo.refresh()
        assertEquals(LoadStatus.READY, repo.state.value.status)
        assertEquals(listOf("v1"), repo.state.value.vouchers.map { it.id })
    }

    @Test fun `로그아웃 중에 도착한 낡은 응답은 버린다`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakePromoApi().apply { vouchers = { gate.await(); VoucherList(listOf(voucher("other-account"))) } }
        val repo = VoucherRepository(api, ON)
        val job = launch { repo.refresh() }
        runCurrent()
        repo.reset() // 로그아웃
        gate.complete(Unit)
        job.join()
        assertTrue(repo.state.value.vouchers.isEmpty())
        assertEquals(LoadStatus.IDLE, repo.state.value.status)
    }

    @Test fun `초기화하면 비워진다`() = runTest {
        val api = FakePromoApi().apply { vouchers = { VoucherList(listOf(voucher("v1"))) } }
        val repo = VoucherRepository(api, ON)
        repo.refresh(); repo.reset()
        assertEquals(VoucherState(), repo.state.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MyMenuRepositoryTest {
    private val item = MyMenuItem("m1", 2, listOf("ice"))

    @Test fun `빈 세트는 걸러 매장별로 모은다`() = runTest {
        val api = FakePromoApi().apply { sets = listOf(MyMenuSet("c1", listOf(item)), MyMenuSet("c2", emptyList())) }
        val repo = MyMenuRepository(api, ON)
        repo.refresh()
        assertEquals(setOf("c1"), repo.state.value.sets.keys)
        assertEquals(LoadStatus.READY, repo.state.value.status)
    }

    @Test fun `저장은 서버가 성공해야 화면 값이 바뀐다`() = runTest {
        val api = FakePromoApi().apply { failSave = AppError.Network() }
        val repo = MyMenuRepository(api, ON)
        val r = repo.save("c1", listOf(item))
        assertEquals(PromoFailure.NETWORK, (r as Outcome.Fail).failure)
        assertTrue(repo.state.value.sets.isEmpty())

        api.failSave = null
        assertIs<Outcome.Ok<Unit>>(repo.save("c1", listOf(item)))
        assertEquals(listOf(item), repo.state.value.sets["c1"])
    }

    @Test fun `빈 목록으로 저장하면 삭제`() = runTest {
        val api = FakePromoApi()
        val repo = MyMenuRepository(api, ON)
        repo.save("c1", listOf(item))
        repo.save("c1", emptyList())
        assertEquals(listOf("c1"), api.deleted)
        assertNull(repo.state.value.sets["c1"])
    }

    @Test fun `저장 중에 시작된 조회의 낡은 결과가 방금 저장한 값을 덮어쓰지 않는다`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = object : MyMenuApi {
            override suspend fun fetch(): List<MyMenuSet> { gate.await(); return emptyList() } // 서버가 저장 전 상태를 늦게 돌려줌
            override suspend fun save(cafeId: String, items: List<MyMenuItem>) {}
            override suspend fun delete(cafeId: String) {}
        }
        val repo = MyMenuRepository(api, ON)
        val job = launch { repo.refresh() }
        runCurrent()
        repo.save("c1", listOf(item))
        gate.complete(Unit)
        job.join()
        assertEquals(listOf(item), repo.state.value.sets["c1"])
    }

    @Test fun `초기화하면 비워진다`() = runTest {
        val api = FakePromoApi()
        val repo = MyMenuRepository(api, ON)
        repo.save("c1", listOf(item)); repo.reset()
        assertTrue(repo.state.value.sets.isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BannerManagerTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val noon: Instant = Instant.parse("2026-11-19T03:00:00Z") // 12:00 KST
    private val clock: Clock = Clock.fixed(noon, ZoneOffset.UTC)
    private fun b(id: String, p: Int) = Banner(id, id, "https://i/$id.png", p)

    private fun TestScope.manager(
        api: FakePromoApi,
        store: BannerHideStore = InMemoryBannerHideStore(),
        ready: suspend (String) -> Boolean = { true },
        flags: FeatureFlags = ON,
    ) = BannerManager(api, store, backgroundScope, ready, { "u1" }, flags, clock, seoul)

    @Test fun `우선순위가 가장 높은 것이 후보`() = runTest {
        val api = FakePromoApi().apply { banners = { listOf(b("low", 1), b("high", 9), b("mid", 5)) } }
        val m = manager(api)
        m.prepare(); runCurrent()
        assertEquals("high", m.state.value.candidate?.id)
    }

    @Test fun `오늘 하루 보지 않기로 숨긴 것은 건너뛰고 다음 순위`() = runTest {
        val store = InMemoryBannerHideStore()
        store.map[BannerRules.hideKey("u1", "high")] = BannerRules.nextMidnight(noon, seoul).toEpochMilli().toString()
        val api = FakePromoApi().apply { banners = { listOf(b("low", 1), b("high", 9)) } }
        val m = manager(api, store)
        m.prepare(); runCurrent()
        assertEquals("low", m.state.value.candidate?.id)
    }

    @Test fun `자정이 지난 숨김은 풀려 다시 뜬다`() = runTest {
        val store = InMemoryBannerHideStore()
        store.map[BannerRules.hideKey("u1", "high")] = (noon.toEpochMilli() - 1000).toString() // 이미 지난 시각
        val api = FakePromoApi().apply { banners = { listOf(b("high", 9)) } }
        val m = manager(api, store)
        m.prepare(); runCurrent()
        assertEquals("high", m.state.value.candidate?.id)
    }

    @Test fun `이미지가 안 받아지면 다음 배너 - 전부 안 되면 팝업 없음`() = runTest {
        val api = FakePromoApi().apply { banners = { listOf(b("a", 9), b("b", 5)) } }
        val m = manager(api, ready = { it.endsWith("b.png") })
        m.prepare(); runCurrent()
        assertEquals("b", m.state.value.candidate?.id)

        val m2 = manager(api, ready = { false })
        m2.prepare(); runCurrent()
        assertNull(m2.state.value.candidate) // 빈 팝업 금지
    }

    @Test fun `이미지가 3초 안에 안 오면 못 받은 것으로 본다`() = runTest {
        val api = FakePromoApi().apply { banners = { listOf(b("slow", 9), b("fast", 1)) } }
        val m = manager(api, ready = { url -> if (url.contains("slow")) { delay(10_000); true } else true })
        m.prepare()
        advanceTimeBy(BannerManager.IMAGE_TIMEOUT_MS + 1); runCurrent()
        assertEquals("fast", m.state.value.candidate?.id)
        assertTrue(currentTime < 10_000)
    }

    @Test fun `이미지 확인에서 예외가 나도 안전하게 건너뛴다`() = runTest {
        val api = FakePromoApi().apply { banners = { listOf(b("boom", 9), b("ok", 1)) } }
        val m = manager(api, ready = { if (it.contains("boom")) error("decode") else true })
        m.prepare(); runCurrent()
        assertEquals("ok", m.state.value.candidate?.id)
    }

    @Test fun `서버 오류면 조용히 표시하지 않는다`() = runTest {
        val api = FakePromoApi().apply { banners = { throw AppError.Network() } }
        val m = manager(api)
        m.prepare(); runCurrent()
        assertNull(m.state.value.candidate)
    }

    @Test fun `세션당 한 번만 서버에 묻는다`() = runTest {
        val api = FakePromoApi().apply { banners = { listOf(b("a", 1)) } }
        val m = manager(api)
        m.prepare(); m.prepare(); m.prepare(); runCurrent()
        assertEquals(1, api.bannerCalls)
        m.reset()
        m.prepare(); runCurrent() // 로그아웃 뒤 다음 세션에서는 다시
        assertEquals(2, api.bannerCalls)
    }

    @Test fun `준비 중 로그아웃하면 결과를 버린다`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakePromoApi().apply { banners = { gate.await(); listOf(b("a", 1)) } }
        val m = manager(api)
        m.prepare(); runCurrent()
        m.reset() // 로그아웃
        gate.complete(Unit); advanceUntilIdle()
        assertNull(m.state.value.candidate)
    }

    @Test fun `확정하면 노출을 집계하고 후보는 비워진다`() = runTest {
        val api = FakePromoApi().apply { banners = { listOf(b("a", 1)) } }
        val m = manager(api)
        m.prepare(); runCurrent()
        assertEquals("a", m.show()?.id)
        runCurrent()
        assertEquals(listOf("a" to BannerEvent.IMPRESSION), api.reported)
        assertNull(m.state.value.candidate)
        assertEquals("a", m.state.value.active?.id)
        assertNull(m.show()) // 두 번 확정 안 됨
    }

    @Test fun `건너뛰면 후보를 버린다 - 집계 없음`() = runTest {
        val api = FakePromoApi().apply { banners = { listOf(b("a", 1)) } }
        val m = manager(api)
        m.prepare(); runCurrent()
        m.skip(); runCurrent()
        assertNull(m.state.value.candidate)
        assertTrue(api.reported.isEmpty())
    }

    @Test fun `오늘 하루 보지 않기 - 다음 자정까지 저장하고 집계`() = runTest {
        val store = InMemoryBannerHideStore()
        val api = FakePromoApi().apply { banners = { listOf(b("a", 1)) } }
        val m = manager(api, store)
        m.prepare(); runCurrent(); m.show()
        m.close(BannerEvent.HIDE_TODAY); runCurrent()
        assertEquals(BannerRules.nextMidnight(noon, seoul).toEpochMilli().toString(), store.map[BannerRules.hideKey("u1", "a")])
        assertTrue(api.reported.contains("a" to BannerEvent.HIDE_TODAY))
        assertNull(m.state.value.active)
    }

    @Test fun `그냥 닫으면 숨김을 저장하지 않는다`() = runTest {
        val store = InMemoryBannerHideStore()
        val api = FakePromoApi().apply { banners = { listOf(b("a", 1)) } }
        val m = manager(api, store)
        m.prepare(); runCurrent(); m.show()
        m.close(BannerEvent.CLOSE); runCurrent()
        assertTrue(store.map.isEmpty())
    }

    @Test fun `집계가 실패해도 흐름은 그대로 - 저장소 오류도 삼킨다`() = runTest {
        val api = FakePromoApi().apply { banners = { listOf(b("a", 1)) }; failReport = true }
        val badStore = object : BannerHideStore {
            override suspend fun get(key: String): String? = error("disk")
            override suspend fun set(key: String, value: String) = error("disk")
        }
        val m = manager(api, badStore)
        m.prepare(); runCurrent()
        assertNotNull(m.state.value.candidate) // 읽기 오류 → 숨기지 않음
        m.show(); m.close(BannerEvent.HIDE_TODAY); runCurrent()
        assertNull(m.state.value.active)
    }

    @Test fun `뒤로가기로 사라지면 활성 배너만 정리`() = runTest {
        val api = FakePromoApi().apply { banners = { listOf(b("a", 1)) } }
        val m = manager(api)
        m.prepare(); runCurrent(); m.show()
        m.clearActive()
        assertNull(m.state.value.active)
    }

    @Test fun `계정마다 숨김 기록이 섞이지 않는다`() = runTest {
        val store = InMemoryBannerHideStore()
        store.map[BannerRules.hideKey("other-user", "a")] = BannerRules.nextMidnight(noon, seoul).toEpochMilli().toString()
        val api = FakePromoApi().apply { banners = { listOf(b("a", 1)) } }
        val m = manager(api, store)
        m.prepare(); runCurrent()
        assertEquals("a", m.state.value.candidate?.id)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EventRepositoryTest {
    @Test fun `켜져 있으면 목록 - 실패는 실패로`() = runTest {
        val api = FakePromoApi().apply { events = listOf(AppEvent("e1", "가을", startsAt = Instant.EPOCH, endsAt = Instant.EPOCH)) }
        val ok = EventRepository(api, ON).load()
        assertEquals(listOf("e1"), (ok as Outcome.Ok).value.map { it.id })

        val failing = object : EventApi { override suspend fun fetchEvents(): List<AppEvent> = throw AppError.Network() }
        assertEquals(PromoFailure.NETWORK, (EventRepository(failing, ON).load() as Outcome.Fail).failure)
    }
}
