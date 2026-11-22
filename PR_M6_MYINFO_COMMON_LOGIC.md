# PR: M6 내 정보·탈퇴·알림 설정·공통 오류 로직 (화면 제외)

**브랜치 이름 제안**: `feat/m6-myinfo-common-logic`  (base: `main`, **M5 가 머지된 뒤** — `DialogText`(M4)를 씁니다. M2 의 `AuthRepository`/`AuthApi` 를 확장하므로 M2 도 머지되어 있어야 합니다)

## 요약

마이그레이션 계획 M6(내 정보 · 공통 상태)에서 **화면을 뺀 규칙과 저장소**를 구현했습니다. 기준은 새 디자인 명세 5.1~5.5, 6.1 입니다. 전체 테스트 **518개 통과** (이번 추가 38개, 3회 재실행 동일).

## 변경 내용

### `:domain`

| 파일 | 내용 |
|---|---|
| `profile/MyInfoRules.kt` | 내 정보(5.1.1): **이름만** 표시(닉네임 없음), 행 6개, **알림 행은 OS 권한이 꺼져 있으면 "꺼져 있어요 · 켜면 픽업 번호를 바로 받아요"**, 로그아웃 다이얼로그 문구(5.1.2), 약관·정책 제목(개인정보 처리방침은 항상 열 수 있어야 함 — 앱스토어 심사) |
| `profile/WithdrawRules.kt` | 탈퇴(5.3): **진행 중 주문이 있으면 차단**("진행 중인 주문이 N건 있어요" / "주문이 끝난 뒤에 탈퇴할 수 있어요"), 비밀번호 확인 폼(틀리면 칸을 비우고 네트워크 오류는 입력 유지), 안내·완료 문구(주문·결제 기록 5년 보관) |
| `profile/NotificationSettings.kt` | 알림 설정(5.5.1): **누르는 즉시 반영·저장, 실패하면 그 스위치만 되돌림**, 저장 중 연타 시 마지막 값 우선, **OS 알림이 꺼져 있으면 스위치는 꺼진 채 비활성 + 안내(저장된 값은 보존)**, 설정이 동작에 주는 영향(`NotificationBehavior`: 주문 알림 끔 → 푸시 없음 / 실시간 현황 끔 → 진행 중 알림 시작 안 함) |
| `common/CommonErrors.kt` | 공통 오류(6.1): 네트워크 / 로그인 만료 / 점검·장애(끝나는 시각 "오후 3시에 점검이 끝나요") 문구, "일부만 실패" 스낵바 3초, **스켈레톤은 0.3초 안에 불러오면 띄우지 않음**, 내용이 있으면 스낵바·없으면 전체 화면 |

### `:data`

| 파일 | 내용 |
|---|---|
| `auth/AuthApi.kt`, `AuthErrors.kt`, `AuthRepository.kt` (M2 파일 확장) | `withdraw(password)`: 성공하면 **기기에 남긴 계정별 데이터 정리 콜백(`onWithdrawn`) → 로그아웃 + `AuthNotice.WITHDRAWN`**, 서버에 로그아웃 요청은 보내지 않음(계정이 이미 없음), 정리 콜백이 실패해도 탈퇴는 완료. 오류 매핑(비밀번호 틀림 / 진행 중 주문 / 네트워크 / 기타)은 `AuthErrors` 한 곳 |
| `remote/CommonErrorMapper.kt` | 예외 → 공통 오류 화면: 네트워크, 502/503/504 또는 점검 코드 → 점검·장애(종료 시각 있으면 KST 로 변환), 그 밖의 오류는 null(화면별 처리). 로그인 만료는 `AuthRepository.expired` 가 정함 |
| `profile/NotificationSettingsRepository.kt` | `NotificationSettingsApi`(서버 인터페이스) + 저장소: 즉시 저장(한 번에 하나씩, 저장 시점의 화면 값을 보내 마지막 값이 이김), 실패 시 되돌림, OS 권한 재확인 |

## 테스트 (전체 518개 통과)

- `ProfileRulesTest`(내 정보·탈퇴·알림 설정 상태 17개), `CommonErrorsTest`(6개)
- `AuthWithdrawTest`(6): 탈퇴 성공 시 정리·로그아웃·안내, 비밀번호 틀림, 진행 중 주문 거절, 네트워크, **정리 실패해도 완료**, 일반 로그아웃은 서버 폐기 유지
- `CommonErrorMapperTest`(5), `NotificationSettingsRepositoryTest`(6: 즉시 저장, 실패 되돌림, OS 꺼짐, 연타)

## 확인이 필요한 것 (코드에 가정 표시)

**BE**
- 탈퇴 API(`POST /me/withdraw { password }` 로 가정), 비밀번호 틀림·진행 중 주문 오류 코드, 탈퇴 시 서버가 푸시 토큰·세션을 폐기하는지
- 알림 설정 API(조회·저장, 전체 객체 저장으로 가정), 푸시 발송 시 서버가 이 설정을 보는지
- 점검 응답 형식(503 / `MAINTENANCE` 코드 / `maintenanceEndsAt` ISO 시각)

**디자인**
- 5.5.1 **주문 알림을 끄면 실시간 현황 스위치도 같이 비활성인지** (지금은 독립)
- 5.3.1 안내 문구의 정확한 표기 (보관 기간 안내 문장은 명세의 개발 메모를 풀어 쓴 것), 원문의 오탈자("주셉서")는 "주셔서"로 바로잡음
- 6.1.3 점검 종료 시각이 지난 뒤의 표시

## 의도적으로 하지 않은 것

- Compose 화면 전부 (내 정보, 로그아웃·탈퇴·차단 다이얼로그, 알림 설정, 약관 표시, 오류 화면 4종, 스켈레톤 shimmer)
- OS 알림 권한 조회(`NotificationManagerCompat`)·설정 앱 열기·Custom Tabs: 플랫폼 코드 (`:app`)
- 비밀번호 변경(5.2.1)은 M2 에서 이미 구현됨

## 연결할 때 참고 (:app 이 생긴 뒤)

- 탈퇴 정리 콜백(`onWithdrawn`)에서 한 번에: `RecentSearchRepository.deleteAccount`, `SelectedStoreStore.clear`, 알림 안내 횟수 초기화, `PendingPaymentStore.clear`. 로그아웃(`SessionResetter`) 쪽에는 **등록하지 않음**(계정별 데이터라 남김, M3·M5 참고).
- 탈퇴 화면 진입 시 `OrderRepository.refreshActive()` → `WithdrawRules.gate(active.size)`. 차단되면 5.3.2 다이얼로그([주문 보기] → 내 주문).
- 모든 화면의 오류 처리는 `CommonErrorMapper.map(e)` → 결과가 null 이면 화면별 처리, 아니면 `CommonErrorRules.presentation(hasContent)` 로 전체 화면/스낵바 결정.
- 앱 전체 `AuthStatus.SIGNED_OUT` + `AuthNotice.SESSION_EXPIRED` → `CommonError.SessionExpired` 화면, `AuthNotice.WITHDRAWN` → 로그인 화면에서 완료 안내.
