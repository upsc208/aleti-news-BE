#!/usr/bin/env node
'use strict';

/**
 * 라이브 피드 지연 측정 스크립트 (docs/live-match-feed-design.md 8장)
 *
 * 목적: "네이버 API가 골을 반영한 시각"과 "내가 중계 화면에서 골을 본 시각"의
 *       차이를 재서, 봇이 시청자보다 얼마나 빠른지(=스포일러가 되는지) 확정한다.
 *
 * 사용법:
 *   node scripts/measure-live-feed.js <gameId> [--interval=2] [--out=경로]
 *
 * 예시 (2026-08-15 마르세유 vs AT 마드리드):
 *   node scripts/measure-live-feed.js QyRcDHa8DA07hWz
 *
 * 진행 방법:
 *   1. 킥오프 10분 전쯤 실행해 둔다.
 *   2. 중계 화면에서 골이 들어가는 순간 이 터미널에 Enter를 친다.
 *      (메모를 남기려면 아무 글자나 치고 Enter — 예: "전반 43분 도밍게스")
 *   3. 경기가 끝나면 Ctrl+C. 짝지어진 결과표가 출력된다.
 *
 * gameId 찾는 법:
 *   node scripts/measure-live-feed.js --find
 */

const fs = require('fs');
const path = require('path');
const readline = require('readline');

const GAME_URL = 'https://api-gw.sports.naver.com/schedule/games/';
const CALENDAR_URL = 'https://api-gw.sports.naver.com/schedule/calendar';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
           '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
const HEADERS = { 'User-Agent': UA, 'Referer': 'https://m.sports.naver.com/' };

/** 시청자 마크와 피드 이벤트를 짝지을 때 인정하는 최대 시간차 */
const MATCH_WINDOW_MS = 180_000;

// ─────────────────────────────── 인자 파싱 ───────────────────────────────

const argv = process.argv.slice(2);
const flag = (name) => argv.includes(`--${name}`);
const opt = (name, fallback) => {
  const hit = argv.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : fallback;
};

const gameId = argv.find((a) => !a.startsWith('--'));
const intervalMs = Math.max(1000, Number(opt('interval', '2')) * 1000);
const outPath = opt('out', path.join(__dirname, `live-measure-${gameId || 'unknown'}.jsonl`));

// ─────────────────────────────── 유틸 ───────────────────────────────

/** 실행 환경 시간대와 무관하게 KST 벽시계로 찍는다 */
function kst(d = new Date()) {
  return new Date(d.getTime() + 9 * 3600_000).toISOString().replace('T', ' ').slice(0, 23);
}

/** 골 하나를 고유하게 식별하는 키. 네이버 응답에는 eventId가 없어 합성해야 한다. */
function goalKey(g) {
  return `${g.isHome ? 'H' : 'A'}|${g.time}+${g.addedTime}|${g.playerName}|${g.ownGoal}`;
}

/** {home:[], away:[]} 를 isHome 플래그가 붙은 평평한 배열로 */
function flattenScorers(scorers) {
  const out = [];
  for (const side of ['home', 'away']) {
    for (const g of (scorers && scorers[side]) || []) {
      out.push({
        name: g.playerName,
        time: g.time,
        addedTime: g.addedTime || 0,
        ownGoal: !!g.ownGoal,
        isHome: side === 'home',
      });
    }
  }
  return out;
}

function describe(g) {
  const min = g.addedTime ? `${g.time}+${g.addedTime}'` : `${g.time}'`;
  return `${min} ${g.name}${g.ownGoal ? ' (자책골)' : ''} [${g.isHome ? 'HOME' : 'AWAY'}]`;
}

// ─────────────────────────────── 기록 ───────────────────────────────

const log = [];            // 파일에 남길 전체 기록
const feedGoals = [];      // 피드가 골을 반영한 시각
const viewerMarks = [];    // 내가 화면에서 골을 본 시각

