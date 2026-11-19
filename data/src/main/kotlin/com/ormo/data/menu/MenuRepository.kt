package com.ormo.data.menu

import com.ormo.domain.model.MenuPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class SyncReason { START, FOREGROUND, SCREEN, ORDER_CONFLICT, RETRY }

/** LOADING: 캐시 없이 첫 로드 중 · ERROR: 캐시도 없고 로드 실패 · READY: 표시할 메뉴 있음 */
enum class MenuStatus { LOADING, READY, ERROR }

data class MenuState(val payload: MenuPayload? = null, val failed: Boolean = false) {
    val status: MenuStatus
        get() = when {
            payload != null -> MenuStatus.READY
            failed -> MenuStatus.ERROR
            else -> MenuStatus.LOADING
        }
}

/**
 * 메뉴 데이터 저장소: 로컬 캐시 + 서버 버전 기반 갱신. RN `use-menu.tsx` 의 동작을 옮겼다.
 *
 * 흐름
 *  1. 학교가 정해지면([setSchool]) 이전 학교 캐시 삭제 → 캐시 읽기 → 있으면 즉시 표시 → `sync(START)`
 *  2. `sync(reason, force)`
 *     - 진행 중인 sync 가 있으면 합친다. 단 `force` 는 그 sync 가 끝난 뒤 **한 번 더** 수행한다.
 *     - `force` 가 아니고, 마지막 체크가 같은 학교에서 30초 이내이고, 표시 중인 메뉴가 있으면 생략.
 *     - 표시 중인 메뉴가 없거나 `force` → `GET menus` → 검증 → 저장 → 반영
 *     - 그 외 → `GET menu-version` → 같으면 끝, 다르면 `GET menus` → 검증 → 저장 → 반영
 *     - 실패: 표시 중인 메뉴가 **없을 때만** error. 있으면 조용히 기존 유지.
 *  3. 학교가 바뀌는 동안 도착한 **낡은 응답은 버린다** (세대 번호 + 취소). 낡은 학교의 캐시는 쓰지 않는다.
 *  4. 품절은 캐시하지 않는다 — [refreshSoldOut] 으로 받아 메모리([soldOut])에만 둔다.
 *
 * 체크 시점(호출하는 쪽): 학교 확정=START, 백그라운드→포그라운드=FOREGROUND, 메뉴 화면 진입=SCREEN,
 * 주문 중 MENU_CHANGED=ORDER_CONFLICT(force), 재시도 버튼=RETRY(force).
 *
 * @param scope sync 작업이 도는 범위 (앱 범위). 호출한 쪽이 취소돼도 진행 중 sync 는 계속된다.
 * @param clock 현재 시각(ms). 테스트에서 주입.
 * @param onUpdated 새 메뉴가 반영된 뒤 호출 (이미지 prefetch 용: 이전 → 새 메뉴 비교). 첫 로드는 prev == null.
 */
