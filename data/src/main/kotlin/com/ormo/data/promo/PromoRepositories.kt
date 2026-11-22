package com.ormo.data.promo

import com.ormo.core.BetaFeature
import com.ormo.core.FeatureFlags
import com.ormo.core.Outcome
import com.ormo.core.Resettable
import com.ormo.data.remote.AppError
import com.ormo.domain.promo.AppEvent
import com.ormo.domain.promo.Banner
import com.ormo.domain.promo.BannerEvent
import com.ormo.domain.promo.BannerRules
import com.ormo.domain.promo.MyMenuItem
import com.ormo.domain.promo.Voucher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Clock
import java.time.ZoneId

/*
 * 베타 제외 기능의 저장소. 공통 규칙 (이식 계획 §2): 플래그가 꺼져 있으면 **서버에 묻지 않고** 빈 상태를 유지한다.
 * 개인 데이터(쿠폰·내 메뉴·배너 세션)는 메모리에만 두고 로그아웃 때 [Resettable.reset] 으로 비운다 (SessionResetter 에 등록).
 */

enum class LoadStatus { IDLE, LOADING, READY, ERROR }

enum class PromoFailure { NETWORK, OTHER }

private fun Exception.toPromoFailure() = if (this is AppError.Network) PromoFailure.NETWORK else PromoFailure.OTHER

// ───────────── 쿠폰 ─────────────

data class VoucherState(
    val vouchers: List<Voucher> = emptyList(),
    val notice: String? = null,
    /** ERROR 는 '받아 둔 데이터가 없는데 실패' 일 때만 (있으면 이전 목록을 계속 보여준다) */
    val status: LoadStatus = LoadStatus.IDLE,
)

/** 내 쿠폰 (개인 데이터): 디스크에 캐시하지 않고 쿠폰·결제 화면에 들어올 때마다 다시 조회 */
class VoucherRepository(private val api: VoucherApi, private val flags: FeatureFlags = FeatureFlags.BETA) : Resettable {
    private val _state = MutableStateFlow(VoucherState())
    val state: StateFlow<VoucherState> = _state

    private val lock = Any()
    private var seq = 0
    private var loaded = false

    suspend fun refresh() {
        if (!flags.allows(BetaFeature.COUPONS)) return // 베타에서는 쿠폰을 쓰지 않는다 — 서버에 묻지 않고 빈 목록 유지
        val mine = synchronized(lock) {
            seq += 1
            if (!loaded) _state.value = _state.value.copy(status = LoadStatus.LOADING)
            seq
        }
        try {
            val res = api.fetchVouchers()
            synchronized(lock) {
                if (mine != seq) return // reset() 이후 도착한 낡은 응답은 버림
                loaded = true
                _state.value = VoucherState(res.vouchers, res.notice, LoadStatus.READY)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) {
                if (mine != seq) return
                if (!loaded) _state.value = _state.value.copy(status = LoadStatus.ERROR) // 있으면 이전 목록 유지
            }
        }
    }

    override fun reset() {
        synchronized(lock) {
            seq += 1
            loaded = false
            _state.value = VoucherState()
        }
    }
}

// ───────────── 내 메뉴 ─────────────

data class MyMenuState(
    /** cafeId → 저장된 세트 (저장된 게 없는 매장은 키가 없음) */
    val sets: Map<String, List<MyMenuItem>> = emptyMap(),
    val status: LoadStatus = LoadStatus.IDLE,
)

/** 내 메뉴 (개인 데이터): 서버가 원본이고 앱은 메모리에만. 저장·삭제는 낙관적 갱신 없이 서버 응답을 받은 뒤에 반영 */
class MyMenuRepository(private val api: MyMenuApi, private val flags: FeatureFlags = FeatureFlags.BETA) : Resettable {
    private val _state = MutableStateFlow(MyMenuState())
    val state: StateFlow<MyMenuState> = _state

