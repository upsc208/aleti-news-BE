#!/usr/bin/env node
/**
 * 채팅방 부하 측정 — 동시 접속자를 늘려 가며 **브로드캐스트 지연**을 잰다.
 *
 * ## 왜 필요한가
 *
 * 채팅은 팬아웃이 제곱으로 는다. 사람이 N명이고 각자 초당 m건을 치면 서버가 내보내야 하는
 * 건수는 **N × m × N**이다. 20명이면 초당 400건이라 여유롭고, 200명이면 40,000건이라
 * 1 vCPU로는 못 버틴다. 그 천장이 어디인지 아무도 모르는 채로 홍보를 하면,
 * 사람이 몰린 순간이 곧 장애다.
 *
 * ## 무엇을 재나
 *
 * 보낸 사람이 메시지에 **보낸 시각을 박아** 두고, 받은 사람이 도착 시각과 비교한다.
 * 한 대에서 다 돌리므로 시계가 하나다 — 시각 동기화 문제가 없다.
 *
 *   지연 = 받은 시각 − 보낸 시각   (연결 하나가 아니라 **모든 수신자**에 대해)
 *
 * 같이 남기는 값: 연결 성공률, 초당 수신 건수, 서버가 거절한 건수(도배 상한),
 * 중간에 끊긴 연결 수.
 *
 * ## 주의 — 한 대에서 돌리면 IP가 하나다
 *
 * 도배 상한(`ChatService.RATE_LIMIT_MAX`)은 **IP당 10초에 N건**이다. 이 스크립트의 모든
 * 연결이 같은 IP라, 전체 전송 속도가 그 상한을 넘으면 서버가 거절한다(거절 수로 보인다).
 * 그래서 기본 전송 속도를 상한 아래로 잡아 뒀다.
 *
 * **골 터진 순간(수십 명이 동시에)을 그대로 재려면** 둘 중 하나가 필요하다.
 *   · 여러 대에서 동시에 돌린다 (`--senders`를 나눠 맡는다)
 *   · 시험용 서버에서 잠시 상한을 올린다
 * 팬아웃 부하 자체는 **수신자 수**가 지배하므로, 전송이 느려도 천장은 보인다.
 *
 * ## 실행
 *
 *   node scripts/chat-load-test.js --url https://atleti.kr --clients 50 --senders 5
 *   node scripts/chat-load-test.js --url http://localhost:8080 --clients 200 --seconds 60
 *
 * | 옵션 | 뜻 | 기본 |
 * |---|---|---|
 * | `--url`      | 서버 주소 (http/https) | http://localhost:8080 |
 * | `--room`     | 방 id — 실제 경기방이 아니라 **자유방(free)** 을 기본으로 둔다 | free |
 * | `--clients`  | 동시 접속 수 | 50 |
 * | `--senders`  | 그중 실제로 채팅을 치는 수 | 5 |
 * | `--rate`     | 보내는 사람 1명의 초당 전송 수 | 0.2 (5초에 한 번) |
 * | `--seconds`  | 측정 시간(초) | 30 |
 * | `--ramp`     | 연결을 이 시간(초)에 걸쳐 나눠 연다 | 5 |
 * | `--origin`   | 핸드셰이크에 붙일 Origin — 403으로 끊길 때만 쓴다 | (안 붙임) |
 *
 * **경기방(`--room <matchId>`)은 방이 열려 있을 때만 받는다** — 킥오프 1시간 전부터다.
 * 닫힌 방에 보내면 전부 거절로 잡히므로, 평시 측정은 자유방으로 한다.
 */

const { setTimeout: sleep } = require('timers/promises');

// ── 옵션 ────────────────────────────────────────────────────────────────

const argv = process.argv.slice(2);
const arg = (name, fallback) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};

const OPTS = {
  url: arg('url', 'http://localhost:8080'),
  room: arg('room', 'free'),
  clients: Number(arg('clients', 50)),
  senders: Number(arg('senders', 5)),
  rate: Number(arg('rate', 0.2)),
  seconds: Number(arg('seconds', 30)),
  ramp: Number(arg('ramp', 5)),
  origin: arg('origin', ''),
};

if (OPTS.senders > OPTS.clients) OPTS.senders = OPTS.clients;

