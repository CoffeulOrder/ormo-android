package com.ormo.data.menu

import com.ormo.domain.model.Cafe
import com.ormo.domain.model.MenuItem
import com.ormo.domain.model.MenuPayload
import com.ormo.domain.model.OptionChoice
import com.ormo.domain.model.OptionGroup
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileMenuCacheTest {
    private lateinit var dir: File

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("ormo-menu-cache").toFile()
    }

    @AfterTest fun tearDown() {
        dir.deleteRecursively()
    }

    private fun payload(version: Long = 1, menuName: String = "아메리카노") = MenuPayload(
        version,
        listOf(
            Cafe(
                "c1", "범석관 카페", "BS",
                listOf(
                    MenuItem(
                        "m1", menuName, "desc", 4000, "커피", thumbnailUrl = "https://x/t.png",
                        optionGroups = listOf(
                            OptionGroup(
                                "temp", "온도", required = true, multi = false,
                                choices = listOf(OptionChoice("hot", "HOT", 0), OptionChoice("ice", "ICE", 0)),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun cacheFile() = dir.listFiles()!!.single { it.name.startsWith("menu-") && it.name.endsWith(".json") }

    @Test fun `쓴 것을 그대로 읽는다`() = runBlocking {
        val cache = FileMenuCache(dir)
        cache.write("s1", payload())
        assertEquals(payload(), cache.read("s1"))
    }

    @Test fun `없는 학교는 null`() = runBlocking {
        assertNull(FileMenuCache(dir).read("nope"))
    }

    @Test fun `다시 쓰면 통째로 교체되고 임시 파일이 남지 않는다`() = runBlocking {
        val cache = FileMenuCache(dir)
        cache.write("s1", payload(1, "옛 이름"))
        cache.write("s1", payload(2, "새 이름"))
        assertEquals(payload(2, "새 이름"), cache.read("s1"))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun `깨진 파일은 캐시 없음`() = runBlocking {
        val cache = FileMenuCache(dir)
        cache.write("s1", payload())
        cacheFile().writeText("{ not json", Charsets.UTF_8)
        assertNull(cache.read("s1"))
    }

    @Test fun `중간에 잘린 파일은 캐시 없음`() = runBlocking {
        val cache = FileMenuCache(dir)
        cache.write("s1", payload())
        val f = cacheFile()
        val text = f.readText(Charsets.UTF_8)
        f.writeText(text.substring(0, text.length / 2), Charsets.UTF_8)
        assertNull(cache.read("s1"))
    }

    @Test fun `내용이 검증을 통과하지 못하면 캐시 없음`() = runBlocking {
        val cache = FileMenuCache(dir)
        cache.write("s1", payload())
        val f = cacheFile()
        f.writeText(f.readText(Charsets.UTF_8).replace("\"price\":4000", "\"price\":-1"), Charsets.UTF_8)
        assertNull(cache.read("s1"))
    }

    @Test fun `옛 스키마 캐시는 서버 버전이 같아도 버린다`() = runBlocking {
        FileMenuCache(dir, schema = 1).write("s1", payload())
        assertNull(FileMenuCache(dir, schema = 2).read("s1"))
        assertNotNull(FileMenuCache(dir, schema = 1).read("s1")) // 같은 스키마로는 읽힌다
        Unit
    }

    @Test fun `다른 학교 캐시를 지운다`() = runBlocking {
        val cache = FileMenuCache(dir)
        cache.write("s1", payload(1))
        cache.write("s2", payload(2))
        cache.write("s3", payload(3))
        File(dir, "menu-stale.json.tmp").writeText("x")
        File(dir, "other.txt").writeText("keep me")

        cache.purgeOthers("s2")

        assertNull(cache.read("s1"))
        assertEquals(payload(2), cache.read("s2"))
        assertNull(cache.read("s3"))
        assertFalse(File(dir, "menu-stale.json.tmp").exists())
        assertTrue(File(dir, "other.txt").exists()) // 메뉴 캐시가 아닌 파일은 건드리지 않는다
    }

    @Test fun `학교 id 에 특수문자가 있어도 파일명이 안전하다`() = runBlocking {
        val cache = FileMenuCache(dir)
        cache.write("을지/대학교:1", payload())
        assertEquals(payload(), cache.read("을지/대학교:1"))
    }
}
