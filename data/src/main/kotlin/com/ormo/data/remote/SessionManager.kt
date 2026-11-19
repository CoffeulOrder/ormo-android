package com.ormo.data.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RefreshedTokens(val accessToken: String, val refreshToken: String? = null)

/**
 * 로그인 세션 토큰 (메모리). RN `api/session.ts` + `client.ts` 의 갱신 로직.
 *
 *  - 액세스 토큰: 항상 메모리에만 둔다.
 *  - 리프레시 토큰: 메모리에 두고, "자동 로그인" 일 때만 AuthRepository 가 암호화 저장소([SessionStore])에도 저장한다.
 *    갱신 때 서버가 새 리프레시 토큰을 주면(회전) [onRefreshTokenRotated] 로 알려 저장값도 교체하게 한다.
 *  - 갱신은 동시에 여러 요청이 401 을 받아도 **한 번만** 한다 (Mutex + "이미 다른 요청이 갱신했는가" 확인).
 *  - 갱신이 **서버에게 거절**되면 세션을 지우고 [expired] 를 내보낸다 (→ 로그아웃 + "로그인이 만료되었어요").
 *    **네트워크 오류·서버 장애(5xx/429/408)** 는 예외로 올리고 세션은 유지한다 (오프라인이라고 로그아웃되면 안 되므로).
 *    ※ RN 은 네트워크 오류가 아니면 전부 거절로 보고 세션을 지웠다. 서버 장애로 로그아웃되지 않도록 여기서 좁혔다.
 */
class SessionManager {
    @Volatile
    var accessToken: String? = null
        private set

    @Volatile
    var refreshToken: String? = null
        private set

    val hasRefreshToken: Boolean get() = refreshToken != null

    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 갱신까지 거절되어 다시 로그인해야 할 때 */
    val expired: SharedFlow<Unit> = _expired

    /** 서버가 새 리프레시 토큰을 줬을 때 (저장소 교체용) */
    @Volatile
    var onRefreshTokenRotated: ((String) -> Unit)? = null

    private val mutex = Mutex()

    /** `refreshToken` 을 생략(null)하면 기존 리프레시 토큰을 유지한다 */
    fun set(accessToken: String, refreshToken: String? = null) {
        this.accessToken = accessToken
        if (refreshToken != null) this.refreshToken = refreshToken
    }

    fun clear() {
        accessToken = null
        refreshToken = null
    }

    /**
     * 액세스 토큰 갱신. 새 액세스 토큰을 돌려주고, 갱신 불가(세션 종료됨)면 null.
     * @param staleAccessToken 401 을 받은 요청이 쓴 토큰. 그새 다른 요청이 이미 갱신했으면 그 토큰을 그대로 돌려준다.
     * @param call 실제 `/auth/refresh` 호출
     */
    suspend fun refresh(staleAccessToken: String?, call: suspend (refreshToken: String) -> RefreshedTokens): String? =
        mutex.withLock {
            val current = accessToken
            if (current != null && current != staleAccessToken) return@withLock current
            val rt = refreshToken ?: return@withLock null
            try {
                val tokens = call(rt)
                accessToken = tokens.accessToken
                tokens.refreshToken?.let {
                    refreshToken = it
                    onRefreshTokenRotated?.invoke(it)
                }
                tokens.accessToken
            } catch (e: AppError.Api) {
                if (!isRejection(e.status)) throw e
                clear()
                _expired.tryEmit(Unit)
                null
            }
        }

    private fun isRejection(status: Int) = status in 400..499 && status != 408 && status != 429
}
