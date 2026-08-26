# 아키텍처 정리 설계

> 작성일: 2026-08-26
> 대상: 백엔드 전체 — Kotlin 58파일 / 6,089줄 (테스트 11파일 / 2,600줄)
> **동작은 바꾸지 않는다.** 패키지 구조와 계층 의존만 정리한다.
> 진행: 0~3단계 완료 (2026-08-26) · 4단계 진행 중 — 4.5절

## 0. 한 문장 요약

> **`service/` 하나에 25개 클래스가 있고, 그 안에 도메인 5개가 섞여 있다.**

문제는 파일이 많은 게 아니다. **패키지가 도메인 경계를 감추고 있다는 것**이다.
경계는 이미 코드에 있다 — 순환 의존 없이 깨끗하게 갈려 있다. 패키지만 그걸 모른다.

**Package by Layer → Package by Feature.** 설계를 새로 하는 게 아니라, 이미 존재하는 경계를
패키지에 반영한다.

---

## 1. 문제 (이동 전 상태)

### 1.1 왜 이렇게 됐나

계층별 패키지(`controller / service / repository / entity / dto`)는 클래스가 20개일 때는 잘 맞는다.
지금은 58개고, **새 클래스의 기본 목적지가 `service/`뿐**이라 거기만 25개로 자랐다.

`service/`를 도메인 기준으로 재분류하면 이렇게 갈린다.

| 도메인 | 수 | 클래스 |
|---|---|---|
| 뉴스 | 9 | Scraper, NitterScraper, TwitterApi, Youtube, Gemini, GeminiResponse, Headline, NewsPipeline, ViewCount |
| 라이브 피드 | 6 | LiveMatchPoller, LiveMatchMapper, LiveMatchKeys, LiveSnapshot, NaverSportsClient, FotMobLiveClient |
| 채팅 | 5 | ChatService, ChatMessageRelay, ChatPresenceRelay, ChatPresenceTracker, SlangFilter |
| 경기 | 4 | FootballData, MatchScraper, MatchKickoff, TournamentNameKo |
| 선수 평점 | 1 | PlayerRating |

### 1.2 증상 네 가지

**① 패키지 응집도 붕괴** — `service/` 한 곳에 도메인 서비스 / 외부 어댑터 8개 /
DTO 5개 / 상수·유틸 3개가 혼재. 패키지명이 정보를 주지 않아 탐색 시 전수 확인이 필요하다.

**② 의존성 방향 역전** — 리포지토리가 서비스를 참조한다.

```sql
-- repository/PlayerRatingVoteRepository.kt:21
SELECT new com.atleti.news.service.PlayerRatingAggregate(...)
```

JPQL 생성자 표현식이라 컴파일 타임에 안 잡히고 **런타임 FQCN으로 결합**돼 있다.
IDE 리팩토링으로 클래스를 옮기면 조용히 깨진다. 집계 투영(projection)이므로 리포지토리 계층에 속해야 한다.

**③ DTO 위치 분산 + 엔티티 노출** — 전송 객체가 `dto/`, `service/` 내부, `controller/` 내부
세 곳에 흩어져 있고, `NewsController.getSources()`는 JPA 엔티티를 그대로 직렬화한다.
스키마 변경이 곧 API 계약 변경이 되고, 연관 매핑이 추가되면 지연 로딩 프록시 직렬화 사고가 난다.

이미 네이밍을 오염시켰다. `entity.UpcomingMatchEntity`와 `service.UpcomingMatch`가 충돌해서
컨트롤러가 FQCN을 쓴다 — 스타일이 아니라 **이름 충돌 회피의 결과**다.

**④ 횡단 관심사 미통일**

| 관심사 | 현황 |
|---|---|
| 로깅 | SLF4J 10파일 / `println` 12파일. `println`은 레벨·타임스탬프가 없어 필터링·시각 조회 불가 |
| 트랜잭션 | `@Transactional` 2파일뿐. 쓰기는 8개 서비스에 분산. `HeadlineService`는 저장+삭제가 비원자적 |
| 외부 호출 | 타임아웃·재시도가 클래스마다 각자. Jsoup 연결 설정만 5곳 중복 |

---

