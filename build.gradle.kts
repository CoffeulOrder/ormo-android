plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("com.android.application") version "9.5.2" apply false
    // AGP 9 는 Kotlin 이 내장되어 있어 kotlin-android 플러그인이 필요 없다. Compose 컴파일러 플러그인만 Kotlin 버전과 맞춰 쓴다.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
