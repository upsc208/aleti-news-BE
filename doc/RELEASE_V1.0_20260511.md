# 🔴 Atleti-news Backend System Documentation v1.0 (Beta)

본 문서는 **Atleti-news** 프로젝트의 1차 베타 버전 배포 기준 백엔드 시스템 설계 및 데이터베이스 구조를 설명합니다.

---

## 1. 시스템 아키텍처 개요
본 시스템은 **"무인 자동화 뉴스 파이프라인"**을 목표로 하며, 스페인 현지 소식을 수집하여 AI로 가공한 뒤 한국 팬들에게 전달하는 구조를 가지고 있습니다.

### ⚙️ 기술 스택
- **언어**: Kotlin 2.1.10 (Idiomatic Kotlin & Coroutines)
- **프레임워크**: Spring Boot 3.4.2
- **데이터베이스**: PostgreSQL 16+
- **인프라**: **Oracle Cloud Infrastructure (OCI)**
- **AI 엔진**: Google Gemini 2.5 Flash Lite
- **주요 라이브러리**: Spring Data JPA, Jsoup (Scraping), WebFlux (WebClient), SpringDoc (Swagger)

---

## 2. 배포 및 인프라 (Deployment)
본 시스템은 **Oracle Cloud (OCI)**의 Always Free 티어를 적극 활용하여 24시간 무중단 운용됩니다.

*   **백엔드 서버**: OCI Compute Instance (ARM 기반 Ampere A1 Compute)
*   **데이터베이스**: OCI 전용 PostgreSQL 인스턴스 또는 Compute 내 설치형 PostgreSQL 활용.
*   **보안**: OCI Security List를 통한 API 포트 및 DB 포트 정밀 제어.

## 2. 핵심 비즈니스 로직 (Workflow)

### 2.1 뉴스 수집 파이프라인 (`NewsPipelineService`)
매 주기마다 실행되며, 다음과 같은 단계로 진행됩니다.
1.  **데이터 수집 (Scraping)**:
    *   **Marca**: `ScraperService`를 통해 아틀레티코 섹션 기사 목록 및 본문 수집.
    *   **Twitter/X**: 주요 기자 7인(@MatteoMoretto 등)의 트윗 수집.
        *   **Primary**: RapidAPI를 통한 공식 API 호출.
        *   **Fail-over**: API 한도 초과나 오류 발생 시 **Nitter** 인스턴스를 통한 웹 스크래핑으로 자동 전환.
2.  **중복 제거 & 필터링**:
    *   DB 내 `original_url` 존재 여부를 확인하여 중복 처리 방지.
    *   사전에 정의된 핵심 키워드(atleti, simeone 등)를 통한 1차 필터링.
3.  **AI 처리 (Gemini API)**:
    *   수집된 원문을 Gemini 2.5 Flash Lite 모델에 전달.
    *   아틀레티코 관련성 최종 판별, 제목/본문 번역, **3줄 핵심 요약** 생성.
4.  **저장**: 가공된 뉴스 데이터를 `news_article` 테이블에 저장.

### 2.2 경기 정보 동기화 (`FootballDataService`)
*   **최근 경기**: 종료된 최신 경기 1개의 스코어, 득점자, 대회명 등을 수집.
*   **향후 일정**: 다가올 3경기의 상대 팀, 일시, 홈/어웨이 여부 수집.
*   **최적화**: 매일 새벽 6시(KST) 정기 업데이트를 수행하며, DB 기반 캐싱으로 API 호출 비용 최소화.

---

## 3. 데이터베이스 스키마 설계

### 3.1 `news_article` (뉴스 기사)
| 컬럼명 | 타입 | 설명 |
| :--- | :--- | :--- |
| `id` | BigSerial (PK) | 고유 식별자 |
| `article_type` | Varchar | WEB_ARTICLE / SNS_POST 구분 |
| `source_name` | Varchar | Marca, Twitter(@username) 등 |
| `author_name` | Varchar | 기자 이름 또는 작성자 |
| `original_url` | Varchar (Unique) | 원문 URL (중복 방지 키) |
| `title_ko` | Text | AI가 번역한 한국어 제목 |
| `summary_ko` | Text | AI가 생성한 3줄 요약 (Bullet points) |
| `content_ko` | Text | 한국어 번역 본문 |
| `content_raw` | Text | 수집된 원문 전체 내용 |
| `image_url` | Varchar | 대표 이미지 URL |
| `is_atm_related` | Boolean | 아틀레티코 관련성 여부 |
| `published_at` | OffsetDateTime | 기사 발행 일시 |

