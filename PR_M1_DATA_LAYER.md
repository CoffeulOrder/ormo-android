# PR: M1 후반 — 네트워크·세션 갱신·메뉴 캐시/동기화 (`:data` 모듈)

**브랜치 이름 제안**: `feat/m1-data-layer`  (base: `main`)

## 요약

마이그레이션 계획의 M1 후반입니다. 안드로이드 의존이 없는 JVM 모듈 `:data` 를 추가해, RN 앱(`CoffeulOrder`)의
네트워크·세션·메뉴 캐시 동작을 Kotlin 으로 옮기고 테스트로 고정했습니다. 앞선 커밋(`b0464aa`)의 `:domain` 위에 얹힙니다.

- **HTTP 클라이언트** (`ApiClient`): Bearer 토큰, 오류 3종 구분, 401 → 갱신 1회 → 같은 요청 재시도
- **세션 관리** (`SessionManager`): 동시 401 에도 갱신 1회, 갱신 결과(네트워크 오류 / 서버 거절)를 구분해 세션 유지·종료
- **메뉴 JSON 검증** (`MenuSchema`): 깨지면 전체 거부, 옵션 그룹·선택지 id 중복 검증
- **메뉴 캐시** (`FileMenuCache`): 파일 1개 `{schema, payload}` + 임시 파일 → rename 교체
- **메뉴 동기화** (`MenuRepository`): 30초 규칙, 진행 중 sync 합치기, force 직렬화, 학교 변경 중 낡은 응답 폐기, 실패 시 기존 유지

## 변경 내용

| 경로 | 내용 |
|---|---|
| `data/.../remote/AppError.kt` | `Network` / `Api(status, code, body)` / `Schema(where)` |
| `data/.../remote/ApiClient.kt` | OkHttp 기반 클라이언트. 10초 타임아웃, BE 응답 봉투 `data` 언랩, 비 JSON 응답 → `BAD_RESPONSE` |
| `data/.../remote/SessionManager.kt` | 토큰 보관(메모리), `Mutex` 로 갱신 1회, `expired` 이벤트, 리프레시 토큰 회전 콜백 |
| `data/.../local/SessionStore.kt` | 자동 로그인 세션 저장소 **인터페이스** + 인메모리 구현 (Keystore 구현은 `:app`) |
| `data/.../menu/MenuSchema.kt` | 메뉴/버전/품절 파서 + 캐시용 인코더 |
| `data/.../menu/MenuApi.kt` | 메뉴 API 인터페이스 + `RemoteMenuApi` |
| `data/.../menu/MenuCache.kt` | `MenuCache` 인터페이스 + `FileMenuCache` |
| `data/.../menu/MenuRepository.kt` | 동기화 상태 머신, 품절(메모리 전용) |
| `domain/.../model/Menu.kt` | `Cafe`, `MenuPayload` 추가 |
| `settings.gradle.kts` | `:data` 포함 |

## 테스트

`./gradlew test` — **153개 통과** (`:domain` 83 + `:data` 70). `:data:test` 를 3회 반복해 불안정한 테스트가 없는 것도 확인했습니다.

- `ApiClientTest` (29, MockWebServer): 토큰 부착, 401 → 갱신 → 재시도(같은 `Idempotency-Key`), 동시 6요청에서 갱신 1회,
  갱신 거절 → 세션 삭제 + `expired`, 갱신 중 네트워크 오류/5xx → 세션 유지, 로그인 401 은 갱신 안 함, 시간 초과, 봉투 언랩
- `MenuRepositoryTest` (20): 30초 규칙, 합치기, force 직렬화, 버전 같음/다름, 캐시 없음/깨짐/저장 실패, 학교 변경 중 낡은 응답, 실패 시 유지, retry, 품절
- `MenuSchemaTest` (12), `FileMenuCacheTest` (9: 깨짐·잘림·검증 실패·옛 스키마·purge·파일명 안전)

## 리뷰 포인트 (RN 과 **의도적으로 다르게** 한 곳)

1. **Retrofit 대신 OkHttp 직접 사용** — 응답 봉투 언랩, 깨진 항목 건너뛰기, 갱신 결과 구분이 전부 맞춤 처리라 Retrofit 인터페이스가 얻는 이득이 적고,
   BE 계약이 아직 흔들려 얇은 계층이 바꾸기 쉽습니다. (계획서의 Retrofit 제안에서 바뀐 점)
2. **`Authenticator` 대신 코루틴에서 직접 재시도** — OkHttp 스레드에서 `runBlocking` 을 하지 않고, 갱신 실패 종류를 호출자에게 그대로 전달하려고.
3. **갱신 실패 분류를 좁힘** — RN 은 네트워크 오류가 아니면 전부 "거절"로 보고 세션을 지웠습니다.
   여기서는 4xx(408·429 제외)만 거절로 보고, **5xx·429·408·네트워크 오류는 세션 유지**입니다 (서버 장애로 로그아웃되는 것을 방지).
4. **이미지 URL 이 빈 문자열이면 "없음"** — RN 은 메뉴 전체를 거부했습니다. 디자인 2.1.1 이 "imageUrl 은 비워 둘 수 있게" 라고 해서 완화.
5. **id 는 문자열/정수 모두 허용**(내부는 String) — BE 가 숫자 id 를 써도 파서를 바꾸지 않도록.
6. **낡은 학교의 캐시는 쓰기 전에 검사** — RN 은 쓰고 나서 확인했습니다. 학교 변경 직후 이전 학교 파일이 되살아나는 경합을 막습니다.
7. **비 JSON 판정을 엄격하게** — kotlinx 는 따옴표 없는 최상위 글자(`<html>oops</html>`)도 값으로 받아 줘서, 객체/배열/null 이 아니면 `BAD_RESPONSE` 로 처리합니다
   (테스트로 발견).
8. **응답 봉투 판정은 휴리스틱** — 최상위 객체에 `data` 와 `timestamp/status/code/message` 중 하나가 같이 있을 때만 언랩. BE 계약 확정 후 `ApiClient.unwrap` 한 곳만 고치면 됩니다.

## 이 PR 에 없는 것 (다음 작업)

- 암호화 세션 저장소(Android Keystore + DataStore) 구현, 이미지 prefetch, 최근 검색어/마지막 매장 저장 — Android 모듈(`:app`)이 생기는 M0 이후
- 주문·인증·영업 상태 등 나머지 API 클라이언트와 파서 (각 화면 마일스톤에서)
- 경로·응답 형태는 RN 앱이 **가정한 것** 그대로입니다 (`BE_COMPARISON.md` 참고). BE 확정 시 `RemoteMenuApi`/`MenuSchema` 수정.

## 확인 방법

```bash
./gradlew test
```

JDK 8 로도 실행됩니다 (Android 모듈이 추가되면 JDK 17 필요).
