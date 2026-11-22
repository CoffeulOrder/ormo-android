# PR: M0 Android 앱 골격 · 디자인 토큰 테마 · 공통 컴포넌트

**브랜치 이름 제안**: `feat/m0-app-theme-components`  (base: `main`. 다른 PR 과 독립이라 어느 시점에 머지해도 됨. 단 Gradle/Kotlin 버전을 올리므로 **M1~M6 브랜치보다 먼저 머지하는 것을 권장**)

## 요약

`:app` Compose 모듈을 만들고, 디자인 명세 2장(토큰)·3장(코드)·4장(공통 컴포넌트)을 코드로 옮겼습니다. 에뮬레이터(Pixel_10, Android 16)에서 컴포넌트 카탈로그 화면으로 하나씩 확인했습니다.
기존 `:domain`·`:data` 테스트 **518개는 새 도구 버전에서도 모두 통과**합니다.

## 1. 빌드 환경 변경

| 항목 | 이전 | 이후 | 이유 |
|---|---|---|---|
| Gradle | 8.10.2 | **9.6.1** | AGP 9.5 가 9.6.0 이상 요구 |
| Kotlin | 2.0.21 | **2.4.21** | 최신 안정판 (Compose 컴파일러 플러그인과 같은 버전) |
| AGP | – | **9.5.2** | 설치된 Android Studio(2026.2)와 같은 세대, 최대 API 37 지원. Kotlin 내장이라 `kotlin-android` 플러그인 불필요 |
| JDK(Gradle 실행) | 8 | **17** | Gradle 9·AGP 9 필수 |
| compileSdk / targetSdk / minSdk | – | 37.1 / 36 / 26 | Compose BOM 2026.11 이 compileSdk 37.1 요구. minSdk 26 = Android 8.0 (갤럭시 S8 은 Android 9) |

환경 메모와 이 PC 의 함정(한글 사용자 폴더 때문에 **Gradle 사용자 폴더·SDK 경로를 영문으로** 지정해야 함)은 [ENVIRONMENT.md](ENVIRONMENT.md) 에 있습니다. `local.properties` 는 PC마다 달라 git 에 올리지 않습니다 (`sdk.dir=C:/dev/android-sdk`).

## 2. 변경 내용

### 디자인 토큰 — `app/.../ui/theme/`

| 파일 | 내용 |
|---|---|
| `Color.kt` | 기본 팔레트(`Base`) + 의미 토큰 `OrmoColors` (명세 2.1·2.2의 Figma `#RRGGBBAA` → Compose `0xAARRGGBB` 변환본). **라이트 고정** (다크 모드는 Figma 에 없음) |
| `Type.kt` | **Pretendard** 4개 굵기(400/500/600/700) + 크기 토큰 전부(`bodyMdSemibold` 등, 자간 0, 폰트 패딩 제거) |
| `Dimens.kt` | 간격·반경·컨트롤 높이→반경(38→10, 40→12, 48→14, 56→16)·크기·아이콘·스위치·다이얼로그 폭 등 |
| `Theme.kt` | `OrmoTheme` / `Ormo.colors`. Material 은 시트·진행 표시용으로 최소한만 맞춤 |

글꼴: Pretendard 1.3.9 OTF 4개를 `res/font/` 에 포함 (SIL OFL, 상용 앱 포함 가능, 전문은 `licenses/Pretendard-OFL.txt`).

### 공통 컴포넌트 — `app/.../ui/components/`

