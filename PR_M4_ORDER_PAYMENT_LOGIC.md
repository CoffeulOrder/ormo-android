# PR: M4 옵션·주문서·결제 로직 (화면 제외)

**브랜치 이름 제안**: `feat/m4-order-payment-logic`  (base: `main`, **M3 가 머지된 뒤** — 주문 불가 안내에 M3 의 `StoreRules` 를 씁니다)

## 요약

마이그레이션 계획 M4(옵션 → 장바구니/주문서 → 결제)에서 **화면과 실제 서버·PG 연동을 뺀 모든 규칙과 결제 상태 머신**을 구현했습니다.
계획서의 "상태 머신은 먼저 문서화 후 테스트로 고정" 원칙대로, 아래 상태 표를 먼저 정하고 테스트(**전체 401개 통과**, 이번 추가 73개)로 고정했습니다.
기준은 새 디자인 명세 2.2, 2.3, 3.1.2, 3.2.2, 3.3.1, 3.3.2 입니다.

## 결제 상태 머신 (`PaymentState` — 순수 불변, IO 는 `PaymentCoordinator`)

```
 Idle ──[결제하기]──► Preparing ──주문 생성──► AwaitingPg ──돌아옴──► Confirming ──서버 확정──► Succeeded → 4.1.1
   ▲                    │  │                    │ 창 닫음                 ├─ 실패(1~4번) ► Failed ─[다시 결제하기]─► Preparing …
   │                    │  └ 영업 중지 ► Blocked (3.1.2, [확인] → Idle)     ├─ 실패(5번째) ► Exhausted (3.3.1) → [장바구니로 이동]
   │                    └ 네트워크·전부 품절·기타 ► Idle + prepareError      └─ 만료 ► Expired (3.3.2) → [다시 주문하기]
   └──────────────────── 창 닫음: 주문은 유지, 실패 횟수 그대로
```

| 규칙 | 구현 |
|---|---|
| 결제 버튼을 여러 번 눌러도 결제는 한 번 | `begin()` 은 Idle/Failed 에서만 받아들임. 코디네이터가 상태 전이를 잠금 안에서 해서 **동시 5번 호출 → 주문 1건, 결제창 1번** (테스트) |
| 같은 주문 = 같은 멱등 키 (O-42, O-43) | 주문이 만들어지면 주문·키 유지. 창을 닫거나 실패 후 다시 결제해도 같은 키 |
| 결제수단을 바꿔도 같은 주문 (3.3.1 [결제수단 변경하기]) | 주문 식별값(`fingerprint`)은 메뉴·옵션·수량·수령 방법만. 결제수단·금액·줄 순서 제외 |
| 서버가 최종 결정 | PG 가 승인/실패/예외를 알려도 항상 `paymentStatus` 로 확정 후 화면 전환 (3.2.2) |
| 창을 닫고 돌아와 다시 누르면 | 새 주문 없이 **같은 주문의 상태를 먼저 조회** → 이미 결제됨이면 결제창 없이 성공, 만료면 만료, 아니면 같은 주문으로 결제창 |
| 5번 실패 | 같은 주문으로 실패 5번째에 `Exhausted` ([장바구니로 이동] 하나) |
| 뒤로가기 막음 | 준비 중·결제창·확인 중에 `backBlocked` |
| 앱을 나갔다 들어와도 서버에 다시 묻는다 | 주문을 `PendingPaymentStore` 에 저장 → 앱 시작 때 `resume()` 이 서버에 물어 이어감 (실패 횟수도 이어서 셈) |
| 서버 확인이 늦으면 | 1초 간격 10번 묻고, 그래도 결론이 없으면 `Confirming(checkFailed)` → [다시 확인] / 앱 복귀 시 `recheck()` |
| 0원 주문 | 결제창 건너뛰고 서버 확인으로 |
| 장바구니·수령 방법이 바뀌면 | `onDraftChanged` 로 이전 주문 폐기 + 새 키 (진행 중이면 무시) |
| 화면이 사라져 코루틴이 취소돼도 | 상태가 `Preparing`/`AwaitingPg` 로 멈춰 남지 않게 정리 (주문은 유지) |

## 변경 내용

### `:domain`

| 파일 | 내용 |
|---|---|
| `order/OptionScreenState.kt` | 옵션 전체 화면(2.2): 필수 기본값 미리 선택, **버튼 금액 = (기본가+옵션 추가금)×수량**, 수량 1~20(한도 안내), 선택 그룹 최대 개수 스낵바(체크 유지), 품절 옵션 '품절' / 추가금 없으면 '+0원', 담기 |
| `order/CheckoutState.kt` | 장바구니+주문서 한 화면(2.3): 수령 방법·결제수단·**취소 불가 동의**(주문마다 재체크)가 있어야 결제 가능, **결제 금액은 품절 제외**·모두 품절일 때만 비활성, 삭제 즉시 + **4초 되돌리기**("아메리카노를 삭제했어요"), 메뉴 갱신 반영, 주문 후 정리, 주문 초안(`OrderDraft`) |
| `payment/PaymentState.kt` | 위 상태 머신 + 모델(`PlacedOrder`, `PaymentStatus`, `PrepareError`) |
| `payment/PaymentMessages.kt` | 3.1.2 영업 종료/잠시 멈춤 다이얼로그, 3.2.2·3.3.1·3.3.2 문구, PG 오류코드→쉬운 문구(표 비어 있음 → 기본 문구) |
| `rules/OptionRules.kt` | `effectiveSoldOut`: **필수 옵션이 모두 품절이면 메뉴도 품절** |
| `home/MenuHomeRules.kt` (M3 파일) | 메뉴 줄이 위 규칙을 쓰도록 `rowState` 한 곳만 수정 (+테스트 1개) |
| `core/Korean.kt` | 을/를 선택 (삭제 안내 문구용) |