let out;
function record(entry) {
  const row = { at: kst(), ...entry };
  log.push(row);
  if (out) out.write(JSON.stringify(row) + '\n');
}

// ─────────────────────────────── gameId 찾기 ───────────────────────────────

async function findGames() {
  const now = new Date();
  const ym = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  console.log(`\n${ym} 아틀레티코 경기를 찾는 중...\n`);

  const cal = await fetch(
    `${CALENDAR_URL}?upperCategoryId=wfootball&categoryId=primera&yearMonth=${ym}`,
    { headers: HEADERS },
  ).then((r) => r.json());

  const ids = [];
  for (const d of (cal.result && cal.result.dates) || []) {
    for (const g of d.gameIds || []) ids.push(g);
  }

  let i = 0;
  const hits = [];
  await Promise.all(
    Array.from({ length: 8 }, async () => {
      while (i < ids.length) {
        const id = ids[i++];
        try {
          const g = (await fetch(GAME_URL + id, { headers: HEADERS }).then((r) => r.json()))
            .result?.game;
          if (g && /AT 마드리드/.test(`${g.homeTeamName} ${g.awayTeamName}`)) hits.push(g);
        } catch { /* 개별 실패는 무시 */ }
      }
    }),
  );

  hits.sort((a, b) => (a.gameDateTime < b.gameDateTime ? -1 : 1));
  for (const g of hits) {
    console.log(
      `  ${g.gameDateTime.replace('T', ' ').slice(0, 16)}  ` +
      `${String(g.categoryName).padEnd(10)}  ` +
      `${g.homeTeamName} vs ${g.awayTeamName}  →  ${g.gameId}`,
    );
  }
  console.log('');
}

// ─────────────────────────────── 폴링 ───────────────────────────────

const seen = new Map();   // goalKey → 골 객체
let lastStatus = null;
let pollCount = 0;
let errorCount = 0;
let rateLimitCount = 0;
let backoffMs = 0;
let started = false;

