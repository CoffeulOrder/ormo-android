package com.ormo.customer.session

import android.content.Context
import com.ormo.data.store.SelectedStoreStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 마지막에 고른 매장을 계정별로 기억한다 (1.1.1: "다음엔 바로 메뉴로"). 민감 정보가 아니라 일반 SharedPreferences. 로그아웃해도 남긴다. */
class PrefsSelectedStoreStore(context: Context) : SelectedStoreStore {
    private val prefs = context.getSharedPreferences("ormo_selected_store", Context.MODE_PRIVATE)

    override suspend fun load(accountKey: String): String? = withContext(Dispatchers.IO) { prefs.getString(accountKey, null) }

    override suspend fun save(accountKey: String, storeId: String): Unit = withContext(Dispatchers.IO) {
        prefs.edit().putString(accountKey, storeId).apply()
    }

    override suspend fun clear(accountKey: String): Unit = withContext(Dispatchers.IO) {
        prefs.edit().remove(accountKey).apply()
    }
}
