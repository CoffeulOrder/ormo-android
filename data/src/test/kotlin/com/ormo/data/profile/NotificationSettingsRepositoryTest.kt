package com.ormo.data.profile

import com.ormo.data.remote.AppError
import com.ormo.domain.profile.NotificationSettings
import com.ormo.domain.profile.NotificationSwitch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSettingsApi(var server: NotificationSettings = NotificationSettings()) : NotificationSettingsApi {
    val saved = mutableListOf<NotificationSettings>()
    var failNext = false
    var fetchError: Exception? = null
    val gates = ArrayDeque<CompletableDeferred<Unit>>()

    override suspend fun fetch(): NotificationSettings { fetchError?.let { throw it }; return server }
    override suspend fun save(settings: NotificationSettings) {
        gates.removeFirstOrNull()?.await()
        if (failNext) { failNext = false; throw AppError.Network() }
        saved += settings
        server = settings
    }
}

class NotificationSettingsRepositoryTest {
    private fun blocking(block: suspend () -> Unit): Unit = runBlocking { block() }

    @Test fun `불러오면 서버 값이 보인다`() = blocking {
        val api = FakeSettingsApi(NotificationSettings(orderPush = false, live = true))
        val repo = NotificationSettingsRepository(api)
        assertTrue(repo.load())
        assertFalse(repo.state.value.isOn(NotificationSwitch.ORDER_PUSH))
        assertTrue(repo.state.value.isOn(NotificationSwitch.LIVE))
    }

    @Test fun `불러오기에 실패하면 기본값 - 켜짐`() = blocking {
        val api = FakeSettingsApi().apply { fetchError = AppError.Network() }
        val repo = NotificationSettingsRepository(api)
        assertFalse(repo.load())
        assertTrue(repo.state.value.isOn(NotificationSwitch.ORDER_PUSH))
    }

    @Test fun `스위치를 누르면 즉시 저장된다`() = blocking {
        val api = FakeSettingsApi()
        val repo = NotificationSettingsRepository(api)
        repo.toggle(NotificationSwitch.ORDER_PUSH, false)
        assertEquals(listOf(NotificationSettings(orderPush = false, live = true)), api.saved)
        assertFalse(repo.state.value.isOn(NotificationSwitch.ORDER_PUSH))
        assertEquals(false, repo.state.value.confirmed.orderPush)
        assertTrue(repo.state.value.saving.isEmpty())
    }

    @Test fun `저장이 실패하면 그 스위치만 되돌리고 알린다`() = blocking {
        val api = FakeSettingsApi()
        val repo = NotificationSettingsRepository(api)
        api.failNext = true
        repo.toggle(NotificationSwitch.LIVE, false)
        assertTrue(repo.state.value.isOn(NotificationSwitch.LIVE)) // 되돌아감
        assertEquals(NotificationSwitch.LIVE, repo.state.value.failed)
        assertTrue(api.saved.isEmpty())
        // 다시 누르면 성공하고 실패 표시가 사라진다
        repo.toggle(NotificationSwitch.LIVE, false)
        assertFalse(repo.state.value.isOn(NotificationSwitch.LIVE))
        assertNull(repo.state.value.failed)
    }

    @Test fun `OS 알림이 꺼져 있으면 누르지 못하고 저장도 안 한다 - 설정 값은 보존`() = blocking {
        val api = FakeSettingsApi(NotificationSettings(orderPush = true, live = false))
        val repo = NotificationSettingsRepository(api, osGranted = false)
        repo.load()
        repo.toggle(NotificationSwitch.ORDER_PUSH, false)
        assertTrue(api.saved.isEmpty())
        assertFalse(repo.state.value.isOn(NotificationSwitch.ORDER_PUSH)) // OS 꺼짐 → 꺼진 채 표시
        assertFalse(repo.state.value.isEnabled(NotificationSwitch.ORDER_PUSH))
        assertEquals("ORMO 알림이 꺼져 있어요", repo.state.value.osOffNotice?.title)
        // 설정에서 켜고 돌아오면 이전 선택이 그대로
        repo.setOsPermission(true)
        assertTrue(repo.state.value.isOn(NotificationSwitch.ORDER_PUSH))
        assertNull(repo.state.value.osOffNotice)
    }

    @Test fun `같은 스위치를 연달아 누르면 마지막 값이 이긴다`() = runTest {
        val api = FakeSettingsApi()
        val repo = NotificationSettingsRepository(api)
        val gate = CompletableDeferred<Unit>()
        api.gates += gate // 첫 저장을 붙잡아 둔다
        val first = launch { repo.toggle(NotificationSwitch.ORDER_PUSH, false) }
        runCurrent()
        val second = launch { repo.toggle(NotificationSwitch.ORDER_PUSH, true) } // 다시 켬
        runCurrent()
        assertTrue(repo.state.value.isOn(NotificationSwitch.ORDER_PUSH)) // 화면은 마지막 값
        gate.complete(Unit)
        runCurrent()
        assertTrue(first.isCompleted && second.isCompleted)
        assertEquals(true, api.server.orderPush) // 서버에도 마지막 값
        assertEquals(true, repo.state.value.confirmed.orderPush)
        assertTrue(repo.state.value.saving.isEmpty())
    }
}
