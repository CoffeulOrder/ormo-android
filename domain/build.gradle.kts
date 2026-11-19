plugins {
    kotlin("jvm")
}

// 이 모듈은 안드로이드/네트워크에 의존하지 않는다 (나중에 KMP shared 로 뽑을 수 있게).
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.3")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showCauses = true
    }
}