### `:data`

| 파일 | 내용 |
|---|---|
| `payment/PaymentPorts.kt` | `OrderApi`(주문 생성·결제 상태 조회), `PaymentGateway`(PG 결제창), `PendingPaymentStore`, `PaymentMethodStore`(마지막 결제수단 기억) 인터페이스 + 메모리 구현, `FakePaymentGateway` |
| `payment/PaymentCoordinator.kt` | 상태 머신을 서버·PG 호출과 이어 주는 앱 범위 객체 (`pay`, `recheck`, `resume`, `onDraftChanged`, `checkExpiry`, `giveUpWaiting`, …) |

## 테스트 (전체 401개 통과, 3회 재실행 동일)

- `OptionScreenStateTest`(11): 명세 예시 (4,000+900+1,600)×3 = 19,500, 수량 한도, 최대 개수 안내, 필수 해제 불가, 품절 옵션, 필수 전부 품절
- `CheckoutStateTest`(15): 결제 조건, 품절 제외 금액(16,000 형태), 되돌리기 4초 경계, 주문 후 정리, 주문 식별값
- `PaymentStateTest`(20): 전이 전부, 연타 무시, 4번까지 재시도·5번째 종료, 순서에 맞지 않는 이벤트 무시
- `PaymentCoordinatorTest`(24): 정상, **연타**, 영업 중지, 창 닫고 재시도(상태 먼저 조회), 5번 실패, PG 예외, 0원, 주문서 변경, **앱 재시작 복구**, 취소 정리 외
- 원래 요구된 시나리오(성공·취소·실패·0원·5회 초과·만료·품절 충돌·영업 종료 충돌)를 모두 커버. "품절 충돌"은 서버가 빼고 접수하는 경우(`removedMenuIds`)와 전부 품절(`ALL_SOLD_OUT`) 둘 다.

## 확인이 필요한 것 (코드에 `TODO(spec …)` 또는 가정으로 표시)

**BE**
- 주문 생성·결제 상태 조회 API 계약 (`OrderApi` 뒤에 숨김). 특히 ① 같은 멱등 키 재요청 시 같은 주문 반환, ② 결제 확정 지연 최대 시간(지금 폴링 10초), ③ 실패 후 같은 주문 재결제 허용 횟수(5)를 서버가 강제하는지, ④ 만료 {N}분 응답에 만료 시각 포함 여부
- 서버 장바구니(5.1.2)와의 동기화: 지금 `CheckoutState` 는 로컬 상태입니다. 결제 성공 시 서버가 장바구니를 비우는지, 품절로 빠진 줄을 남기는지(지금은 남기는 쪽으로 가정)

**디자인**
- 2.3 "메뉴 20개, 수량 20개까지": 줄 20개 + 줄당 수량 20으로 구현 (M2 에서 정한 대로, 전체 수량 20 의미인지 확인)
- 3.3.1 "같은 주문으로 5번까지 다시 결제": 실패 5번째에 막음 (첫 시도+재시도 5번이면 6번째)
- 3.2.2 "뒤로가기 막음"만 있어 확인이 계속 안 될 때 갇히는 문제 → **[주문서로 나가기] 탈출구를 가정**해 `giveUpWaiting` 추가 (확인 필요)
- 3.3.1 KG이니시스 오류 코드 → 쉬운 문구 표 (없어서 기본 문구)

## 의도적으로 하지 않은 것

- Compose 화면 전부 (옵션 화면, 장바구니·주문서, 결제 확인 중, 실패/만료 화면, 다이얼로그)
- PG SDK 연동(포트원 v2 / KG이니시스): 확정 전. `PaymentGateway` 구현은 `:app` 에서 (Fake 포함)
- 다른 매장 메뉴를 담을 때의 1.1.2 경고 연결: 규칙은 M3 `StoreRules.decideStoreChange` 에 있음, 화면에서 호출
- `PendingPaymentStore`/`PaymentMethodStore` 의 DataStore 구현 (`:app`)

## 연결할 때 참고 (:app 이 생긴 뒤)

- `PaymentCoordinator` 는 **앱 범위 싱글톤**으로, ViewModel 은 `state` 만 구독. 앱 시작 때 `resume()`, 포그라운드 복귀 때 `recheck()`, 주문서가 바뀔 때마다 `onDraftChanged(checkout.toDraft())`.
- 로그아웃 때 `PendingPaymentStore` 도 비울 것 (다른 계정의 주문을 이어받지 않도록).
- 결제 성공 → `CheckoutState.afterOrderPlaced()` + `PaymentMethodStore.save` + 4.1.1 로 이동 + `coordinator.leaveTerminal()`.
