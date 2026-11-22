# PR: M8-1 실제 BE 와 인증 계약 맞추기 · 빌드 모드 · 릴리스 안전장치

**브랜치 이름 제안**: `feat/m8-be-auth-contract`  (base: `main`, M7 까지 머지된 뒤)

## 요약

M8(BE 연동)을 시작하면서 로컬에 있는 BE 소스(`C:\dev\Coffeul\Backend`)를 읽어 보니, **앱이 가정했던 API 와 실제 BE 가 꽤 달랐고**(경로·본문·응답 모양·오류 코드), BE 에는 고객 앱에 필요한 API 가 **아직 많이 없었습니다**(내 주문 조회·결제 확인·알림 설정·메뉴 옵션 등). 그래서 이번 PR 은 두 가지만 합니다.

1. **BE 에 이미 있는 부분(인증·회원·이메일 인증)을 실제 계약에 맞춤** — 켜면 진짜 서버로 갑니다.
2. **BE 에 없는 부분은 격차표로 정리**(`BE_GAP_M8.md`)하고, 그 부분이 가짜 서버로 남아 있는 채로 **릴리스가 나가지 않게 막음**.

`fake/` 삭제와 `USE_FAKE_BACKEND=false` 전환은 하지 않았습니다(BE 가 완성돼야 가능 — 격차표 3번 참고). 전체 테스트 **630개 통과**(이번 추가 9개), 릴리스(R8) 빌드 통과, 모의 서버로 에뮬레이터 확인.

## 변경 내용

### `:data`
- `AuthApi.kt`: `RemoteAuthApi` 를 BE 계약으로 — `/verifications/email(/confirm)`, `POST /members`(약관 `agreements[]`, 이메일 없음), 로그인 응답의 `member{}`, `POST /members/password-reset`(이메일 없음), `PATCH /members/me/password`, `GET /members/me`, `DELETE /members/me`(본문 있음). 약관 버전 상수 `TERMS_VERSION="1.0"`(확정 필요)
- `ApiClient.kt`: `patch`, 본문 있는 `delete`. **401 이어도 `C002` 일 때만 토큰 갱신** (현재 비밀번호 틀림 `U004` 도 401 이라, 예전 방식은 틀릴 때마다 토큰을 회전시킴)
- `AuthErrors.kt`: BE 코드 반영(`U004`·`U005`·`EV00x`), 잠김은 `data.lockedUntil` 로 남은 시간 계산, `EV007` 오매핑 수정
- `SchoolDirectoryApi.kt`: BE 에 목록 API 가 없어 `BundledSchoolDirectory`(`g.eulji.ac.kr`) 추가
- `:domain` `UserProfile.schoolName` 추가 → 내 정보 카드가 BE 프로필의 학교 이름을 우선 사용

### `:app`
- 빌드 모드: `-Pormo.realAuth=true`(인증만 진짜 서버), `-Pormo.apiBaseUrl=…`(기본 `http://10.0.2.2:8080/api/v1`). 기본(디버그)은 지금처럼 전부 가짜
- 디버그 전용 `network_security_config`(10.0.2.2·localhost 평문 허용). 릴리스는 https 만
- 릴리스: `isShrinkResources`, `proguard-rules.pro` 채움(R8 로 빌드 확인, APK 약 6MB), **`verifyReleaseReadiness`**: BE 미완성 상태로 릴리스를 만들면 빌드 중단(점검용 `-Pormo.allowIncompleteRelease=true`, 주소는 https 강제)
- `AppContainer`: `USE_FAKE_AUTH` / `USE_FAKE_BACKEND` 분리

## 테스트 (전체 630개)

- `RemoteAuthApiTest` 를 실제 BE 경로·본문·봉투 모양으로 다시 작성(가입 `agreements`, 로그인 `member{}`, DELETE·PATCH 메서드 확인)
- `ApiClientTest`: U004 401 은 갱신하지 않음, DELETE·PATCH 본문·인증 헤더
- `AuthErrorsRealBeTest`: 잠김 `lockedUntil`(지난 시각·깨진 값 기본값), EV004/005/006/007/008, U002~U005
- 에뮬레이터(모의 BE): 로그인 실패 문구 → 로그인 → 학교 이름 표시 → 탈퇴 비밀번호 틀림(DELETE 한 번, 갱신 없음)

## 확인이 필요한 것

- **BE 팀**: `BE_GAP_M8.md` 3번 표 — 옵션 조회 API, 주문 생성의 JWT 전환·픽업 방법, 고객 주문 조회/취소, 매장 목록 필드(재개 시각·주소·학교), `GET /schools`, 알림 설정·푸시, 점검 응답. 약관 버전 표기. 가입 응답의 `expiresIn` 은 앱이 쓰지 않음(401 때 갱신) — 선제 갱신이 필요하면 알려주세요
- **진짜 서버로 한 번 눌러보기**: 이 PC 에 Docker 가 없어 못 했습니다. `docker compose up` 이 되는 환경에서 `-Pormo.realAuth=true`
- 로그아웃: 액세스 토큰이 만료된 상태에서 로그아웃하면 갱신(토큰 회전) 후 옛 리프레시 토큰으로 로그아웃을 보내게 됨 → 서버가 거절해도 앱은 로컬 세션을 지우므로 동작에는 문제없으나, BE 의 재사용 감지(30초 유예)와 부딪히는지 실서버에서 확인 필요

## 의도적으로 하지 않은 것

- 매장·메뉴·주문·결제·알림 설정의 `Remote*` 구현 — BE 에 API 가 없거나 모자라서 (격차표)
- `fake/` 삭제, 실제 PG·FCM, 접근성(TalkBack) 점검·기기 점검·성능, 스토어 서류(서명 키·개인정보 처리방침 주소·데이터 안전 항목) — 이어서 (BE 와 병행 가능한 것은 접근성·스토어 준비)
