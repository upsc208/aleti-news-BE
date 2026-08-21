#!/usr/bin/env node
'use strict';

/**
 * 가짜 라이브 경기 서버 (docs/live-match-feed-design.md 리허설용)
 *
 * 목적: 경기가 없는 날에도 라이브 피드 전체를 **실제 코드 경로 그대로** 돌려본다.
 *       네이버·FotMob 자리에 이 서버가 앉아서 대본대로 골을 넣어 준다.
 *
 * 확인되는 것:
 *   · FotMob 일정 스크랩 → upcoming_match 적재
 *   · matchId ↔ gameId 매핑 (킥오프 대조)
 *   · 폴러 구동 — 킥오프 전 60초 / 경기 중 5초
 *   · 골 알림, VAR 취소, 자책골 표기, 추가시간 표기
 *   · 네이버가 죽었을 때 FotMob 폴백 + 소스 전환 시 골 정렬
 *   · GET /api/chat/{matchId} 의 live 스냅샷
 *
 * 사용법 — **앱을 먼저 띄운다.** 킥오프 카운트다운이 이 스크립트가 뜬 순간부터 흐르는데,
 * 앱 부팅이 그보다 오래 걸리면 동기화 시점에 킥오프가 지나 일정이 0건으로 잡히고
 * 폴러가 영영 안 깬다.
 *   1) docker run -d -p 6379:6379 redis:7-alpine
 *   2) ./gradlew bootRun --args='--spring.profiles.active=h2,livedemo'
 *   3) /api/chat/9999999 가 200을 주면:  node scripts/fake-live-match.js
 *
 * 대본 고르기 (--scenario):
 *   stress  (기본) 골 15건 / VAR 3건 — 폴러가 무너질 자리를 골라 넣은 스트레스 테스트.
 *                  5분이면 끝나지만 분 표기가 0′~2′라 화면을 눈으로 판단하기 어렵다
 *   malaga         8/20 라리가 말라가전 예행연습 — 골 4건, 실제 페이스(110분).
 *                  분 표기가 실제 경기처럼 흐르고 하프타임도 제자리에 온다
 *   malaga-half    같은 경기의 전반만 5분에 압축, 골 7건 + 취소 1건.
 *                  골 연출을 여러 번 보고 하프타임 정지를 확인하는 용도
 *
 * --nohalfs        FotMob이 halfs를 안 주는 상황 (8/20 실경기에서 실제로 그랬다).
 *                  네이버 `전반종료`만으로 시계가 멈추는지 확인한다
 *
 * 진행 중 키 입력:
 *   k  네이버를 죽인다 (503)  → FotMob 폴백으로 넘어가는지 본다
 *   r  네이버를 살린다        → 되돌아오는지 본다
 *   n  다음 대본 이벤트를 즉시 실행
 *   s  현재 상태 출력
 *   q  종료
 *
 * 옵션:
 *   --speed=2       대본을 2배 빠르게
 *   --kickoff=120   시작 후 몇 초 뒤가 킥오프인지 (기본 120)
 *   --port=8099
 *   --app=http://localhost:8080
 */

const http = require('http');
const readline = require('readline');
const { URL } = require('url');

// ─────────────────────────── 설정 ───────────────────────────

const argv = process.argv.slice(2);
const opt = (name, fallback) => {
  const hit = argv.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : fallback;
};

const PORT = Number(opt('port', '8099'));
const APP = opt('app', 'http://localhost:8080');
const SPEED = Math.max(0.1, Number(opt('speed', '1')));
const KICKOFF_DELAY_SEC = Number(opt('kickoff', '10'));

/**
 * FotMob이 `halfs`를 안 주는 상황을 흉내낸다 (`--nohalfs`).
 *
 * 2026-08-20 말라가전에서 실제로 그랬다 — `firstHalfEnded`가 하프타임 15분이 지나도록
 * 끝내 안 왔고, 그동안 화면 시계가 멈추지 않았다. FotMob CDN 캐시로 추정한다(설계 8.6).
 * 이 옵션을 켜면 서버가 네이버 `전반종료`만으로 시계를 멈출 수 있는지 확인할 수 있다.
 */
const NO_HALFS = process.argv.includes('--nohalfs');

/** 채팅방 키 = FotMob matchId. 실제 경기와 겹치지 않게 가짜 번호를 쓴다 */
const MATCH_ID = '9999999';
/** 네이버 gameId — 불투명 문자열이라는 점만 흉내내면 된다 */
const GAME_ID = 'FAKEGAME00001';
const ATLETI_TEAM_ID = '9906';

const KST_OFFSET_MS = 9 * 3600_000;

// ─────────────────────────── 경기 대본 ───────────────────────────

/**
 * `t`는 킥오프 기준 초.
 *
 * **득점 분은 박아 넣지 않고 경기 시계에서 뽑아 쓴다.** 손으로 적어 두면 LED 시계는
 * 실제 경과 시간을 세는데 골은 "58′"이라고 떠서 둘이 딴 세상이 된다. 아래
 * [matchMinute]가 LED와 **같은 계산**을 하므로 `--speed`를 뭘 주든 항상 맞는다.
 *
 * 그래서 90분 경기를 흉내내지 않는다. **전반 2분 + 추가시간, 후반 2분짜리 경기**다.
 * 분당 3골로 몰아치는 건 그대로다 — 14골 + VAR 취소 3건.
 *
 * 폴러가 무너질 만한 자리를 골라 넣었다:
 *
 *   · 2~3초 간격 버스트  — 폴링 주기(5초)보다 촘촘해서 **한 턴에 여러 골**이 잡힌다
 *   · 같은 분에 여러 골   — 골 키가 선수 이름으로 갈리는지 (분이 같아도 키는 달라야 한다)
 *   · 목록 중간 골 취소   — 마지막 골이 아니라 가운데 항목이 사라진다
 *   · 취소 번복          — 지웠던 골이 되살아난다 (SREM 뒤 SADD → 다시 알려야 정상)
 *   · 아주 오래된 골 취소 — 0′ 골이 후반에 취소된다
 *   · 양쪽 자책골        — 점수가 넣은 선수의 반대편에 붙는지
 *   · 추가시간           — 전반이 2분이라 3분대 골은 `2+1′`로 뜬다
 *   · 하프타임           — 후반 시작에 시계가 45:00으로 점프하는지
 *
 * 취소는 분이 아니라 `id`로 지목한다 — 같은 분에 골이 여러 개라 분으로는 짚을 수 없다.
 */

