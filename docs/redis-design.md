# Redis 도입 설계

> 작성일: 2026-07-28
> 대상 환경: Oracle Cloud VM.Standard.E2.1.Micro (1 OCPU / RAM 1GB), Spring Boot 3.4 + Kotlin, PostgreSQL

## 1. 목표

Redis를 도입해 다음을 달성:

1. **조회수 IP 중복 방지를 DB에서 Redis로 이전** — 매 상세 조회마다 발생하는 `article_view_log` INSERT/EXISTS 쿼리 제거, 무한히 자라는 로그 테이블 문제 해소
2. **매치데이 라이브 채팅 신규 구축** — WebSocket(STOMP) + Redis Pub/Sub 기반, 로그인 없이 닉네임으로 참여
3. (후순위) 인기 기사·선수 평점 집계 캐싱

동접 목표는 최대 200명 수준. 1GB 램 환경이므로 **Redis는 maxmemory 64MB로 제한**하여 운영.

## 2. 인프라 구성

```
[Oracle Cloud VM (1GB)]
 ├─ Spring Boot 앱 (기존)
 ├─ PostgreSQL (기존)
 └─ Redis 7.x  ← apt로 신규 설치, localhost 전용
```

- 설치: `apt install redis-server` (Docker 미사용, 이유는 아래)
- 설정 (`/etc/redis/redis.conf`):
  - `maxmemory 64mb` / `maxmemory-policy allkeys-lru` — 메모리 상한 도달 시 오래 안 쓴 키부터 자동 삭제
  - `bind 127.0.0.1` 유지 — **외부 노출 금지**. 오라클 Security List에서도 6379 포트를 열지 않음
- 스왑 2GB 생성 (OOM kill 방지 보험)
- 앱 접속: `spring-boot-starter-data-redis` (Lettuce), `localhost:6379`

### Docker를 쓰지 않는 이유

1. **메모리 예산** — RAM 1GB 환경에서 Docker 데몬(dockerd + containerd)은 그 자체로 수십~100MB를 상시 점유. Redis에 64MB만 허락한 환경에서 "설치 편의 도구"가 Redis보다 메모리를 더 먹는 것은 본말전도
2. **기존 구성과의 일관성** — 이 VM은 Spring 앱과 PostgreSQL을 호스트에 직접 설치해 운영 중. Redis만 컨테이너로 띄우면 관리 방식이 두 갈래(systemd + Docker)가 되어 오히려 운영 복잡도 증가
3. **Docker의 장점이 발휘되지 않는 상황** — Docker의 가치는 여러 환경에 동일하게 배포(이식성), 여러 컨테이너 격리, 오케스트레이션에서 나옴. 단일 VM에 단일 Redis 인스턴스를 상주시키는 경우 이 장점이 하나도 해당 안 됨
4. **apt 설치로 충분한 운영 품질** — 배포판 패키지는 systemd 유닛이 포함되어 자동 시작·크래시 재시작·로그 관리가 표준 방식으로 제공됨. 컨테이너로 얻으려던 것이 이미 다 있음

단, 로컬 개발 환경(Windows)에서는 반대로 Docker가 합리적 — Redis는 공식 Windows 빌드가 없고, 로컬은 메모리 제약도 없으며 켰다 껐다 하는 일회성 용도이기 때문. **"운영은 apt, 로컬은 Docker"**로 환경별 최적 도구를 택함.

### 프로파일별 설정

| 프로파일 | Redis 접속 | 비고 |
|---|---|---|
| `h2` (로컬 개발) | `localhost:6379` | 로컬에 Redis 설치 또는 Docker로 띄움 |
| `local-pg` | `localhost:6379` | 〃 |
| `prod` | `localhost:6379` | VM에 apt로 설치된 인스턴스 |

접속 정보는 프로파일 공통이므로 `application.properties`에 한 번만 선언.

## 3. Redis 적용 기능 목록

| # | 기능 | Redis 자료구조 | 단계 |
|---|---|---|---|
| 1 | 조회수 IP 중복 방지 | String (`SET NX` + TTL) | **Phase 1** |
| 2 | 채팅 메시지 브로드캐스트 | Pub/Sub | **Phase 2** |
| 3 | 채팅 최근 메시지 보관 | List (`LPUSH`+`LTRIM`) + TTL | **Phase 2** |
| 4 | 채팅 동시 접속자 수 | String 카운터 (`INCR`/`DECR`) | **Phase 2** |
| 5 | 채팅 도배 방지 (rate limit) | String 카운터 + TTL | **Phase 2** |
| 6 | 인기 기사(hyped) 목록 캐시 | String (JSON) + TTL | Phase 3 (선택) |
| 7 | 선수 평점 집계 캐시 | String (JSON) + TTL | Phase 3 (선택) |

