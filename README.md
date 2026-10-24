# Together on the Road

각자의 차에서, 하나의 여정으로 — 현대자동차그룹 차량용 앱 개발 공모전 프로토타입.

**v0.1은 정차한 개발 벤치 전용입니다.** Android 앱에서 3대 차량의 그룹 이동·지연·합류 추천·개별 승인을 시연하고, 선택적으로 서버에 여러 단말을 연결합니다. 실제 주행에 사용하지 마세요.

## 현재 구현

- **Replay Android 앱:** 서울·수원·인천 3대의 가상 ETA, B 차량 +25분 지연, 비용 기반 합류 후보 재정렬, 차량별 승인·모의 내비 결과, 종료 시 상태 삭제.
- **그룹 서버와 Android 클라이언트:** 생성·초대 코드 참여(최대 3대), 멤버 토큰 인증, 2초 간격 REST 위치/ETA 공유, 제안과 자신의 차량 승인, 결과 보고, 철회·종료·TTL 삭제.
- **Pleos 빌드:** 공식 Maven의 **NaviHelper 3.0.5**를 직접 의존합니다. `initialize` → 리스너 → `requestRoute` → 탐색 완료와 목적지 좌표 콜백 → `removeListener`/`release`를 구현합니다. 함수 반환만으로 성공 처리하지 않습니다.
- JVM 핵심 상태 테스트와 Node HTTP/도메인 테스트.

| 구성 | 데이터와 동작 | 실제 차량 검증 |
|---|---|---|
| `replayDebug` | 위치·ETA·장소·내비 응답 모두 REPLAY | 해당 없음 |
| `pleosDebug` | 위치·ETA·후보는 REPLAY, A/현재 단말의 승인된 목적지 요청은 실제 NaviHelper | Pleos 전용 AVD 및 실차 검증 필요 |
| 서버 | 가상 위치만 수신; 메모리 저장; 토큰 인증; REST polling | 차량 SDK와 독립 |

**미구현:** 실제 FusedLocation, Vehicle State, STT/TTS, 실제 지도·교통 API, LLM, 다수결 투표, 초대 링크, 주행 이력, 재연결 이벤트 큐, WSS, 앱 마켓 배포/서명. 합류 후보와 비용은 고정 fixture입니다. AI 모델이 계산한 추천으로 표시하지 않습니다. 앱의 정차 스위치는 실제 차량 상태가 아닌 벤치 확인 입력입니다.

## Android 빌드

필요 환경: JDK 21, Android SDK Platform 35, SDK Build Tools 34.0.0 이상, Android Studio. Wrapper는 Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21을 고정합니다. minSdk 28이며 APK의 targetSdk 35는 벤치용 설정입니다. 마켓 제출 전 플랫폼 배포 정책을 별도 확인해야 합니다.

1. Android Studio에서 저장소 루트를 엽니다. SDK 경로를 설정하거나 로컬 `local.properties`에 `sdk.dir=...`를 작성합니다(커밋 금지).
2. `replayDebug` variant를 선택해 일반 Android API 28+ 기기/AVD에서 실행합니다.
3. Pleos 전용 AVD 확보 후 `pleosDebug`로 별도 설치합니다. 일반 AVD에서 Pleos 서비스가 동작한다고 가정하지 않습니다.

```sh
./gradlew :core:test :app:assembleReplayDebug :app:assemblePleosDebug
./gradlew :app:lintReplayDebug :app:lintPleosDebug
```

생성 위치:

- `app/build/outputs/apk/replay/debug/app-replay-debug.apk`
- `app/build/outputs/apk/pleos/debug/app-pleos-debug.apk`

`replayDebug`와 `pleosDebug`의 applicationId는 각각 `io.togetherroad.app.replay`, `io.togetherroad.app.pleos`입니다. **debug APK이며 공모전용 서명된 release APK는 아직 아닙니다.**

## 3대 로컬 데모

앱 시작 → 위치 공유 동의 후 데모 시작 → 합류 후보 제안 → B 차량 지연 주입 → 후보 재제안 → 차량 A/B/C에서 각각 승인 → 결과 확인 → 종료.

A는 현재 단말이며 선택한 flavor의 내비 어댑터를 사용합니다. B/C는 같은 화면에서 조작하는 가상 차량이며 항상 모의 응답입니다. Pleos flavor에서는 승인한 A의 요청이 **실제 내비게이션을 변경**할 수 있습니다. 가상 테스트 좌표에 대한 정차 벤치 검증에만 사용하세요. 두 번째 SDK 요청은 늦은 콜백 혼동을 막기 위해 앱 재시작 전까지 제한됩니다.

## 서버와 3개 독립 클라이언트

Node.js 22 이상. 외부 패키지를 설치할 필요가 없습니다.

```sh
cd server
npm test
npm start
# 다른 터미널: 임시 그룹을 생성하고 세 차량 요청을 재현한 뒤 삭제
node src/demo.mjs
```

Android 앱에서 “서버에 연결해 그룹 만들기 / 참여”를 선택합니다. 일반 Android Emulator는 호스트 서버를 `http://10.0.2.2:8787`로 접근합니다. 기본 서버는 `127.0.0.1:8787`에만 바인딩합니다.

1. 첫 단말에서 표시 이름 A와 공유 동의를 입력해 그룹을 만듭니다.
2. “초대 정보 복사”로 세션 ID와 코드를 얻습니다.
3. 두 번째·세 번째 단말은 같은 서버 URL과 세션 ID/코드로 참여합니다.
4. 각 단말이 **가상** 위치·ETA를 전송합니다. 한 단말에서 제안하면 모두의 화면에 표시됩니다.
5. 각 운전자는 자신의 단말에서 승인해야 합니다. 서버는 다른 차량을 대신 승인할 API를 제공하지 않습니다.
6. 철회는 로컬 전송·화면 캐시를 먼저 중단한 뒤 서버 삭제를 요청합니다. 실패하면 종료 버튼으로 재시도합니다.

`adb reverse tcp:8787 tcp:8787`를 설정한 기기는 `http://127.0.0.1:8787`을 사용할 수 있습니다. LAN 테스트는 개발자가 의도적으로 `HOST=0.0.0.0 node src/http.mjs`를 선택한 경우에만 가능합니다. 인터넷 공개용 서버가 아니며 운영 배포 전 TLS, 서비스 인증, 저장·삭제 정책, 부하 검증이 필요합니다. Release는 cleartext HTTP를 허용하지 않습니다.

## 문서

- [Pleos 연동 근거와 검증 절차](docs/PLEOS.md)
- [API 계약과 데이터 수명](docs/API.md)
- [테스트와 다음 개발 단계](docs/DEVELOPMENT.md)

사용자 개인정보나 공모전 동의서, SDK 자격증명은 이 저장소에 포함하지 않습니다.
