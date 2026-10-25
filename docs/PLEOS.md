# Pleos SDK 연동

공식 Maven `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 받은 실제 artifact 타입으로 빌드합니다.

- NaviHelper **3.0.5**: initialize → 이벤트 리스너 → requestNavigationScreen(Foreground) / requestRoute(RouteInfo) → onRouteEnded(Connect 2.1.0) 또는 onRoutingCompleted → getRouteStateInfo/getDestinationInfo → ROUTE_GUIDING 및 좌표 일치 확인 → removeListener/release. 오류·30초 timeout은 실패입니다. 콜백에 요청 식별자가 없어 요청마다 새 gateway 수명주기를 사용합니다. 원격 SDK 호출은 worker에서 수행해 지도 터치를 막지 않습니다.
- FusedLocation **1.1.3**: isSupported → FUSED_LOCATION 런타임 권한 → initialize → registerFusedLocationCallback/getLastKnownLocation → LocationData의 원래 timestampMillis → unregister/release. 지원/권한/신규 관측이 없으면 Android GPS/Network의 실제 관측을 별도 source로 사용합니다.
- NAVI_ROUTE, NAVI_ROUTE_SEARCH, NAVI_CUSTOM_MAP, FUSED_LOCATION은 Pleos flavor에서 선언합니다. CUSTOM_MAP은 내비 foreground 화면 요청에 필요하며 OEM 마커 삽입 권한을 뜻하지 않습니다.

공식 Pleos Connect 2.1.0 API34 ARM64 AVD에서 APK 설치, 내장 내비 foreground, 위치 SDK 서비스 연결 및 실제 지도 렌더링을 검증합니다. 시스템의 NaviHelper 서비스 버전은 3.0.4로 보고됐으며 앱 artifact는 3.0.5입니다.

Vehicle State 기반 주행 제한·STT/TTS·실시간 교통·실차 검증·마켓 제출용 서명은 아직 범위 밖입니다. 목적지 전달 시 실제 내장 내비가 바뀔 수 있으므로 사용자 확인을 거칩니다. 에뮬레이터 실행과 실차 인증을 같은 것으로 표기하지 않습니다.

공식 자료:

- [NaviHelper](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)
- [목적지 요청](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations)
- [FusedLocation 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions)
- [에뮬레이터 설정](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [Pleos 디자인 가이드](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro)
- [MapLibre Android](https://github.com/maplibre/maplibre-native/blob/main/platform/android/docs/getting-started.md)
- [OpenFreeMap](https://openfreemap.org/quick_start/)
- [OSRM](https://project-osrm.org/docs/v5.24.0/api/)

2026-10-25 AVD 실행에서 내장 내비가 목적지를 수신한 후 실제 안내 모드·목적지 일치 콜백을 반환하고 앱의 연결 확인 창이 표시됐습니다. 앱 OSRM 경로와 OEM 내비 경로는 서로 다른 제공자가 계산하므로 거리/시간이 같다고 가정하지 않습니다.
