# 개발 환경 메모 (이 PC 기준)

## 버전 (2026-11-22 확인)

| 항목 | 버전 | 비고 |
|---|---|---|
| JDK | 17 (Temurin 17.0.21) | Gradle 9·AGP 9 필수. JDK 8 은 그대로 두고 터미널에서만 17 을 지정 |
| Gradle | 9.6.1 | AGP 9.5 최소 9.6.0 |
| Android Gradle Plugin | 9.5.2 | 최대 지원 API 37, Kotlin 내장(kotlin-android 플러그인 불필요) |
| Kotlin | 2.4.21 | `:domain`·`:data`(JVM) 와 Compose 컴파일러 플러그인 같은 버전 |
| Compose BOM | 2026.11.01 | `compileSdk` **37.1 이상** 필요 |
| compileSdk / targetSdk / minSdk | 37.1 / 36 / 26 | minSdk 26 = Android 8.0 (갤럭시 S8 은 Android 9 라 실행 가능) |
| Build-Tools | 36.0.0 | |

## 이 PC 의 함정 두 가지 (사용자 폴더 이름에 한글이 있음)

1. **Gradle 사용자 폴더를 영문 경로로**: 기본 위치(`C:\Users\<한글>\.gradle`)에서는 Gradle 9 의 테스트 실행기가
   `ClassNotFoundException: worker.org.gradle...GradleWorkerMain` 으로 죽는다. → `GRADLE_USER_HOME=C:\dev\gradle-home`
   - 터미널: `GRADLE_USER_HOME` 환경 변수
   - Android Studio: Settings > Build, Execution, Deployment > Build Tools > Gradle > **Gradle user home** 을 `C:\dev\gradle-home` 으로
2. **SDK 경로도 영문 경로로**: Android Studio 기본 SDK(`...\AppData\Local\Android\Sdk`)는 한글 경로다.
   `local.properties` 에 `sdk.dir=C:/dev/android-sdk` (플랫폼 36·37.1, Build-Tools 36.0.0, cmdline-tools 설치됨). 에뮬레이터 이미지는 Studio 쪽 SDK 에 있음.

## 터미널에서 빌드·테스트

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.21.10002-hotspot"
$env:GRADLE_USER_HOME = "C:\dev\gradle-home"
.\gradlew test                # :domain + :data 단위 테스트 (518개)
.\gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

## 에뮬레이터

Windows 기능 **Windows 하이퍼바이저 플랫폼**을 켜고 재부팅한 뒤 `Pixel_10`(Android 16, API 36.1) 에뮬레이터가 동작한다.
터미널에서 설치·실행: `adb install -r app/build/outputs/apk/debug/app-debug.apk` 후 `adb shell am start -n com.ormo.customer/.MainActivity`
(adb 는 Studio SDK 의 `%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe`).

## 글꼴

Pretendard 1.3.9 (SIL OFL) OTF 4개를 `app/src/main/res/font/` 에 넣었다 (출처: orioncactus/pretendard `packages/pretendard/dist/public/static`). 라이선스 전문: `licenses/Pretendard-OFL.txt`.
