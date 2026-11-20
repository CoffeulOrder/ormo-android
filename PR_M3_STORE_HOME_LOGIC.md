# PR: M3 매장·메뉴 홈·검색 로직 (화면 제외)

**브랜치 이름 제안**: `feat/m3-store-home-logic`  (base: `main`, M2 가 머지된 뒤 — 이 PR 은 M2 코드를 건드리지 않음)

## 요약

마이그레이션 계획의 M3(매장 + 메뉴 홈)에서 **화면과 서버 연동을 뺀 규칙·저장 로직**을 구현했습니다. Android SDK 가 아직 없어 Compose 화면은 뒤로 미루고,
화면이 그대로 가져다 쓸 순수 함수/상태(`:domain`)와 저장소(`:data`)를 테스트와 함께 만들었습니다. 기준은 새 디자인 명세 1.1, 1.2, 2.1.1~2.1.3 입니다.

메뉴 캐시 동기화(`MenuRepository`)는 M1 에서 이미 끝나 있어 이번에는 손대지 않았고, 이 PR 의 `MenuHomeRules.applySoldOut` 이 그 결과(메모리 품절)를 화면용 목록에 합칩니다.

## 변경 내용

### `:domain`

| 파일 | 내용 |
|---|---|
| `store/StoreModels.kt` | `StoreStatus`(영업 중 / 잠시 쉼 / 영업 종료 + 재개 시각), `Store`, `StoreInfo`, `SellerInfo`, `DayHours` |
| `store/StoreRules.kt` | 매장 목록 규칙(메뉴 0개 제외, 영업 종료 흐리게, 상태 라벨), **주문 불가 안내 문구**(2.1.2), **매장 변경 시 장바구니 경고**(1.1.2), **시작 목적지 결정**(기억한 매장 → 메뉴 / 없으면 매장 선택, 가입 직후 뒤로가기 숨김), 거리·도보 시간(위치 권한 없으면 숨김) |
| `store/StoreInfoRules.kt` | 매장 정보(1.2.1): 요일별 운영시간(월~일), 공지 없으면 숨김, 판매자 정보 6줄, 신고번호 없으면 '준비 중', **필수 고지 문구 상수** |
| `home/MenuHomeRules.kt` | 카테고리 묶음/탭(데이터 순서, 빈 카테고리 숨김, **최근 주문 탭은 이력 있을 때만 맨 앞**), 탭↔스크롤 위치 계산(`HomeLayout`), 메뉴 줄 상태(품절은 흐리고 안 눌림 / 주문 불가여도 줄은 열리고 담기만 꺼짐), 장바구니·주문 현황 바 표시, **수량 뱃지**, 메모리 품절 덮어쓰기 |
| `search/SearchRules.kt` | 검색 화면 내용 결정(시작 / 결과 / 결과 없음), 많이 찾는 메뉴(서버 순위 → 현재 메뉴, 5개/3개), 결과 없음 제목(긴 검색어 말줄임), **최근 검색어 연산**(최대 10, 같은 검색어는 맨 앞으로, 공백·대소문자 무시 중복 제거, 개별/전체 삭제), 디바운스 0.2초 상수 |

### `:data`

| 파일 | 내용 |
|---|---|
| `store/StoreRepository.kt` | `StoreDirectory`(서버 인터페이스, **Fake 로 먼저**), `SelectedStoreStore`(마지막 매장 기억, 계정별), `StoreRepository`: 목록/정보 로드(네트워크 vs 기타 실패 구분, 취소는 삼키지 않음), 시작 목적지 결정(목록을 못 받아도 기억한 매장이 있으면 메뉴 캐시로 열리게 메뉴 홈) |
| `search/RecentSearchRepository.kt` | `RecentSearchStore` 인터페이스 + 메모리 구현, `RecentSearchRepository`(읽고-고치고-쓰기를 Mutex 로 직렬화, 변경 없으면 저장 안 함) |
| `search/FileRecentSearchStore.kt` | 파일 구현: `{schema, accounts:{계정:{매장:[검색어]}}}` 임시 파일 → rename 원자 교체, 깨진 파일/옛 스키마/이상한 항목은 '기록 없음'으로 읽음 |