/** 전반 정규 시간(분). 이 시간을 넘겨 들어간 골은 `2+1′`처럼 추가시간으로 적힌다 */
const FIRST_HALF_MIN = 2;
/** 후반 시계는 실물처럼 45:00부터 시작한다 (formatMatchClock과 같은 규칙) */
const SECOND_HALF_BASE_MIN = 45;
/** 후반 정규 시간(분) — 45+2 = 47분까지 */
const SECOND_HALF_MIN = SECOND_HALF_BASE_MIN + 2;

/**
 * 지금의 경기 분. 프론트 `formatMatchClock`과 같은 규칙으로 센다.
 * 정규 시간을 넘으면 `{time: 정규, added: 초과분}`으로 쪼개 네이버 표기에 맞춘다.
 */
function matchMinute() {
  const h = state.halfs;
  const now = Date.now();

  let elapsedMin;
  let nominal;
  if (h.secondHalfStarted) {
    elapsedMin = SECOND_HALF_BASE_MIN + Math.floor((now - h.secondHalfStarted) / 60_000);
    nominal = SECOND_HALF_MIN;
  } else if (h.firstHalfStarted) {
    elapsedMin = Math.floor((now - h.firstHalfStarted) / 60_000);
    nominal = FIRST_HALF_MIN;
  } else {
    return { time: 0, added: 0 };
  }

  return elapsedMin > nominal
    ? { time: nominal, added: elapsedMin - nominal }
    : { time: elapsedMin, added: 0 };
}
const P = {
  // 홈 (아틀레티)
  alvarez: { ko: '훌리안 알바레스', en: 'Julián Álvarez' },
  sorloth: { ko: '알렉산더 쇠를로트', en: 'Alexander Sørloth' },
  griezmann: { ko: '앙투안 그리즈만', en: 'Antoine Griezmann' },
  koke: { ko: '코케', en: 'Koke' },
  normand: { ko: '로빈 르 노르망', en: 'Robin Le Normand' },
  // 원정 (소시에다드)
  oyarzabal: { ko: '미켈 오야르사발', en: 'Mikel Oyarzabal' },
  kubo: { ko: '쿠보 다케후사', en: 'Takefusa Kubo' },
  mendez: { ko: '브라이스 멘데스', en: 'Brais Méndez' },
  remiro: { ko: '알렉스 레미로', en: 'Álex Remiro' },
};

/** 분은 여기서 정하지 않는다 — 골이 터지는 순간의 시계에서 뽑는다 */
const goal = (id, t, desc, isHome, player, extra = {}) => ({
  t, kind: 'goal', id, desc,
  goal: { isHome, ownGoal: false, ...player, ...extra },
});

/**
 * 선수 교체. 골과 달리 **FotMob 응답에만 실린다** — 네이버에는 교체 데이터가 없다.
 * 그래서 이 이벤트는 네이버 쪽 스코어·득점자를 건드리지 않는다.
 */
const sub = (id, t, desc, isHome, out, into, extra = {}) => ({
  t, kind: 'sub', id, desc,
  sub: { isHome, outEn: out.en, inEn: into.en, outKo: out.ko, inKo: into.ko, ...extra },
});

const STRESS_SCRIPT = [
  { t: 0, kind: 'status', code: 'STARTED', info: '경기중', desc: '킥오프' },

  // ── 0분: 3골. 시작부터 3초 간격 버스트 ──
  goal('g1', 6, '아틀레티 선제골', true, P.alvarez),
  goal('g2', 9, '소시에다드 즉답 (3초 뒤 — 한 턴에 같이 잡힐 것)', false, P.oyarzabal),
  goal('g3', 35, '쇠를로트 역전골', true, P.sorloth),

  // ── 1분: 3골 + 목록 중간 취소 ──
  goal('g4', 66, '쿠보 동점골', false, P.kubo),
  goal('g5', 69, '멘데스 연속골 (3초 뒤 버스트)', false, P.mendez),
  { t: 80, kind: 'cancel', id: 'g3', desc: 'VAR — 쇠를로트 골 취소 (목록 가운데가 사라진다)' },
  goal('g6', 95, '그리즈만 추격골', true, P.griezmann),

  // ── 2분: 3골. 양쪽 자책골 ──
  goal('g7', 126, '르 노르망 자책골 (홈 선수 → 원정 득점)', true, P.normand, { ownGoal: true }),
  goal('g8', 129, '코케 즉답 (3초 뒤 버스트, 같은 분)', true, P.koke),
  goal('g9', 155, '레미로 자책골 (원정 GK → 홈 득점)', false, P.remiro, { ownGoal: true }),

  // ── 전반 추가시간: 정규 2분을 넘겼으므로 `2+1′`로 적혀야 한다 ──
  goal('g10', 185, '전반 추가시간 알바레스 멀티골', true, P.alvarez),

  // ── 하프타임 → 후반 ──
  // 여기서 LED 시계가 45:00으로 점프해야 한다.
  //
  // `전반종료`는 **네이버가 실제로 쓰는 표기다** (2026-08-20 말라가전 실측).
  // 예전에는 여기에 지어낸 `하프타임`을 넣어 뒀는데, 그 바람에 리허설이
  // `"전반종료".contains("종료")` 오판정을 통과시켰다 — 실경기에서 폴러가 전반 끝나자마자
  // 멈춰 후반 골이 통째로 안 나갔다 (docs/round1.md). 지어낸 값으로 검증한 대가다.
  { t: 195, kind: 'status', code: 'HALFTIME', info: '전반종료', desc: '전반 종료' },
  { t: 210, kind: 'status', code: 'STARTED', info: '경기중', desc: '후반 시작 (시계가 45:00으로 점프해야 한다)' },

  // ── 45분: 3골 ──
  goal('g11', 215, '알바레스 해트트릭', true, P.alvarez),
  goal('g12', 218, '오야르사발 즉답 (3초 뒤 버스트)', false, P.oyarzabal),
  goal('g13', 245, '쿠보 동점골', false, P.kubo),

  // ── 46분: 취소 번복과 아주 오래된 골 취소 ──
  { t: 252, kind: 'cancel', id: 'g13', desc: 'VAR — 쿠보 골 취소' },
  { t: 262, kind: 'restore', id: 'g13', desc: 'VAR 번복 — 쿠보 골 인정 (다시 알려야 정상)' },
  goal('g14', 275, '쇠를로트 결승골', true, P.sorloth),
  { t: 282, kind: 'cancel', id: 'g1', desc: 'VAR — 0′ 알바레스 취소 (경기 초반 골을 후반에)' },

  { t: 300, kind: 'status', code: 'RESULT', info: '경기종료', desc: '경기 종료' },
];