/** SockJS의 **raw WebSocket** 통로. `/ws`는 SockJS 협상이 붙지만 이쪽은 바로 붙는다 */
const wsUrl = OPTS.url.replace(/^http/, 'ws').replace(/\/$/, '') + '/ws/websocket';

// ── STOMP 최소 구현 ─────────────────────────────────────────────────────
//
// 라이브러리를 안 쓴다. 이 저장소의 다른 스크립트와 같은 규칙이다 —
// 부하를 재려고 npm 트리를 들이면 그 자체가 변수가 된다.

const NULL = '\u0000';

const frame = (command, headers = {}, body = '') =>
  command + '\n' +
  Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n') +
  (Object.keys(headers).length ? '\n' : '') + '\n' + body + NULL;

/**
 * SockJS 프레임을 벗겨 STOMP 프레임 목록으로.
 *
 * `o`(열림) `h`(하트비트) `c[...]`(닫힘)는 버리고 `a[...]`(메시지)만 남긴다.
 * 배열 하나에 프레임이 여럿 실려 오는 경우가 있어 flat하게 편다.
 */
function unwrap(raw) {
  if (typeof raw !== 'string' || raw[0] !== 'a') return [];
  try {
    return JSON.parse(raw.slice(1));
  } catch {
    return [];
  }
}

/** STOMP 프레임 하나를 명령·헤더·본문으로 */
function parseFrame(text) {
  const clean = text.replace(/\u0000$/, '');
  const split = clean.indexOf('\n\n');
  const head = split < 0 ? clean : clean.slice(0, split);
  const body = split < 0 ? '' : clean.slice(split + 2);
  const [command, ...lines] = head.split('\n');
  const headers = {};
  for (const line of lines) {
    const at = line.indexOf(':');
    if (at > 0) headers[line.slice(0, at)] = line.slice(at + 1);
  }
  return { command, headers, body };
}

// ── 측정값 ──────────────────────────────────────────────────────────────

const stats = {
  connected: 0,
  failed: 0,
  dropped: 0,
  sent: 0,
  rejected: 0,
  received: 0,
  latencies: [],
};

const percentile = (sorted, p) =>
  sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))] : NaN;

// ── 클라이언트 하나 ─────────────────────────────────────────────────────

/**
 * 접속자 한 명. 구독만 하는 사람과 채팅도 치는 사람이 같은 코드를 쓴다 —
 * 실제 방도 대부분 보기만 하고 일부만 친다.
 */
function spawn(index, isSender, deadline) {
  return new Promise((resolve) => {
    const nickname = `부하${String(index).padStart(3, '0')}`;
    let socket;
    try {
      // Origin은 **기본으로 안 붙인다.** 브라우저가 아닌 클라이언트는 Origin이 없고,
      // 스프링의 출처 검사는 그 경우를 통과시킨다. 서버가 403으로 끊으면 그때
      // `--origin https://atleti.kr`처럼 허용 목록에 든 값을 준다 (WebSocketConfig).
      socket = OPTS.origin
        ? new WebSocket(wsUrl, { headers: { Origin: OPTS.origin } })
        : new WebSocket(wsUrl);
    } catch (e) {
      stats.failed++;
      return resolve();
    }

    let alive = false;
    let timer = null;
    // **한 번만 끝낸다.** 연결에 실패한 소켓을 close()하면 error가 다시 오는데,
    // 그 안에서 또 close()를 부르면 서로를 부르며 초당 수천 번 돈다 (실측했다)
    let done = false;
    const stop = () => {
      if (done) return;
      done = true;
      if (timer) clearInterval(timer);
      try { socket.close(); } catch { /* 이미 닫혔으면 그만이다 */ }
      resolve();
    };

    socket.addEventListener('open', () => {
      // SockJS raw 통로는 클라이언트가 **JSON 배열 한 겹**을 씌워 보낸다
      socket.send(JSON.stringify([frame('CONNECT', {
        'accept-version': '1.2',
        'heart-beat': '0,0',
        'x-chat-client-id': `load-${index}`,
      })]));
    });

    socket.addEventListener('message', (event) => {
      for (const raw of unwrap(event.data)) {
        const { command, headers, body } = parseFrame(raw);

        if (command === 'CONNECTED') {
          alive = true;
          stats.connected++;
          socket.send(JSON.stringify([frame('SUBSCRIBE', {
            id: `sub-${index}`,
            destination: `/topic/chat/${OPTS.room}`,
          })]));
          socket.send(JSON.stringify([frame('SUBSCRIBE', {
            id: `err-${index}`,
            destination: '/user/queue/errors',
          })]));

          if (isSender) {
            timer = setInterval(() => {
              if (Date.now() > deadline) return;
              stats.sent++;
              socket.send(JSON.stringify([frame(
                'SEND',
                { destination: `/app/chat/${OPTS.room}`, 'content-type': 'application/json' },
                // 보낸 시각을 본문에 박는다 — 받는 쪽이 이걸로 지연을 잰다
                JSON.stringify({ nickname, content: `t${Date.now()}` })
              )]));
            }, Math.max(50, 1000 / OPTS.rate));
          }
          continue;
        }

        if (command !== 'MESSAGE') continue;

        // 정책 위반 회신(도배 상한·닫힌 방 등)은 개인 큐로 온다 — 방송과 목적지가 다르다
        if ((headers.destination || '').includes('/queue/errors')) {
          stats.rejected++;
          continue;
        }

        stats.received++;
        const stamped = /"content":"t(\d{13})"/.exec(body);
        if (stamped) stats.latencies.push(Date.now() - Number(stamped[1]));
      }
    });

    socket.addEventListener('close', () => {
      if (!done && alive && Date.now() < deadline) stats.dropped++;
      stop();
    });
    socket.addEventListener('error', () => {
      if (!done && !alive) stats.failed++;
      stop();
    });

    setTimeout(stop, Math.max(0, deadline - Date.now()) + 1000);
  });
}

