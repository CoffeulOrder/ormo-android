package com.ormo.data.auth

import com.ormo.data.remote.ApiClient
import com.ormo.data.remote.AppError
import com.ormo.data.remote.SessionManager
import com.ormo.domain.auth.SendCodeResult
import com.ormo.domain.auth.SignUpRequest
import com.ormo.domain.auth.UserProfile
import com.ormo.domain.auth.VerifyPurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RemoteAuthApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RemoteAuthApi

    @BeforeTest fun setUp() {
        server = MockWebServer().also { it.start() }
        api = RemoteAuthApi(ApiClient(server.url("/api/v1").toString(), SessionManager()))
    }

    @AfterTest fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun blocking(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking { block() }

    private fun bodyOf(req: okhttp3.mockwebserver.RecordedRequest) = Json.parseToJsonElement(req.body.readUtf8()).jsonObject

    @Test fun `인증코드 발송 - 경로와 본문과 응답`() = blocking {
        server.enqueue(json("""{"expiresInSec":240,"resendAfterSec":45}"""))
        val r = api.sendEmailCode("kim@eulji.ac.kr", VerifyPurpose.SIGNUP)
        assertEquals(SendCodeResult(240, 45), r)
        val req = server.takeRequest()
        assertEquals("/api/v1/auth/email/send-code", req.path)
        val b = bodyOf(req)
        assertEquals("kim@eulji.ac.kr", b["email"].toString().trim('"'))
        assertEquals("SIGNUP", b["purpose"].toString().trim('"'))
    }

    @Test fun `인증코드 발송 - 응답에 값이 없으면 기본값`() = blocking {
        server.enqueue(json("{}"))
        assertEquals(SendCodeResult(300, 60), api.sendEmailCode("a@b.c", VerifyPurpose.PASSWORD_RESET))
        server.enqueue(json("""{"expiresInSec":0,"resendAfterSec":"x"}"""))
        assertEquals(SendCodeResult(300, 60), api.sendEmailCode("a@b.c", VerifyPurpose.SIGNUP))
    }

    @Test fun `인증코드 확인 - 토큰을 돌려주고 없으면 Schema 오류`() = blocking {
        server.enqueue(json("""{"verificationToken":"tok-1"}"""))
        assertEquals("tok-1", api.verifyEmailCode("a@b.c", VerifyPurpose.SIGNUP, "123456"))
        val req = server.takeRequest()
        assertEquals("/api/v1/auth/email/verify-code", req.path)
        assertEquals("123456", bodyOf(req)["code"].toString().trim('"'))

        server.enqueue(json("{}"))
        assertFailsWith<AppError.Schema> { api.verifyEmailCode("a@b.c", VerifyPurpose.SIGNUP, "123456") }
        Unit
    }

    @Test fun `가입 - 약관 동의를 담아 보내고 토큰이 있으면 바로 로그인 세션`() = blocking {
        server.enqueue(json("""{"accessToken":"a","refreshToken":"r","schoolId":"s1","name":"가을"}"""))
        val s = api.signUp(SignUpRequest("kim@eulji.ac.kr", "가을", "abc12345", "tok", termsAgreed = true, privacyAgreed = true))
        assertEquals(LoginSession("a", "r", "s1", "가을"), s)
        val req = server.takeRequest()
        assertEquals("/api/v1/auth/signup", req.path)
        val b = bodyOf(req)
        assertEquals("tok", b["verificationToken"].toString().trim('"'))
        assertEquals("""{"terms":true,"privacy":true}""", b["consents"].toString())
        assertNull(b["nickname"]) // 닉네임 없음
    }

    @Test fun `가입 - 응답에 토큰이 없으면 null`() = blocking {
        server.enqueue(json("{}"))
        assertNull(api.signUp(SignUpRequest("a@b.c", "n", "p", "t", true, true)))
    }

    @Test fun `로그인 - 학교 id 는 있을 때만 보낸다`() = blocking {
        server.enqueue(json("""{"accessToken":"a","refreshToken":"r"}"""))
        val s = api.login("kim@eulji.ac.kr", "pw", schoolId = null, autoLogin = true)
        assertEquals(LoginSession("a", "r", null, null), s)
        val first = bodyOf(server.takeRequest())
        assertNull(first["schoolId"])
        assertEquals("true", first["autoLogin"].toString())

        server.enqueue(json("""{"accessToken":"a","refreshToken":"r"}"""))
        api.login("kim@eulji.ac.kr", "pw", schoolId = "s1", autoLogin = false)
        assertEquals("s1", bodyOf(server.takeRequest())["schoolId"].toString().trim('"'))
    }

    @Test fun `로그인 - 토큰이 빠진 응답은 Schema 오류`() = blocking {
        server.enqueue(json("""{"accessToken":"a"}"""))
        assertFailsWith<AppError.Schema> { api.login("a@b.c", "pw", null, true) }
        Unit
    }

    @Test fun `갱신 - 회전된 토큰은 있을 때만`() = blocking {
        server.enqueue(json("""{"accessToken":"a2","refreshToken":"r2","name":"가을"}"""))
        assertEquals(RefreshResult("a2", "r2", "가을"), api.refresh("r1"))
        server.enqueue(json("""{"accessToken":"a3"}"""))
        assertEquals(RefreshResult("a3", null, null), api.refresh("r2"))
        server.enqueue(json("""{"nope":1}"""))
        assertFailsWith<AppError.Schema> { api.refresh("r3") }
        Unit
    }

    @Test fun `로그아웃 비밀번호 재설정 변경 경로와 본문`() = blocking {
        repeat(3) { server.enqueue(json("{}")) }
        api.logout("r1")
        api.resetPassword("a@b.c", "tok", "new12345")
        api.changePassword("old12345", "new12345")

        val logout = server.takeRequest()
        assertEquals("/api/v1/auth/logout", logout.path)
        assertEquals("r1", bodyOf(logout)["refreshToken"].toString().trim('"'))

        val reset = server.takeRequest()
        assertEquals("/api/v1/auth/password/reset", reset.path)
        val rb = bodyOf(reset)
        assertEquals("tok", rb["verificationToken"].toString().trim('"'))
        assertEquals("new12345", rb["newPassword"].toString().trim('"'))

        val change = server.takeRequest()
        assertEquals("/api/v1/me/password", change.path)
        val cb = bodyOf(change)
        assertEquals("old12345", cb["currentPassword"].toString().trim('"'))
        assertEquals("new12345", cb["newPassword"].toString().trim('"'))
    }

    @Test fun `내 정보`() = blocking {
        server.enqueue(json("""{"name":"가을","email":"kim@eulji.ac.kr"}"""))
        assertEquals(UserProfile("가을", "kim@eulji.ac.kr"), api.me())
        assertEquals("GET", server.takeRequest().method)

        server.enqueue(json("""{"email":"kim@eulji.ac.kr"}"""))
        assertFailsWith<AppError.Schema> { api.me() }
        Unit
    }

    @Test fun `학교 목록은 깨진 항목을 건너뛰고 도메인을 소문자로 모은다`() {
        val raw = Json.parseToJsonElement(
            """[
              {"id":"s1","name":"을지대학교","emailDomains":["Eulji.ac.kr","student.eulji.ac.kr"]},
              {"id":"s2","emailDomains":["x.ac.kr"]},
              {"id":"s3","name":"깨짐"},
              7,
              {"id":4,"name":"숫자 id","emailDomains":["num.ac.kr","eulji.ac.kr"]}
            ]""",
        )
        val d = RemoteSchoolDirectoryApi.parse(raw)
        assertEquals(listOf("eulji.ac.kr", "student.eulji.ac.kr", "num.ac.kr"), d.domains) // 중복 도메인은 첫 번째만
        assertEquals("4", d.schoolIdOf("num.ac.kr"))
        assertEquals("s1", d.schoolIdOf("eulji.ac.kr"))
    }

    @Test fun `학교 목록 - 객체로 감싸도 되고 쓸 수 있는 도메인이 없으면 실패`() {
        val wrapped = Json.parseToJsonElement("""{"schools":[{"id":"s1","name":"을지","emailDomains":["eulji.ac.kr"]}]}""")
        assertEquals(listOf("eulji.ac.kr"), RemoteSchoolDirectoryApi.parse(wrapped).domains)
        assertFailsWith<AppError.Schema> { RemoteSchoolDirectoryApi.parse(Json.parseToJsonElement("[]")) }
        assertFailsWith<AppError.Schema> { RemoteSchoolDirectoryApi.parse(Json.parseToJsonElement("""{"x":1}""")) }
        assertFailsWith<AppError.Schema> { RemoteSchoolDirectoryApi.parse(null) }
    }
}
