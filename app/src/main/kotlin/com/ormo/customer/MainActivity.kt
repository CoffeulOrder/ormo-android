package com.ormo.customer

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ormo.customer.ui.catalog.ComponentCatalog
import com.ormo.customer.ui.theme.OrmoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 라이트 테마 고정: 상태바·내비게이션 바 아이콘은 어두운 색 (밝은 배경 위에서 보이도록)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            OrmoTheme {
                // M0 동안은 컴포넌트 카탈로그를 첫 화면으로 둔다. M2 에서 스플래시·로그인으로 교체.
                ComponentCatalog()
            }
        }
    }
}