    private val lock = Any()
    private var seq = 0

    suspend fun refresh() {
        if (!flags.allows(BetaFeature.MY_MENU)) return
        val mine = synchronized(lock) {
            seq += 1
            if (_state.value.status != LoadStatus.READY) _state.value = _state.value.copy(status = LoadStatus.LOADING)
            seq
        }
        try {
            val list = api.fetch()
            synchronized(lock) {
                if (mine != seq) return // reset()/save() 이후 도착한 낡은 응답은 버림
                _state.value = MyMenuState(list.filter { it.items.isNotEmpty() }.associate { it.cafeId to it.items }, LoadStatus.READY)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) {
                if (mine != seq) return
                if (_state.value.status != LoadStatus.READY) _state.value = _state.value.copy(status = LoadStatus.ERROR)
            }
        }
    }

    /** 그 매장의 세트를 통째로 교체. 빈 목록이면 삭제. 서버가 성공해야 화면 값이 바뀐다. */
    suspend fun save(cafeId: String, items: List<MyMenuItem>): Outcome<Unit, PromoFailure> {
        if (!flags.allows(BetaFeature.MY_MENU)) return Outcome.Fail(PromoFailure.OTHER)
        try {
            if (items.isEmpty()) api.delete(cafeId) else api.save(cafeId, items)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Fail(e.toPromoFailure())
        }
        synchronized(lock) {
            seq += 1 // 진행 중이던 낡은 조회 결과가 방금 저장한 값을 덮어쓰지 않게
            val next = _state.value.sets.toMutableMap()
            if (items.isEmpty()) next.remove(cafeId) else next[cafeId] = items
            _state.value = _state.value.copy(sets = next)
        }
        return Outcome.Ok(Unit)
    }

    override fun reset() {
        synchronized(lock) {
            seq += 1
            _state.value = MyMenuState()
        }
    }
}

// ───────────── 이벤트 목록 ─────────────

/** 진행 중인 이벤트 (햄버거 메뉴 → 이벤트). 진행 여부는 서버가 걸러서 준다 */
class EventRepository(private val api: EventApi, private val flags: FeatureFlags = FeatureFlags.BETA) {
    suspend fun load(): Outcome<List<AppEvent>, PromoFailure> {
        if (!flags.allows(BetaFeature.EVENTS)) return Outcome.Ok(emptyList())
        return try {
            Outcome.Ok(api.fetchEvents())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Fail(e.toPromoFailure())
        }
    }
}

// ───────────── 배너(이벤트 팝업) ─────────────

/** '오늘 하루 보지 않기' 저장소 (실제는 :app 의 DataStore). 키는 [BannerRules.hideKey]. 오류는 호출하는 쪽이 삼킨다. */
interface BannerHideStore {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)
}

class InMemoryBannerHideStore : BannerHideStore {
    val map = HashMap<String, String>()
    override suspend fun get(key: String): String? = map[key]
    override suspend fun set(key: String, value: String) { map[key] = value }
}

/**
 * 이벤트 팝업 상태 (이식 계획 §4.9).
 * - 로그인 후 **세션당 1회만** [prepare]: 서버 목록을 우선순위 내림차순으로 보며 "오늘 하루 보지 않기"가 아니고 **이미지가 로드된** 첫 번째만 후보.
 *   이미지가 늦거나(3초) 실패하면 팝업을 아예 띄우지 않는다 (빈 팝업 금지). 준비 중 로그아웃하면 결과를 버린다 (세션 번호).
 * - 노출·열기·닫기·숨김 이벤트 전송은 실패해도 무시한다. 숨김 저장소 오류면 숨기지 않는다.
 * @param imageReady 이미지를 미리 받아 두었는가 (실제는 Coil prefetch). 예외는 false 로 본다.
 */