// ─────────────────── 대본 2: 8/20 말라가전 예행연습 ───────────────────
//
// 위 대본이 "폴러가 무너지는지" 보는 것이라면, 이건 **내일 본 경기와 같은 조건에서
// 화면이 어떻게 보이는지** 보는 것이다. 그래서 골이 4개뿐이고 페이스가 정상이다.
//
// **t가 실제 경기 초 단위다.** 전반 45분이면 t=2700이고, 대본 전체가 110분이다.
// 기본 속도(--speed=1)로 돌리면 화면의 분 표기가 실제 경기와 똑같이 흐른다 —
// 45+1′, 63′, 88′, 90+4′가 그 시각에 그대로 뜬다.
//
// 급하면 `--speed=6`으로 18분에 끝낼 수 있지만, **그러면 분 표기가 같이 줄어든다.**
// 시계는 실제 흐른 시간으로 세는데 대본만 빨리 감기기 때문이다. 표기까지 진짜처럼
// 보려면 속도를 건드리지 않아야 한다.
//
// 이 대본으로만 확인되는 것이 하나 있다: ㉡의 **"전반 43분 이후 촘촘히"** 조건이다
// (docs/live-match-8-15-postmortem.md). 5분짜리 스트레스 대본에서는 벽시계로 43분이
// 오지 않아 그 분기가 아예 안 돌았다.
/**
 * 26/27 시즌 실제 명단 (2026-08-19 FotMob 조회).
 *
 * 한글 표기는 네이버가 쓸 법한 음역이다. 이 스크립트가 네이버 대역이므로 여기 적은 것이
 * 곧 "네이버가 주는 이름"이 되고, 라틴 표기는 FotMob 대역이 준다. **둘을 일부러 다르게
 * 둔 것이 핵심이다** — 서버는 이름으로 짝을 짓지 않고 팀·분으로 맞추기 때문이다.
 *
 * `leeKangIn`이 그 시험대다. `이강인` ↔ `Kang-In Lee`는 글자가 하나도 안 겹치는데,
 * 짝짓기가 이름을 안 보므로 그대로 붙어야 정상이다. 프론트에서는 성만 떼어 `LEE`가 된다.
 */
const MALAGA_P = {
  // ── 아틀레티 ──
  alvarez: { ko: '훌리안 알바레스', en: 'Julián Álvarez' },
  domInguez: { ko: '호르헤 도밍게스', en: 'Jorge Domínguez' },
  lemar: { ko: '토마 르마르', en: 'Thomas Lemar' },
  martin: { ko: '카를로스 마르틴', en: 'Carlos Martín' },
  sorloth: { ko: '알렉산더 쇠를로트', en: 'Alexander Sørloth' },
  baena: { ko: '알렉스 바에나', en: 'Álex Baena' },
  lookman: { ko: '아데몰라 루크만', en: 'Ademola Lookman' },
  leeKangIn: { ko: '이강인', en: 'Kang-In Lee' },
  gSimeone: { ko: '줄리아노 시메오네', en: 'Giuliano Simeone' },
  koke: { ko: '코케', en: 'Koke' },
  llorente: { ko: '마르코스 요렌테', en: 'Marcos Llorente' },
  normand: { ko: '로빈 르 노르망', en: 'Robin Le Normand' },

  // ── 말라가 ──
  larrubia: { ko: '다비드 라루비아', en: 'David Larrubia' },
  chupe: { ko: '추페', en: 'Chupe' },
  nino: { ko: '아드리안 니노', en: 'Adrián Nino' },
  jauregi: { ko: '에네코 하우레기', en: 'Eneko Jauregi' },
  lorenzo: { ko: '다니 로렌소', en: 'Dani Lorenzo' },
};

