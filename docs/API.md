# 프로토타입 API

모든 POST는 `Content-Type: application/json`을 요구합니다. 회원가입 대신 임시 세션 멤버 토큰을 사용하며, 생성/참여를 제외한 모든 요청에 `Authorization: Bearer <token>`이 필요합니다. 로그·URL·디스크에 토큰이나 위치를 기록하지 않습니다.

| 메서드 | 경로 | 입력 |
|---|---|---|
| GET | `/health` | 없음 |
| POST | `/sessions` | `name`, `consent: true` |
| POST | `/sessions/{id}/join` | `name`, `inviteCode`, `consent: true` |
| GET | `/sessions/{id}` | 인증된 snapshot |
| POST | `/sessions/{id}/telemetry` | `seq`, `source: REPLAY`, `latitude`, `longitude`, `etaMinutes`, `observedAt` |
| POST | `/sessions/{id}/proposals` | `{}` |
| POST | `/sessions/{id}/approve` | `proposalId` |
| POST | `/sessions/{id}/result` | `proposalId`, `result: APPLIED 또는 FAILED` |
| POST | `/sessions/{id}/leave` | `{}` |
| POST | `/sessions/{id}/end` | `{}` |

생성·참여 결과에는 `sessionId`, `inviteCode`, `memberId`, `token`, `expiresAt`이 있습니다. 초대 코드는 의도한 참가자에게만 전달합니다. snapshot은 토큰과 초대 코드를 포함하지 않습니다. 최대 3대, 세션 수 1000개, IP당 분당 600요청, 요청 본문 8KiB로 제한합니다. 서비스용 인증·분산 rate limit은 구현 전입니다.

## 상태

가장 최근의 정상 위치만 보관합니다. 좌표 범위·유한 수·ETA·관측 시각(서버 기준 ±15초)·단조 증가 seq를 검증합니다. 15초가 지난 관측은 stale로 표시하고 합류 계산에 사용하지 않습니다. 단말 시계 동기화가 필요합니다. seq는 연결 생명주기 동안 유지하며 재시작하면 신규 멤버십을 생성해야 합니다.

제안은 2분 후 만료됩니다. `PENDING → APPLYING → APPLIED/FAILED`. 승인 요청은 인증한 자신의 차량만 변경합니다. `APPLYING`은 승인과 적용 요청이 시작되었다는 뜻이며 SDK 성공을 뜻하지 않습니다. 결과 보고는 어댑터 콜백 뒤에만 보냅니다. 서버는 단말의 결과 보고를 신뢰하는 프로토타입이며 원격 증명 기능은 없습니다.

새 제안 또는 멤버 변경은 기존 승인을 무효화합니다. 이미 단말에 전달되어 실행 중인 SDK 요청을 서버가 취소할 수는 없습니다. 이번 버전은 실제 주행용이 아니며, 운영용에는 목적지 버전·취소·재승인 프로토콜과 이벤트 상관관계 검증이 추가되어야 합니다.

## 공유 철회

탈퇴 시 해당 멤버와 위치·토큰을 삭제하고 그룹 제안을 무효화합니다. 종료 시 전체 세션을 삭제합니다. 4시간 TTL에 도달하면 다음 요청 또는 1분 주기 정리 시 세션을 제거합니다(정리 시점은 최대 1분 지연 가능). 활성 조회는 만료 즉시 거부합니다. 저장소는 프로세스 메모리뿐이며 재시작하면 데이터가 사라집니다.

앱은 철회 시 로컬 전송을 먼저 중단하고 캐시를 지웁니다. 네트워크가 끊기면 서버는 즉시 철회를 알 수 없으므로 사용자에게 서버 삭제 확인 실패를 표시하고 수동 재시도를 제공합니다. 나머지 클라이언트는 다음 polling에 변경을 반영하므로 이미 전달한 위치를 즉시 회수할 수 없습니다. 앱이 갑자기 종료되고 탈퇴 요청이 실패하면 TTL이 최종 삭제 수단입니다.

## 통신 범위

v0.1 Android는 2초 REST polling입니다. WSS/SSE·이벤트 재전송은 구현 전입니다. HTTPS가 있는 운영 배포 대신 로컬 벤치 HTTP만 검증합니다. 호스트는 기본 loopback이며 외부 공개하지 않습니다. Android debug만 HTTP를 허용합니다.