class BannerManager(
    private val api: BannerApi,
    private val hideStore: BannerHideStore,
    private val scope: CoroutineScope,
    private val imageReady: suspend (String) -> Boolean,
    private val userKey: () -> String,
    private val flags: FeatureFlags = FeatureFlags.BETA,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) : Resettable {
    data class State(
        /** 표시 준비가 끝난 후보 (이미지 로드됨, 오늘 숨김 아님). 소비([show]/[skip])하면 비워진다. */
        val candidate: Banner? = null,
        /** 지금 팝업으로 떠 있는 배너 */
        val active: Banner? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val lock = Any()
    private var session = 0
    private var started = false
    private var job: Job? = null

    /** 세션당 한 번만 동작 */
    fun prepare() {
        if (!flags.allows(BetaFeature.EVENTS)) return // 서버에 묻지 않는다
        val mySession: Int
        synchronized(lock) {
            if (started) return
            started = true
            mySession = session
        }
        job = scope.launch {
            try {
                for (b in BannerRules.byPriority(api.fetchBanners())) {
                    if (isHiddenToday(b.id)) continue
                    if (!ready(b.imageUrl)) continue
                    synchronized(lock) {
                        if (mySession != session) return@launch // 그 사이 로그아웃함
                        _state.value = _state.value.copy(candidate = b)
                    }
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 팝업 준비 실패 — 표시하지 않는다
            }
        }
    }

    private suspend fun isHiddenToday(bannerId: String): Boolean = try {
        BannerRules.isHidden(hideStore.get(BannerRules.hideKey(userKey(), bannerId)), clock.instant())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false // 저장소 오류 → 숨기지 않음 (팝업을 못 보는 것보다 한 번 더 보이는 쪽이 낫다)
    }

    private suspend fun ready(url: String): Boolean = try {
        withTimeout(IMAGE_TIMEOUT_MS) { imageReady(url) }
    } catch (e: TimeoutCancellationException) {
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** 후보를 팝업으로 확정한다 (노출 집계). 후보가 없으면 null */
    fun show(): Banner? {
        val b = synchronized(lock) {
            val c = _state.value.candidate ?: return null
            _state.value = State(candidate = null, active = c)
            c
        }
        report(b.id, BannerEvent.IMPRESSION)
        return b
    }

    /** 후보를 표시하지 않고 버린다 (이 세션에서는 다시 안 뜸) */
    fun skip() {
        synchronized(lock) { _state.value = _state.value.copy(candidate = null) }
    }

    /** 팝업을 닫는다. [BannerEvent.HIDE_TODAY] 면 오늘 자정까지 이 배너를 숨긴다. */
    suspend fun close(how: BannerEvent) {
        require(how != BannerEvent.IMPRESSION)
        val b = synchronized(lock) {
            val a = _state.value.active
            _state.value = _state.value.copy(active = null)
            a
        } ?: return
        report(b.id, how)
        if (how == BannerEvent.HIDE_TODAY) {
            try {
                hideStore.set(BannerRules.hideKey(userKey(), b.id), BannerRules.nextMidnight(clock.instant(), zone).toEpochMilli().toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 저장 실패 — 다음에 한 번 더 보일 뿐
            }
        }
    }

    /** 화면이 (뒤로가기 등으로) 사라질 때 정리 */
    fun clearActive() {
        synchronized(lock) { _state.value = _state.value.copy(active = null) }
    }

    /** 노출/열기/닫기/숨김 집계: 기다리지도, 실패를 알리지도 않는다 */
    private fun report(bannerId: String, event: BannerEvent) {
        scope.launch {
            try { api.reportEvent(bannerId, event) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* 무시 */ }
        }
    }

    /** 로그아웃: 세션 초기화 → 다음 로그인 후 첫 진입 때 다시 시도 */
    override fun reset() {
        synchronized(lock) {
            session += 1
            started = false
            _state.value = State()
        }
        job?.cancel()
        job = null
    }

    companion object {
        const val IMAGE_TIMEOUT_MS = 3_000L
    }
}