class MenuRepository(
    private val api: MenuApi,
    private val cache: MenuCache,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minCheckIntervalMs: Long = MENU_CHECK_MIN_INTERVAL_MS,
    private val onUpdated: (prev: MenuPayload?, fresh: MenuPayload) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow(MenuState())
    val state: StateFlow<MenuState> = _state

    private val _soldOut = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** cafeId → 품절 메뉴 id (메모리 전용) */
    val soldOut: StateFlow<Map<String, Set<String>>> = _soldOut

    private val lock = Any()
    private var schoolId: String? = null
    private var generation = 0
    private var inflight: Deferred<MenuPayload?>? = null
    private var lastCheck: Pair<String, Long>? = null

    private fun isCurrent(gen: Int) = synchronized(lock) { generation == gen }

    /**
     * 학교를 정한다 (로그인 후 확정되거나 다른 학교로 바뀔 때). 같은 학교면 아무것도 안 한다.
     * 캐시를 읽어 표시하고 `sync(START)` 가 끝날 때까지 suspend 한다 (기다리고 싶지 않으면 launch).
     */
    suspend fun setSchool(id: String?) {
        val gen: Int
        synchronized(lock) {
            if (id == schoolId) return
            schoolId = id
            generation++
            gen = generation
            inflight?.cancel()
            inflight = null
            lastCheck = null
            _state.value = MenuState()
            _soldOut.value = emptyMap()
        }
        if (id == null) return

        try {
            cache.purgeOthers(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 이전 학교 캐시 삭제 실패는 무시 (다음 기회에 지워짐)
        }
        val cached = try {
            cache.read(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (cached != null && isCurrent(gen)) _state.value = MenuState(cached)
        if (isCurrent(gen)) sync(SyncReason.START)
    }

    /**
     * 버전 체크 + 필요 시 갱신. 갱신 후(또는 생략/실패 시 기존) 메뉴를 돌려준다. 학교가 없으면 null.
     */
    suspend fun sync(reason: SyncReason, force: Boolean = false): MenuPayload? {
        while (true) {
            var toAwait: Deferred<MenuPayload?>? = null
            var started = false
            synchronized(lock) {
                val sid = schoolId ?: return null
                val running = inflight
                if (running != null) {
                    toAwait = running
                } else {
                    val prev = _state.value.payload
                    val last = lastCheck
                    if (prev != null && !force && last != null && last.first == sid &&
                        clock() - last.second < minCheckIntervalMs
                    ) {
                        return prev
                    }
                    lastCheck = sid to clock()
                    val gen = generation
                    val job = scope.async { execute(sid, gen, prev, force) }
                    inflight = job
                    job.invokeOnCompletion {
                        synchronized(lock) { if (inflight === job) inflight = null }
                    }
                    toAwait = job
                    started = true
                }
            }
            val result = try {
                toAwait!!.await()
            } catch (e: CancellationException) {
                // 학교가 바뀌어 sync 가 취소된 경우 (호출한 쪽 자신이 취소된 것이면 그대로 전파)
                currentCoroutineContext().ensureActive()
                return _state.value.payload
            }
            // 다른 호출이 시작한 sync 에 합류했더라도 강제 갱신은 끝난 뒤 한 번 더
            if (force && !started) continue
            return result
        }
    }

    private suspend fun execute(sid: String, gen: Int, prev: MenuPayload?, force: Boolean): MenuPayload? {
        try {
            if (prev == null || force) {
                val fresh = api.fetchMenus(sid)
                return commit(sid, gen, fresh, prev)
            }
            val serverVersion = api.fetchVersion(sid)
            if (serverVersion == prev.version) return prev
            val fresh = api.fetchMenus(sid)
            return commit(sid, gen, fresh, prev)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isCurrent(gen) && _state.value.payload == null) {
                _state.update { it.copy(failed = true) }
            }
            // 표시 중인 메뉴가 있으면 조용히 기존 유지
            return _state.value.payload
        }
    }

    /** 검증된 새 메뉴를 저장하고 화면에 반영한다. 학교가 바뀌었으면 저장도 반영도 하지 않는다. */
    private suspend fun commit(sid: String, gen: Int, fresh: MenuPayload, prev: MenuPayload?): MenuPayload? {
        if (!isCurrent(gen)) return _state.value.payload
        try {
            cache.write(sid, fresh) // 파일 통째로 교체
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 저장 실패 — 이번 실행에서만 새 메뉴를 사용
        }
        if (!isCurrent(gen)) return _state.value.payload
        _state.value = MenuState(fresh)
        onUpdated(prev, fresh)
        return fresh
    }

    /** 재시도 버튼: 오류 표시를 지우고 강제로 다시 받는다 */
    suspend fun retry() {
        _state.update { it.copy(failed = false) }
        sync(SyncReason.RETRY, force = true)
    }

    /** 메뉴 화면 진입 시 품절 목록을 받아 메모리에만 둔다. 실패하면 직전 표시를 유지 (주문 시 서버가 다시 검증). */
    suspend fun refreshSoldOut(cafeId: String) {
        val gen = synchronized(lock) { generation }
        try {
            val ids = api.fetchSoldOutIds(cafeId)
            if (isCurrent(gen)) _soldOut.update { it + (cafeId to ids.toSet()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 무시
        }
    }

    /** 주문 중 409 MENU_SOLD_OUT 을 받으면 해당 메뉴를 로컬로 품절 표시 */
    fun markSoldOut(cafeId: String, menuIds: Collection<String>) {
        _soldOut.update { it + (cafeId to ((it[cafeId] ?: emptySet()) + menuIds)) }
    }

    companion object {
        const val MENU_CHECK_MIN_INTERVAL_MS = 30_000L
    }
}