// ── 실행 ────────────────────────────────────────────────────────────────

async function main() {
  console.log(`\n🎯 대상        ${wsUrl}`);
  console.log(`👥 동시 접속    ${OPTS.clients}명 (그중 ${OPTS.senders}명이 전송, 1인당 ${OPTS.rate}건/초)`);
  console.log(`⏱️  측정 시간    ${OPTS.seconds}초 (연결은 ${OPTS.ramp}초에 걸쳐 나눠 연다)\n`);

  const deadline = Date.now() + (OPTS.ramp + OPTS.seconds) * 1000;
  const gap = (OPTS.ramp * 1000) / Math.max(1, OPTS.clients);

  const all = [];
  for (let i = 0; i < OPTS.clients; i++) {
    all.push(spawn(i, i < OPTS.senders, deadline));
    if (gap > 0) await sleep(gap);
  }

  const ticker = setInterval(() => {
    const left = Math.max(0, Math.round((deadline - Date.now()) / 1000));
    process.stdout.write(
      `\r   연결 ${stats.connected}/${OPTS.clients} · 수신 ${stats.received} · 거절 ${stats.rejected} · ${left}초 남음   `
    );
  }, 1000);

  await Promise.all(all);
  clearInterval(ticker);

  const sorted = [...stats.latencies].sort((a, b) => a - b);
  const seconds = OPTS.seconds || 1;

  console.log('\n\n── 결과 ────────────────────────────────');
  console.log(`연결 성공        ${stats.connected}/${OPTS.clients} (실패 ${stats.failed}, 도중 끊김 ${stats.dropped})`);
  console.log(`전송 시도        ${stats.sent}건 (서버 거절 ${stats.rejected}건)`);
  console.log(`수신 총합        ${stats.received}건 — 초당 ${(stats.received / seconds).toFixed(1)}건`);
  if (sorted.length) {
    console.log(`브로드캐스트 지연  p50 ${percentile(sorted, 0.5)}ms · p95 ${percentile(sorted, 0.95)}ms · 최대 ${sorted[sorted.length - 1]}ms`);
  } else {
    console.log('브로드캐스트 지연  측정값 없음 — 전송이 전부 거절됐거나 구독이 안 붙었다');
  }
  console.log('────────────────────────────────────────');
  console.log('판정 기준: p95 500ms 이하면 사람이 못 느낀다. 2초를 넘으면 대화가 끊긴 것처럼 보인다.');
  console.log('연결 실패·도중 끊김이 0이 아니면 그 수치부터 본다 — 지연보다 먼저 터진 것이다.\n');
}

// 모듈로 불러오면 프레임 처리만 따로 시험할 수 있다 (테스트·디버깅용)
module.exports = { frame, unwrap, parseFrame };

if (require.main !== module) return;

main().catch((e) => {
  console.error('부하 측정 실패:', e.message);
  process.exit(1);
});
