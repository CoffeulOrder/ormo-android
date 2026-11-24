# BE 연동 격차표 (M8) — 실제 BE 와 앱이 가정한 API 의 차이

> **2026-11-24 갱신**: 이 문서의 3번 표(BE 에 없는 것) 중 메뉴 옵션·고객 주문 조회/취소·결제 상태는 BE 에 들어왔습니다. 최신 대응은 [BE_GUIDE_MAPPING_M8.md](BE_GUIDE_MAPPING_M8.md) 를 보세요.

기준: 로컬 BE 저장소 `C:\dev\Coffeul\Backend` 의 컨트롤러·DTO·오류 코드 (2026-11-23 시점, 커밋 `0979ab7`).
이 문서는 **앱 쪽을 BE 에 맞춘 것**과 **BE 에 아직 없어서 가짜 서버로 남은 것**을 구분합니다. 서버를 직접 띄워 보지는 못했고(이 PC 에 Docker 없음), 컨트롤러 소스와 같은 모양의 모의 서버로 눌러 확인했습니다.

공통: 기준 URL `/api/v1`, 응답 봉투 `{timestamp,status,code,message,data,errors}`(앱의 `ApiClient` 가 `data` 만 꺼냄), 오류는 `code` 로 구분(`C00x`·`AUTH_00x`·`EV00x`·`U00x`…).

## 1. 맞춘 것 (앱 → BE 계약, `USE_FAKE_AUTH=false` 로 켬)

| 기능 | BE | 앱이 예전에 가정한 것 → 바꾼 것 |
|---|---|---|
| 인증코드 발송 | `POST /verifications/email` `{email,purpose}` → `{expiresInSeconds,resendAvailableInSeconds}` | `/auth/email/send-code`, `expiresInSec`·`resendAfterSec` |
| 인증코드 확인 | `POST /verifications/email/confirm` → `{verificationToken}` | `/auth/email/verify-code` |
| 가입 | `POST /members` `{verificationToken,name,password,agreements:[{type,version,agreed}]}` (이메일은 토큰에 묶임) → 토큰 포함 응답 | `/auth/signup`, `email`·`consents{}` 를 보냄 |
| 로그인 | `POST /auth/login` `{email,password}` → `{accessToken,refreshToken,expiresIn,member:{memberId,name,schoolId}}` | `schoolId`·`autoLogin` 을 보냄, 이름·학교를 최상위에서 읽음 |
| 토큰 갱신·로그아웃 | `POST /auth/refresh`, `/auth/logout` | 같음 (회전 토큰) |
| 비밀번호 재설정 | `POST /members/password-reset` `{verificationToken,newPassword}` | `/auth/password/reset`, `email` 도 보냄 |
| 비밀번호 변경 | `PATCH /members/me/password` `{currentPassword,newPassword}` | `POST /me/password` |
| 내 정보 | `GET /members/me` → `{memberId,name,email,school:{name,campus},createdAt}` | `/me`. **학교 이름이 프로필에 옴** → 내 정보 카드가 이걸 우선 사용 (M6 의 "학교 이름 출처" 확인사항 해소) |
| 탈퇴 | `DELETE /members/me` `{password}` (**본문이 있는 DELETE**) | `POST /me/withdraw` |

오류 코드 매핑(`AuthErrors`): `AUTH_001` 로그인 실패 · `AUTH_002`(423) 잠김 + **`data.lockedUntil`(시각)로 남은 시간 계산** · `AUTH_005` 이용 불가 · `EV002/003` 재발송·하루 한도 · `EV004` 코드 틀림 · `EV005`(410) 만료(5회 실패 포함) · `EV006`/`U001` 이미 가입 · `EV008` 인증 토큰 무효 · `U002` 비밀번호 규칙 · `U003` 약관 · `U004` 현재 비밀번호 틀림 · `U005` 진행 중 주문(탈퇴 불가). `EV007` 은 메일 발송 실패(503)라서 '횟수 초과'가 아님 — 예전 매핑 바로잡음.

`ApiClient` 보강: `PATCH`·본문 있는 `DELETE`, **401 이라도 `C002`(토큰 문제)일 때만 갱신** — BE 가 '현재 비밀번호 틀림'(U004)도 401 로 주기 때문에, 예전 방식이면 비밀번호를 틀릴 때마다 불필요하게 토큰이 회전됩니다.

## 2. BE 에 없어서 앱이 임시로 때운 것

| 항목 | 지금 | BE 에 필요한 것 |
|---|---|---|
| 학교 메일 도메인 목록 | 앱에 넣어 둔 목록(`g.eulji.ac.kr`, `BundledSchoolDirectory`). 서버는 인증코드 발송 때 `EV001` 로 최종 판단 | `GET /schools` (도메인 포함) — 학교가 늘 때 앱 배포 없이 |
| 약관 버전 | 문자열 `"1.0"` 고정 (BE 는 길이 20자 이하만 검사) | 약관 버전 표기 확정 |
| 자동 로그인 on/off | BE 계약에 없음 → 앱 저장소 정책으로 처리 | 필요하면 리프레시 토큰 수명 정책 |
| 추천인 코드 | 값이 있을 때만 `referralCode` 를 실어 보냄(베타 꺼짐) | 가입 요청 필드·정책 |

