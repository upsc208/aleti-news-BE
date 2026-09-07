# 서버 배포 환경 준비 가이드 (로드맵 2단계)

> 작성일: 2026-07-29
> 대상: OCI 서버 (Ubuntu 가정)에서 한 번만 수행하는 작업
> 목표: `deploy.sh`를 손으로 실행해서 "교체 → 재시작 → 헬스체크"가 도는 것을 확인

## 0. 전제 확인

서버 SSH 접속 후 아래 값들 확인. 이후 명령어에서 다르면 바꿔서 사용.

```bash
whoami          # 계정명 확인 — 아래에서 ubuntu로 가정, 다르면 치환
which java      # /usr/bin/java 인지 확인 — 다르면 atleti-news.service의 ExecStart 경로 수정
java -version   # 21인지 확인
```

현재 앱 실행 방식(nohup 등)과 `.env` 파일 위치도 파악해 둘 것. 아래에서 기존 레포 위치를 `~/aleti-news-BE`로 가정.

## 1. 배포 디렉터리 생성

```bash
sudo mkdir -p /opt/atleti-news
sudo chown $USER:$USER /opt/atleti-news
```

## 2. Redis 설치 + 스왑 (채팅·조회수 전제)

**한 줄 요약: 이걸 빼먹으면 배포는 ✅ 성공으로 뜨는데 채팅만 조용히 안 된다.**

앱은 Redis가 없어도 정상 기동하도록 만들어져 있고(fail-open, `RedisConfig`), 헬스체크(`/api/news/sources`)는 DB만 보므로 **Redis 없이도 배포가 성공으로 표시된다.** 그래서 설치를 잊으면 매치데이에 채팅방을 열어보고서야 알게 된다. 설계 근거는 `docs/redis-design.md` 2장.

```bash
# ① 스왑 2GB 먼저 (RAM 1GB 환경의 OOM kill 보험)
free -h                                    # Swap이 0이면 아래 진행
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab   # 재부팅 후에도 유지

# ② Redis 설치 (Docker 미사용 — 이유는 redis-design.md 2장)
sudo apt update && sudo apt install -y redis-server
```

설정 파일 `/etc/redis/redis.conf`에서 세 줄을 확인/수정:

```conf
maxmemory 64mb                # RAM 1GB 예산 안에서 상한 고정
maxmemory-policy allkeys-lru  # 상한 도달 시 오래 안 쓴 키부터 자동 삭제
bind 127.0.0.1 ::1            # 외부 노출 금지 (OCI Security List에서도 6379를 열지 않음)
```

```bash
sudo systemctl restart redis-server
sudo systemctl enable redis-server    # 부팅 시 자동 시작
```

**완료 기준:**

```bash
redis-cli ping                        # PONG
redis-cli config get maxmemory        # 67108864 (=64MB)
```

`PONG`이 안 나오면 채팅은 100% 동작하지 않는다 (`redis-design.md` 6.10 — 채팅은 Redis가 곧 저장소라 fallback이 없다).

> 앱의 systemd 유닛에는 `After=redis-server.service` / `Wants=redis-server.service`가 걸려 있다. 재부팅 시 Redis보다 앱이 먼저 뜨면 Pub/Sub 구독 등록에 실패해서 — 앱은 뜨지만 — 재시작 전까지 채팅 메시지가 중계되지 않기 때문이다.

## 3. .env 복사

앱은 `.env`를 **실행 디렉터리**에서 읽으므로 새 위치로 복사 필요.

```bash
cp ~/aleti-news-BE/.env /opt/atleti-news/.env
```

## 4. 레포 최신화 후 인프라 파일 설치

```bash
cd ~/aleti-news-BE
git pull

# systemd 유닛 설치 (계정명이 ubuntu가 아니면 User= 줄 먼저 수정)
sudo cp infra/atleti-news.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable atleti-news    # 부팅 시 자동 시작

# 배포 스크립트 설치
cp infra/deploy.sh /opt/atleti-news/
chmod +x /opt/atleti-news/deploy.sh
```

## 5. sudoers — 재시작 권한만 허용

배포 시 필요한 sudo는 `systemctl restart atleti-news` 하나뿐. 그것만 비밀번호 없이 허용 (SSH 키가 유출돼도 할 수 있는 게 재시작뿐이도록).

> **⚠️ 2026-09-07 — 이 규칙은 지금 효력이 없다.** 클라우드 이미지의 기본 계정에는 이미
> `(ALL) NOPASSWD: ALL`이 걸려 있어서, **같은 계정에 좁은 규칙을 더해도 허용 범위가 줄지 않는다.**
> `sudo -l`로 확인할 수 있다.
>
> 제대로 하려면 **배포 전용 계정을 새로 만들고** 그 계정에만 이 규칙을 준다.
> 기본 계정에서 `NOPASSWD`를 빼는 방향은 **위험하다** — 그 계정에는 비밀번호가 설정돼 있지 않아
> `sudo`가 존재하지 않는 비밀번호를 묻게 되고 **root 권한에서 영구히 잠긴다.**

