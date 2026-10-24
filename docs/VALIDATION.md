# 검증 결과

2026-10-24 KST, macOS arm64, JDK 21, Gradle 8.9, AGP 8.7.3, Android Platform 35 및 Build Tools 34.0.0.

| 검사 | 결과 |
|---|---|
| `:core:test` | 5개 통과, 실패 0 |
| `node --test` (`server/`) | 10개 통과, 실패 0 |
| `:app:assembleReplayDebug` | APK 생성 성공 |
| `:app:assemblePleosDebug` | 실제 NaviHelper 3.0.5 의존성으로 APK 생성 성공 |
| `:app:lintReplayDebug`, `:app:lintPleosDebug` | 작업 성공, 오류 0; 백업 호환성 경고가 보고됨 |
| `node server/src/demo.mjs` + 로컬 서버 | 아래 세 차량 흐름 완료 |
| AVD UI, Pleos SDK 런타임, 실차, 성능·부하 | 미검증 |

CLI 출력:

```text
Before delay: 합류 후보 1 · 가상 휴게소 / score 55
B delayed +25min: 합류 후보 2 · 가상 주차장 / score 70
REPLAY apply states: APPLIED, APPLIED, APPLIED
Session ended; server locations and tokens erased.
```

이 결과는 가상 데이터로 수행한 프로토타입 검증입니다. 실제 지도 탐색·위치·차량 정차 감지·음성 및 내장 내비 실동작을 증명하지 않습니다. 전용 Pleos AVD가 이 환경에 구성되지 않아 런타임 검증은 별도 수행해야 합니다.

두 APK는 debug 빌드이며 Gradle 산출물은 `app/build/outputs/apk/`에 있습니다. 저장소에는 SDK 바이너리·로컬 SDK 경로·개인정보·키·debug keystore를 커밋하지 않습니다. 사용자는 README 절차로 동일 소스를 빌드할 수 있습니다.
