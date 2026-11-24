# BE 연동 가이드 ↔ 앱 대응표 (2026-11-24 가이드 기준)

출처: 바탕 화면의 `ORMO · FE·BE 연동 가이드.html` (develop `f603843`, 43개 항목) + 같은 서버의 공개 OpenAPI(`https://api.orso.it.kr/v3/api-docs`, 조회만 함).
이 문서는 `BE_GAP_M8.md`(11/23, 로컬 BE 소스 기준)를 **대체**합니다 — 그 사이 BE 에 고객 주문·메뉴 옵션·결제 API 가 들어왔습니다.

> 한계: 저장된 가이드 HTML 에는 카드 목록만 있고 요청·응답 상세(`catalog.json`)가 없습니다. 공개 OpenAPI 는 같은 이름의 안쪽 클래스(`Item`, `Response`)를 하나로 합쳐 **내 주문 목록의 항목 모양**과 **취소 응답**이 정확하지 않습니다. 그 둘은 가이드 상세 화면으로 확인이 필요합니다.

## 1. 앱이 필요로 하는 것 ↔ BE

| 앱 기능 | BE (가이드 번호) | 상태 | 앱에서 할 일 / 메모 |
|---|---|---|---|
| 로그인·가입·재발급·로그아웃·내 정보·비밀번호·탈퇴·인증코드 | SM-1~7, MS-1·3·4 | 서버 명세 노출 | **M8-1 에서 이미 연결**(`USE_FAKE_AUTH=false`). `U004`(현재 비밀번호 불일치, 401)·`AUTH_003`(갱신 토큰 사용 불가 → 토큰 지우고 로그인) 처리는 이미 있음. 로그아웃·비밀번호 변경 후 갱신 토큰 폐기 보완은 BE PR #25 검토 중 |
| 매장 목록 | MS-5 `GET /stores` | 노출 | `{storeId,name,location,status}` 뿐 — **주소·재개 시각·메뉴 수·대기 건수·학교 이름 없음**(아래 3번) |
| 매장 정보 | MS-6 `GET /stores/{id}` | 노출 | 영업시간·공지·판매자 정보 그대로 매핑 가능 |
| 카테고리·메뉴 목록 | MS-9·10 | 노출 | `MenuResponse{menuId,name,description,basePrice,imageUrl,soldOut,isNew,isEvent,hasOptions}`. 인기(popular) 값은 없음 → `isEvent` 와 의미 확인 |
| **메뉴 옵션** | **MS-11** `GET /stores/{s}/menus/{m}` (고객 Bearer) | **노출 (새로)** | `optionGroups[{optionGroupId,name,type,required,minSelect,maxSelect,options[{optionItemId,name,priceDelta,isDefault,soldOut}]}]` → 앱 `OptionGroup`/`OptionChoice` 에 거의 1:1. **옵션 있는 메뉴는 메뉴마다 한 번 더 호출** 필요(목록엔 `hasOptions` 만) |
| 주문 생성 | **MS-16** `POST /orders` (Bearer + `Idempotency-Key` 헤더) | 노출 | `X-Member-Id` 임시 헤더는 사라짐. 요청 `{storeId, items[{menuId,quantity,optionItemIds}], requestMemo, withdrawalLimitAgreed}`. 응답에 `orderId·orderCode·expiresAt·items(단가·줄 금액)·subtotal/discount/total·payment{…portOne{storeId,channelKey,paymentId,currency}}`. **HTTP 201 / 본문 status 200**. **매장 섭취·테이크아웃 필드는 여전히 없음** |
| 내 주문 목록 | **MS-17** `GET /orders?page&size&filter` | 노출 | 페이지 방식(`page,size,hasNext`) — 앱은 커서 방식(`OrderPage.nextCursor`) → 어댑터에서 변환. **항목 모양 확인 필요** |
| 주문 상세·내 앞 대기 | **MS-18** `GET /orders/{id}` | 노출 | `orderCode·pickupNo·aheadCount·cancelable·items[menuName,options[],unitPrice,quantity,lineAmount]·subtotal/discount/total·rejectReason·rejectReasonDetail·timeline{placedAt,acceptedAt,makingAt,readyAt,completedAt,canceledAt,rejectedAt}·refundStatus`. **`cancelable` 을 서버가 줌** → 앱의 "REQUESTED 만 취소 가능" 추정 대신 이 값을 쓰는 게 안전 |
| 주문 취소 | **MS-19** `POST /orders/{id}/cancel` | 노출 | 고객 취소는 REQUESTED 에서만. 본문 `{reasonCode,reasonDetail}`(공개 문서상) — 필수 여부 확인 |
| 결제 승인 | MS-27 `POST /payments/confirm` | **환경 전환 필요** | 공개 서버는 FAKE 결제 경로. PortOne 확인·웹훅·결제창 재개는 환경 전환 후 검증 |
| 결제·환불 상태 | **MS-28** `GET /orders/{id}/payment` | 노출 | `{orderStatus, expiresAt, pickupNo, retryAvailable, payment{provider,status,amount,approvedAt}, refund{status,amount,succeededAt}}` → 앱 `OrderApi.paymentStatus` 에 대응. **카드 번호·승인 번호는 없음**(영수증 2줄 영향) |
| 결제 다시 시도 | MS-29 `POST /orders/{id}/payment-attempts` | 환경 전환 필요 | 앱의 "다시 결제하기" |
| 푸시 토큰 | TW-1 `PUT /devices/push-token` | PR #12 검토 중 | 계약 확정 전 → FCM 연동은 대기 |
| 개발용 가짜 결제 확정 | DEV-1 `POST /dev/orders/{id}/confirm-payment` | 노출 | **통합 시험에 유용**: 앱의 `PaymentGateway` 를 "DEV-1 호출" 구현으로 바꾸면 PortOne 없이 주문→접수 흐름을 실서버로 끝까지 확인 가능 |

