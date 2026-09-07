# PostgreSQL — 운영 서버 설치·복구 절차

> **2026-09-07 —** Docker 컨테이너(`postgres:15-alpine`)로 돌던 것을 **네이티브 설치로 옮겼다.**
> 이 문서는 서버를 다시 만들 때, 그리고 **다시 Docker로 돌아갈 때** 둘 다에 쓴다.

---

## 1. 왜 네이티브인가 — 그리고 언제 되돌리나

컨테이너를 걷어낸 이유는 Docker가 나빠서가 아니라 **이 서버의 프로세스가 3개뿐**이어서다.

| Docker가 주는 것 | 이 서버에서 |
| --- | --- |
| 격리 | 서비스가 하나뿐 |
| 이식성 | 배포 대상이 VM 1대 |
| 오케스트레이션 | 스케일 아웃 계획 없음 |
| 자동 시작·재시작·로그 | **systemd가 이미 한다** |

**상주 비용은 컨테이너 수와 무관하게 고정이다.** `dockerd` + `containerd`가 약 60MB를 상시 점유했는데, 정작 굴리던 컨테이너가 41MB였다. **데몬이 내용물보다 무거웠다.** RAM 1GB에서 이 비율은 정당화되지 않는다.

실제로 정지 후 사용 메모리가 **308MB → 270MB**로 줄었다.

> **다시 Docker로 돌아갈 조건 — 서버 프로세스가 4개 이상이 되거나, 서버를 증설할 때.**
>
> 검색 엔진(Elasticsearch·Meilisearch), 메시지 큐(Kafka·RabbitMQ), 모니터링(Prometheus·Grafana)처럼 **별도 프로세스가 늘어나는 기능**을 붙이는 날이 그날이다. 그때는 1GB로도 안 되니 증설이 먼저고, 컨테이너가 여러 개면 데몬 비용이 분산돼 Docker가 유리해진다.
>
> **앱 출시·기능 추가는 이 조건에 해당하지 않는다.** 앱은 같은 REST/WebSocket을 쓰고, 푸시 알림은 FCM(외부)이라 서버에 프로세스가 늘지 않는다.

**잃은 것도 적어 둔다** — 버전이 OS에 엮이고(우분투 업그레이드 시 영향), `docker run` 한 줄이던 재현성이 이 문서로 대체됐다. 그래서 이 문서가 있다.

---

## 2. 설치 (Ubuntu 22.04)

우분투 기본 저장소는 **PostgreSQL 14**를 준다. 기존 컨테이너가 15라 **버전을 맞추려면 공식 저장소(PGDG)를 추가해야 한다.**

```bash
sudo install -d /usr/share/postgresql-common/pgdg
sudo curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
  https://www.postgresql.org/media/keys/ACCC4CF8.asc
echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
http://apt.postgresql.org/pub/repos/apt jammy-pgdg main" \
  | sudo tee /etc/apt/sources.list.d/pgdg.list

sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y postgresql-15
```

**설치하면 클러스터가 자동 생성된다.** 5432가 이미 쓰이고 있으면 **다음 빈 포트(5433)**로 잡히므로 확인한다.

```bash
pg_lsclusters
# Ver Cluster Port Status Owner    Data directory
# 15  main    5432 online postgres /var/lib/postgresql/15/main
```

포트를 바꾸려면 `/etc/postgresql/15/main/postgresql.conf`의 `port =`를 고치고 재시작한다.

```bash
sudo sed -i "s/^port = 5433/port = 5432/" /etc/postgresql/15/main/postgresql.conf
sudo pg_ctlcluster 15 main restart
```

## 3. 역할·DB 생성

비밀번호는 `/opt/atleti-news/.env`의 `DB_PASSWORD`와 같아야 한다.

```bash
sudo -u postgres psql -c "CREATE ROLE atleti_sadmin LOGIN PASSWORD '<DB_PASSWORD>';"
sudo -u postgres createdb -O atleti_sadmin atleti_news
```

