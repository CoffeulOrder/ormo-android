plugins {
    kotlin("jvm")
}

// 안드로이드 의존 없음. 암호화 저장소(Keystore)·DataStore 구현은 :app 에서 이 모듈의 인터페이스를 구현한다.
dependencies {
    api(project(":domain"))
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showExceptions = true
        showCauses = true
    }
}