const MALAGA_SCRIPT = [
  { t: 0, kind: 'status', code: 'STARTED', info: '경기중', desc: '킥오프' },

  goal('m1', 14 * 60, "14' 알바레스 선제골", true, MALAGA_P.alvarez),
  goal('m2', 31 * 60, "31' 라루비아 동점골", false, MALAGA_P.larrubia),

  // 전반 45분 + 추가시간 1분 → 화면에 `45+1′`로 찍혀야 한다
  { t: 46 * 60, kind: 'status', code: 'HALFTIME', info: '전반종료', desc: '전반 종료 (시계가 멈춰야 한다)' },

  // 휴식 15분. 후반 시계는 45:00부터 다시 센다
  { t: 61 * 60, kind: 'status', code: 'STARTED', info: '경기중', desc: '후반 시작 (시계가 45:00으로 점프)' },

  goal('m3', 61 * 60 + 18 * 60, "63' 쇠를로트 역전골", true, MALAGA_P.sorloth),

  // 취소 한 건 — 실제 경기에 흔한 빈도다. 스트레스 대본처럼 몰아치지 않는다
  goal('m4', 61 * 60 + 26 * 60, "71' 바에나 추가골 (곧 취소된다)", true, MALAGA_P.baena),
  { t: 61 * 60 + 28 * 60, kind: 'cancel', id: 'm4', desc: "VAR — 71' 바에나 골 취소 (오프사이드)" },

  // 이강인 ↔ Kang-In Lee — 두 소스의 이름이 하나도 안 겹친다. 보드에 LEE가 떠야 정상
  goal('m5', 61 * 60 + 43 * 60, "88' 이강인 쐐기골", true, MALAGA_P.leeKangIn),

  // 후반 추가시간 4분 → `90+4′`
  { t: 61 * 60 + 49 * 60, kind: 'status', code: 'RESULT', info: '경기종료', desc: '경기 종료 (3-1)' },
];

// ─────────────── 대본 3: 말라가전 전반만, 골 몰아서 ───────────────
//
// `malaga`는 실제 페이스라 하프타임까지 46분을 기다려야 한다. 이건 **같은 팀·같은 리그로
// 전반만 5분에 압축**한 것이다. 골 연출을 여러 번 보고 하프타임 정지까지 확인하는 용도.
//
// 압축했으므로 분 표기는 `0′~4′`로 작게 나온다. 표기까지 진짜처럼 보려면 `malaga`를 쓴다.
// 후반은 없다 — 하프타임에서 대본이 끝난다.
const MALAGA_HALF_SCRIPT = [
  { t: 0, kind: 'status', code: 'STARTED', info: '경기중', desc: '킥오프' },

  // 득점자를 매번 다르게 둔다 — 라틴 표기가 선수마다 제대로 붙는지 보려는 것이다
  goal('h1', 20, '알바레스 선제골', true, MALAGA_P.alvarez),
  goal('h2', 45, '라루비아 동점골', false, MALAGA_P.larrubia),
  goal('h3', 75, '바에나 역전골', true, MALAGA_P.baena),

  // 자책골 — 넣은 선수는 아틀레티인데 점수는 말라가에 붙어야 한다
  goal('h4', 105, '르 노르망 자책골 (홈 선수 → 원정 득점)', true, MALAGA_P.normand, { ownGoal: true }),

  // 3초 간격 버스트 — 한 턴(5초)에 두 골이 같이 잡히는지
  goal('h5', 140, '이강인 재역전골 (이강인 ↔ Kang-In Lee 짝짓기)', true, MALAGA_P.leeKangIn),
  goal('h6', 143, '추페 즉답 (3초 뒤)', false, MALAGA_P.chupe),

  { t: 170, kind: 'cancel', id: 'h3', desc: 'VAR — 바에나 골 취소 (목록 가운데가 사라진다)' },

  goal('h7', 200, '루크만 동점골', true, MALAGA_P.lookman),

  /*
    ── 교체 ── FotMob에만 실린다. 보드가 5초간 교체 화면으로 바뀌어야 한다.

    골보다 늦게 뜬다. 골은 네이버를 5초마다 보지만 교체는 FotMob을 45초마다 보고,
    거기에 라이브 지연(기본 30초)이 더 붙는다. 그래서 대본에서도 서로 띄워 둔다 —
    붙여 놓으면 어느 교체가 늦은 건지 화면만 보고는 구분이 안 된다.
  */
  sub('s1', 110, '요렌테 → 도밍게스 (보드가 교체 화면으로)', true, MALAGA_P.llorente, MALAGA_P.domInguez),

  // 상대팀 교체 — **보드가 안 바뀌어야 정상이다** (아틀레티 것만 띄운다)
  sub('s2', 175, '라루비아 → 로렌소 (말라가 — 보드 안 바뀜)', false, MALAGA_P.larrubia, MALAGA_P.lorenzo),

  // 부상 교체 — 화살표가 ⇄ 가 아니라 ✚ 로 떠야 한다
  sub('s3', 225, '르마르 → 마르틴 (부상 교체 — ✚ 표기)', true, MALAGA_P.lemar, MALAGA_P.martin, { injury: true }),

  // 여기서 시계가 멈춰야 한다 (8/15에 안 멈췄던 것).
  // 마지막 교체가 화면에 뜰 시간을 두고 잡는다 (FotMob 주기 + 지연)
  { t: 330, kind: 'status', code: 'HALFTIME', info: '전반종료', desc: '전반 종료 — 시계가 멈춰야 한다' },
];

const SCENARIOS = {
  stress: {
    desc: '골 15건 / VAR 취소 3건 — 폴러가 무너질 자리를 골라 넣은 스트레스 대본',
    home: { ko: 'AT 마드리드', en: 'Atletico Madrid' },
    away: { ko: '레알 소시에다드', en: 'Real Sociedad' },
    awayFotmobId: 8560,
    script: STRESS_SCRIPT,
  },
  malaga: {
    desc: '8/20 04:00 라리가 AT 마드리드 vs 말라가 예행연습 — 골 4건, 실제 페이스(110분)',
    home: { ko: 'AT 마드리드', en: 'Atletico Madrid' },
    // 네이버는 `말라가`, FotMob은 `Malaga`로 준다(악센트 없음 — 2026-08-19 실측).
    // 보드 약칭 표에 없는 팀이라 **라틴 이름 앞 세 글자(MAL)로 떨어지는 것이 정상**이다
    // (설계 11.3)
    away: { ko: '말라가', en: 'Malaga' },
    awayFotmobId: 9864,   // FotMob 실측 (2026-08-19). 내일 경기의 FotMob 매치 ID는 5868012다
    script: MALAGA_SCRIPT,
  },
  'malaga-half': {
    desc: '말라가전 전반만 — 골 7건 + VAR 취소 1건을 5분에 압축, 하프타임에서 끝',
    home: { ko: 'AT 마드리드', en: 'Atletico Madrid' },
    away: { ko: '말라가', en: 'Malaga' },
    awayFotmobId: 9864,
    script: MALAGA_HALF_SCRIPT,
  },
};