**앱은 스키마를 직접 만든다** (`spring.jpa.hibernate.ddl-auto=update`). 빈 DB만 있으면 기동 시 테이블이 생긴다.

## 4. 접속 확인

```bash
PGPASSWORD='<DB_PASSWORD>' psql -h 127.0.0.1 -U atleti_sadmin -d atleti_news -c '\dt'
```

> **기본 설정이 `127.0.0.1`에만 바인딩한다.** 컨테이너 시절에는 Docker가 `0.0.0.0:5432`로 열어 뒀는데, 이건 **호스트 방화벽(iptables INPUT)을 우회**하는 동작이라 클라우드 보안 목록에만 의존하고 있었다. 네이티브로 오면서 자연히 내부 전용이 됐다.

---

## 5. 백업

```bash
PGPASSWORD='<DB_PASSWORD>' pg_dump -h 127.0.0.1 -U atleti_sadmin -d atleti_news \
  --clean --if-exists --no-owner --no-privileges \
  > ~/pg-backup/atleti_news-$(date +%Y%m%d-%H%M%S).sql
```

**복구**

```bash
PGPASSWORD='<DB_PASSWORD>' psql -h 127.0.0.1 -U atleti_sadmin -d atleti_news -f <덤프파일>
```

> **복구 후에는 반드시 건수를 대조한다.** 2026-09-07 이관 때 `pg_stat_user_tables`의 `n_live_tup`이 62 vs 60으로 달라 잠깐 놀랐는데, **그건 추정치**였다. `count(*)`로 다시 세니 일치했다. **정확한 검증에는 `count(*)`를 쓴다.**

```sql
SELECT 'news_article', count(*) FROM news_article
UNION ALL SELECT 'match_result', count(*) FROM match_result
UNION ALL SELECT 'player_rating_vote', count(*) FROM player_rating_vote;
```

---

## 6. 다시 Docker로 돌아가려면

옛 컨테이너와 데이터를 **지우지 않고 남겨 뒀다.**

```
컨테이너   atleti-db-prod (Exited, 자동시작 해제됨)
데이터     /home/ubuntu/atleti-data/postgres     ← 바인드 마운트라 그대로 있다
이미지     postgres:15-alpine (392MB)
```

**되돌리는 순서**

```bash
sudo systemctl stop atleti-news                    # ① 앱 정지
sudo pg_ctlcluster 15 main stop                    # ② 네이티브 정지 (5432 해제)
sudo systemctl enable --now docker containerd      # ③ 도커 기동
sudo docker start atleti-db-prod                   # ④ 컨테이너 기동
sudo systemctl start atleti-news                   # ⑤ 앱 기동
```

**앱 설정은 안 건드려도 된다** — 어느 쪽이든 `127.0.0.1:5432`로 붙기 때문이다. 다만 컨테이너 데이터는 **이관 시점(2026-09-07)에서 멈춰 있으므로**, 그 뒤에 쌓인 기사는 네이티브에서 덤프해 옮겨야 한다.

> **정리는 서두르지 않는다.** 옛 데이터 디렉터리는 최소 몇 주 두고, 이상이 없다고 확신한 뒤에 지운다. 디스크는 45GB 중 10GB(23%)만 쓰고 있어 급할 이유가 없다.

---

## 7. 부팅 시 자동 시작

`systemctl is-enabled postgresql@15-main`이 **`enabled-runtime`**으로 나오는데 **정상이다.**

우분투는 `postgresql.service`(빈 껍데기)와 **제너레이터**로 클러스터를 올린다. `/lib/systemd/system-generators/postgresql-generator`가 부팅 때마다 `/etc/postgresql/15/main/start.conf`의 `auto`를 읽고 링크를 만든다. 그래서 영구 심볼릭 링크가 `/etc`에 없어도 **매 부팅마다 다시 생성돼 정상 기동한다.**

`atleti-news.service`는 `After=network.target postgresql.service`로 순서를 잡아 둔다.
