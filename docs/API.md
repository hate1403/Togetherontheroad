# Live API v0.3

기본 `127.0.0.1:8787`, JSON 요청, Bearer 멤버 토큰. 요청 본문 8KiB, IP당 분당 600회. 토큰은 응답의 자기 멤버 자격에만 포함되며 snapshot에는 노출하지 않습니다.

| Method / 경로 | 입력 / 동작 |
|---|---|
| GET `/health` | LIVE 버전 |
| POST `/route` | `origin`, `destination`, 선택적 `waypoint`: 실제 OSRM 경로, 불가 시 503 |
| POST `/sessions` | `name`, `consent:true`: 그룹/소유 멤버 생성 |
| POST `/join` | `inviteCode`, `name`, `consent:true`: 8자리 코드 참여 |
| GET `/sessions/{id}` | 멤버 위치·freshness·route·proposal·messages |
| POST `/sessions/{id}/telemetry` | `seq`, `source`, `observedAt`, `latitude`, `longitude`, 선택적 `accuracyMeters` |
| POST `/sessions/{id}/destination` | 소유자만 `name`, `latitude`, `longitude`로 공동 목적지 설정 |
| POST `/sessions/{id}/proposals` | `candidate`, `kind:MEETUP/DESTINATION`: 3분 유효 제안 |
| POST `/sessions/{id}/vote` | `proposalId`, `yes:boolean`: 전원 동의 필요 |
| POST `/sessions/{id}/adopt` | `proposalId`: 자기 차량의 실제 도로 경로 계산/적용, 실패 시 성공 보고 안 함 |
| POST `/sessions/{id}/retry` | 자신의 경로 다시 요청 |
| POST `/sessions/{id}/message` | `kind:REST/LATE/READY/ARRIVED`; 도착은 현재 위치와 경로의 목적지 근접 확인 |
| POST `/sessions/{id}/leave` | 멤버/토큰/좌표 제거, 마지막 멤버면 그룹 제거 |
| POST `/sessions/{id}/end` | 소유자만 전체 종료 |

위치 source는 ANDROID_GPS/ANDROID_NETWORK/PLEOS_FUSED, 관측 시각 ±30초, 단조 증가 seq를 확인합니다. 클라이언트가 보낸 source는 장치 무결성 증명은 아닙니다. ETA를 업로드하지 않으며 서버가 도로 경로 제공자에서 계산합니다.

정상 재계산은 이동 250m 또는 60초 경과 시, 실패 후 30초 대기. 경로 캐시는 60초, 탈퇴·종료 시 캐시 제거. TTL 4시간 후 그룹 메모리 삭제, 60초 정리 타이머. 메시지는 최대 30개/화면 최근 10분, 탈퇴자의 메시지 삭제. 회원 토큰·원시 위치는 파일/로그에 저장하지 않습니다. HTTP 접근 로그도 생성하지 않습니다.

운영 배포 전 HTTPS/인증/모니터링/데이터 수명 정책을 적용해야 합니다. 현재 서버는 로컬 개발·실기기 연결 검증용이며 다중 프로세스 영속 그룹 서버가 아닙니다.

## 동행 경험 v0.4.0

`POST /sessions/{id}/experience/{action}`은 기존 Bearer 멤버 토큰이 필요합니다. action은 profile, wave, trail-consent, music-start, music-pause, music-resume, voice, drop, drop-delete, follow-share, follow, personal-destination, pair-upload입니다. snapshot의 experience는 수신자마다 잠금과 업로드 코드 노출 범위를 다르게 적용합니다.

`POST /sessions/{id}/assets`는 MIME·이용 목적(MUSIC/VOICE/MEMORY)·파일명·실제 길이를 헤더로 받는 binary 업로드입니다. `GET /sessions/{id}/assets/{assetId}`도 매번 멤버·장소 잠금을 검사합니다. 서버는 포맷 시그니처·크기·세션/전체 용량을 제한하며 VOICE는 WAV/MP4 컨테이너의 실제 길이를 파싱해 최대 20초를 강제합니다. 미디어를 공개 URL로 배포하지 않습니다.

`GET /upload`는 자체 제공 파일 선택 페이지입니다. 앱에서 pair-upload로 받은 일회용 코드와 초대 코드를 `POST /pair-upload`에 보내면 업로드 용도로 한정된 ticket을 받습니다. `POST /paired-media`는 이 ticket으로만 선택한 종류·장소의 파일 1개를 등록합니다. 코드 5분 만료·회원 탈퇴·세션 종료 후 재사용할 수 없습니다. 주 멤버 토큰은 브라우저에 전달하지 않습니다.
