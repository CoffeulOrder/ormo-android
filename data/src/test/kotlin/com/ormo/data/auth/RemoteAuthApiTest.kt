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
        server.enqueue(json("""{"timestamp":"2026-11-23T10:00:00+09:00","status":200,"code":"SUCCESS","message":"인증코드를 보냈어요.","data":{"email":"kim@g.eulji.ac.kr","expiresInSeconds":240,"resendAvailableInSeconds":45}}"""))
        val r = api.sendEmailCode("kim@g.eulji.ac.kr", VerifyPurpose.SIGNUP)
        assertEquals(SendCodeResult(240, 45), r)
        val req = server.takeRequest()
        assertEquals("/api/v1/verifications/email", req.path)
        val b = bodyOf(req)
        assertEquals("kim@g.eulji.ac.kr", b["email"].toString().trim('"'))
        assertEquals("SIGNUP", b["purpose"].toString().trim('"'))
    }

    @Test fun `인증코드 발송 - 응답에 값이 없으면 기본값`() = blocking {
        server.enqueue(json("{}"))
        assertEquals(SendCodeResult(300, 60), api.sendEmailCode("a@b.c", VerifyPurpose.PASSWORD_RESET))
        server.enqueue(json("""{"expiresInSeconds":0,"resendAvailableInSeconds":"x"}"""))
        assertEquals(SendCodeResult(300, 60), api.sendEmailCode("a@b.c", VerifyPurpose.SIGNUP))
    }

    @Test fun `인증코드 확인 - 토큰을 돌려주고 없으면 Schema 오류`() = blocking {
        server.enqueue(json("""{"status":200,"code":"SUCCESS","message":"ok","data":{"verificationToken":"evt_1","expiresInSeconds":1800}}"""))
        assertEquals("evt_1", api.verifyEmailCode("a@b.c", VerifyPurpose.SIGNUP, "123456"))
        val req = server.takeRequest()
        assertEquals("/api/v1/verifications/email/confirm", req.path)
        assertEquals("123456", bodyOf(req)["code"].toString().trim('"'))

        server.enqueue(json("{}"))
        assertFailsWith<AppError.Schema> { api.verifyEmailCode("a@b.c", VerifyPurpose.SIGNUP, "123456") }
        Unit
    }

    @Test fun `가입 - 이메일은 보내지 않고 약관 둘을 목록으로 보낸다, 응답으로 바로 로그인 세션`() = blocking {
        server.enqueue(
            json(
                """{"status":201,"code":"SUCCESS","message":"가입을 환영해요.","data":{"memberId":7,"name":"가을","email":"kim@g.eulji.ac.kr",
                "school":{"schoolId":1,"name":"을지대학교","campus":"성남캠퍼스"},"accessToken":"a","refreshToken":"r","expiresIn":3600}}""",
                201,
            ),
        )
        val s = api.signUp(SignUpRequest("kim@g.eulji.ac.kr", "가을", "abc12345", "tok", termsAgreed = true, privacyAgreed = true))
        assertEquals(LoginSession("a", "r", "1", "가을"), s)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/members", req.path)
        val b = bodyOf(req)
        assertEquals("tok", b["verificationToken"].toString().trim('"'))
        assertNull(b["email"]) // 이메일은 토큰에 묶여 있다
        assertNull(b["nickname"]) // 닉네임 없음
        assertEquals(
            """[{"type":"SERVICE","version":"1.0","agreed":true},{"type":"PRIVACY","version":"1.0","agreed":true}]""",
            b["agreements"].toString(),
        )
    }

    @Test fun `가입 - 응답에 토큰이 없으면 null`() = blocking {
        server.enqueue(json("{}"))
        assertNull(api.signUp(SignUpRequest("a@b.c", "n", "p", "t", true, true)))
    }

    @Test fun `로그인 - 이메일과 비밀번호만 보내고 이름·학교는 member 에서 읽는다`() = blocking {
        server.enqueue(
            json("""{"status":200,"code":"SUCCESS","message":"로그인했어요.","data":{"accessToken":"a","refreshToken":"r","expiresIn":3600,"member":{"memberId":7,"name":"가을","schoolId":1}}}"""),
        )
        val s = api.login("kim@g.eulji.ac.kr", "pw", schoolId = "1", autoLogin = true)
        assertEquals(LoginSession("a", "r", "1", "가을"), s)
        val req = server.takeRequest()
        assertEquals("/api/v1/auth/login", req.path)
        assertEquals(setOf("email", "password"), bodyOf(req).keys)
    }

    @Test fun `로그인 - member 가 없어도 토큰만 있으면 된다`() = blocking {
        server.enqueue(json("""{"accessToken":"a","refreshToken":"r"}"""))
        assertEquals(LoginSession("a", "r", null, null), api.login("kim@g.eulji.ac.kr", "pw", null, true))
    }

    @Test fun `로그인 - 토큰이 빠진 응답은 Schema 오류`() = blocking {
        server.enqueue(json("""{"accessToken":"a"}"""))
        assertFailsWith<AppError.Schema> { api.login("a@b.c", "pw", null, true) }
        Unit
    }

    @Test fun `갱신 - 회전된 토큰은 있을 때만`() = blocking {
        server.enqueue(json("""{"accessToken":"a2","refreshToken":"r2","expiresIn":3600}"""))
        assertEquals(RefreshResult("a2", "r2", null), api.refresh("r1"))
        server.enqueue(json("""{"accessToken":"a3"}"""))
        assertEquals(RefreshResult("a3", null, null), api.refresh("r2"))
        server.enqueue(json("""{"nope":1}"""))
        assertFailsWith<AppError.Schema> { api.refresh("r3") }
        Unit
    }

    @Test fun `로그아웃 비밀번호 재설정 변경 탈퇴 - 경로와 메서드와 본문`() = blocking {
        repeat(4) { server.enqueue(json("""{"status":200,"code":"SUCCESS","message":"ok"}""")) }
        api.logout("r1")
        api.resetPassword("a@b.c", "tok", "new12345")
        api.changePassword("old12345", "new12345")
        api.withdraw("pw12345")

        val logout = server.takeRequest()
        assertEquals("/api/v1/auth/logout", logout.path)
        assertEquals("r1", bodyOf(logout)["refreshToken"].toString().trim('"'))

        val reset = server.takeRequest()
        assertEquals("POST", reset.method)
        assertEquals("/api/v1/members/password-reset", reset.path)
        val rb = bodyOf(reset)
        assertEquals("tok", rb["verificationToken"].toString().trim('"'))
        assertEquals("new12345", rb["newPassword"].toString().trim('"'))
        assertNull(rb["email"]) // 이메일은 토큰에 묶여 있다

        val change = server.takeRequest()
        assertEquals("PATCH", change.method)
        assertEquals("/api/v1/members/me/password", change.path)
        val cb = bodyOf(change)
        assertEquals("old12345", cb["currentPassword"].toString().trim('"'))
        assertEquals("new12345", cb["newPassword"].toString().trim('"'))

        val withdraw = server.takeRequest()
        assertEquals("DELETE", withdraw.method)
        assertEquals("/api/v1/members/me", withdraw.path)
        assertEquals("pw12345", bodyOf(withdraw)["password"].toString().trim('"'))
    }

    @Test fun `내 정보 - 학교 이름까지 읽는다`() = blocking {
        server.enqueue(
            json("""{"status":200,"code":"SUCCESS","message":"ok","data":{"memberId":7,"name":"가을","email":"kim@g.eulji.ac.kr","school":{"schoolId":1,"name":"을지대학교","campus":"성남캠퍼스"},"createdAt":"2026-11-01T09:00:00+09:00"}}"""),
        )
        assertEquals(UserProfile("가을", "kim@g.eulji.ac.kr", "을지대학교"), api.me())
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/members/me", req.path)

        // 학교를 못 찾으면 BE 가 빈 값을 준다 → 학교 이름 없음
        server.enqueue(json("""{"name":"가을","email":"kim@g.eulji.ac.kr","school":{"schoolId":null,"name":"","campus":""}}"""))
        assertEquals(UserProfile("가을", "kim@g.eulji.ac.kr", null), api.me())

        server.enqueue(json("""{"email":"kim@g.eulji.ac.kr"}"""))
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

/** 추천인 코드(베타 꺼짐): 값이 있을 때만 요청에 싣는다 */
class SignupReferralRequestTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RemoteAuthApi

    @BeforeTest fun setUp() {
        server = MockWebServer().also { it.start() }
        api = RemoteAuthApi(ApiClient(server.url("/api/v1").toString(), SessionManager()))
    }

    @AfterTest fun tearDown() { server.shutdown() }

    private fun body(referral: String?): kotlinx.serialization.json.JsonObject = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{}"))
        api.signUp(SignUpRequest("a@b.c", "n", "p", "t", true, true, referralCode = referral))
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    }

    @Test fun `추천인 코드가 있으면 보낸다`() {
        assertEquals("ORMO2026", body("ORMO2026")["referralCode"].toString().trim('"'))
    }

    @Test fun `없거나 비어 있으면 보내지 않는다`() {
        assertNull(body(null)["referralCode"])
        assertNull(body("  ")["referralCode"])
    }
}