const SCENARIO = SCENARIOS[opt('scenario', 'stress')];
if (!SCENARIO) {
  console.error(`알 수 없는 대본입니다. 쓸 수 있는 값: ${Object.keys(SCENARIOS).join(', ')}`);
  process.exit(1);
}
/**
 * 대본은 **시각순으로 정렬해 둔다.**
 *
 * 실행 루프가 `SCRIPT[nextEvent]` 하나만 보고 때를 기다리므로(tick), 배열 순서가
 * 시각 순서와 다르면 앞선 항목이 끝날 때까지 뒤 항목을 아예 안 쳐다본다.
 * 실제로 교체를 대본 끝에 적어 넣었다가 t=110짜리가 t=200 뒤에 묶여 안 나왔다.
 *
 * 같은 시각끼리는 적어 둔 순서를 지킨다(정렬이 안정적이다) — `cancel` 다음 `restore`처럼
 * 순서가 뜻을 갖는 짝이 있다.
 */
const SCRIPT = [...SCENARIO.script].sort((a, b) => a.t - b.t);

// ─────────────────────────── 상태 ───────────────────────────

const startedAt = Date.now();

/**
 * 킥오프 시각. 대본 t=0의 기준이자 네이버·FotMob이 광고하는 예정 시각이다.
 *
 * 폴러가 이 경기를 잡기 전에 대본이 출발하면 그 사이 골이 **조용히 기준선으로 먹힌다**
 * (첫 스냅샷은 알리지 않고 기준만 잡는 것이 정상 동작이라서). 그래서 앱이 실제로
 * 보기 시작한 것을 확인한 뒤 필요하면 킥오프를 지금으로 당긴다.
 */
let kickoffMs = startedAt + KICKOFF_DELAY_SEC * 1000;

/** 폴러 확인 전에는 대본을 돌리지 않는다 */
let armed = false;

const state = {
  statusCode: 'BEFORE',
  statusInfo: '경기전',
  goals: [],          // { id, isHome, minute, added, ownGoal, ko, en }
  subs: [],           // { id, isHome, minute, added, outEn, inEn, injury } — FotMob에만 실린다
  naverDown: false,

  /**
   * 실제 전·후반 시작 시각 (epoch ms). FotMob의 `halfs`에 해당한다.
   * 대본이 그 지점을 지날 때 **실제로 지난 시각**을 적는다 — 미리 계산해 두면
   * `n` 키로 건너뛰었을 때 시계와 어긋난다.
   */
  halfs: { firstHalfStarted: null, firstHalfEnded: null, secondHalfStarted: null, gameEnded: null },
};

/** 취소된 골을 보관해 둔다 — VAR 번복으로 되살릴 수 있어야 한다 */
const cancelled = new Map();

let nextEvent = 0;
let requestCount = { naver: 0, fotmob: 0, scrape: 0 };
let lastSeenMessages = 0;

/** 채팅방에서 실제로 관측한 알림 수 — 대본이 기대하는 수와 맞아야 한다 */
const observed = { GOAL: 0, GOAL_CANCELLED: 0 };

/**
 * 대본이 기대하는 알림 수.
 * 취소 번복(restore)도 새 골로 다시 알려야 정상이다 — 폴러는 SREM 했던 키를
 * 다시 SADD 하게 되므로 "처음 보는 골"로 취급한다.
 */
const expected = {
  GOAL: SCRIPT.filter((e) => e.kind === 'goal' || e.kind === 'restore').length,
  GOAL_CANCELLED: SCRIPT.filter((e) => e.kind === 'cancel').length,
};

const HOME = SCENARIO.home;
const AWAY = SCENARIO.away;
const AWAY_FOTMOB_ID = SCENARIO.awayFotmobId;

function scores() {
  let home = 0;
  let away = 0;
  for (const g of state.goals) {
    // 자책골은 넣은 선수의 **반대편** 점수가 오른다
    if (g.isHome !== g.ownGoal) home++;
    else away++;
  }
  return { home, away };
}

function clock() {
  return new Date(Date.now() + KST_OFFSET_MS).toISOString().slice(11, 19);
}

function log(msg) {
  console.log(`  ${clock()}  ${msg}`);
}

// ─────────────────────────── 응답 만들기 ───────────────────────────

/** 네이버 벽시계 형식: 오프셋 없는 KST */
function naverDateTime(ms) {
  return new Date(ms + KST_OFFSET_MS).toISOString().slice(0, 19);
}

function naverGame() {
  const s = scores();
  const side = (isHome) => state.goals
    .filter((g) => g.isHome === isHome)
    .map((g) => ({ time: g.minute, addedTime: g.added, playerName: g.ko, ownGoal: g.ownGoal }));

  return {
    result: {
      game: {
        gameId: GAME_ID,
        gameDateTime: naverDateTime(kickoffMs),
        categoryName: '라리가',
        stadium: '리야드 에어 메트로폴리타노',
        homeTeamName: HOME.ko,
        homeTeamScore: s.home,
        awayTeamName: AWAY.ko,
        awayTeamScore: s.away,
        statusCode: state.statusCode,
        statusInfo: state.statusInfo,
        scorers: { home: side(true), away: side(false) },
      },
    },
  };
}

/**
 * FotMob의 halfs 표기 — `09.08.2026 13:03:30`. **타임존이 안 적혀 있다.**
 *
 * 실물은 유럽 시간으로 적어 오는데, 여기서는 일부러 **KST(UTC+9)** 로 내보낸다.
 * 서버가 `utcTime`과 대조해 오프셋을 역산하고 있으므로 어느 지역 시간으로 적든
 * 같은 순간으로 읽어야 정상이다. 역산이 깨져 있으면 LED 시계가 9시간 틀어져 바로 보인다.
 */