### 3.2 `collection_log` (수집 로그)
| 컬럼명 | 타입 | 설명 |
| :--- | :--- | :--- |
| `id` | BigSerial (PK) | 고유 식별자 |
| `status` | Varchar | SUCCESS / FAIL |
| `scanned_count` | Integer | 발견된 총 기사 수 |
| `ai_processed_count` | Integer | AI가 최종 승인하여 저장된 수 |
| `twitter_api_call_count` | Integer | 트위터 API 호출 횟수 (Rate limit 관리용) |
| `football_api_call_count` | Integer | 축구 API 호출 횟수 |
| `error_msg` | Text | 실패 시 에러 메시지 |
| `execution_time_ms` | Long | 소요 시간 (ms) |
| `created_at` | OffsetDateTime | 로그 기록 일시 |

### 3.3 경기 관련 테이블

#### `match_result` (최근 경기 결과)
| 컬럼명 | 타입 | 설명 |
| :--- | :--- | :--- |
| `id` | BigSerial (PK) | 고유 식별자 |
| `match_id` | Varchar (Unique) | Football API에서 제공하는 경기 고유 ID (중복 저장 방지) |
| `home_team` | Varchar | 홈 팀 명칭 |
| `away_team` | Varchar | 어웨이 팀 명칭 |
| `home_score` | Integer | 홈 팀 득점 |
| `away_score` | Integer | 어웨이 팀 득점 |
| `match_date` | Varchar | 경기 일시 (ISO 형식 또는 API 제공 문자열) |
| `tournament` | Varchar | 대회 명칭 (La Liga, UCL 등) |
| `is_home` | Boolean | 아틀레티코 마드리드의 홈 경기 여부 |
| `updated_at` | Timestamp | 데이터 동기화 일시 |

#### `upcoming_match` (향후 경기 일정)
| 컬럼명 | 타입 | 설명 |
| :--- | :--- | :--- |
| `id` | BigSerial (PK) | 고유 식별자 |
| `opponent` | Varchar | 상대 팀 명칭 |
| `match_date` | Varchar | 경기 예정 일시 |
| `is_home` | Boolean | 아틀레티코 마드리드의 홈 경기 여부 |
| `tournament` | Varchar | 대회 명칭 |
| `updated_at` | Timestamp | 데이터 동기화 일시 |

---

## 4. API 엔드포인트

### News API (`/api/news`)
*   `GET /api/news`: 뉴스 목록 조회 (페이징, 소스 필터링 지원)
*   `GET /api/news/{id}`: 뉴스 상세 정보 조회 (이미지, 본문 포함)
*   `GET /api/news/sources`: 활성화된 뉴스 소스 목록 조회
*   `GET /api/news/logs`: 수집 배치 실행 로그 조회
*   `POST /api/news/trigger`: 뉴스 수집 파이프라인 수동 실행

### Match API (`/api/matches`)
*   `GET /api/matches/recent`: 최근 경기 결과 조회
*   `GET /api/matches/upcoming`: 향후 경기 일정 조회

---

## 5. 안정성 및 비용 최적화
*   **회복 탄력성**: Nitter Fail-over를 통해 트위터 정책 변화에 유연하게 대응.
*   **API 비용 최적화**: 
    *   **Twitter API**: Basic 플랜(월 1,000회) 한도 내에서 운용하며, 초과 시 Nitter로 우회하여 추가 비용 발생 차단.
    *   **Football API**: Basic 플랜(월 100회) 한도에 맞춰 일일 1~2회 업데이트로 정밀 조정.
    *   **Gemini API**: 2.5 Flash Lite 모델 사용으로 비용 극최소화.
        *   **실제 비용 사례**: 1차 배포 기간(2026.05.04 ~ 05.11) 동안 Gemini API 실제 결제 금액 **약 227원** 발생.
*   **확장성**: `NewsSource` 테이블을 통해 새로운 수집원을 코드 수정 없이 추가 가능.

---

## 6. 현안 및 향후 개선 과제 (Challenges & Future Roadmap)

### 6.1 현재 직면한 문제점
*   **SNS 수집 불안정성**: 최근 Nitter 인스턴스들의 잦은 차단으로 인해 Twitter/X Fail-over 로직의 안정성이 저하됨.
*   **데이터 정제 미흡 (Twitter)**: X(Twitter) 게시물은 별도의 제목이 없어 현재 DB 저장 시 제목과 본문이 동일하게 들어가는 설계 했었음. 이로 인해 프론트엔드 UI상에서 텍스트가 중복되어 가독성을 해침.
*   **수집 속도(Latency)**: 현재 배치 주기(15분) 및 수집 프로세스 효율 한계로 인해 Naver Cafe, FM Korea 등 국내 대형 커뮤니티 유저들의 전파 속도보다 늦는 경우가 발생함.