async function pollOnce() {
  const t0 = Date.now();
  let res, body;
  try {
    res = await fetch(GAME_URL + gameId, { headers: HEADERS });
    body = await res.text();
  } catch (e) {
    errorCount++;
    backoffMs = Math.min(30_000, (backoffMs || intervalMs) * 2);
    record({ kind: 'error', message: String(e.message || e), backoffMs });
    console.log(`  ⚠️  요청 실패: ${e.message} — ${backoffMs / 1000}초 후 재시도`);
    return;
  }

  const durationMs = Date.now() - t0;

  if (res.status === 429) {
    rateLimitCount++;
    backoffMs = Math.min(60_000, (backoffMs || intervalMs) * 2);
    record({ kind: 'rate_limit', status: 429, backoffMs });
    console.log(`  ⚠️  429 Too Many Requests — ${backoffMs / 1000}초 후 재시도`);
    return;
  }
  if (!res.ok) {
    errorCount++;
    record({ kind: 'http_error', status: res.status });
    console.log(`  ⚠️  HTTP ${res.status}`);
    return;
  }

  backoffMs = 0;
  pollCount++;

  let game;
  try {
    game = JSON.parse(body).result.game;
  } catch (e) {
    errorCount++;
    record({ kind: 'parse_error', message: String(e.message || e) });
    return;
  }

  // 첫 폴링: 경기 정보와 이미 들어간 골을 기준선으로 잡는다 (지연 측정 대상 아님)
  if (!started) {
    started = true;
    console.log(
      `\n  경기   : ${game.homeTeamName} vs ${game.awayTeamName}` +
      `\n  대회   : ${game.categoryName}` +
      `\n  경기장 : ${game.stadium}` +
      `\n  킥오프 : ${game.gameDateTime.replace('T', ' ')} KST\n`,
    );
    record({
      kind: 'start',
      gameId,
      home: game.homeTeamName,
      away: game.awayTeamName,
      category: game.categoryName,
      kickoff: game.gameDateTime,
      intervalMs,
    });
    for (const g of flattenScorers(game.scorers)) {
      seen.set(goalKey(g), g);
      console.log(`  (기존 골) ${describe(g)}`);
    }
  }

  // 8장 미검증 항목 — 라이브 중의 실제 값을 남긴다
  const meta = {
    statusCode: game.statusCode,
    statusInfo: game.statusInfo,
    score: `${game.homeTeamScore}-${game.awayTeamScore}`,
    cacheControl: res.headers.get('cache-control'),
    bytes: Buffer.byteLength(body),
    durationMs,
  };

  if (game.statusInfo !== lastStatus) {
    console.log(`\n  ── 상태 변경: ${lastStatus || '(최초)'} → ${game.statusInfo} ──`);
    console.log(`     cache-control: ${meta.cacheControl} / ${meta.bytes}B / ${durationMs}ms\n`);
    record({ kind: 'status_change', from: lastStatus, ...meta });
    lastStatus = game.statusInfo;
  }

  // ── 골 변화 감지 ──
  const current = flattenScorers(game.scorers);
  const currentKeys = new Set(current.map(goalKey));

  for (const g of current) {
    const key = goalKey(g);
    if (seen.has(key)) continue;
    seen.set(key, g);
    const at = new Date();
    feedGoals.push({ at, goal: g, kind: 'GOAL' });
    record({ kind: 'feed_goal', goal: describe(g), score: meta.score, ...meta });
    console.log('');
    console.log(`  ⚽ [피드 반영] ${kst(at)}  ${describe(g)}   →  ${meta.score}`);
    console.log(`     화면에서 골을 본 순간 Enter를 치세요.`);
    console.log('');
  }

  for (const [key, g] of [...seen]) {
    if (currentKeys.has(key)) continue;
    seen.delete(key);
    const at = new Date();
    feedGoals.push({ at, goal: g, kind: 'CANCELLED' });
    record({ kind: 'feed_goal_cancelled', goal: describe(g), score: meta.score, ...meta });
    console.log(`\n  ⚠️  [VAR 취소] ${kst(at)}  ${describe(g)}   →  ${meta.score}\n`);
  }

  // 60초마다 생존 신호 (파일이 불필요하게 커지지 않게)
  if (pollCount % Math.max(1, Math.round(60_000 / intervalMs)) === 0) {
    record({ kind: 'heartbeat', pollCount, ...meta });
    console.log(
      `  · ${kst()}  ${meta.statusInfo}  ${meta.score}  ` +
      `(${pollCount}회 / ${meta.bytes}B / ${durationMs}ms)`,
    );
  }
}

async function loop() {
  for (;;) {
    await pollOnce();
    await new Promise((r) => setTimeout(r, backoffMs || intervalMs));
  }
}

// ─────────────────────────────── 시청자 마크 ───────────────────────────────

function setupViewerInput() {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: false });
  rl.on('line', (line) => {
    const at = new Date();
    const note = line.trim();
    viewerMarks.push({ at, note });
    record({ kind: 'viewer_mark', note });
    console.log(`  👁  [화면 확인] ${kst(at)}${note ? `  "${note}"` : ''}`);
  });
}

// ─────────────────────────────── 결과 요약 ───────────────────────────────

