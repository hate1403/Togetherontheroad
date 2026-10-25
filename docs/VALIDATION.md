# 검증 기록 — 2026-10-25

**상태: 공식 Pleos AVD에서 핵심 동작을 실행 확인한 검증용 프로토타입. 실차/마켓 배포 완료를 뜻하지 않습니다.**

| 항목 | 확인한 결과 |
|---|---|
| 서버 테스트 | 20개 중 19개 통과, 외부 live 네트워크 1개는 기본 실행에서 skip |
| 별도 live OSRM 테스트 | 최종 재실행 3개 모두 통과, 실제 도로 geometry/거리 반환 |
| 실제 로컬 서버 `/route` | HTTP 200, OSRM, 9,351.7m / 705.8초 / geometry 171점, trafficAware=false |
| Core JVM | 2개 통과 |
| Android APK/Lint | 두 variant 최종 빌드 성공(1분 13초), Lint 오류/경고 0. Core 2개 통과 |
| 공식 Pleos AVD | Connect 2.1.0 / API34 / ARM64, 2560×1440, RAM 4GB/4 cores, host GPU |
| 지도 조작 | 실제 도로/지명, 길게 누르기, 실제 목적지 좌표 선택, OSRM 경로 7.0km/약 9분 표시 |
| 그룹 생성/참여 | 공유 동의 후 첫 APK가 생성한 8자리 코드로 두 번째 실제 설치 APK 참여, 두 프로필/거리 표시 |
| 합류 | 지도에서 선택한 좌표 제안 → 두 APK 각각 동의 → 각 차량이 실제 경유 도로 경로 적용, 적용 완료 표시 |
| 위치/세로 막대 | 테스트 위치를 37.48544722,127.03636666 → 지도에서 선택한 37.49515,127.06558로 이동. 새 도로 경로 3.8km/약 5분 및 공통 거리 축 막대 갱신 |
| 메시지/탈퇴 | 실제 출발 준비 메시지 표시, 두 번째 APK 탈퇴 후 첫 APK의 참가자 수가 2→1로 갱신 |
| 접기/복원 | MapView를 해제한 작은 그룹 버튼 상태에서 연결 유지, 재진입 시 현재 그룹/경로 복원 |
| 내장 내비 SDK | NaviHelper artifact 3.0.5 → AVD service 3.0.4. 실제 onRouteEnded 후 getRouteStateInfo/getDestinationInfo, 안내 모드와 목적지 좌표 일치로 성공 창 확인 |
| 실제 다중 차량 | 미검증. 위 UI 검증은 같은 AVD에 설치한 독립 패키지 두 개이며 같은 테스트 GPS를 사용. HTTP 자동 테스트의 클라이언트/경로 fixture도 실차가 아님 |

## 에뮬레이터 장애와 검증 조건

하드웨어 GNSS의 `android.hardware.gnss.BpGnss.stopNmea`가 android.fg를 72~104초 막아 system_server watchdog이 재시작하는 로그를 확인했습니다. 앱 실행 전에도 발생했습니다. 이후 Android의 GPS test provider를 사용한 cold boot에서 동일 system_server PID 775가 35분 이상 유지됐고, 두 APK와 내장 내비의 실제 동작을 확인했습니다. 하드웨어 GNSS 문제가 해결됐다는 의미는 아닙니다.

별도 `scripts/avd-location.py`는 소유한 테스트 AVD에 개발자가 지정한 좌표만 공급하며 앱 실행과 분리됩니다. 화면에 AVD 테스트 위치라고 표시하고, 입력이 멈추면 30초 뒤 stale로 처리합니다. 실제 사용자 위치/차량 주행을 뜻하지 않습니다. 제품의 기본 참가자·좌표·ETA·성공 응답은 없습니다.

## 구현 범위와 남은 검증

공개 NaviHelper에서 OEM 지도 마커 삽입/카메라 투영 API는 확인되지 않았습니다. 현재 지도는 내비게이션 영역에 배치하는 앱 소유 MapLibre 지도입니다. 앱 OSRM과 내장 내비는 독립된 실제 경로 제공자이며 거리/시간이 같을 필요가 없습니다. 합류 경유지는 앱 지도 경로에 적용되며 OEM 내비에 자동 삽입하지 않습니다.

OSRM 예상 시간은 실시간 교통 ETA가 아닙니다. 실제 Vehicle State 기반 주행 상호작용 제한, 실차 위치/권한 승인, 운영 HTTPS 서버와 지도/경로 계약, release 서명, 다양한 화면 크기/접근성 검증은 남아 있습니다. 음성 기능은 제공하지 않습니다.