```bash
echo "$USER ALL=(root) NOPASSWD: /usr/bin/systemctl restart atleti-news" | sudo tee /etc/sudoers.d/atleti-deploy
sudo chmod 440 /etc/sudoers.d/atleti-deploy
sudo visudo -c    # 문법 검증 — "parsed OK" 확인
```

## 6. 첫 jar 준비

CI가 만든 jar를 그대로 사용 (파이프라인 산출물 검증 겸함).

1. GitHub 레포 → Actions → 초록불 뜬 실행 클릭 → 하단 **Artifacts**에서 `app-jar` 다운로드
2. 압축 풀면 `news-0.0.1-SNAPSHOT.jar`
3. 로컬(Windows PowerShell)에서 서버로 전송:

```powershell
scp .\news-0.0.1-SNAPSHOT.jar ubuntu@<서버IP>:/opt/atleti-news/app-new.jar
```

(평소 SSH 접속에 키를 쓰면 `-i <키경로>` 추가)

## 7. 기존 실행 중지

기존 방식(nohup 등)으로 떠 있는 앱 종료. **이 순간부터 헬스체크 통과까지 서비스 중단이므로 트래픽 적은 시간대 권장.**

```bash
ps aux | grep java     # 기존 프로세스 확인
kill <PID>             # 종료
```

재부팅 시 기존 방식으로 자동 시작하게 해둔 게 있다면(crontab @reboot, rc.local 등) 함께 제거:

```bash
crontab -l    # @reboot 항목 있는지 확인
```

## 8. 첫 배포 실행 (완료 기준)

```bash
cd /opt/atleti-news
./deploy.sh
```

기대 출력:

```
[deploy] 새 jar 활성화
[deploy] 재시작 지시 완료 — 헬스체크 시작
[deploy] 헬스체크 통과 (N회차) — 배포 성공
```

확인 명령:

```bash
systemctl status atleti-news                        # active (running)
curl -s http://localhost:8080/api/news/sources      # JSON 응답
journalctl -u atleti-news -f                        # 실시간 로그 (Ctrl+C로 종료)
```

외부에서 실제 서비스 URL 열어서 정상 동작 확인까지 하면 2단계 완료.

## 9. ~~Nginx — WebSocket(채팅) 프록시 설정~~ → **제거함 (2026-09-07)**

> **Nginx는 이 서버에서 제거했다.** 아래는 왜 있었고 왜 없앴는지의 기록이다.

**있었던 이유** — 2026-05-04 초기 세팅 때 *"Spring Boot 배포 시 앞단에 Nginx 리버스 프록시를
둔다"*는 일반적인 가이드를 따라 설치했다. 의도한 경로는 `Cloudflare → Nginx(:80) → Spring(:8080)`.

**그런데 그 경로로 트래픽이 흐른 적이 없다.** Cloudflare가 앱 포트(`:8080`)에 직접 붙고 있었다.
앱을 먼저 띄우고 Cloudflare를 `:8080`에 연결한 뒤, 하루 뒤 Nginx를 설치하면서 **Cloudflare 쪽을
`:80`으로 바꾸지 않은 것**으로 보인다.

```
믿고 있던 구조:  Cloudflare ──▶ :80 Nginx ──▶ :8080 Spring
실제:           Cloudflare ─────────────────▶ :8080 Spring
```

**확인 방법 (같은 상황이 의심되면 이 순서로)**

```bash
# ① 프록시가 실제 요청을 받고 있나 — 클라이언트 IP 분포를 본다
sudo awk '{print $1}' /var/log/nginx/access.log | sort | uniq -c | sort -rn | head

# ② 유입 패킷이 어느 포트로 오나
sudo tcpdump -i any -nn 'tcp[tcpflags] & tcp-syn != 0 and dst port 8080'

# ③ 앱에 붙은 연결의 상대가 127.0.0.1(프록시 경유)인가, 외부 IP(직결)인가
sudo ss -tnp | grep java
```

넉 달치 액세스 로그에 **실제 사용자 요청이 한 건도 없었고** `/api/.git/config` 같은 취약점
스캐너뿐이었다. `tcpdump`에는 `162.158.x.x → 10.0.0.7:8080` — Cloudflare가 앱 포트로 직행하는
것이 그대로 찍혔다.

