# Pleos 연동 기록

2026-10-24 공식 문서와 Maven 배포를 확인했습니다. 문서의 예제보다 배포된 artifact의 실제 타입을 빌드 기준으로 삼습니다.

## 이번에 구현한 연동

공식 repository: `https://nexus-playground.pleos.ai/repository/maven-releases/`

- `ai.pleos.playground:NaviHelper:3.0.5`
- SDK POM에서 확인한 compile 의존성: `ai.pleos.playground:navi-data:3.0.4`
- 런타임 의존성: `navi-aidl:3.0.1` 등은 Gradle이 전이 의존성으로 받습니다.
- 공식 AAR의 `NaviHelper`, `NaviHelperEventListener`, `RouteInfo` 타입과 Kotlin 명명 인자를 확인했습니다.
- `pleos.car.permission.NAVI_ROUTE`, `pleos.car.permission.NAVI_ROUTE_SEARCH`만 Pleos flavor에 선언합니다. custom map 권한은 요청하지 않습니다.

`PlatformNavigation`은 SDK 초기화 성공 후 리스너를 등록합니다. 사용자 승인 후 `requestRoute(RouteInfo(...))`를 호출합니다. `onRoutingCompleted`에서 목적지 조회를 요청하고 `onDestinationInfo`의 좌표가 요청 좌표와 일치하는지 확인한 뒤 성공을 보고합니다. 오류·15초 timeout은 실패로 처리합니다. 생명주기 종료 시 리스너와 SDK를 해제합니다.

현재 SDK 콜백에는 앱이 부여한 요청 ID가 없으므로 gateway 인스턴스당 요청은 한 번으로 제한합니다. 재시도·취소·경유지 추가 및 정확한 콜백 상관관계는 다음 단계입니다. 현재 `requestRoute`는 **목적지를 변경**하며 경유지 삽입은 구현 전입니다. 사용자에게 “경유지 적용 완료”라고 주장하지 않습니다.

## 실행 전 확인

1. Pleos Playground의 Workspace에서 프로젝트와 SDK 상태를 확인합니다. 계정 비밀값을 코드나 이슈에 붙여넣지 않습니다.
2. 전용 Pleos Connect AVD 이미지를 확보하고 호스트 CPU 지원을 확인합니다.
3. Virtual Console을 구성하고 cold boot로 실행합니다. 일반 Android AVD는 Pleos 서비스 대체 수단이 아닙니다.
4. 정차 벤치에서 APK를 설치하고 SDK 초기화·권한·서비스 연결을 확인합니다.
5. 내비 앱 단독 호출 성공, 앱에서 승인 후 실제 목적지 변경, 탐색 실패, timeout, 종료를 확인합니다.
6. Kotlin 콜백·화면·내비 앱을 함께 기록합니다. 현재 저장소의 빌드 성공은 이 검증을 대신하지 않습니다.

## 다음 SDK

- FusedLocation: `isSupported` → 권한 → `initialize` → nullable 위치 콜백과 `onError` → unregister → release 순서로 별도 어댑터를 구현할 예정입니다. `pleos.car.permission.FUSED_LOCATION`을 사용하며 Google FLP API로 대체했다고 주장하지 않습니다.
- Vehicle State: 기어/속도 등의 실제 지원 필드와 권한을 검증해 정차 확인 스위치를 제거해야 합니다. 확인되지 않은 상태에는 복잡한 UI를 허용하지 않는 것이 목표입니다.
- STT/TTS: 실제 console 사용 가능 상태와 인증 조건을 확인한 후 진행합니다. 음성 없는 현재 빌드를 차량 음성 앱으로 소개하지 않습니다.

프로젝트 신청 문서는 FusedLocation/STT/TTS 등을 준비 중으로 표기합니다. 계정과 현재 배포 상태는 직접 확인해야 합니다. 한국어·영어 릴리즈 노트에 Vehicle/FusedLocation 버전 불일치도 있어 이 SDK들은 추측한 버전으로 의존성을 추가하지 않았습니다.

## 공식 근거

- [NaviHelper 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)
- [SDK 의존성](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk)
- [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions)
- [목적지 요청과 결과](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations)
- [리스너](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners)
- [SDK 사용 신청](https://document.pleos.ai/docs/pleos-only/create-my-project)
- [에뮬레이터](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [FusedLocation 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions)
- [AGP 8.7 호환성](https://developer.android.com/build/releases/agp-8-7-0-release-notes)
