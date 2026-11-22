# PR: M7 베타 제외 기능 준비 — 쿠폰 · 이벤트 배너 · 내 메뉴 · 추천인 (규칙·저장소·플래그만, UI 없음)

**브랜치 이름 제안**: `feat/m7-beta-off-features`  (base: `main`, **M6 까지 머지된 뒤** — `FeatureFlags`·`SessionResetter`(M1)와 `OptionRules`(M1)를 씁니다)

## 요약

마이그레이션 계획의 M7("베타 제외 기능, 보류 — 디자인이 나올 때까지 UI 는 만들지 않음")을 했습니다. 새 디자인(Figma)에는 쿠폰·이벤트·내 메뉴·추천인 화면이 **없으므로 화면은 만들지 않았고**, RN 앱(`constants/coupons.ts`, `use-vouchers`, `use-banners`, `banner-store`, `use-my-menu`, `signup` 추천인)의 **규칙과 저장소를 같은 동작으로 이식**하고 **플래그(전부 꺼짐)** 로 막아 두었습니다. 꺼진 기능은 *서버에 묻지 않고, 로그아웃 때 비워지고, 앱에는 아무 것도 보이지 않습니다.* 전체 테스트 **621개 통과**(이번 추가 69개). 에뮬레이터에서 로그인·내 정보·로그아웃이 그대로 동작하는 것만 확인했습니다(보이는 변화 없음).

> 앞서 M6 PR 에 'M7 = 공통 마감'이라고 적었는데, 계획서의 M7 은 이쪽입니다. 접근성·기기 점검·성능·스토어 준비는 계획서대로 **M8**(BE 연동·안정화·릴리스)에 있습니다.

## 변경 내용

### `:domain` (`com.ormo.domain.promo`, `core`, `rules`)

| 파일 | 내용 |
|---|---|
| `core/Core.kt` | `FeatureFlags` 에 `allows(BetaFeature)` + `FeatureFlags.BETA`(전부 꺼짐). 꺼진 기능 = 메뉴 숨김 + 화면 진입 차단 + 서버 호출 안 함 |
| `promo/Vouchers.kt` | `Voucher`(교환권·금액권), `CouponRules.usability`(**서버보다 느슨하거나 같게**: 상태·유효기간 → 교환권은 다른 매장·판매 안 함·품절·장바구니에 없음 순 / 금액권은 다른 매장 전용·최소 주문 금액), 한 줄 설명, 남은 일수, `VoucherSelection`(종류별 1장 · 같은 종류는 교체 · 막힌 쿠폰은 선택 불가 · **장바구니가 바뀌어 못 쓰게 되면 자동 해제 + 안내**) |
| `promo/Banners.kt` | 링크 허용 목록(앱 안 경로 7개 / https 만), 우선순위 정렬, **'오늘 하루 보지 않기'**(기기 지역 시간 다음 자정, 깨진 값·25시간보다 먼 미래 값 무시, 배너별 독립 키), `AppEvent` |
| `promo/MyMenu.kt` | 저장된 세트를 **현재 메뉴에 맞춰 풀기**(옵션 보정·같은 줄 수량 합침·사라진 메뉴 세기), 예상 금액, 한도(줄 10 · 수량 9) |
| `rules/InputRules.kt` | `ReferralRules`: 영문/숫자 4~12자, 입력 중 필터, 빈 칸은 오류 아님, 플래그 꺼짐이면 요청에 안 실음 |
| `auth/AuthModels.kt` | `SignUpRequest.referralCode`(기본 null) — `RemoteAuthApi` 가 값이 있을 때만 `referralCode` 를 보냄 |

### `:data` (`com.ormo.data.promo`)

- `PromoApis.kt`: `VoucherApi` / `BannerApi` / `EventApi` / `MyMenuApi` 인터페이스 + `Remote*`(RN 앱이 **가정한** 경로: `GET /vouchers`, `GET /banners`, `POST /banners/{id}/events`, `GET /events`, `GET·PUT·DELETE /my-menus…`), 서버가 없는 자리용 `UnavailablePromoApi`
- `PromoParsers.kt`: 응답 검증. **모르는 종류의 쿠폰은 건너뜀**(구버전 앱이 목록을 통째로 못 보여주는 일 방지), 깨진 배너·이벤트 하나는 건너뛰고 나머지 살림, 링크는 허용 목록만
- `PromoRepositories.kt`: `VoucherRepository`(한 번 받은 뒤 실패하면 이전 목록 유지, **로그아웃 중 도착한 낡은 응답 버림**), `MyMenuRepository`(서버 성공 후 반영, 저장 중 늦게 온 낡은 조회가 덮어쓰지 않음), `EventRepository`, `BannerManager`(**세션당 1회 준비** · 우선순위 · 숨김 제외 · **이미지 3초 안에 안 받아지면 팝업 안 띄움** · 준비 중 로그아웃하면 결과 버림 · 집계 실패·저장소 오류는 삼킴)