**없앤 이유** — Nginx가 주려던 것을 이미 다른 곳이 하고 있었다.

| Nginx로 하려던 것 | 실제로 하는 곳 |
| --- | --- |
| HTTPS 종료 | **Cloudflare** |
| CDN · DDoS 방어 | **Cloudflare** |
| `X-Forwarded-For` 전달 | **Cloudflare**가 직접 넣어 준다 |
| 정적 파일 서빙 | **Vercel** |
| 여러 서비스 라우팅 | 서비스가 **하나**뿐 |

RAM 1GB에서 하는 일 없는 계층을 유지할 이유가 없었다. **제거 후 사용 메모리 498MB → 378MB**
(워커 + `libgd` 등 의존성 포함). `:80`이 닫히면서 **스캐너 표적도 하나 줄었다.**

> **되돌리려면** — `sudo apt install nginx` 후 백업해 둔 설정을 복원한다
> (`~/nginx-removal/etc-nginx-backup`, 서버에 보관). 그리고 **Cloudflare 쪽에서 오리진 포트를
> `:80`으로 바꿔야** 실제로 경로에 들어간다. 그 설정을 안 바꾸면 이번과 똑같이 켜 두기만 하게 된다.
>
> **다시 필요해지는 조건** — 한 서버에 서비스가 둘 이상이 되어 경로 분기가 필요할 때,
> 또는 앱 앞단에서 접근 제어·요청 제한을 걸어야 할 때.

**남은 과제** — 앱 포트가 여전히 인터넷에 열려 있어, IP만 알면 **Cloudflare를 건너뛰고 앱에 직접
닿을 수 있다.** Cloudflare IP 대역만 허용하도록 제한해야 한다. (앱 포트를 그냥 막으면 **정문을
잠그는 것**이다 — 2026-09-07에 실제로 그렇게 해서 4분간 서비스가 끊겼다.)

## 10. 배포 전용 SSH 키 생성 (3단계 준비물)

GitHub Actions가 서버에 접속할 때 쓸 전용 키. 기존 개인 키를 재사용하지 않고 새로 만듦 (용도 분리 — 유출 시 이 키만 폐기하면 됨).

로컬(Windows PowerShell)에서:

```powershell
ssh-keygen -t ed25519 -f atleti-deploy-key -N '""' -C "github-actions-deploy"
```

- `atleti-deploy-key` (개인 키) → 3단계에서 GitHub Secret `OCI_SSH_KEY`에 등록할 파일. **레포에 절대 커밋 금지**
- `atleti-deploy-key.pub` (공개 키) → 내용을 서버의 `~/.ssh/authorized_keys`에 한 줄 추가:

```bash
echo "<atleti-deploy-key.pub 내용 붙여넣기>" >> ~/.ssh/authorized_keys
```

등록 후 로컬에서 접속 테스트:

```powershell
ssh -i atleti-deploy-key ubuntu@<서버IP> "echo ok"
```

`ok` 출력되면 3단계(GitHub Secrets 등록)로 진행 가능.

## 문제 해결

| 증상 | 원인/조치 |
|---|---|
| `deploy.sh: /usr/bin/env: 'bash\r'` 류 에러 | 파일이 CRLF로 전송됨 — `git pull`로 받은 파일을 다시 복사 (레포는 LF 강제 설정됨) |
| 헬스체크 계속 실패 | `journalctl -u atleti-news -n 100`으로 기동 로그 확인. `.env` 누락, DB 접속 실패가 흔한 원인 |
| `systemctl restart` 시 비밀번호 요구 | 4번 sudoers 설정 확인 (`sudo visudo -c`) |
| 메모리 부족으로 기동 실패 | 스왑 확인 `free -h` — 없으면 2번 항목의 스왑 2GB 생성 선행 |
| 배포는 성공인데 채팅만 안 됨 | 십중팔구 Redis 미설치/미기동. `redis-cli ping`으로 확인 (2번 항목). 앱 로그에 `⚠️ Redis 연결 실패` 경고가 남는다 |
| 채팅 연결이 계속 실패/재시도 | Redis 확인이 먼저(위 항목). 프록시는 없다 — Cloudflare가 앱에 직접 붙는다 (9번 항목) |
| 채팅이 1분쯤 뒤 끊김 | Cloudflare 무료 플랜의 프록시 유휴 타임아웃은 100초. SockJS 하트비트(25초)가 그 안에서 오가므로 정상이라면 유지된다 |
| 도배 방지가 안 걸림 / 모두가 같은 ipHash | Cloudflare가 `X-Forwarded-For`에 원본 IP를 넣어 준다. 앱의 `ClientIp` 파싱과 `server.forward-headers-strategy=framework` 설정을 확인 |