7가지 용도가 전부 **Redis 하나로** 해결된다는 점이 선택의 근거. 캐시 전용(Memcached), 메시징 전용(RabbitMQ) 도구를 각각 띄우면 1GB VM에서 프로세스만 늘어남 — Redis는 TTL 캐시·Pub/Sub·List·원자 카운터를 한 프로세스가 제공하므로 이 규모에서는 사실상 유일한 합리적 선택. 항목별 상세 이유는 각 Phase 절에 기술.

## 4. 키 설계

네임스페이스는 `기능:대상:식별자` 규칙을 따름. **모든 키는 TTL을 가짐** (수동 청소 불필요).

| 키 패턴 | 타입 | TTL | 용도 |
|---|---|---|---|
| `view:{articleId}:{ip}` | String | 24h | 조회수 중복 방지 마커 |
| `chat:msg:{matchId}` | List | 24h | 최근 메시지 최대 100개 |
| `chat:online:{matchId}` | String | 24h | 현재 접속자 수 |
| `chat:nick:{matchId}` | Hash | 24h | 닉네임 선점 (정규화 닉네임 → ipHash) |
| `chat:rl:{ip}` | String | 10s | 도배 방지 카운터 |
| `chat:banned` | Set | 없음(영구) | 관리자가 차단한 IP 목록 |
| `cache:hyped` | String | 5m | 인기 기사 목록 JSON (Phase 3) |
| `cache:rating:{matchId}` | String | 60s | 평점 집계 JSON (Phase 3) |

Pub/Sub 채널(키 아님): `chat.messages` — 단일 채널, 메시지 페이로드에 `matchId` 포함.

### 메모리 예산 (64MB 대비)

- 조회수 마커: 키+값 ≈ 100B × (일일 순방문 × 기사 수) — 1만 건이어도 ~1MB
- 채팅 메시지: 300B × 100개 × 동시 활성 방 1개 ≈ 30KB
- 합계는 넉넉히 잡아도 수 MB 수준. 64MB 상한으로 충분.

## 5. Phase 1 — 조회수 중복 방지 이전

### 현재 (AS-IS)

`NewsController.getNewsDetail()`에서 요청마다:
1. `article_view_log`에 EXISTS 쿼리 (articleId + ip + 24시간)
2. 미조회 시 `news_article.view_count` UPDATE + 로그 INSERT

### 변경 (TO-BE)

```kotlin
// ViewCountService (신규)
fun tryMarkViewed(articleId: Long, ip: String): Boolean =
    redis.opsForValue().setIfAbsent("view:$articleId:$ip", "1", Duration.ofHours(24)) == true
```

- `setIfAbsent`(SETNX)가 원자적으로 "처음 봤는지" 판정 → true일 때만 `view_count` UPDATE
- `ArticleViewLog` 엔티티/리포지토리는 제거 (테이블은 마이그레이션 시 DROP)
- `view_count` 자체는 지금처럼 DB 컬럼 유지 — 영구 데이터이므로 Redis에 두지 않음

### 왜 이 기능에 Redis가 맞는가

1. **"24시간 내 중복"은 본질적으로 TTL 문제** — DB로 풀면 시간 조건 쿼리 + 오래된 로그를 지우는 청소 작업이 별도로 필요(현재는 청소가 없어 테이블이 무한 증가 중). Redis는 키 만료가 자료구조에 내장되어 있어 이 문제 전체가 `Duration.ofHours(24)` 한 줄로 끝남
2. **원자성** — 현재 코드는 EXISTS 확인 → INSERT 사이에 틈이 있어 동시 요청 시 중복 카운트 가능. SETNX는 확인과 기록이 한 번의 원자 연산이라 이 경쟁 조건이 구조적으로 사라짐
3. **hot path 부하 제거** — 기사 상세는 가장 많이 호출되는 API인데, 매 호출마다 DB 왕복 2회(EXISTS + INSERT)가 붙어 있음. 메모리 연산인 Redis로 옮기면 이 비용이 사실상 0
4. **유실돼도 되는 데이터** — 마커가 날아가면 최악의 경우 조회수가 한 번 더 오를 뿐. Redis의 약점(휘발성)이 약점이 되지 않는 자리. 반대로 유실되면 안 되는 `view_count` 누적값은 DB에 유지 — **"휘발 가능한 것만 Redis에"**가 이 설계 전체의 원칙