## 2. 대안 비교 — 왜 Package by Feature인가

후보를 놓고 **"여기서 무슨 값을 하는가"**로만 판단했다.

| 후보 | 무엇을 푸는가 | 이 프로젝트에서의 값 | 판정 |
|---|---|---|---|
| **Package by Feature** | 패키지 응집도, 탐색 비용 | 파일 이동만으로 달성. 되돌리기 쉬움 | **채택** |
| 헥사고날 (포트-어댑터) | 구현체 교체, 의존성 역전 | 교체 계획 없음 → 인터페이스:구현 1:1 껍데기만 증가 | 개념만 차용 |
| 클린 아키텍처 | 계층 4겹 + 의존 규칙 강제 | 엔티티↔도메인모델 변환 코드가 순증 | 미채택 |
| DDD 전술 패턴 | Aggregate·VO로 도메인 불변식 관리 | 엔티티 7개에 불변식이 거의 없음. 지금은 CRUD에 가깝다 | 보류 |
| DDD 전략 패턴 | Bounded Context로 경계 식별 | **경계 식별은 이미 끝나 있음** | 개념만 차용 |
| 모듈러 모놀리스 | Gradle 멀티모듈로 경계를 컴파일 타임에 강제 | 경계 위반을 빌드가 막아 주지만, 1인 개발에 빌드 복잡도가 앞선다 | 나중에 |

### 2.1 무엇을 빌려 쓰는가

전면 도입은 안 하되, **각 아키텍처에서 값을 하는 개념 하나씩만** 가져온다.

- **헥사고날 → 어댑터 분리.** 외부 시스템과 통신하는 코드를 `external/`로 격리한다.
  인터페이스 계층은 만들지 않는다 (3.3절)
- **DDD 전략 → 도메인이 패키지 경계.** Aggregate·VO 같은 전술 패턴은 안 쓴다
- **모듈러 모놀리스 → 의존 방향 규칙.** 멀티모듈로 강제하진 않고 문서와 리뷰로 지킨다 (3.2절 표)

### 2.2 Package by Feature를 고른 결정적 이유

**되돌릴 수 있다.** 파일 이동과 import 수정이 전부라, 아니다 싶으면 되돌리는 비용도 같다.
헥사고날·클린은 한 번 인터페이스를 깔면 되돌리기가 훨씬 비싸다.

**전제 조건이 이미 충족돼 있다.** 서비스 간 생성자 주입 그래프에 **순환 의존이 없다.**
사이클이 있으면 패키지를 나누는 순간 순환 참조가 되어 분리 자체가 불가능하다.

**참조 구현이 이미 있다.** 라이브 피드가 사실상 목표 구조다 — 어댑터(`NaverSportsClient`)와
도메인 로직(`LiveMatchPoller`)이 분리돼 있고, `LiveMatchKeys`로 키를 중앙화했고,
설계 문서와 테스트 3개 클래스가 붙어 있다. **나머지를 여기에 맞추면 된다.**

---

## 3. 목표 구조

### 3.1 패키지 배치

```
com.atleti.news
├─ common/          도메인 무관 공통 — 시간 파싱, 예외 계층, Redis 공통 설정
├─ news/            api / domain / external
├─ match/           api / domain / external
├─ live/            api / domain / external
├─ chat/            api / domain / infra
└─ rating/          api / domain
```

### 3.2 계층 정의와 의존 방향

| 계층 | 포함 | 의존 가능 |
|---|---|---|
| `api/` | 컨트롤러, Request/Response DTO | → `domain` |
| `domain/` | 엔티티, 리포지토리, 도메인 서비스 | → `external`, `common` |
| `external/` | 외부 API·스크래핑 어댑터, 응답 파싱 DTO | → `common` |
| `infra/` | 전달 배관 — Redis Pub/Sub 릴레이, WebSocket 세션 추적 | → `common` |

`chat`만 `external` 대신 `infra`를 쓴다. 외부 시스템을 호출하지 않고 메시지 전달 배관만 있기 때문이다.

### 3.3 어댑터 분리가 핵심

현재 여러 클래스가 **I/O + 도메인 로직 + 영속화**를 한 클래스에서 수행한다.