function fotmobHalf(ms) {
  if (!ms) return '';
  const d = new Date(ms + KST_OFFSET_MS);
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getUTCDate())}.${p(d.getUTCMonth() + 1)}.${d.getUTCFullYear()} ` +
         `${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}`;
}

function fotmobMatch() {
  const s = scores();
  const finished = state.statusCode === 'RESULT';
  const started = state.statusCode !== 'BEFORE';

  return {
    general: {
      homeTeam: { name: HOME.en, id: Number(ATLETI_TEAM_ID) },
      awayTeam: { name: AWAY.en, id: AWAY_FOTMOB_ID },
      started,
      finished,
    },
    header: {
      status: {
        // 오프셋 역산의 기준점. 이게 없으면 서버가 halfs를 통째로 버린다
        utcTime: new Date(kickoffMs).toISOString(),
        started,
        finished,
        cancelled: false,
        scoreStr: `${s.home} - ${s.away}`,
        halfs: {
          firstHalfStarted: fotmobHalf(state.halfs.firstHalfStarted),
          // --nohalfs면 전반 시작만 주고 나머지는 숨긴다 (실경기에서 그랬다)
          firstHalfEnded: NO_HALFS ? '' : fotmobHalf(state.halfs.firstHalfEnded),
          secondHalfStarted: NO_HALFS ? '' : fotmobHalf(state.halfs.secondHalfStarted),
          gameEnded: fotmobHalf(state.halfs.gameEnded),
        },
      },
      teams: [
        { name: HOME.en, score: s.home },
        { name: AWAY.en, score: s.away },
      ],
    },
    content: {
      matchFacts: {
        events: {
          events: [
            // 골이 아닌 이벤트도 섞어 둔다 — 파서가 걸러내는지 함께 확인한다
            { type: 'Half', time: 45 },
            { type: 'Card', time: 33, isHome: true, player: { name: 'Koke' } },
            // 교체는 **네이버에 없어서** 여기가 유일한 소스다. swap은 [나가는 선수, 들어오는 선수]
            ...state.subs.map((s) => ({
              type: 'Substitution',
              time: s.minute,
              overloadTime: s.added || null,
              isHome: s.isHome,
              injuredPlayerOut: s.injury || false,
              swap: [{ name: s.outEn }, { name: s.inEn }],
            })),
            ...state.goals.map((g) => ({
              type: 'Goal',
              time: g.minute,
              // FotMob은 추가시간을 overloadTime으로 준다. 값이 없으면 null
              overloadTime: g.added || null,
              isHome: g.isHome,
              // 실측에서 자책골이 아닌 골은 false가 아니라 null로 왔다
              ownGoal: g.ownGoal ? true : null,
              player: { name: g.en },
            })),
          ],
        },
      },
    },
  };
}

/** FotMob 팀 페이지 — MatchScraperService가 __NEXT_DATA__에서 일정을 긁어간다 */
function fotmobTeamPage() {
  const next = {
    props: {
      pageProps: {
        fallback: {
          [`team-${ATLETI_TEAM_ID}`]: {
            overview: {
              // lastMatch는 일부러 비운다 — 경기 결과 스크랩까지 흉내낼 필요는 없다
              overviewFixtures: [
                {
                  id: MATCH_ID,
                  status: { utcTime: new Date(kickoffMs).toISOString(), finished: false, cancelled: false },
                  home: { id: ATLETI_TEAM_ID, name: HOME.en },
                  away: { id: String(AWAY_FOTMOB_ID), name: AWAY.en },
                  opponent: { name: AWAY.en },
                  tournament: { name: 'LaLiga' },
                },
              ],
            },
          },
        },
      },
    },
  };

  return `<!doctype html><html><head><title>fake</title></head><body>