### 장애 시 동작 (fail-open)

Redis 접속 실패 시 **중복 방지 없이 조회수를 올리지 않고 기사만 반환**. 조회수는 부가 기능이므로 기사 조회 자체가 실패해서는 안 됨. try-catch로 감싸고 WARN 로그만 남김.

## 6. Phase 2 — 매치데이 라이브 채팅

### 6.1 개념

- 경기마다 채팅방 1개. **경기 시작 1시간 전 오픈, 경기 종료(또는 킥오프 +3시간) 후 닫힘**
- 로그인 없음: 입장 시 닉네임 입력 (2~12자, 서버에서 금칙어/공백 검증)
- **닉네임은 방 단위 1회성 + 선착순 선점** — 상세는 6.5 참고
- 메시지는 휘발성 — DB 저장 없음, Redis List에 최근 100개만

### 6.2 아키텍처

```
브라우저 ──STOMP over WebSocket──▶ Spring
  SEND /app/chat/{matchId}
        │ ① 방 오픈 여부 검증 (UpcomingMatch 킥오프 시각 기준)
        │ ② 차단 IP 검사 (chat:banned)
        │ ③ 닉네임 소유 검증 (chat:nick:{matchId}, 6.5 선점 규칙)
        │ ④ rate limit 검사 (chat:rl:{ip}, 10초에 5건)
        │ ⑤ 메시지 정제 (길이 ≤ 200자, trim)
        ▼
   Redis PUBLISH chat.messages ──▶ RedisMessageListener (모든 앱 인스턴스가 구독)
        │                               │
        └─ LPUSH chat:msg:{matchId}     └─ SimpMessagingTemplate
           LTRIM 0 99                        .convertAndSend("/topic/chat/{matchId}")
                                                  │
                                             구독 중인 모든 브라우저에 전송
```

### 기술 선택 이유

**왜 WebSocket인가 (HTTP 폴링이 아니라)** — 폴링은 메시지가 없어도 전체 클라이언트가 주기적으로 요청을 보냄. 동접 200명이 2초 폴링이면 초당 100 요청이 상시 발생하고, 그래도 최대 2초 지연이 남음. WebSocket은 연결을 유지한 채 이벤트가 있을 때만 데이터가 흐르므로 유휴 비용이 거의 0이고 지연은 밀리초 단위. 90분간 이어지는 실시간 대화에는 WebSocket이 구조적으로 맞음.

**왜 STOMP인가 (raw WebSocket이 아니라)** — raw WebSocket은 바이트 통로일 뿐이라 "어느 방의 메시지인지" 구분(라우팅), 구독 관리를 전부 직접 구현해야 함. STOMP는 SEND/SUBSCRIBE 의미 체계를 제공하고 Spring이 `@MessageMapping`, `/topic` 브로커, 세션 이벤트까지 기본 지원. 방 개념이 있는 채팅에서 필요한 것이 이미 다 있음.

**왜 브로드캐스트에 Redis Pub/Sub인가** — WebSocket 연결은 특정 서버 프로세스의 메모리에 묶여 있어, 서버가 2대가 되는 순간 각 서버는 자기에게 붙은 유저에게만 뿌릴 수 있음(채팅방이 서버 대수만큼 쪼개짐). 모든 인스턴스가 공유하는 Redis를 방송 허브로 두면 어느 서버에 접속했든 같은 방이 됨. 지금은 1대라 당장 필수는 아니지만, 처음부터 이 경로를 태워야 확장 시 코드 변경이 없고 — Pub/Sub 학습이 이번 도입의 핵심 목적이기도 함.

**왜 최근 메시지를 List에 두나 (DB가 아니라)** — 필요한 것은 "입장자에게 직전 대화 100개 보여주기"뿐. 영구 보관·검색·수정이 전혀 필요 없는 휘발성 데이터를 DB에 넣으면 매치데이마다 수천 행의 쓰기 부하와 청소 배치가 생김. `LPUSH`+`LTRIM`은 최근 100개 유지가 자료구조 연산으로 끝나고, TTL로 방과 함께 자동 소멸.

**왜 접속자 수/도배 방지를 Redis 카운터로** — `INCR`은 원자적이라 동시 접속/전송에도 카운트가 어긋나지 않고, rate limit의 "10초 창"은 TTL이 그대로 구현해줌. 앱 메모리의 변수로 세면 서버가 늘어날 때 서버별로 따로 세게 되는 것도 이유.

### 6.3 엔드포인트 설계