- `MatchScraperService` — HTTP 요청 + JSON 파싱 + `MatchResultRepository.save()`
- `YoutubeService` — 유튜브 API 호출 + Gemini 요약 + `NewsArticleRepository.save()`

테스트 더블을 주입할 **이음매(seam)가 없어** 실제 외부 API를 호출해야만 테스트가 성립한다.

> 뉴스 파이프라인 테스트는 **0개**다. 라이브 피드는 어댑터와 도메인 로직이 생성자 주입으로
> 분리돼 있어 테스트가 3개 클래스에 걸쳐 있다. **커버리지 차이는 의지가 아니라 이음매의 유무에서 나왔다.**

---

## 4. 실행

### 4.1 지금이 적기인 이유

패키지 대이동은 보통 진행 중인 브랜치와의 머지 충돌 때문에 미뤄진다. 경로가 전면 변경되면
git rename 추적이 실패해 충돌 해소 비용이 급증한다.

현재 master 외 브랜치 12개 중 **11개가 `ahead:0`** — master에 없는 커밋이 하나도 없는
머지 완료 브랜치다. 미머지 커밋은 `feat/brief`의 1개뿐이다. **충돌 대상이 사실상 없다.**

### 4.2 순서

| 단계 | 작업 | 선행 이유 | 결과 |
|---|---|---|---|
| 0 | 머지 완료 브랜치 11개 삭제 | 이동 후 브랜치 생사 판별이 어려워진다 | ✅ 브랜치 13 → 1 |
| 1 | 의존성 역전 해소 — `PlayerRatingAggregate` → 리포지토리 계층 | 남아 있으면 리포지토리를 옮길 수 없다 | ✅ `2a05ee7` |
| 2 | DTO 위치 확정 + 엔티티 노출 차단 | **각 클래스의 목적지가 여기서 결정된다** | ✅ `5c505d0` `58ce654` |
| 3 | 패키지 이동 | 이동과 import 수정만. 동작 변경 없음 | ✅ `3fd40e7` — 68파일 |
| 4 | 도메인 내부 정리 | 배치 확정 후 내용 정리 | 진행 중 (4.5절) |

2가 3보다 앞서지 않으면 같은 파일을 두 번 옮기게 된다.

### 4.3 실행 기록 — 계획과 달랐던 것

**도메인별로 나눠서 진행했다.** 계획은 "한 번에"였지만, 58개를 한 번에 옮기면 컴파일 에러가
한꺼번에 터져 원인 추적이 어렵다. `rating`(5개)으로 절차를 먼저 검증한 뒤
`live` → `match` → `chat` → `news` → `common` 순으로 갔고, 단계마다 컴파일·테스트를 통과시켰다.
커밋은 계획대로 하나로 묶었다.

**깨질 참조를 미리 계산했다.** "같은 패키지라 import 없이 쓰던 참조가 갈라지면 깨진다"를
이동 전에 스크립트로 뽑아 41건을 예측했고, 컴파일러가 지목한 지점이 그대로였다.

**자동 import 도구가 처음에 틀렸다.** 주석 제거를 정규식 순서로 처리하다 **문자열 안의
`https://` 를 줄주석으로 오인**해, 따옴표 짝이 깨지고 파일의 85%가 날아간 상태로 판정하고 있었다
(10,770자 → 1,577자). 상태 기계 스캐너로 바꿔 해결. 안 잡았으면 import를 조용히 빠뜨렸을 것이다.

**1단계 검증이 여기서 값을 했다.** JPQL 생성자 표현식의 FQCN도 함께 옮겼는데 이건 문자열이라
컴파일러가 검증하지 않는다. 1단계에서 "이 쿼리를 지키는 것은 `contextLoads`"임을 확인해 둔 것이
그대로 방어막이 됐다.

**검증 결과** — git이 67개를 rename으로 추적해 이력이 보존됐다. 변경 줄은 183 insert /
183 delete로 대칭이고, `package`·`import`가 아닌 변경은 FQCN 경로 갱신뿐이다(로직 변경 0).
`clean` 후 전체 테스트 통과.

### 4.4 이동 결과

```
com.atleti.news
├─ chat/     api 2    domain 3    infra 6
├─ common/   4
├─ live/     domain 5             external 2
├─ match/    api 1    domain 6    external 2
├─ news/     api 4    domain 13   external 5
└─ rating/   api 1    domain 4
```

