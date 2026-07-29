#!/usr/bin/env bash
# 배포 스크립트 — GitHub Actions가 app-new.jar 전송 후 호출 (비상시 수동 실행도 동일)
# 흐름: 백업 → 교체 → 재시작 → 헬스체크 → 실패 시 자동 롤백
# 설계: docs/ci-cd-design.md 4.3
set -euo pipefail

APP_DIR="/opt/atleti-news"
HEALTH_URL="http://localhost:8080/api/news/sources"
MAX_TRIES=60   # 3초 × 60회 = 최대 3분 대기 (Micro 인스턴스는 기동이 느림)

cd "$APP_DIR"

if [[ ! -f app-new.jar ]]; then
  echo "[deploy] app-new.jar 없음 — jar 전송부터 확인 필요"
  exit 1
fi

# ① 현재 버전 백업 (롤백 보험)
if [[ -f app.jar ]]; then
  cp -f app.jar app-prev.jar
  echo "[deploy] 현재 버전을 app-prev.jar로 백업"
fi

# ② 새 버전 활성화
mv -f app-new.jar app.jar
echo "[deploy] 새 jar 활성화"

# ③ 재시작
sudo /usr/bin/systemctl restart atleti-news
echo "[deploy] 재시작 지시 완료 — 헬스체크 시작"

health_ok() {
  curl -sf --max-time 5 "$HEALTH_URL" > /dev/null
}

# ④ 헬스체크 (앱이 뜨고 DB 조회까지 되는지 확인)
for ((i = 1; i <= MAX_TRIES; i++)); do
  sleep 3
  if health_ok; then
    echo "[deploy] 헬스체크 통과 (${i}회차) — 배포 성공"
    exit 0
  fi
done

# ⑤ 실패 → 자동 롤백
echo "[deploy] 헬스체크 실패 — 이전 버전으로 롤백 시작"
if [[ ! -f app-prev.jar ]]; then
  echo "[deploy] 백업본 없음 — 수동 복구 필요: journalctl -u atleti-news -n 100"
  exit 1
fi

cp -f app-prev.jar app.jar
sudo /usr/bin/systemctl restart atleti-news

for ((i = 1; i <= MAX_TRIES; i++)); do
  sleep 3
  if health_ok; then
    echo "[deploy] 롤백 성공 — 이전 버전으로 운영 중. 새 버전의 실패 원인 확인 필요: journalctl -u atleti-news -n 100"
    exit 1   # 배포 자체는 실패했으므로 실패 코드 반환 (Actions에 ❌ 표시)
  fi
done

echo "[deploy] 롤백 후에도 헬스체크 실패 — 즉시 수동 확인 필요: journalctl -u atleti-news -n 100"
exit 1
