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

// M1: 안드로이드 의존이 없는 순수 Kotlin 도메인 모듈. (M0 에서 :app 을 추가한다)
include(":domain")