## 3. BE 에 아직 없어서 **가짜 서버(`fake/`)로 남은 것** — 출시 막는 항목

`USE_FAKE_BACKEND=true`(디버그 기본) 로 남아 있고, **릴리스 빌드는 `verifyReleaseReadiness` 가 막습니다**(점검용만 `-Pormo.allowIncompleteRelease=true`).

| 영역 | BE 현황 | 앱이 필요로 하는 것 |
|---|---|---|
| **매장 목록** | `GET /stores` → `{storeId,name,location,status}` (status 는 `OPEN`/`PAUSED`/`CLOSED` 문자열) | 주소, **재개·마감 시각**(쉬는 시간 "03:21에 다시 열어요"), 메뉴 수, 대기 건수, 학교 이름 |
| 매장 상세 | `GET /stores/{id}` → 영업시간·공지·판매자 정보 | 그대로 매핑 가능(필드 대부분 있음) |
| **메뉴** | `GET /stores/{id}/categories`, `/menus?categoryId` → `{menuId,name,description,basePrice,imageUrl,soldOut,isNew,isEvent,hasOptions}` | **옵션 그룹·선택지 조회 API 가 없음**(`hasOptions` 만) → 옵션 있는 메뉴 주문 불가. 메뉴 버전(캐시 무효화)·인기 메뉴 없음. 앱은 학교 단위 한 번 조회 구조 → 매장별로 여러 번 호출하도록 바꿔야 함 |
| **주문 생성** | `POST /orders` — **임시로 `X-Member-Id` 헤더**로 회원 식별(JWT 아님), `Idempotency-Key` 필요. 요청: `{storeId,items[{menuId,quantity,optionItemIds}],requestMemo,withdrawalLimitAgreed}` | **픽업 방법(매장/포장) 필드 없음**, 쿠폰 없음. 응답에 `expiresAt`·결제 정보(가짜 PG)는 있음 |
| **내 주문** 조회·상세·취소·재주문 | **고객용 API 없음** (직원용 `/staff/...` 만 있음) | 진행 중 목록, 상세(상태·대기 순번), 내역(커서 페이지), 취소(접수 전만) |
| **결제 승인/상태** | 결제 확인·상태 조회 API 없음, `payment` 는 어댑터뿐 | `paymentStatus`·승인 확정 — 실제 PG 연동(`PaymentGateway` 구현, SDK) |
| **알림 설정·푸시 토큰** | `notification` 모듈 비어 있음 (로그아웃 요청에 `pushToken` 필드만 있음) | 알림 설정 조회·저장, 푸시 토큰 등록, FCM 발송 |
| 점검 응답 | 없음 | 503/`MAINTENANCE` + 종료 시각 (앱 `CommonErrorMapper` 가 이미 대기 중) |
| 쿠폰·이벤트·배너·내 메뉴 | 없음 (베타 제외, M7) | — |

## 4. 다음 순서 제안

1. **BE 쪽 결정/구현 요청**: 위 3번 표의 굵은 항목 — 특히 ① 옵션 조회 ② 주문 생성의 JWT 전환·픽업 방법 ③ 고객 주문 조회/취소 ④ 매장 목록 보강(재개 시각·주소·학교).
2. 앱: 매장·메뉴 `Remote*` 구현(매장별 호출로 `MenuPayload` 조립) — ①이 오면 같이.
3. PG 확정 후 `PaymentGateway` 실구현, FCM.
4. 모두 끝나면 `fake/` 삭제 + 릴리스 가드 제거.

## 5. 이번에 확인한 방법과 못 한 것

- 단위 테스트: BE 컨트롤러와 같은 경로·본문·봉투·오류 코드로 `MockWebServer` 테스트(전체 630개 통과).
- 에뮬레이터(Pixel_10): `-Pormo.realAuth=true` 로 설치, **컨트롤러 소스를 그대로 따라 만든 모의 서버**에 대해 로그인 실패(AUTH_001 문구) → 로그인 → `GET /members/me`(학교 이름 표시) → 탈퇴 비밀번호 틀림(U004: 한 번만 호출, 토큰 갱신 없음) 확인.
- **못 한 것**: 진짜 BE 와의 통신(Docker·MySQL 없음). 가입·재설정·비밀번호 변경 성공 흐름은 단위 테스트까지만. 진짜 서버로 한 번 눌러 보는 것이 다음 확인 사항 — `docker compose up` 이 되는 PC 에서 `-Pormo.realAuth=true -Pormo.apiBaseUrl=http://10.0.2.2:8080/api/v1`.
