plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ormo.customer"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "com.ormo.customer"
        // 갤럭시 S8(Android 9)에서도 돌려 보려고 8.0(API 26)부터. 디자인 명세에 최소 버전 요구가 없어 가정한 값.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // ---- 백엔드 연결 모드 (M8) ----
        // 인증·회원·이메일 인증은 실제 BE(Coffeul/Backend)와 계약이 맞춰져 있다 → 켜면 진짜 서버로 간다.
        // 매장·메뉴·주문·결제·알림 설정은 BE 에 아직 없는 부분이 있어(BE_GAP_M8.md) 가짜 서버(fake/)를 그대로 쓴다.
        //   ./gradlew :app:installDebug -Pormo.realAuth=true [-Pormo.apiBaseUrl=http://10.0.2.2:8080/api/v1]
        // 에뮬레이터에서 내 PC 의 서버는 10.0.2.2 로 보인다.
        //   ./gradlew :app:installDebug -Pormo.realBackend=true   ← 인증 + 매장·메뉴·주문·결제(개발 서버 DEV-1)까지 전부 실서버 (알림 설정만 가짜)
        val realBackend = (project.findProperty("ormo.realBackend") as String?)?.toBoolean() ?: false
        val realAuth = realBackend || ((project.findProperty("ormo.realAuth") as String?)?.toBoolean() ?: false)
        val apiBaseUrl = (project.findProperty("ormo.apiBaseUrl") as String?) ?: if (realBackend) "https://api.orso.it.kr/api/v1" else "http://10.0.2.2:8080/api/v1"
        buildConfigField("boolean", "USE_FAKE_AUTH", (!realAuth).toString())
        buildConfigField("boolean", "USE_FAKE_BACKEND", (!realBackend).toString())
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildTypes {
        release {
            // 릴리스에는 가짜 서버를 쓰지 않는다. 주소는 반드시 https 로 직접 지정한다 (없으면 빌드를 멈춘다).
            val prodUrl = project.findProperty("ormo.apiBaseUrl") as String?
            buildConfigField("boolean", "USE_FAKE_AUTH", "false")
            buildConfigField("boolean", "USE_FAKE_BACKEND", "false")
            buildConfigField("String", "API_BASE_URL", "\"${prodUrl ?: "https://api.invalid/api/v1"}\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))

    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    // Figma 모션(스플래시 로고·가입 축하·주문 완료·주문 만료)을 Lottie JSON 으로 재생
    implementation("com.airbnb.android:lottie-compose:6.6.7")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// 릴리스 안전장치: 아직 BE 에 없는 부분(주문 조회·결제·알림 설정·메뉴 옵션 등, BE_GAP_M8.md)이 가짜 서버로만 돌아간다.
// 그대로 스토어에 올리지 않도록, 의도적으로 허용하지 않으면 릴리스 빌드를 멈춘다. (R8 점검용: -Pormo.allowIncompleteRelease=true)
val verifyReleaseReadiness by tasks.registering {
    doLast {
        val allowed = (project.findProperty("ormo.allowIncompleteRelease") as String?)?.toBoolean() ?: false
        val url = project.findProperty("ormo.apiBaseUrl") as String?
        if (!allowed) {
            throw GradleException(
                "릴리스 빌드 중단: BE 에 없는 기능(BE_GAP_M8.md)이 남아 있습니다. " +
                    "점검용이면 -Pormo.allowIncompleteRelease=true 를 붙이세요.",
            )
        }
        if (url != null && !url.startsWith("https://")) {
            throw GradleException("릴리스의 ormo.apiBaseUrl 은 https 여야 합니다: $url")
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseReadiness) }
