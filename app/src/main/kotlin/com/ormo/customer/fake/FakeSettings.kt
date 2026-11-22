package com.ormo.customer.fake

import com.ormo.data.profile.NotificationSettingsApi
import com.ormo.domain.profile.NotificationSettings
import kotlinx.coroutines.delay

/** 개발용 가짜 알림 설정 서버: 메모리에 저장한다. M8 에서 실제 API 로 바꾸고 지운다. */
class FakeNotificationSettingsApi(private val latencyMs: Long = 300) : NotificationSettingsApi {
    @Volatile private var saved = NotificationSettings()

    override suspend fun fetch(): NotificationSettings { delay(latencyMs); return saved }
    override suspend fun save(settings: NotificationSettings) { delay(latencyMs); saved = settings }
}
