# 개발과 검증

## 첫 마일스톤

실행 가능한 replay 네이티브 앱, 실제 SDK에 의존하는 Pleos variant, 3단말 세션 API, 핵심 상태·권한·삭제 테스트를 만듭니다. 공모전 기획안의 전체 기능이 완료되었다고 주장하지 않습니다.

## 재현 가능한 검사

```sh
cd server
npm test
# 루트에서
./gradlew :core:test :app:assembleReplayDebug :app:assemblePleosDebug
./gradlew :app:lintReplayDebug :app:lintPleosDebug
```

핵심 검사는 미승인/만료 적용 차단, 차량별 상태 격리, 늦은 결과 무효화, 종료 삭제, 지연에 따른 후보 순위 변화를 다룹니다. 서버는 세 클라이언트 HTTP 요청, 공유 동의·멤버십, 잘못된 초대, stale/미래/중복 위치, 만료 제안, 타 차량 결과 보고, 탈퇴·TTL, 토큰 비노출을 검증합니다.

실제 테스트 결과는 `VALIDATION.md`를 봅니다. AVD 화면·SDK 런타임·실차·부하·p95 성능·사용성 검증은 빌드 테스트와 구분합니다.

## 다음 개발 순서

1. Pleos AVD 확보 및 정차 벤치에서 NaviHelper 초기화·목적지 반영·실패·timeout 확인.
2. FusedLocation와 Vehicle State의 콘솔 가용성 확인 → 실제 위치·기어/속도 어댑터와 주행 UI 제한. 실제 위치 서버 수신은 정책·동의·테스트가 준비된 후 활성화.
3. 내비 목적지를 덮어쓰는 현재 실험을 정리하고 `RequestWaypointInfo` 기반 경유지 요청 및 원래 목적지 보존 검증.
4. 국내 경로/POI 공급자 선택, 실제 경로 비용과 후보 접근성 검증. 현재 fixture와 구분된 source 추가.
5. 다수결 투표와 개별 승인 분리, 목적지 버전·멱등 재시도·만료·취소·복원 프로토콜.
6. polling을 WSS로 바꾸고 지연·끊김·백그라운드 복귀 시험. 원시 좌표 없는 계측과 성능 기준 검증.
7. SDK 권한이 확보된 경우 STT/TTS 짧은 명령 3개, 선택적으로 설명용 AI 연결.
8. 서명된 release APK와 README/영상 작성. SDK 키는 로컬/서버 비밀 저장소에서만 관리.

프로토타입 APK는 debug 서명이며 출시 인증, 차량 안전/UX 심사, 위치 데이터 정책 검토를 마친 결과물이 아닙니다.