### 4.5 4단계 항목

| 대상 | 내용 |
|---|---|
| `LiveMatchPoller` | 경기별 상태 `ConcurrentHashMap` 12개 → 상태 클래스 1개. 현재 8개가 경기 종료 시 미정리 |
| 채팅 Redis 키 | `ChatKeys`로 중앙화 — `LiveMatchKeys`와 동일 패턴 |
| 로깅 | `println` 12파일 → SLF4J |
| Jsoup | 연결 설정 5곳 → 공통 팩토리 |
| 트랜잭션 | 서비스 메서드 단위 `@Transactional` 경계 |
| `WebSocketConfig` | `setAllowedOriginPatterns("*")` 제한. "배포 전 제한 예정" 주석이 남아 있으나 **이미 배포됨** — 리팩토링이 아니라 보안 이슈 |

### 4.6 이동 커밋 규칙

3단계 커밋에 기능 수정을 섞지 않는다. 회귀 발생 시 이동 때문인지 수정 때문인지 격리할 수 없다.
diff는 경로 변경과 import로만 구성한다.

---

## 5. 완료 기준

- [x] `service/` 패키지가 존재하지 않는다
- [ ] 도메인 패키지당 클래스 수 10개 이하 — **`news/domain` 13개, 미충족** (6.1절)
- [x] 리포지토리가 상위 계층을 참조하는 지점 0곳
- [x] JPA 엔티티가 응답 본문으로 직렬화되는 지점 0곳
- [x] 컨트롤러에 FQCN 사용 없음
- [x] 각 단계 종료 시 `./gradlew test` 전체 통과
- [x] 3단계 커밋 diff가 경로 변경과 import로만 구성

---

## 6. 남긴 문제

이동 중에 드러났지만 이번 범위에서 다루지 않은 것들이다. 판단 근거가 아직 약하거나
성격이 다른 작업이다.

### 6.1 `news/domain` 13개 — 완료 기준 미충족

엔티티 4 + 리포지토리 4 + 서비스 5 구성이다. 10개 이하로 만들려면 뉴스 도메인 자체를
쪼개야 하는데(수집 / 헤드라인 등), **지금 그 경계를 그을 근거가 없다.** 실제로 불편해지는
시점에 나눈다. 기준을 못 맞춘 것을 지우지 않고 미충족으로 남겨 둔다.

### 6.2 패키지 이름 중복 — `com.atleti.news.news.domain`

루트 패키지가 `news`인데 도메인 이름도 `news`라 겹쳐 보인다. 도메인을 `article`이나 `press`로
바꾸면 해소되지만, 다른 도메인이 모두 대상 명사 하나(`match` `live` `chat` `rating`)라
일관성이 깨진다. 현재는 일관성을 택했다.

### 6.3 `HeadlineResponse` 이름 충돌

같은 이름의 서로 다른 타입이 둘 있다.

| 위치 | 정체 |
|---|---|
| `news/api` | API 응답 DTO — `id`, `content`, `createdAt` |
| `news/external` | Gemini 응답 파싱용 — `headlines: List<String>` |

패키지가 갈려 컴파일은 되지만 이름만 보고는 구분이 안 된다. 후자를
`GeminiHeadlineResponse` 등으로 바꾸는 것이 맞다.

### 6.4 죽은 코드 — `MatchStatusResponse`

`match/domain/FootballDataService.kt`에 선언돼 있으나 참조하는 곳이 없다.

### 6.5 컨트롤러가 리포지토리를 직접 주입

`NewsController`가 서비스를 거치지 않고 리포지토리 3개(`NewsArticleRepository`,
`NewsSourceRepository`, `CollectionLogRepository`)를 직접 받는다. 계층을 건너뛰는 구조다.
조회 전용이라 당장 문제는 없지만, 조회 로직이 늘면 컨트롤러에 쌓인다.

---

## 7. 관련 문서

[live-match-feed-design.md](live-match-feed-design.md) · [redis-design.md](redis-design.md) ·
[ci-cd-design.md](ci-cd-design.md) · [round1.md](round1.md)
