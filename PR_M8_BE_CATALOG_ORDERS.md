# feat: 매장·메뉴·주문·결제·내 주문 실서버 연동 (M8-5)

브랜치 제안: `feat/m8-be-catalog-orders`

## 요약
`-Pormo.realBackend=true` 로 빌드하면 매장/메뉴(옵션)/주문 생성/결제 상태/내 주문(목록·상세·취소)이 실서버(`https://api.orso.it.kr/api/v1`) 계약으로 동작합니다. 끄면 기존 가짜 데이터 그대로입니다.

## 변경
- `:data` `backend/BackendParsers.kt`, `backend/BackendApis.kt`: BE 응답 → 앱 모델 변환, `RemoteStoreDirectory` / `BackendMenuApi` / `RemoteOrderApi` / `RemoteMyOrdersApi` / `DevPaymentGateway` / `UnavailablePaymentGateway`
- `:domain` `ReorderRules`: 메뉴 id 없이도 이름·옵션 이름으로 재주문
- `:app` `AppContainer`, `build.gradle.kts`: `ormo.realBackend` 스위치(`USE_FAKE_BACKEND`)
- 문서: `BE_GUIDE_MAPPING_M8.md` §4 현황 갱신

## 알아둘 점
- 수령 방법(매장/테이크아웃)은 BE 필드가 없어 `requestMemo` 에 임시 전달 (BE 요청 필요)
- `ACCEPTED` → '만드는 중' 으로 묶음 (디자인 확인 필요)
- 릴리스 빌드는 결제 게이트웨이가 없어 결제 불가 (PortOne 연동 전까지). DEBUG 만 DEV-1 가짜 결제
- `TODO(BE)`: 목록 항목 모양, 취소 본문, payment.status 값, dayOfWeek 범위, 옵션 type, UNCLAIMED, canceledBy
- 실서버는 호출하지 않음 (계정 없음)

## 확인
- `gradlew test` 652개 통과, `:app:assembleRelease -Pormo.allowIncompleteRelease=true` 성공
- 에뮬레이터 + 로컬 모의 서버: 로그인 → 매장 → 메뉴/옵션 → 결제(DEV-1) → 주문 상세(접수→만드는 중→완료) → 내역 → 재주문 → 취소 확인
