# Together on the Road

각자의 차에서, 하나의 여정으로. Pleos 기반 그룹 이동 프로토타입 **v0.3.0**.

**현재 상태: 개발 중인 검증용 프로토타입입니다. Pleos 화면의 패널 터치/전환 문제를 수정 중이며, 전체 사용자 흐름의 실행 검증은 완료되지 않았습니다.**

실제 지도·기기 위치·도로 경로·인증된 그룹 상태를 사용합니다. 기본 가상 참가자, 임의 ETA, 모의 지도, 고정 추천 장소, 모의 내비 성공 응답을 제거했습니다. 위치나 경로를 얻지 못하면 대기·오류로 표시합니다.

## 구현한 기능

- MapLibre Native 12.0.1 + OpenFreeMap의 실제 도로·지명 지도. 길게 누른 장소를 목적지 또는 합류 장소로 선택.
- Android GPS/Network 위치 및 Pleos FusedLocation 1.1.3. 원래 관측 시각을 유지하고 30초가 지나면 오래된 위치로 표시.
- OSRM의 실제 도로 경로·거리·예상 소요 시간. 교통정보는 포함하지 않으며 실패 시 숫자를 만들어 표시하지 않음.
- 최대 8대 그룹 생성·8자리 초대 코드 참여, 명시적 공유 동의, 단말별 토큰 인증, 실시간 위치 공유(2.5초 polling).
- 지도 위 참여자 프로필, 참여자 선택/그룹 전체 보기, 차량별 남은 도로 거리·ETA·세로 진행률. 진행률은 각 차량의 최초 유효 경로 거리가 기준.
- 지도에서 합류 장소 제안 → 전원 동의 → 각 차량이 직접 적용. 서버가 실제 경유 도로 경로를 계산한 후에만 적용 완료. 합류점 100m 이내의 새 위치가 수신되면 경유 지점을 제거.
- 휴식·지연·출발 준비 메시지, 현재 위치와 도로 경로가 목적지 200m 안일 때만 도착 확인. 계산된 도착 예상이 5분 이상 늦어지면 지연 알림.
- 탈퇴 시 로컬 위치 전송 즉시 중단, 서버 멤버·좌표·자격 삭제. 그룹은 4시간 후 만료. 위치·토큰을 파일에 저장하지 않음.
- Pleos의 계기/차량 화면과 상단 상태·하단 공조 영역을 유지하는 지도+우측 그룹 패널. 사용자가 종료하거나 알림의 연결 종료를 누르면 지도·위치·SDK 수명주기를 해제.
- 명시적으로 선택한 목적지만 NaviHelper 3.0.5로 내장 내비에 전달. 탐색 완료와 목적지 좌표 콜백을 확인해 성공 판단.

## 표시 방식의 정확한 범위

공개 NaviHelper에는 OEM 지도의 마커 삽입·좌표 투영 API가 확인되지 않았습니다. **현재 지도는 내비게이션 영역에 표시하는 앱 소유 MapLibre 지도이며 OEM 지도 엔진을 수정한 결과가 아닙니다.** 내장 내비 목적지 요청은 별도의 실제 SDK 기능입니다. 앱 지도에서 합의한 합류 경유지가 OEM 내비에 자동 삽입되지는 않습니다.

Pleos 에뮬레이터에서 지도 타일 수신과 실제 위치 SDK 연결을 확인했습니다. 에뮬레이터 GPS 입력은 테스트 도구에서 제공하며 제품 코드에 경로/참가자를 자동 생성하는 재생 기능이 없습니다. 실제 차량·앱 마켓 배포 검증은 별도입니다. Vehicle State 기반 주행 제한, 음성 기능, 실시간 교통, 운영용 인프라는 구현 범위에 포함하지 않습니다.

## 빌드

JDK 21, Android SDK Platform 35/Build Tools 34+, Gradle Wrapper 8.9, AGP 8.7.3, Kotlin 2.0.21. 로컬 `local.properties`에 SDK 경로를 설정합니다(커밋 금지).

```sh
./gradlew :core:test :app:assembleReplayDebug :app:assemblePleosDebug
./gradlew :app:lintReplayDebug :app:lintPleosDebug
```

| Variant | 실제 위치 제공자 | APK |
|---|---|---|
| `replayDebug` (기존 식별자 유지) | Android GPS/Network | `app/build/outputs/apk/replay/debug/app-replay-debug.apk` |
| `pleosDebug` | Pleos FusedLocation + 실제 Android 위치 fallback | `app/build/outputs/apk/pleos/debug/app-pleos-debug.apk` |

`replay`는 기존 패키지 업데이트를 위한 flavor 이름일 뿐 가상 재생을 뜻하지 않습니다. 두 APK 모두 실제 지도·그룹 서버를 사용하며 debug 서명입니다.

## 실행

```sh
cd server
npm test
npm start
```

Node.js 22 이상, 외부 npm 의존성 없음. 기본 서버 `127.0.0.1:8787`, Android Emulator에서 `http://10.0.2.2:8787`. 기기에서는 `adb reverse tcp:8787 tcp:8787` 후 연결 설정을 `http://127.0.0.1:8787`로 지정할 수 있습니다. 서로 다른 실제 단말은 동일한 서버 주소에 연결해야 합니다. LAN 바인딩은 의도적으로 `HOST=0.0.0.0 npm start`를 실행할 때만 허용합니다. release는 HTTPS가 필요합니다.

1. APK 실행 → 지도 표시/정밀 위치 권한 허용. Pleos 이미지에 표시 권한 설정 화면이 없으면 테스트 AVD에서 개발자가 appops로 구성해야 합니다.
2. 실제 위치 수신 후 지도에서 목적지를 길게 눌러 선택합니다. 경로 서버와 타일 서버에 인터넷 연결이 필요합니다.
3. 그룹 만들기 → 공유 동의 → 초대 코드 전달. 다른 설치 단말은 같은 서버에서 코드로 참여합니다.
4. 지도에 참여 단말의 실제 위치만 나타납니다. 위치가 없으면 임의 위치에 표시하지 않습니다.
5. 합류 장소 제안·투표·개별 적용, 메시지와 탈퇴를 사용할 수 있습니다.

공개 OSRM은 개발 검증용입니다. 요청은 직렬화해 초당 1회 이내, 동일 경로는 60초 캐시합니다. 운영 시 `ROUTING_URL`로 자체/계약 서버를 지정해야 합니다. 공개 Nominatim을 차량 위치 기반 장소 검색에 사용하지 않습니다.

- [Pleos 연동](docs/PLEOS.md)
- [표시 방식](docs/NAVIGATION_OVERLAY.md)
- [API와 데이터 수명](docs/API.md)
- [개발·검증](docs/DEVELOPMENT.md)
- [검증 기록](docs/VALIDATION.md)

개인정보, 공모전 동의서, SDK 자격 증명은 저장소에 포함하지 않습니다.