| 구분 | 경로 | 설명 |
|---|---|---|
| WebSocket 핸드셰이크 | `GET /ws` (SockJS fallback 포함) | STOMP 연결 |
| 메시지 발행 | STOMP SEND `/app/chat/{matchId}` | 채팅 전송 |
| 메시지 구독 | STOMP SUBSCRIBE `/topic/chat/{matchId}` | 브로드캐스트 수신 |
| 방 정보 조회 | `GET /api/chat/{matchId}` (REST) | 오픈 여부, 접속자 수, 최근 메시지 100개 |
| 닉네임 선점 | `POST /api/chat/{matchId}/nickname` (REST) | 입장 시 닉네임 등록. 선점 실패 시 409 |
| IP 차단 관리 | `POST/DELETE /api/admin/chat/ban` (REST) | 관리자용 차단 등록/해제 |

REST로 최근 메시지를 내려주는 이유: 입장 시점에 WebSocket 연결 전에 직전 대화를 먼저 그릴 수 있어 프론트 구현이 단순해짐.

### 6.4 메시지 포맷

```json
// 클라이언트 → 서버 (SEND)
{ "nickname": "골레아도르", "content": "골이다!!!" }

// 서버 → 클라이언트 (브로드캐스트) = Redis에 저장되는 형태와 동일
{
  "matchId": "12345",
  "nickname": "골레아도르",
  "content": "골이다!!!",
  "sentAt": "2026-08-15T21:04:11+09:00",
  "ipHash": "a3f9"   // IP 앞부분 해시 4자리 — 동일인 식별용 (닉네임 사칭 완화)
}
```

원본 IP는 절대 클라이언트로 내보내지 않음. `ipHash`는 salt 포함 해시의 앞 4자리만 사용.

### 6.5 닉네임 정책 — 방 단위 1회성 + 선착순 선점

닉네임은 계정이 아니라 **방(경기) 단위로만 존재**. 방이 열릴 때마다 새로 정하고, 방이 닫히면 소멸.

- **선점 규칙**: 방 안에서 닉네임은 선착순. 입장(첫 SEND 또는 방 정보 조회 시 등록 API) 시점에 `HSETNX chat:nick:{matchId} {정규화닉네임} {ipHash}` — 이미 다른 ipHash가 선점했으면 "이미 사용 중인 닉네임" 에러
- **재접속 허용**: 같은 IP(ipHash 일치)는 새로고침/재접속 후에도 자기 닉네임을 그대로 사용 가능
- **정규화**: trim → 소문자화 → 연속 공백 축약 후 비교. 유사 닉네임 사칭(`Koke` vs `koke `) 방지
- **클라이언트 연속성**: 프론트는 localStorage에 마지막 닉네임을 저장해 다음 경기 입장 시 자동 입력. 단골은 매 경기 같은 이름으로 나타나지만, 서버는 이를 보장하지 않음 (다른 사람이 먼저 선점하면 그 방에서는 다른 이름 사용)
- 이 구조로 **방 안 사칭은 원천 차단**되므로, 메시지의 `ipHash` 노출은 보조 수단이 됨

### 6.6 방 오픈 판정

```
open = (킥오프 - 1h) ≤ now ≤ (킥오프 + 3h)
```

- 킥오프 시각은 기존 `UpcomingMatch` / `MatchResult` 데이터 재사용
- 닫힌 방에 SEND 시 에러 프레임 반환, SUBSCRIBE는 허용(과거 메시지 열람은 TTL 내 가능)
- 별도 "방 생성" 작업 불필요 — matchId 기반으로 키가 자연 생성되고 TTL로 자연 소멸

### 6.7 접속자 수

- WebSocket `SessionConnectedEvent` / `SessionDisconnectEvent` 리스너에서 `INCR`/`DECR chat:online:{matchId}`
- 비정상 종료로 인한 카운트 드리프트는 방 TTL(24h)로 자연 리셋되므로 허용

### 6.8 도배/어뷰징 방지

| 항목 | 정책 |
|---|---|
| 전송 빈도 | IP당 10초에 5건 (`INCR chat:rl:{ip}` + `EXPIRE 10`) — 초과 시 에러 프레임 |
| 메시지 길이 | 200자 초과 거부 |
| 닉네임 | 2~12자, 금칙어 목록 필터 + 방 단위 선착순 선점 (6.5) |
| 링크 스팸 | URL 포함 메시지 거부 (초기 정책, 필요 시 완화) |
| IP 차단 | `chat:banned` Set에 등록된 IP는 SEND 거부. 관리자 API로 등록/해제 (기존 어드민 컨트롤러 패턴 재사용) |