| 파일 | 내용 (명세) |
|---|---|
| `Buttons.kt` | 버튼 Solid / Critical / Weak(Brand·Neutral) / Outline / Ghost, 크기 S·M·L·XL, **로딩(라벨 숨기고 폭 유지)**, 비활성, 눌림 표시, **중복 탭 방지(500ms)**, 하단 CTA(Scroll Fog 40 + 아래 32·좌우 20·간격 8) (4.1·4.2) |
| `Field.kt` | 입력칸 L56/M48, 라벨·도움말·**오류(아이콘 + 빨간 2dp 테두리)**, focused 2dp brand, 도메인 접미사, **지우기 버튼(포커스 때만, 접미사·아이콘 있으면 끔)**, 비밀번호 eye, 비밀번호 규칙 행 (4.3) |
| `Dialog.kt` | 알림 다이얼로그: 폭 320·반경 24·Shadow/Large·뒤 배경 Dim, 가로/세로/확인만, Neutral/Critical, **알파 0.2초 + 0.94→1 등장** (4.4) |
| `Sheet.kt` | 바텀 시트: 위쪽 28, 핸들 40×4, 헤더 Left/Center, 푸터 버튼, 스크림 Dim (4.5) |
| `Controls.kt` | 원형 체크박스, 약관 행(전체 동의 위아래 12 / 개별 4 / chevron 44×44 탭 영역), 스위치 50×30, 구분선 3종, 뱃지 5종(10자·높이 24), 상단 내비게이션(Center/Left/Large·Transparent) (2.4·4.6·4.7) |
| `Feedback.kt` | 스낵바·토스트(유지 시간 상수 2/4/3/3/2초), 스켈레톤 shimmer, **`DelayedSkeleton`(0.3초 안에 불러오면 띄우지 않음 — M6 `CommonErrorRules` 상수 사용)** (4.7·6.1.4) |
| `Shadows.kt` | Figma 식 그림자 (Large: y8/블러32, Small: 두 겹). API 28+ 는 블러 마스크로 직접 그림, 26~27 은 elevation 근사 |
| `Icons.kt` | 24 그리드·획 2.4·둥근 끝의 아이콘 8종 (check, chevron, back, close, clear, alert, eye, eye-off) |

### 컴포넌트 카탈로그 — `ui/catalog/ComponentCatalog.kt`

색 견본, 타이포, 버튼 전 종류, 입력칸 상태, 뱃지·체크·스위치·구분선, 그림자·스켈레톤, 내비게이션, 다이얼로그·시트·스낵바를 한 화면에서 훑는 개발용 화면입니다. 지금은 `MainActivity` 의 첫 화면이며 M2 에서 스플래시·로그인으로 교체합니다.

### 그 밖

- 상태바·내비게이션 바: 라이트 고정 + 투명 (골격 단계에서 흰 글자가 배경에 묻히던 문제 해결)
- 릴리스 빌드는 R8 켬 (`proguard-rules.pro` 는 M8 에서 채움)
- `android:allowBackup="false"` (자동 로그인 토큰 등 민감 정보 백업 방지)

## 3. 확인 방법

에뮬레이터(Pixel_10, Android 16)에서 카탈로그를 스크롤하며 확인했습니다: 색·글꼴(한글 Pretendard)·버튼 7종 상태·입력칸(기본/포커스+지우기/접미사+chevron/비밀번호/오류)·체크·스위치·다이얼로그(폭 320, 그림자, 딤)·바텀 시트·스낵바(되돌리기 문구와 액션).
단위 테스트는 기존 518개 통과 (`./gradlew test`). UI 컴포넌트의 자동 테스트(스크린샷·접근성)는 아직 없음.

## 4. 확인이 필요한 것 (코드에 TODO / 가정 표시)

**디자이너**
- **아이콘 에셋**: 명세는 "ORMO 자체 제작 + eye 는 Material Symbols Rounded" 라고 하지만 파일이 없어, 같은 규격으로 **임시 제작**했습니다. Figma 에서 벡터를 내보내 교체 필요 (이름을 `OrmoIcons` 로 통일해 두어 교체 지점은 한 곳).
- 버튼 S 32 의 반경·글자 크기, 체크박스 체크 시 채움 색(Brand 로 가정), 스낵바 좌우 여백 16·최소 높이 48·반경 14
- **토스트 배경 블러 30**: Compose 에 배경 블러가 없어 Fill/Neutral 단색으로 대체 (명세도 이 대체를 제안). 확인 필요.
- **그림자 근사**: Figma 와 완전히 같지 않음 (명세 3장 주의사항). 특히 API 26~27 은 elevation 근사.
- 입력칸 오류 문구의 아이콘 색·크기, 눌림(pressed) 표현, Outline 테두리 색 (Grey Opacity 300 으로 가정)
- 픽셀 단위 레이아웃은 화면 구현 단계에서 `get_design_context` 로 한 번씩 재확인 (명세 8.4)

## 5. 의도적으로 하지 않은 것

- 다크 모드 (Figma 에 없음)
- 스플래시 로고 모션(0.0)·앱 아이콘·워드마크: 에셋 필요
- 화면 전부 (M2 부터)
- 접근성 세부(TalkBack 순서 등): 기본 시맨틱(역할·헤딩·콘텐츠 설명)만 넣음, M8 에서 점검

## 6. 이어서 할 일

M2 화면(스플래시 → 로그인 → 가입 → 비밀번호 찾기)을 이 컴포넌트와 M2 의 상태 클래스(`LoginFormState` 등)로 조립합니다.
