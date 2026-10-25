# 개발과 검증

`LiveController`가 위치 수명주기·네트워크 작업 직렬화·UI 상태를 관리하고 `LiveNavigationView`는 실제 지도/상태를 표시합니다. `PhoneLocation`과 flavor별 `PlatformLocation`은 실제 위치만 제공합니다. `RoadRouter`는 검증된 실제 도로 경로만 반환합니다. 경로 실패 시 직선 거리나 임의 ETA를 대체 표시하지 않습니다.

```sh
cd server
node --test test/*.test.mjs
RUN_LIVE_NETWORK=1 node --test test/routing.test.mjs
cd ..
./gradlew :core:test :app:assembleReplayDebug :app:assemblePleosDebug :app:lintReplayDebug :app:lintPleosDebug
```

테스트 fixture는 server/test에만 있습니다. 제품 코드에 기본 가상 참가자·위치·경로는 없습니다. 단위 테스트는 유효성·투표/개별 적용·비동기 종료 경쟁·삭제와 토큰 폐기를 검증하며 HTTP 테스트는 3개 독립 클라이언트를 사용합니다. live OSRM 테스트는 실제 외부 경로와 geometry를 확인합니다.

수동 검증: 권한 허용/거절, 실제 위치 수신, 지도 선택/실제 경로, 그룹 생성/다른 단말 코드 참여, 위치 신선도/연결 끊김, 제안/전원 동의/개별 경유 경로 적용, 빠른 메시지, 탈퇴·종료, 내장 내비 목적지 SDK 콜백. AVD GPS 입력은 개발 도구이며 앱에 자동 재생을 넣지 않습니다.

운영 전 필요한 작업은 실제 Vehicle State 기반 상호작용 제한, 실차/정식 권한 검증, 운영 지도·경로 제공자, HTTPS/서비스 인증과 서버 배포, 접근성/다양한 화면 크기 검증입니다. 확인되지 않은 SDK 기능이나 AI 기능을 UI 버튼으로 미리 노출하지 않습니다.
