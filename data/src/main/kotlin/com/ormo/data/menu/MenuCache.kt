package com.ormo.data.menu

import com.ormo.domain.model.MenuPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 메뉴 캐시: 학교별 1개. 학교가 바뀌면 [purgeOthers] 로 나머지를 지운다. */
interface MenuCache {
    /** 읽을 수 없거나(없음·깨짐·옛 스키마) 검증을 통과하지 못하면 null — 호출한 쪽은 "캐시 없음"으로 처리한다 */
    suspend fun read(schoolId: String): MenuPayload?
    suspend fun write(schoolId: String, payload: MenuPayload)
    suspend fun purgeOthers(keepSchoolId: String)
}

/**
 * 파일 하나에 `{schema, payload}` 를 쓰고 **임시 파일 → rename** 으로 교체한다.
 * 그래서 RN 의 "JSON 과 localVersion 두 키를 원자적으로" 트릭이 필요 없다: 파일이 통째로 바뀌거나 그대로이거나.
 * 읽을 때는 같은 파서([MenuSchema])로 다시 검증한다.
 *
 * `schema` = **캐시 스키마 번호**(현재 3: 옵션 선택지 품절 추가).올리면 서버 버전이 같아도 옛 캐시를 버리고 다시 받는다.
 * 저장 위치는 백업 제외 영역(Android `noBackupFilesDir`)을 넘겨야 한다.
 */
class FileMenuCache(
    private val dir: File,
    private val schema: Int = CACHE_SCHEMA,
) : MenuCache {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fileFor(schoolId: String) =
        File(dir, PREFIX + URLEncoder.encode(schoolId, "UTF-8") + SUFFIX)

    override suspend fun read(schoolId: String): MenuPayload? = withContext(Dispatchers.IO) {
        val file = fileFor(schoolId)
        try {
            if (!file.isFile) return@withContext null
            val root = json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject ?: return@withContext null
            val storedSchema = (root["schema"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            if (storedSchema != schema) return@withContext null
            MenuSchema.parsePayload(root["payload"])
        } catch (e: Exception) {
            // 깨진 캐시(부분 쓰기, 형태 오류 등)는 "캐시 없음"
            null
        }
    }

    override suspend fun write(schoolId: String, payload: MenuPayload): Unit = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = fileFor(schoolId)
        val tmp = File(dir, target.name + TMP_SUFFIX)
        val text = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("schema", schema)
                put("payload", MenuSchema.encodePayload(payload))
            },
        )
        tmp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override suspend fun purgeOthers(keepSchoolId: String): Unit = withContext(Dispatchers.IO) {
        val keep = fileFor(keepSchoolId).name
        dir.listFiles()?.forEach { f ->
            val isMenuFile = f.name.startsWith(PREFIX) && (f.name.endsWith(SUFFIX) || f.name.endsWith(SUFFIX + TMP_SUFFIX))
            if (isMenuFile && f.name != keep) f.delete()
        }
    }

    companion object {
        const val CACHE_SCHEMA = 3
        private const val PREFIX = "menu-"
        private const val SUFFIX = ".json"
        private const val TMP_SUFFIX = ".tmp"
    }
}
