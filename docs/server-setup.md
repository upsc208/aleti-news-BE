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

## 2. .env 복사

앱은 `.env`를 **실행 디렉터리**에서 읽으므로 새 위치로 복사 필요.

```bash
cp ~/aleti-news-BE/.env /opt/atleti-news/.env
```

## 3. 레포 최신화 후 인프라 파일 설치

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

## 4. sudoers — 재시작 권한만 허용

배포 시 필요한 sudo는 `systemctl restart atleti-news` 하나뿐. 그것만 비밀번호 없이 허용 (SSH 키가 유출돼도 할 수 있는 게 재시작뿐이도록).

```bash
echo "$USER ALL=(root) NOPASSWD: /usr/bin/systemctl restart atleti-news" | sudo tee /etc/sudoers.d/atleti-deploy
sudo chmod 440 /etc/sudoers.d/atleti-deploy
sudo visudo -c    # 문법 검증 — "parsed OK" 확인
```

## 5. 첫 jar 준비

CI가 만든 jar를 그대로 사용 (파이프라인 산출물 검증 겸함).

1. GitHub 레포 → Actions → 초록불 뜬 실행 클릭 → 하단 **Artifacts**에서 `app-jar` 다운로드
2. 압축 풀면 `atleti-news-0.0.1-SNAPSHOT.jar`
3. 로컬(Windows PowerShell)에서 서버로 전송:

```powershell
scp .\atleti-news-0.0.1-SNAPSHOT.jar ubuntu@<서버IP>:/opt/atleti-news/app-new.jar
```

(평소 SSH 접속에 키를 쓰면 `-i <키경로>` 추가)

## 6. 기존 실행 중지

기존 방식(nohup 등)으로 떠 있는 앱 종료. **이 순간부터 헬스체크 통과까지 서비스 중단이므로 트래픽 적은 시간대 권장.**

```bash
ps aux | grep java     # 기존 프로세스 확인
kill <PID>             # 종료
```

재부팅 시 기존 방식으로 자동 시작하게 해둔 게 있다면(crontab @reboot, rc.local 등) 함께 제거:

```bash
crontab -l    # @reboot 항목 있는지 확인
```

## 7. 첫 배포 실행 (완료 기준)

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

## 8. 배포 전용 SSH 키 생성 (3단계 준비물)

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
| 메모리 부족으로 기동 실패 | 스왑 확인 `free -h` — 없으면 redis-design.md의 스왑 2GB 생성 선행 |
