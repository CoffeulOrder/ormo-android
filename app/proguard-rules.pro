# R8 (릴리스) 규칙. 확인: ./gradlew :app:assembleRelease -Pormo.allowIncompleteRelease=true
#
# - OkHttp·Okio 는 소비자 규칙을 가지고 있다. 플랫폼별 선택 의존(Conscrypt·BouncyCastle·OpenJSSE)은 없어도 되는 경고라 무시한다.
# - kotlinx-serialization 은 JsonElement 만 쓰고 @Serializable 클래스는 없다 → 리플렉션 유지 규칙이 필요 없다.
# - Compose·AndroidX 는 라이브러리 규칙으로 충분하다.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 오류 보고용: 줄 번호는 남기고 원래 파일 이름은 숨긴다 (스택 트레이스를 매핑 파일로 되돌릴 수 있다)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