## 테스트

전체 **328개 통과** (M2 258 + 이번 70), `cleanTest test` 로 3번 돌려 모두 같은 결과.

- `StoreRulesTest`: 주문 불가 문구(같은 날/내일/며칠 뒤/자정 넘김/시각 없음·지남), 매장 변경 경고, 진입 결정, 거리·도보 시간
- `StoreInfoRulesTest`, `MenuHomeRulesTest`(탭 스크롤 인덱스·최근 주문·수량 뱃지·품절 덮어쓰기), `SearchRulesTest`(초성·띄어쓰기 검색 결과 포함)
- `StoreRepositoryTest`, `RecentSearchRepositoryTest`(메모리와 파일 구현을 같은 시나리오로, 동시 추가·깨진 파일 포함)

## 확인이 필요한 것 (코드에 `TODO(spec …)` 또는 가정으로 표시)

**BE 에 확인**
- 매장 목록·상태·정보 API 계약이 아직 없습니다 → `StoreDirectory` 뒤에 숨김. 확정되면 `RemoteStoreDirectory` 하나만 추가 (영업 상태 표현, 재개 시각의 시간대, 대기 건수 노출 여부 C-14-1 포함).
- '많이 찾는 메뉴'(최근 7일 주문 상위 5개)와 '최근 주문 메뉴'(이 매장에서 내가 주문한 메뉴), 주문 이력 건수를 어떤 API 로 받을지.

**디자인에 확인 (Figma)**
- 1.2.1 판매자 정보 **6줄의 실제 항목·순서** (지금은 상호/대표자/사업자등록번호/통신판매업 신고번호/주소/연락처로 가정)
- 1.1.2 장바구니 경고의 개수 문구 (지금은 "담아 둔 메뉴 N개가 사라져요"), 2.1.3 긴 검색어를 몇 글자에서 줄일지 (지금 12자)
- 1.1.1 도보 시간 계산식 (지금 80m/분 가정)
- 주문 이력은 있는데 현재 메뉴에 남은 게 없을 때의 '최근 주문' 탭 (지금은 빈 묶음 대신 탭 숨김)

## 의도적으로 하지 않은 것

- Compose 화면 전부 (매장 선택, 메뉴 홈, 매장 정보, 검색) — M0 환경(Android Studio + JDK 17) 필요
- 이미지 prefetch: 디자인에서 사진 버전은 "MVP 여유 시" 옵션이라 보류 (`MenuRepository.onUpdated` 훅은 M1 에서 이미 준비됨)
- 위치 권한 요청/현재 위치 조회: 플랫폼 코드. 규칙(`distanceLabel`)은 사용자 좌표가 없으면 null 을 돌려줌
- `SelectedStoreStore` 의 DataStore 구현: `:app` 에서 인터페이스 구현 (Fake/메모리 구현은 포함)

## 연결할 때 참고 (:app 이 생긴 뒤)

- 로그아웃 때 `SessionResetter` 에 **등록하지 않는 것**: 마지막 매장(`SelectedStoreStore`), 최근 검색어(`RecentSearchStore`) — 둘 다 계정별로 남기는 게 명세 의도. 회원 탈퇴(5.3) 때만 `RecentSearchRepository.deleteAccount` / `SelectedStoreStore.clear`.
- 검색 화면 ViewModel: 입력은 `SearchRules.DEBOUNCE_MS` 디바운스 후 `SearchRules.view(...)`, **최근 검색어 저장은 결과를 눌렀을 때/검색 키를 눌렀을 때만** (글자마다 저장하지 않음).
- 주문 불가 상태는 시계가 흐르면 바뀔 수 있으므로(재개 시각 도달) 화면은 `StoreRules.availability(status, now)` 를 1분 정도 간격으로 다시 계산하거나 서버 상태 갱신을 받을 때 다시 계산.
