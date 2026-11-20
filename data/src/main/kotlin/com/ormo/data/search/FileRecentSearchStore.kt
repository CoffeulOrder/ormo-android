package com.ormo.data.search

import com.ormo.domain.search.RecentSearches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 파일 하나에 `{ schema, accounts: { <account>: { <storeId>: ["검색어", ...] } } }` 를 쓰고 임시 파일 → rename 으로 교체한다.
 * 깨진 파일은 "기록 없음"으로 읽는다 (최근 검색어는 잃어도 큰일이 아니므로 앱을 막지 않는다).
 * 호출은 [RecentSearchRepository] 의 Mutex 로 직렬화된다고 가정한다.
 * 저장 위치는 Android `filesDir`(백업 대상이어도 무방 — 민감 정보 아님) 또는 `noBackupFilesDir`.
 */
class FileRecentSearchStore(private val file: File) : RecentSearchStore {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(accountKey: String, storeId: String): List<String> = withContext(Dispatchers.IO) {
        val terms = readAccounts()[accountKey]?.get(storeId).orEmpty()
        terms.take(RecentSearches.MAX)
    }

    override suspend fun save(accountKey: String, storeId: String, terms: List<String>): Unit = withContext(Dispatchers.IO) {
        val all = readAccounts().toMutableMap()
        val stores = (all[accountKey] ?: emptyMap()).toMutableMap()
        if (terms.isEmpty()) stores.remove(storeId) else stores[storeId] = terms
        if (stores.isEmpty()) all.remove(accountKey) else all[accountKey] = stores
        write(all)
    }

    override suspend fun deleteAccount(accountKey: String): Unit = withContext(Dispatchers.IO) {
        val all = readAccounts().toMutableMap()
        if (all.remove(accountKey) != null) write(all)
    }

    private fun readAccounts(): Map<String, Map<String, List<String>>> = try {
        if (!file.isFile) {
            emptyMap()
        } else {
            val root = json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
            val schema = (root?.get("schema") as? JsonPrimitive)?.content?.toIntOrNull()
            val accounts = root?.get("accounts") as? JsonObject
            if (schema != SCHEMA || accounts == null) {
                emptyMap()
            } else {
                accounts.mapNotNull { (account, stores) ->
                    val o = stores as? JsonObject ?: return@mapNotNull null
                    account to o.mapNotNull { (storeId, list) ->
                        val arr = list as? JsonArray ?: return@mapNotNull null
                        storeId to arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    }.toMap()
                }.toMap()
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun write(all: Map<String, Map<String, List<String>>>) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        val root = buildJsonObject {
            put("schema", SCHEMA)
            put(
                "accounts",
                buildJsonObject {
                    all.forEach { (account, stores) ->
                        put(
                            account,
                            buildJsonObject {
                                stores.forEach { (storeId, terms) -> put(storeId, JsonArray(terms.map { JsonPrimitive(it) })) }
                            },
                        )
                    }
                },
            )
        }
        tmp.writeText(json.encodeToString(JsonObject.serializer(), root), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val SCHEMA = 1
    }
}
