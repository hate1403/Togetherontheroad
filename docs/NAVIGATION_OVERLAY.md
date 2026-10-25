# 내비게이션 영역의 실제 지도와 그룹 패널

`NavOverlayActivity`는 권한만 확인합니다. `GroupOverlayService`의 사용자 시작 foreground service가 MapLibre 지도와 우측 참여자 패널을 표시합니다. Pleos에서는 전체 화면의 오른쪽 2/3 영역을 사용하며 상태·공조 바 insets를 적용합니다. 실제 차량 위치를 표시하는 SDK와 경로/그룹 연결은 `LiveController`가 소유합니다.

TYPE_APPLICATION_OVERLAY 권한이 필요합니다. 공식 Pleos Connect 2.1.0 AVD에는 일반 표시 권한 설정 Activity가 없어, 이 테스트 AVD에서만 개발자가 `adb shell appops set --user 10 io.togetherroad.app.pleos SYSTEM_ALERT_WINDOW allow`로 구성했습니다. 앱 자체가 몰래 권한을 우회하거나 자동 부여하지 않습니다. 일반 Android는 시스템 권한 설정을 엽니다. 마켓/실차에서 이 표시 방식의 허용 여부는 별도 확인 사항입니다.

OEM 지도 위 임의 마커 추가나 OEM 지도 카메라와의 동기화 기능은 공개 SDK에서 확인되지 않았습니다. 앱 소유 실제 지도에만 참여자와 경로를 그립니다. 기본 OEM 내비게이션은 NaviHelper로 foreground 요청하고, 목적지 전달은 사용자 확인 후 실제 SDK 결과를 기다립니다. 그룹 합류 경유지의 앱 경로와 내장 내비 목적지는 같은 기능으로 취급하지 않습니다.

종료 버튼/서비스 알림은 위치 구독·폴링·그룹 공유·지도 렌더러·SDK 리스너를 해제합니다. 앱 강제 종료 시 Android가 위치 구독을 종료하고 서버의 마지막 관측은 30초 후 stale, 그룹은 TTL로 삭제됩니다. 프로세스 사망 시 즉시 서버 삭제는 보장할 수 없습니다.