<script id="__NEXT_DATA__" type="application/json">${JSON.stringify(next)}</script>
</body></html>`;
}

// ─────────────────────────── 서버 ───────────────────────────

function send(res, status, body, type = 'application/json; charset=utf-8') {
  const payload = typeof body === 'string' ? body : JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': type,
    // 네이버를 흉내낸다 (설계 2.2). 폴러가 캐시에 기대지 않는지 함께 확인
    'Cache-Control': 'no-cache, no-store, must-revalidate',
  });
  res.end(payload);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const path = url.pathname;

  // ── 네이버 ──
  if (path.startsWith('/schedule/')) {
    if (state.naverDown) {
      requestCount.naver++;
      log(`🔴 네이버 503  ${path}`);
      return send(res, 503, { error: 'fake outage' });
    }

    if (path === '/schedule/calendar') {
      requestCount.naver++;
      log(`   네이버 캘린더 조회 (${url.searchParams.get('yearMonth')})`);
      return send(res, 200, { result: { dates: [{ gameIds: [GAME_ID] }] } });
    }
    if (path === `/schedule/games/${GAME_ID}`) {
      requestCount.naver++;
      const s = scores();
      log(`   네이버 경기 조회  ${state.statusInfo} ${s.home}-${s.away} (${requestCount.naver}회)`);
      return send(res, 200, naverGame());
    }
    return send(res, 404, { error: 'unknown game' });
  }

  // ── FotMob ──
  if (path === '/api/data/matchDetails') {
    requestCount.fotmob++;
    const s = scores();
    log(`   FotMob 조회      ${state.statusInfo} ${s.home}-${s.away} (${requestCount.fotmob}회)`);
    return send(res, 200, fotmobMatch());
  }
  if (path.startsWith('/teams/')) {
    requestCount.scrape++;
    log(`   FotMob 팀 페이지 스크랩 (일정 1건 제공)`);
    return send(res, 200, fotmobTeamPage(), 'text/html; charset=utf-8');
  }
  if (path.startsWith('/match/')) {
    // 경기 상세 스크랩 — 리허설 대상이 아니라 빈 페이지로 넘긴다
    return send(res, 200, '<html></html>', 'text/html; charset=utf-8');
  }

  send(res, 404, { error: 'not found' });
});

// ─────────────────────────── 대본 진행 ───────────────────────────

function applyEvent(ev) {
  if (ev.kind === 'status') {
    state.statusCode = ev.code;
    state.statusInfo = ev.info;

    // 경기 시계 기준점을 지금 시각으로 찍는다
    const now = Date.now();
    if (ev.code === 'STARTED' && !state.halfs.firstHalfStarted) state.halfs.firstHalfStarted = now;
    else if (ev.code === 'HALFTIME') state.halfs.firstHalfEnded = now;
    else if (ev.code === 'STARTED' && !state.halfs.secondHalfStarted) state.halfs.secondHalfStarted = now;
    else if (ev.code === 'RESULT') state.halfs.gameEnded = now;
  } else if (ev.kind === 'goal') {
    // 분을 지금 찍는다 — LED 시계와 같은 계산이라 둘이 어긋날 수 없다
    const { time, added } = matchMinute();
    state.goals.push({ id: ev.id, minute: time, added, ...ev.goal });
  } else if (ev.kind === 'sub') {
    // 교체도 분을 지금 찍는다. 골과 달리 스코어에는 영향이 없다
    const { time, added } = matchMinute();
    state.subs.push({ id: ev.id, minute: time, added, ...ev.sub });
  } else if (ev.kind === 'cancel') {
    const hit = state.goals.find((g) => g.id === ev.id);
    if (hit) cancelled.set(ev.id, hit);
    state.goals = state.goals.filter((g) => g.id !== ev.id);
  } else if (ev.kind === 'restore') {
    const hit = cancelled.get(ev.id);
    if (hit) {
      state.goals.push(hit);
      cancelled.delete(ev.id);
    }
  }

  const s = scores();
  const mark =
    ev.kind === 'goal' ? '⚽'
    : ev.kind === 'sub' ? '🔄'
    : ev.kind === 'cancel' ? '❌'
    : ev.kind === 'restore' ? '♻️'
    : '▶';
  console.log('');
  log(`${mark} 대본: ${ev.desc}   →  ${s.home}-${s.away}`);
  console.log('');
}

function tick() {
  if (!armed || nextEvent >= SCRIPT.length) return;
  const ev = SCRIPT[nextEvent];
  const dueAt = kickoffMs + (ev.t * 1000) / SPEED;
  if (Date.now() >= dueAt) {
    nextEvent++;
    applyEvent(ev);
  }
}

// ─────────────────────────── 앱 쪽 확인 ───────────────────────────

/** 앱이 뜨면 일정 동기화를 대신 눌러 준다 — upcoming_match가 있어야 폴러가 깬다 */
async function waitForAppAndSync() {
  for (;;) {
    try {
      const r = await fetch(`${APP}/api/matches/sync`);
      const text = await r.text();
      log(`✅ 앱 일정 동기화: ${text.trim()}`);
      return;
    } catch {
      await new Promise((r) => setTimeout(r, 2000));
    }
  }
}

/**
 * 폴러가 이 경기를 실제로 보기 시작할 때까지 기다린다.
 *
 * 판단 기준은 방 정보의 `live.updatedAt`이 **이 스크립트가 뜬 뒤**인지다. 스냅샷이
 * 있기만 한 것으로는 부족하다 — 지난 판의 낡은 스냅샷이 Redis에 남아 있을 수 있다.
 */
async function waitUntilPollerWatching() {
  log('⏳ 폴러가 이 경기를 잡을 때까지 기다립니다...');
  for (;;) {
    try {
      const room = await fetch(`${APP}/api/chat/${MATCH_ID}`).then((r) => r.json());
      const seenAt = room?.live?.updatedAt ? new Date(room.live.updatedAt).getTime() : 0;
      if (seenAt >= startedAt) return room;
    } catch {
      // 앱이 아직 준비 중
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
}

/** 동기화 → 폴러 확인 → 대본 출발. 이 순서를 지켜야 첫 골을 놓치지 않는다 */
async function armTimeline() {
  await waitForAppAndSync();
  const room = await waitUntilPollerWatching();

  // 방에 이미 쌓여 있던 메시지는 지난 판의 것이다. 세지 않는다 —
  // 그러지 않으면 판정이 시작부터 어긋나고, 어제 골이 지금 터진 것처럼 출력된다.
  const backlog = (room?.messages || []).length;
  if (backlog > 0) log(`ℹ️ 방에 남아 있던 메시지 ${backlog}건은 세지 않습니다`);
  lastSeenMessages = backlog;

  const now = Date.now();
  if (kickoffMs < now) {
    // 기다리는 사이 예정 킥오프가 지났으면 지금을 킥오프로 삼는다.
    // 앱이 스크랩해 둔 예정 시각과는 몇 초 어긋나는데, 매핑 허용 오차(±30분) 안이라 문제없다.
    kickoffMs = now;
  }
  armed = true;

  const wait = Math.max(0, Math.round((kickoffMs - now) / 1000));
  log(`✅ 폴러 확인 — ${wait > 0 ? `${wait}초 뒤 킥오프` : '킥오프'}`);
  console.log('');
}

/** 유저가 실제로 보는 것 — 방 정보 API를 그대로 읽어 새 메시지와 스코어보드를 출력한다 */
async function pollApp() {
  if (!armed) return;   // 대본이 출발하기 전 출력은 전부 지난 판의 것이다

  let room;
  try {
    room = await fetch(`${APP}/api/chat/${MATCH_ID}`).then((r) => r.json());
  } catch {
    return;
  }

  const messages = room.messages || [];
  if (messages.length > lastSeenMessages) {
    for (const m of messages.slice(lastSeenMessages)) {
      if (m.type in observed) observed[m.type]++;
      const badge = m.type === 'GOAL' ? '⚽' : m.type === 'GOAL_CANCELLED' ? '⚠️' : '💬';
      console.log(`\n  ${badge} [채팅방] ${m.nickname}: ${m.content}`);
      if (m.live) {
        console.log(
          `        스코어보드 ${m.live.homeTeam} ${m.live.homeScore} - ` +
          `${m.live.awayScore} ${m.live.awayTeam}  (${m.live.status})`,
        );
        for (const s of m.live.scorers || []) {
          const min = s.addedTime ? `${s.time}+${s.addedTime}'` : `${s.time}'`;
          console.log(`          · ${min} ${s.name}${s.ownGoal ? ' (자책골)' : ''}`);
        }
      }
      console.log('');
    }
    lastSeenMessages = messages.length;
  }
}

function showState() {
  const s = scores();
  const live = Math.round((Date.now() - kickoffMs) / 1000);
  const done = nextEvent >= SCRIPT.length;

  console.log('');
  console.log('  ─────────────────── 현재 상태 ───────────────────');
  console.log(`     경기      : ${state.statusInfo} (${state.statusCode})  ${s.home}-${s.away}`);
  console.log(`     킥오프    : ${live >= 0 ? `+${live}초` : `${-live}초 뒤`}`);
  console.log(`     네이버    : ${state.naverDown ? '🔴 죽음' : '🟢 정상'}  (${requestCount.naver}회 호출)`);
  console.log(`     FotMob    : ${requestCount.fotmob}회 호출`);
  console.log(`     남은 대본 : ${SCRIPT.length - nextEvent}건`);
  console.log('');
  console.log(`     골 알림   : ${observed.GOAL} / ${expected.GOAL} 건`);
  console.log(`     취소 알림 : ${observed.GOAL_CANCELLED} / ${expected.GOAL_CANCELLED} 건`);

  if (done) {
    const ok = observed.GOAL === expected.GOAL && observed.GOAL_CANCELLED === expected.GOAL_CANCELLED;
    console.log('');
    if (ok) {
      console.log('     판정: ✅ 대본과 알림 수가 일치합니다.');
    } else {
      // 많으면 중복 발행, 적으면 놓친 것. 어느 쪽이든 로그에서 해당 시각을 찾아본다
      console.log('     판정: ❌ 수가 어긋납니다.');
      if (observed.GOAL > expected.GOAL) console.log('           골 알림이 많습니다 — 중복 발행 의심 (소스 전환 직후를 보세요)');
      if (observed.GOAL < expected.GOAL) console.log('           골 알림이 적습니다 — 놓친 골 의심 (버스트 구간을 보세요)');
      if (observed.GOAL_CANCELLED > expected.GOAL_CANCELLED) console.log('           취소 알림이 많습니다 — 없는 취소를 알렸을 수 있습니다');
      if (observed.GOAL_CANCELLED < expected.GOAL_CANCELLED) console.log('           취소 알림이 적습니다 — VAR 감지 누락');
    }
    console.log('');
    console.log('     ※ 네이버를 죽였던(k) 경우엔 어긋나는 게 정상입니다 —');
    console.log('       소스 전환 턴에는 취소를 알리지 않고, 끊긴 사이의 골만 뒤늦게 알립니다 (설계 8.6)');
  }

  console.log(`     득점      : ${state.goals.map((g) => `${g.minute}${g.added ? `+${g.added}` : ''}' ${g.ko}${g.ownGoal ? '(자책)' : ''}`).join(', ') || '없음'}`);
  console.log('  ────────────────────────────────────────────────');
  console.log('');
}

// ─────────────────────────── 진입점 ───────────────────────────

function setupKeys() {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: false });
  rl.on('line', (line) => {
    const key = line.trim().toLowerCase();
    if (key === 'k') {
      state.naverDown = true;
      log('🔴 네이버를 죽였습니다 — 연속 3회 실패 후 FotMob으로 내려가야 합니다');
    } else if (key === 'r') {
      state.naverDown = false;
      log('🟢 네이버를 살렸습니다 — 폴백 유지 시간(5분)이 지나야 되돌아옵니다');
    } else if (key === 'n') {
      if (nextEvent < SCRIPT.length) {
        const ev = SCRIPT[nextEvent++];
        applyEvent(ev);
      } else {
        log('대본이 끝났습니다');
      }
    } else if (key === 's') {
      showState();
    } else if (key === 'q') {
      showState();
      process.exit(0);
    }
  });
}

server.listen(PORT, () => {
  console.log('');
  console.log('══════════════════════════════════════════════════════');
  console.log('  가짜 라이브 경기 서버');
  console.log('══════════════════════════════════════════════════════');
  console.log(`  주소     : http://localhost:${PORT}`);
  console.log(`  경기     : ${HOME.ko} vs ${AWAY.ko}`);
  console.log(`  matchId  : ${MATCH_ID}   gameId: ${GAME_ID}`);
  console.log(`  대본     : ${SCENARIO.desc}`);
  console.log(`             골 ${expected.GOAL}건 / VAR 취소 ${expected.GOAL_CANCELLED}건, 속도 ${SPEED}배`);
  console.log(`             경기 ${Math.round(SCRIPT[SCRIPT.length - 1].t / SPEED)}초`);
  console.log('');
  console.log('  앱(백엔드)이 떠 있으면 곧바로 시작합니다.');
  console.log('  아직이면 여기서 기다립니다 — 앱을 띄우면 알아서 이어집니다.');
  console.log('');
  console.log('  키:  k 네이버 죽이기 / r 살리기 / n 다음 이벤트 / s 상태 / q 종료');
  console.log('  끝나고 s 를 누르면 대본과 실제 알림 수가 맞는지 판정해 줍니다.');
  console.log('══════════════════════════════════════════════════════');
  console.log('');

  setupKeys();
  armTimeline();
  setInterval(tick, 250);
  setInterval(pollApp, 1000);
});
