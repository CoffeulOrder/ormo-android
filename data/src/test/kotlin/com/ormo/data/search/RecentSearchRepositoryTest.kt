package com.ormo.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecentSearchRepositoryTest {
    private val dir: File = Files.createTempDirectory("recent-search").toFile()
    private fun blocking(block: suspend () -> Unit): Unit = runBlocking { block() }

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    private fun repos(): List<RecentSearchRepository> =
        listOf(RecentSearchRepository(InMemoryRecentSearchStore()), RecentSearchRepository(FileRecentSearchStore(File(dir, "r.json"))))

    @Test fun `추가 중복 삭제 전체삭제 - 메모리와 파일 구현이 같게 동작`() = blocking {
        for (repo in repos()) {
            repo.add("u1", "s1", "라떼")
            repo.add("u1", "s1", "아메리카노")
            assertEquals(listOf("라떼", "아메리카노"), repo.add("u1", "s1", "라떼")) // 다시 검색하면 맨 앞으로
            assertEquals(listOf("라떼", "아메리카노"), repo.load("u1", "s1"))
            assertEquals(listOf("라떼"), repo.remove("u1", "s1", "아메리카노"))
            assertEquals(emptyList(), repo.clear("u1", "s1"))
            assertEquals(emptyList(), repo.load("u1", "s1"))
        }
    }

    @Test fun `계정별 매장별로 나뉜다`() = blocking {
        for (repo in repos()) {
            repo.add("u1", "s1", "a")
            repo.add("u1", "s2", "b")
            repo.add("u2", "s1", "c")
            assertEquals(listOf("a"), repo.load("u1", "s1"))
            assertEquals(listOf("b"), repo.load("u1", "s2"))
            assertEquals(listOf("c"), repo.load("u2", "s1"))
        }
    }

    @Test fun `최대 10개`() = blocking {
        for (repo in repos()) {
            (1..13).forEach { repo.add("u1", "s1", "검색$it") }
            val list = repo.load("u1", "s1")
            assertEquals(10, list.size)
            assertEquals("검색13", list.first())
        }
    }

    @Test fun `회원 탈퇴 때 그 계정만 지운다`() = blocking {
        for (repo in repos()) {
            repo.add("u1", "s1", "a")
            repo.add("u2", "s1", "b")
            repo.deleteAccount("u1")
            assertEquals(emptyList(), repo.load("u1", "s1"))
            assertEquals(listOf("b"), repo.load("u2", "s1"))
        }
    }

    @Test fun `빠르게 연속으로 추가해도 항목이 사라지지 않는다`() = blocking {
        for (repo in repos()) {
            coroutineScope {
                (1..8).map { i -> async(Dispatchers.Default) { repo.add("u1", "s1", "q$i") } }.awaitAll()
            }
            assertEquals(8, repo.load("u1", "s1").toSet().size)
        }
    }

    @Test fun `앱을 다시 켜도 파일에서 읽는다`() = blocking {
        val file = File(dir, "r.json")
        RecentSearchRepository(FileRecentSearchStore(file)).add("u1", "s1", "한글 검색어")
        assertEquals(listOf("한글 검색어"), RecentSearchRepository(FileRecentSearchStore(file)).load("u1", "s1"))
    }

    @Test fun `깨진 파일이나 옛 스키마는 기록 없음으로 읽고 다시 쓸 수 있다`() = blocking {
        val file = File(dir, "r.json")
        file.writeText("{not json", Charsets.UTF_8)
        val repo = RecentSearchRepository(FileRecentSearchStore(file))
        assertEquals(emptyList(), repo.load("u1", "s1"))
        file.writeText("""{"schema":99,"accounts":{"u1":{"s1":["x"]}}}""")
        assertEquals(emptyList(), repo.load("u1", "s1"))
        assertEquals(listOf("새"), repo.add("u1", "s1", "새"))
        assertEquals(listOf("새"), RecentSearchRepository(FileRecentSearchStore(file)).load("u1", "s1"))
    }

    @Test fun `파일의 이상한 항목은 건너뛴다`() = blocking {
        val file = File(dir, "r.json")
        file.writeText("""{"schema":1,"accounts":{"u1":{"s1":["ok",1,null,"two"],"s2":"x"},"u2":5}}""")
        val repo = RecentSearchRepository(FileRecentSearchStore(file))
        assertEquals(listOf("ok", "two"), repo.load("u1", "s1"))
        assertEquals(emptyList(), repo.load("u1", "s2"))
        assertEquals(emptyList(), repo.load("u2", "s1"))
    }

    @Test fun `변경이 없으면 파일을 쓰지 않는다`() = blocking {
        val file = File(dir, "r.json")
        val repo = RecentSearchRepository(FileRecentSearchStore(file))
        repo.remove("u1", "s1", "없음")
        repo.clear("u1", "s1")
        assertTrue(!file.exists())
    }
}
