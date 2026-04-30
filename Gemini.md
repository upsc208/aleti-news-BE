# 🔴 프로젝트명: Atleti-news (백엔드 통합 설계서)

## 1. 프로젝트 개요 및 정체성
- **컨셉**: 스페인 마드리드(출처)와 한국(목적지)을 잇는 지능형 뉴스 파이프라인.
- **네이밍**: 아틀레티코 레전드 '사울(Saúl -> Seoul)'과 '코레아(Correa -> Korea)'의 이름을 딴 언어유희 브랜딩.
- **목표**: 24시간 무인 자동화, 고신뢰성, 초저비용(월 5,000원 미만) 뉴스 큐레이션 서비스.
- **철학**: "무인 운용" - 개발자의 노트북이 꺼져 있어도 클라우드에서 완벽하게 작동해야 함.

#배경 및 동기

팬심: 스페인 라리가의 '아틀레티코 마드리드(ATM)' 골수 팬인 한국인 개발자가 만든 프로젝트입니다.

정보의 격차: 현지 언론(Marca 등)과 1티어 기자(Matteo Moretto 등)의 소식은 스페인어나 영어로 올라오며, 한국 커뮤니티에 전달되기까지 시차가 발생합니다.

기술적 목표: 이를 해결하기 위해 Gemini AI를 활용하여 현지 소식을 실시간으로 수집·판별·요약하여 한국 팬들에게 최단 시간에 배달하는 **'지능형 뉴스 파이프라인'**을 구축하고자 합니다.

3. 네이밍의 의미 (The "Crack" Concept)

아틀레티코의 상징적인 두 선수의 이름과 한국의 지리적 명칭을 매칭한 언어유희입니다.

Saúl (사울) → Seoul (서울): 소식이 시작되고 전달되는 중심지.

Correa (코레아) → Korea (코리아): 소식이 최종적으로 도착하는 목적지이자 한국 팬들.

즉, **"마드리드에서 발생한 뉴스를 서울(Saúl)을 거쳐 한국(Correa)으로 잇는 다리"**라는 의미입니다.

4. 주요 기능 (Core Value)

무중단 자동화: 개발자가 잠든 사이에도 15분마다 스페인 현지를 감시합니다.

AI 큐레이션: 단순 번역을 넘어, Gemini AI가 아틀레티코와 관련 없는 노이즈 기사를 걸러내고 핵심만 3줄로 요약합니다.

고퀄리티 UI: 트위터(X) 위젯 등을 활용하여 현장감 있는 뉴스를 제공합니다.

## 2. 기술 스택 (Tech Stack)
- **언어**: Kotlin 1.9+ (Idiomatic Kotlin 지향)
- **프레임워크**: Spring Boot 3.2+
- **비동기 처리**: Kotlin Coroutines (Scraping 시 Non-blocking I/O 구현)
- **AI 엔진**: Google Gemini 2.5 Flash Lite API (Google AI Java SDK 활용)
- **데이터베이스**: PostgreSQL (Supabase) / Spring Data JPA
- **크롤링**: Jsoup

## 3. 데이터베이스 스키마 (PostgreSQL)
### 3.1 news_article (뉴스 본문)
- `id` (BIGSERIAL PK)
- `source_name` (VARCHAR): 출처명 (Marca, Nitter 등)
- `original_url` (VARCHAR): 원문 주소 (**UNIQUE INDEX** - 중복 수집 방지 핵심)
- `title_ko` (TEXT): 한글 제목
- `summary_ko` (TEXT): Gemini AI가 생성한 3줄 요약
- `image_url` (TEXT): 대표 이미지 경로
- `published_at` (TIMESTAMPTZ): 발행 일시
- `is_atm_related` (BOOLEAN): ATM 관련 여부 판별값

### 3.2 collection_log (수집 로그)
- `id`, `status` (SUCCESS/FAIL), `scanned_count`, `ai_processed_count`, `error_msg`, `execution_time_ms`, `created_at`

## 4. 기능적 워크플로우 (15분 주기 배치)
1. **수집(Scraping)**: Marca(ATM 섹션) 및 Nitter(Matteo Moretto 등 1티어 기자) 데이터 획득.
2. **필터링(Filtering)**:
    - **1단계 (중복 제거)**: DB에 `original_url` 존재 시 즉시 제외.
    - **2단계 (키워드 검사)**: 정규표현식으로 핵심 키워드 체크하여 AI 호출 전 1차 선별 (비용 절감).
3. **AI 처리(AI Processing)**: **Gemini 2.5 Flash Lite** 호출.
    - **작업 A**: 아틀레티코 마드리드 관련성 최종 판별 (True/False).
    - **작업 B**: 관련성 확인 시, 3줄 불렛포인트 한국어 요약 및 번역 생성.
4. **저장(Storage)**: 최종 가공 데이터를 DB에 저장하고 배치 실행 결과 기록.

## 5. 비기능적 요구사항 (금융 SI급 표준)
- **회복 탄력성(Resilience)**: Nitter 인스턴스 Fail-over 구현. 특정 도메인(nitter.net) 응답 없을 시 백업 도메인(nitter.it, nitter.cz 등)으로 자동 전환.
- **견고성(Robustness)**: 모든 수집 단계에 엄격한 Exception Handling 적용. 배치가 중단 없이 지속되어야 함.
- **비용 최적화**: Gemini API 무료 티어(15 RPM / 1,500 RPD) 한도 내에서 작동하도록 Rate Limiter 설계.
- **보안(Security)**: 모든 API Key와 DB 접속 정보는 환경 변수(`.env`)로 관리하고 코드 노출 엄금.

---

## 🚀 Gemini CLI를 위한 초기 지시어
"위 명세서를 기반으로 **Saúl-Correa Bridge** 백엔드 개발을 시작하자.

1. **Kotlin Coroutines**와 **Spring Data JPA**를 사용하는 프로젝트 구조를 생성해줘.
2. `news_article` 테이블과 연동되는 엔티티를 작성하고, `original_url` 중복 방지 로직을 최우선으로 구현해줘.