## 2. 상태값 대응 (결정 필요)

BE: `PENDING_PAYMENT → REQUESTED → ACCEPTED → MAKING → READY → COMPLETED` (+ 거절·취소·환불·만료).
앱: `PENDING_PAYMENT, REQUESTED, MAKING, READY, COMPLETED, REJECTED, CANCELED, REFUNDED, EXPIRED, UNCLAIMED`.

- **`ACCEPTED`(매장이 수락, 아직 제조 전)** 가 앱에는 없음. Figma 단계 바는 4칸(주문 확인 중 · 만드는 중 · 픽업 대기 · 픽업 완료) — 제안: `ACCEPTED` 는 '만드는 중'(2칸)으로 묶고 시각은 `timeline.acceptedAt` 을 우선 사용. 디자인 확인 필요
- 환불은 주문 상태가 아니라 `refundStatus`(+`MS-28 refund`) — 앱의 `REFUNDED`/`UNCLAIMED` 는 이 값과 `timeline` 으로 도출해야 함. `UNCLAIMED`(미수령) 에 해당하는 BE 상태가 가이드에 보이지 않음 → 확인 필요
- 결제 직후 접수 불가 시 **보상 환불**(`PY013`, PR #21): REQUESTED / SUCCEEDED / FAILED 를 구분해 "접수 완료" 로 표시하지 말 것

## 3. 아직 없는 것 (BE 요청)

1. **매장 목록 보강**: 주소, 영업 재개·마감 시각(앱: "15:25에 다시 열어요"), 메뉴 수, 대기 건수, 학교 이름 — 또는 앱이 안 보여도 되는지 디자인 결정
2. **매장 섭취/테이크아웃** 필드(주문 생성) — Figma 주문서에 있음
3. **내 주문 목록** 항목 모양 확정(현재 문서 모호), 날짜·상태 필터 값(`filter`)
4. **영수증용**: 카드 번호(마스킹)·승인 번호·할부, 옵션별 금액, 판매자 정보 스냅샷
5. **알림 설정**(주문 알림·잠금 화면 실시간 현황) 조회·저장 API — 가이드에 없음
6. **학교 메일 도메인 목록**(`GET /schools`) — 가이드에 없음
7. **점검 응답 형식**(503/점검 코드 + 종료 시각) — 가이드에 없음
8. **인기 메뉴** 기준(최근 7일 상위 5개)·**메뉴 버전**(캐시 무효화) — 없음. 앱은 메뉴를 매장별로 다시 받으면 되지만 인기 표시는 BE 값 필요
9. 쿠폰·이벤트·배너·내 메뉴·추천인 — 베타 제외라 요청 안 함

## 4. 앱 쪽 구현 계획 (M8-5, 가이드 상세 확인 후)

1. `RemoteStoreDirectory`: MS-5 + MS-6 (주소·재개 시각이 없는 동안은 해당 줄 숨김)
2. `RemoteMenuApi`: 매장 → 카테고리(MS-9) → 메뉴(MS-10) 조합으로 `MenuPayload` 구성, 옵션은 메뉴 진입 시 MS-11 지연 호출(앱 메뉴 캐시 구조 수정 필요: 학교 단위 한 번 → 매장 단위)
3. `RemoteOrderApi`: MS-16 (키 = 기존 `Idempotency-Key`), MS-28 폴링, MS-29
4. `RemoteMyOrdersApi`: MS-17/18/19 + 위 상태 도출 규칙(`cancelable`·`refundStatus`)
5. 결제: 개발 서버는 `DEV-1` 게이트웨이, 운영 전환 때 PortOne Android SDK 게이트웨이
6. 오류 코드 매핑 추가: `OD009`(결제 기한 만료 → 상태 재조회), `OD010`(같은 키·다른 주문 → 새 키), `OD006`(현재 상태에서 불가 → 상세 갱신), `PY009/PY012`(결제 미확인·확인 중), `PY013`
7. 전부 끝나면 `fake/` 삭제와 릴리스 가드 제거

## 5. 가이드에서 앱에 바로 반영할 규칙 (체크리스트 대응)

- 고객 ID 를 헤더·쿼리로 보내지 않음(Bearer 만) — 현재 앱은 이미 그렇게 동작
- 로그인·재발급 응답의 토큰 쌍을 **함께** 교체 — `SessionManager` 가 처리
- 같은 주문 재전송엔 **같은 `Idempotency-Key`**, 새 논리 주문엔 새 키 — `PaymentCoordinator` 가 처리(결제 중 앱 재시작 복구 포함)
- 응답 봉투의 null 필드는 생략됨 → `data`/`errors` 는 항상 있다고 가정하지 않음 — `ApiClient.unwrap` 이 처리
- 환불 요청(HTTP 202)은 환불 완료가 아님 — 환불 화면은 요청/완료 구분
- 주문 코드와 픽업 번호는 다른 값 — 앱은 픽업 번호만 보여줌