### 6.2 향후 개선 계획 (To-do)
*   **데이터 품질 고도화**:
    *   **SNS 제목 생성**: Gemini AI를 활용하여 X 기사인 경우 3줄 요약 기능을 제거하고 SNS 본문의 핵심 내용을 요약한 '한 줄 제목'을 별도로 생성하도록 프롬프트 개선.
    *   **뉴스 카테고리화**: `category` 필드를 추가하여 [이적시장], [경기리뷰], [부상소식], [예상라인업] 등으로 뉴스를 자동 분류.
*   **수집 효율 최적화**:
    *   수집 엔진 최적화 및 우선순위 큐 도입을 통해 'Breaking News'의 전파 속도를 커뮤니티 수준으로 단축.
    *   `application.properties` 내 키워드 리스트를 최신 이적시장 타겟 및 이슈에 맞춰 정기적으로 업데이트.
*   **인프라 강화**:
    *   Nitter를 대체할 수 있는 더 안정적인 Twitter 스크래핑 방식 탐색 또는 API 사용량 쿼터 상향 검토.

### 6.3 기술적 부채 및 코드 품질 (Technical Debt)
*   **서비스 비대화 (Monolithic Service)**: `NewsPipelineService`가 수집, 필터링, AI 처리, 저장 로직을 모두 포함하고 있어 단일 책임 원칙(SRP) 위반 소지가 있음. 각 단계를 독립적인 컴포넌트로 분리하여 테스트 가능성을 높여야 함.
*   **데이터 소스 관리 불일치**: `DataInitializer`를 통해 DB(`news_source`)에 수집원 리스트를 구축해 두었으나, 실제 `NewsPipelineService` 로직에서는 여전히 기자 리스트가 하드코딩되어 사용되고 있음. 이를 DB 기반으로 완전히 전환하여 동적 관리가 가능하도록 개선이 필요함.
*   **동기식 순차 처리**: 현재 트위터 수집 및 AI 분석이 리스트 순서대로 하나씩 진행되어 전체 실행 시간이 김. 코루틴의 `async/await`를 활용한 병렬 처리를 통해 수집 속도를 개선할 여지가 큼.
*   **로깅 전략**: `println` 기반의 단순 로깅을 사용 중임. 운영 환경의 추적성을 위해 `SLF4J(Logback)` 등 표준 로깅 프레임워크로의 전환이 필요함.
*   **테스트 코드 부족**: 비즈니스 로직의 복잡도에 비해 단위 테스트 및 통합 테스트 코드가 부족하여, 리팩토링 시 안정성을 담보하기 어려움.

---

## 7. 회고 (Retrospective)

1인으로 개발하고 배포까지 해보는 건 이번이 처음. 평소 회사에서 심심할 때마다 Gemini랑 "이런 서비스 만들면 어떨 것 같아?" 하고 대화하면서 놀았는데, 진짜로 구현하니까 뿌듯 ^^. 퇴근하고 집에서 짬짬이 개발했고, 시작부터 베타 배포까지 딱 일주일 정도 걸림.

배포하면서 클라우드플레어(Cloudflare)로 HTTPS 붙일 때 좀 고생했음. Cloud flare에서 네임서버를 2개만 주길래 가비아에서 1, 2번만 바꾸고 3번은 그대로 뒀다가 자꾸 에러가 나서 한참 헤맸음;; 결국 3번 네임서버까지 싹 지우니까 바로 해결 완료. 하나의 AI만 사용하다가 막힐 때는 다른 AI한테 물어보는것도 좋은 방법!

테스트 단계에서 RapidAPI를 너무 많이 써서 월 사용량을 금방 초과했는데, 계정 여러 개 생성 후 돌려가면서 돈 아껴보려고 노력했음(짠돌이 개발자). 가비아에서 도메인도 사고 Gemini API 결제도 했지만, 내가 실력 향상 겸 좋아하는 축구 팀 소식을 한국 팬들한테 전달한다는 생각에 돈은 하나도 안 아까움. 나중에 서비스 커져서 운영비가 월 2~3만 원씩 나가게 되면, 그때는 구글 애드센스 달아서 적자만 면하자는 마인드로 계속 운영해 볼 생각!

---
*작성일 - 2026-05-11*