function summarize() {
  console.log('\n');
  console.log('══════════════════════ 측정 결과 ══════════════════════');
  console.log(`  폴링 ${pollCount}회 (주기 ${intervalMs / 1000}초) / 오류 ${errorCount}회 / 429 ${rateLimitCount}회`);
  console.log(`  피드 이벤트 ${feedGoals.length}건 / 화면 확인 ${viewerMarks.length}건`);
  console.log('');

  const usedMarks = new Set();
  const offsets = [];

  for (const ev of feedGoals) {
    // 이 피드 이벤트에 가장 가까운(아직 안 쓴) 시청자 마크를 찾는다
    let best = null;
    viewerMarks.forEach((m, idx) => {
      if (usedMarks.has(idx)) return;
      const diff = Math.abs(m.at - ev.at);
      if (diff > MATCH_WINDOW_MS) return;
      if (!best || diff < Math.abs(best.mark.at - ev.at)) best = { mark: m, idx };
    });

    const label = `${ev.kind === 'CANCELLED' ? '취소' : '골'}  ${describe(ev.goal)}`;
    if (!best) {
      console.log(`  ${kst(ev.at)}  ${label}`);
      console.log(`      → 짝지을 화면 확인 기록 없음\n`);
      continue;
    }

    usedMarks.add(best.idx);
    const offsetSec = (best.mark.at - ev.at) / 1000;
    offsets.push(offsetSec);

    console.log(`  ${kst(ev.at)}  ${label}`);
    console.log(`      피드 반영 : ${kst(ev.at)}`);
    console.log(`      화면 확인 : ${kst(best.mark.at)}${best.mark.note ? `  "${best.mark.note}"` : ''}`);
    console.log(
      `      차이      : ${offsetSec >= 0 ? '+' : ''}${offsetSec.toFixed(1)}초  ` +
      `(피드가 화면보다 ${offsetSec >= 0 ? '빠름' : '느림'})\n`,
    );
  }

  if (offsets.length) {
    const avg = offsets.reduce((a, b) => a + b, 0) / offsets.length;
    const min = Math.min(...offsets);
    const max = Math.max(...offsets);
    console.log('  ─────────────────────────────────────────────────');
    console.log(`  평균 격차 : ${avg >= 0 ? '+' : ''}${avg.toFixed(1)}초   (최소 ${min.toFixed(1)} / 최대 ${max.toFixed(1)})`);
    console.log('');
    if (avg <= 5) {
      console.log('  판정: 피드가 화면보다 느리거나 비슷함 → 지연 버퍼 불필요.');
    } else if (avg <= 20) {
      console.log('  판정: 약간 빠르지만 무시 가능한 수준 → 지연 버퍼 불필요.');
    } else {
      console.log(`  판정: 피드가 ${avg.toFixed(0)}초 빠름 → 스포일러. 지연 버퍼 도입 검토 필요.`);
    }
    console.log('  ─────────────────────────────────────────────────');
  } else {
    console.log('  짝지어진 기록이 없어 격차를 계산하지 못했습니다.');
  }

  console.log(`\n  전체 기록: ${outPath}`);
  console.log('══════════════════════════════════════════════════════\n');
}

// ─────────────────────────────── 진입점 ───────────────────────────────

function usage() {
  console.log(`
사용법:
  node scripts/measure-live-feed.js <gameId> [--interval=2] [--out=경로]
  node scripts/measure-live-feed.js --find      # 아틀레티코 경기 gameId 목록

예시:
  node scripts/measure-live-feed.js QyRcDHa8DA07hWz
`);
}

(async () => {
  if (flag('find')) { await findGames(); return; }
  if (!gameId) { usage(); process.exit(1); }

  out = fs.createWriteStream(outPath, { flags: 'a' });

  console.log('');
  console.log('══════════════════════════════════════════════════════');
  console.log('  라이브 피드 지연 측정');
  console.log('══════════════════════════════════════════════════════');
  console.log(`  gameId  : ${gameId}`);
  console.log(`  폴링    : ${intervalMs / 1000}초 주기`);
  console.log(`  기록    : ${outPath}`);
  console.log('');
  console.log('  중계 화면에서 골을 본 순간 Enter를 치세요.');
  console.log('  (메모를 남기려면 글자를 치고 Enter)');
  console.log('  종료는 Ctrl+C — 종료 시 결과표가 출력됩니다.');
  console.log('══════════════════════════════════════════════════════');

  setupViewerInput();

  let closing = false;
  process.on('SIGINT', () => {
    if (closing) process.exit(1);
    closing = true;
    summarize();
    if (out) out.end();
    process.exit(0);
  });

  await loop();
})();
