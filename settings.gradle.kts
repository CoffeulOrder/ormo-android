rootProject.name = "ormo-android"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// 안드로이드 의존이 없는 순수 Kotlin 도메인 모듈
include(":domain")

// M1 후반: 네트워크·세션·메뉴 캐시/동기화. 안드로이드 의존 없는 JVM 모듈 (Keystore·DataStore 구현은 :app 에서 인터페이스를 채운다)
include(":data")

// M0: Compose 앱. 화면·ViewModel·플랫폼 구현(Keystore, DataStore, PG, FCM)이 여기에 들어간다
include(":app")
