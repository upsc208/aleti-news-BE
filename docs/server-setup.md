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

## 9. Nginx — WebSocket(채팅) 프록시 설정

> **실제 설정 파일은 [`infra/nginx-api.conf`](../infra/nginx-api.conf)에 있다.** 서버에 그대로 복사해 쓴다.
>
> **2026-09-04 —** 이 절의 설정이 **실서버에 반영돼 있지 않은 것을 발견해 적용했다.** 핸드셰이크가
> `400`으로 거절되고 있었고, SockJS가 조용히 HTTP 폴백으로 동작해 **기능은 되니까 넉 달간 몰랐다.**
> 적용 후 `101 Switching Protocols` 확인. **같은 일이 반복되지 않도록 설정 파일을 레포로 옮겼다.**

**한 줄 요약: 일반 HTTP 설정만 있으면 채팅 연결(`/ws`)이 프록시에서 끊긴다.**

WebSocket은 평범한 HTTP 요청으로 시작해서 "이 연결을 WebSocket으로 승격시켜 달라"는 `Upgrade` 헤더를 주고받으며 전환된다. Nginx는 기본적으로 이 헤더를 뒤로 전달하지 않으므로, 그냥 두면 승격이 실패하고 채팅만 연결되지 않는다 (일반 API는 멀쩡해서 원인을 찾기 어렵다).

`/etc/nginx/sites-available/<사이트>` 의 `server { }` 안에 추가:

```nginx
# STOMP over WebSocket 핸드셰이크 경로 (SockJS 폴백 경로도 /ws 하위로 들어온다)
location /ws {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;

    # ① 연결 승격에 반드시 필요한 두 줄
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";

    # ② 클라이언트 IP 전달 — rate limit·IP 차단·ipHash가 전부 이 값을 쓴다.
    #    없으면 모든 접속자가 프록시 IP 하나로 보여 도배 방지가 사실상 무력화된다.
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Real-IP $remote_addr;

    # ③ 유휴 타임아웃 — 기본 60초라 대화가 잠깐 끊기면 연결이 죽는다.
    #    경기 한 판(전·후반 + 하프타임)을 덮도록 넉넉히 잡는다.
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
}
```

```bash
sudo nginx -t              # syntax is ok / test is successful 확인
sudo systemctl reload nginx
```

**완료 기준** — 핸드셰이크가 `101 Switching Protocols`로 응답하면 성공:

```bash
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGVzdHRlc3R0ZXN0dGVzdA==" \
     https://<도메인>/ws/websocket
```

`101`이 아니라 `200`/`400`이 오면 위 설정이 적용되지 않은 것이다.

### 관리자 페이지 접근 제한 (선택, 권장)

채팅 관리자 페이지는 백엔드가 정적 파일로 서빙한다: **`https://<도메인>/admin-ec23cf19d466.html`**

파일명이 추측 불가능하고 `noindex`가 걸려 있으며 모든 관리자 API가 `X-Admin-Key`로 잠겨 있어, 페이지가 노출돼도 키 없이는 아무것도 못 한다. 다만 **키를 무차별 대입할 기회조차 주고 싶지 않다면** Nginx에서 한 겹 더 막는다.

```nginx
# 관리자 페이지와 관리자 API를 아이디/비밀번호로 한 번 더 감싼다
location ~ ^/(admin-ec23cf19d466\.html|api/admin/) {
    auth_basic "restricted";
    auth_basic_user_file /etc/nginx/.htpasswd;
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

```bash
sudo apt install -y apache2-utils
sudo htpasswd -c /etc/nginx/.htpasswd admin   # 비밀번호 입력
sudo nginx -t && sudo systemctl reload nginx
```

> 주의: 이 설정을 켜면 `/api/admin/` 전체가 Basic 인증 뒤로 들어간다. 관리자 페이지는 브라우저가 인증 정보를 기억하므로 그대로 쓸 수 있지만, `curl`로 호출할 때는 `-u admin:<비번>`을 함께 넘겨야 한다.

### Cloudflare를 앞단에 쓰는 경우

- WebSocket은 **무료 플랜에서도 기본 활성화**되어 있다. 다만 대시보드 Network 탭에서 `WebSockets`가 On인지 한 번 확인할 것
- 원본 IP는 Cloudflare가 `CF-Connecting-IP`로도 보내지만, 이 앱은 `X-Forwarded-For`의 첫 번째 값을 읽는다(`IpHandshakeInterceptor`). Cloudflare가 XFF에 원본 IP를 맨 앞에 넣어주므로 그대로 동작한다
- 무료 플랜의 프록시 유휴 타임아웃은 100초다. SockJS 하트비트(25초)가 그 안에서 계속 오가므로 연결은 유지된다

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
| 채팅 연결이 계속 실패/재시도 | Nginx `/ws` 프록시 설정 확인 (9번 항목). `sudo nginx -T \| grep -A5 "location /ws"` |
| 채팅이 1분쯤 뒤 끊김 | `proxy_read_timeout` 기본값(60초)에 걸린 것 — 9번 항목대로 3600s로 상향 |
| 도배 방지가 안 걸림 / 모두가 같은 ipHash | 프록시가 `X-Forwarded-For`를 안 넘기는 상태. 9번 항목 ② 확인 |
