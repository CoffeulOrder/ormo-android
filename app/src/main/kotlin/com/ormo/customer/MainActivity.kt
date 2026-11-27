package com.ormo.customer

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ormo.customer.ui.theme.NavigationBarScrim
import com.ormo.customer.ui.theme.OrmoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 기기의 시스템 설정(라이트/다크)을 따른다: 상태바·내비게이션 바 아이콘은 라이트에선 어둡게, 다크에선 밝게 (앱 안 설정 없음)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // 3버튼 내비게이션에서 시스템이 따로 까는 막을 끄고, 앱이 직접 반투명 막을 깐다 (NavigationBarScrim)
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        val container = (application as OrmoApp).container
        container.portOne.attach(this) // 결제창 결과를 받을 준비는 화면이 시작되기 전에 해야 한다
        setContent {
            OrmoTheme {
                Box(Modifier.fillMaxSize()) {
                    AppNav(container)
                    NavigationBarScrim(Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}