### 6.9 개인정보 처리

- IP 주소는 개인정보로 해석될 수 있으므로, 서비스에 **개인정보처리방침 페이지를 두고 수집 사실과 목적(어뷰징 방지)을 고지** — 조회수/평점의 기존 IP 수집도 함께 커버
- 원본 IP는 클라이언트로 절대 내보내지 않음. 노출은 salt 포함 해시 앞 4자리(`ipHash`)만
- 채팅 메시지는 DB에 저장하지 않고 Redis TTL(24h)로 자동 파기되므로 별도 보존/파기 절차 불필요

### 6.10 장애 시 동작

Redis 다운 시 채팅은 **기능 전체 불가** (에러 프레임 반환). 채팅은 Redis가 곧 저장소이므로 fallback이 없는 게 맞음.

## 7. Phase 3 (선택) — 조회 캐싱

트래픽이 늘어 DB 부하가 보이면 진행. 지금은 설계만 남김.

- `cache:hyped` — `searchHypedNews` 결과 JSON, TTL 5분. 매치데이에 목록 요청이 몰릴 때 효과
- `cache:rating:{matchId}` — `aggregateByMatch` 집계 JSON, TTL 60초. 평점 제출 시 키 삭제(즉시 반영 유지)

## 8. 신규 의존성 및 파일 구성

```kotlin
// build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-data-redis")
implementation("org.springframework.boot:spring-boot-starter-websocket")
```

```
src/main/kotlin/com/atleti/news/
 ├─ config/
 │   ├─ RedisConfig.kt          # RedisTemplate, Pub/Sub 리스너 컨테이너
 │   └─ WebSocketConfig.kt      # STOMP 엔드포인트, 브로커 설정
 ├─ controller/
 │   └─ ChatController.kt       # STOMP @MessageMapping + REST 방 정보 조회
 ├─ service/
 │   ├─ ViewCountService.kt     # Phase 1: 조회수 중복 방지
 │   └─ ChatService.kt          # Phase 2: 발행/구독/기록/rate limit
 └─ dto/ (또는 service 내)
     └─ ChatMessage.kt
```

## 9. 구현 로드맵

| 순서 | 작업 | 완료 기준 |
|---|---|---|
| 0 | VM에 Redis 설치 + 스왑 생성 | `redis-cli ping` → PONG |
| 1 | 의존성 추가 + `RedisConfig` + 연결 확인 | 앱 기동 시 Redis 연결 로그 |
| 2 | **Phase 1**: `ViewCountService` 작성, `NewsController` 전환, `ArticleViewLog` 제거 | 같은 IP 24h 내 재조회 시 조회수 불변 (테스트) |
| 3 | **Phase 2a**: WebSocket + STOMP 설정, 에코 수준 동작 확인 | 브라우저 2개에서 상호 수신 |
| 4 | **Phase 2b**: Redis Pub/Sub 경유 브로드캐스트 + List 기록 + REST 방 정보 | 재입장 시 최근 메시지 로딩 |
| 5 | **Phase 2c**: 방 오픈 판정, rate limit, 닉네임 검증, 접속자 수 | 정책 위반 시 에러 프레임 |
| 6 | 운영 배포 + 실경기 1회 시범 운영 | 매치데이 실사용 |

각 단계는 독립적으로 배포 가능. 특히 Phase 1은 작고 위험이 낮아 Redis 연동 검증을 겸함.

## 10. 결정 사항 요약 (Q&A 대비)

- **왜 메시지를 DB에 안 남기나** — 휘발성 대화이고 1GB 램 VM에서 쓰기 부하를 아끼는 것. 추후 "경기 하이라이트 채팅 아카이브"가 필요해지면 경기 종료 시점에 List 내용을 일괄 저장하는 배치 하나로 해결 가능
- **왜 Pub/Sub이지 Streams가 아닌가** — 채팅은 유실 허용(재전송 불필요), 구독자에게 실시간 전달만 하면 됨. Streams는 소비 이력 관리가 필요한 경우(추후 Kafka 학습 주제와 겹침)에 적합
- **왜 조회수 카운트 자체는 Redis에 안 두나** — 영구 데이터라 재시작/eviction에 유실되면 안 됨. Redis는 "중복 방지 마커"만 담당
- **인증 없는 IP 기반의 한계** — VPN/모바일 IP 변경으로 우회 가능하나, 기존 평점 기능과 동일한 수용 범위. 로그인 도입 전까지는 감수
