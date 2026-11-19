package com.ormo.data.local

/** "자동 로그인" 을 체크했을 때만 저장하는 값. 비밀번호는 어디에도 저장하지 않는다. */
data class PersistedSession(val schoolId: String?, val refreshToken: String)

/**
 * 자동 로그인 세션 저장소. 실제 구현(:app)은 Android Keystore(AES-GCM)로 암호화해 DataStore 에 저장하고
 * 백업에서 제외한다. 로그아웃/만료/갱신 거절 시 [clear].
 */
interface SessionStore {
    suspend fun load(): PersistedSession?
    suspend fun save(session: PersistedSession)
    suspend fun clear()
}

/** 테스트·미리보기용 */
class InMemorySessionStore(private var value: PersistedSession? = null) : SessionStore {
    override suspend fun load(): PersistedSession? = value
    override suspend fun save(session: PersistedSession) { value = session }
    override suspend fun clear() { value = null }
}
