package com.ormo.customer.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ormo.data.local.PersistedSession
import com.ormo.data.local.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 자동 로그인 세션 저장소 (리프레시 토큰 + 학교 id). **Android Keystore 의 AES-256-GCM 키**로 암호화해 SharedPreferences 에 둔다.
 * 키는 기기 밖으로 나오지 않는다. 복호화에 실패하면(키 소실·앱 데이터 불일치) "저장된 세션 없음"으로 보고 지운다 — 다시 로그인하면 된다.
 * 비밀번호는 어디에도 저장하지 않는다. 백업 제외는 매니페스트의 `allowBackup=false`.
 */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun load(): PersistedSession? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_DATA, null) ?: return@withContext null
        try {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
            val text = String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
            val sep = text.indexOf('\n')
            if (sep < 0) return@withContext null
            val school = text.substring(0, sep).ifEmpty { null }
            PersistedSession(school, text.substring(sep + 1))
        } catch (e: Exception) {
            prefs.edit().remove(KEY_DATA).apply()
            null
        }
    }

    override suspend fun save(session: PersistedSession): Unit = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val text = (session.schoolId.orEmpty() + "\n" + session.refreshToken).toByteArray(Charsets.UTF_8)
        val out = cipher.iv + cipher.doFinal(text)
        prefs.edit().putString(KEY_DATA, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_DATA).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val PREFS = "ormo_session"
        const val KEY_DATA = "data"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ormo_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