### `:app`

- `AppContainer`: `featureFlags`(BETA), 저장소 4개 + `BannerManager`, **`SessionResetter` 에 쿠폰·내 메뉴·배너 등록**(로그아웃·만료 때 비움), `PrefsBannerHideStore`
- 서버·UI 연결은 하지 않음: API 는 `UnavailablePromoApi`(플래그가 꺼져 있어 호출되지 않음), 배너 이미지 확인은 자리 표시(`imageReady = { false }`)

## 테스트 (전체 621개)

- `:domain` 29개: 쿠폰 사용 가능 판정 전 경로·경계(0일 남음, 최소 금액 경계), 선택 교체·해제·자동 해제 안내, 배너 링크 허용 목록·자정 계산·시계 조작 방어, 내 메뉴 풀기·한도, 추천인 형식, 플래그
- `:data` 40개: 응답 검증(모르는 종류 건너뜀·깨진 항목·스키마 오류 9개), **플래그 꺼짐 = 호출 0회**, 쿠폰 낡은 응답 폐기·실패 시 이전 목록 유지, 내 메뉴 서버 성공 후 반영·덮어쓰기 방지, 배너(우선순위·숨김·이미지 3초·예외·세션당 1회·로그아웃 중 준비·집계 실패·계정별 숨김 16개), 이벤트, 가입 추천인 요청

## 확인이 필요한 것

- **디자인**: 쿠폰 관리·결제 화면의 쿠폰 선택, 이벤트 목록·팝업, 내 메뉴 관리·'내 메뉴로 주문', 가입 추천인 칸 — Figma 에 없어 화면 없음. 피드백 문서(261105)의 '내 메뉴' 제안(장바구니가 비었을 때 하단 `내 메뉴로 주문`)은 아직 팀 논의 대상
- **BE**: 위 API 경로·형태는 RN 앱이 가정한 것(스펙 없음, 이식 계획 §5.3 11~14·26~28번). 쿠폰은 주문 견적(`POST /orders/quote`)의 `vouchers`·`discounts` 응답과 짝이라 쿠폰을 켤 때 같이 정해야 함. 추천인 코드 정책(이벤트)도 미정
- **쿠폰 만료 판정**: RN 과 같게 '일 단위 올림'이라 방금(1시간 전) 지난 쿠폰은 앱에서는 아직 쓸 수 있다고 봄 — 서버가 최종 판단(느슨한 쪽이라 안전). 만료 시각이 '당일 끝'이면 문제없음
- 배너 숨김 키의 계정 구분: RN 은 고정 `anon` 이었는데 여기서는 로그인한 계정 키(`accountKey`)를 씀

## 의도적으로 하지 않은 것

- 모든 화면(쿠폰·이벤트·배너 팝업·내 메뉴·추천인 칸), 결제 화면·가입 화면에 연결 — 플래그를 켜는 시점에 디자인과 함께
- 서버 연결(`Remote*Api` 연결)·이미지 prefetch(Coil)·`BANNER_ROUTES` ↔ 새 앱 화면 경로 매핑 (M8)
- 닉네임(BE 결정 대기)·1월 1일 재인증(미구현 제안) — 계획서에서도 이번 범위 밖

## 켜는 방법 (나중에)

1. 디자인이 나오면 해당 화면을 만들고, `AppContainer.featureFlags` 를 `FeatureFlags(coupons = true, …)` 로 바꾼다 (빌드 설정으로 뺄 수도 있음)
2. 화면 진입 지점에서 `flags.allows(BetaFeature.X)` 를 먼저 검사 (꺼져 있으면 메뉴 숨김 + 진입 차단)
3. `UnavailablePromoApi` 를 `Remote*Api` 로 교체하고 BE 경로를 맞춘다
