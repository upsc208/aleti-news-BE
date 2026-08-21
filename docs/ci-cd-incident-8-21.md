# 인시던트 — 8/21 배포 파이프라인 중단

설계 문서: [ci-cd-design.md](ci-cd-design.md)

| | |
|---|---|
| **영향** | master push 배포 전면 불가. 애플리케이션 서비스는 정상(기존 버전 계속 구동) |
| **원인** | ① GitHub Actions 아티팩트 저장 한도 초과 ② 그 수정 과정에서 워크플로 YAML 파손 |
| **탐지** | Actions 실행 실패 (사람이 확인) |
| **조치** | 아티팩트 의존 제거 — build/deploy 잡 통합 |
| **관련 커밋** | `8474095` (구조 변경) · `4fa0ae0` (YAML 수정) |

---

## 무슨 일이 있었나

배포하려는 코드에는 문제가 없었다. **파이프라인이 자기 저장 공간 때문에 멈췄다.**

```
build
Failed to CreateArtifact: Artifact storage quota has been hit.
Unable to upload any new artifacts. Usage is recalculated every 6-12 hours.
```

빌드는 성공했는데 그 결과물(jar)을 아티팩트로 올리는 단계에서 실패했다. `deploy` 잡은
`needs: build`라 시작조차 못 했다.

---

## 근본 원인 ① — 배포 경로가 저장 한도에 묶여 있었다

원래 구조는 잡이 둘이었고 jar를 아티팩트로 주고받았다.

```
build ──[upload-artifact: app-jar]──▶ (GitHub 저장소) ──[download-artifact]──▶ deploy
```

이 구조에는 **배포와 무관한 실패 지점**이 하나 끼어 있다. 아티팩트 저장 한도는
지난 실행들이 쌓아 올린 것이고, 이번 배포가 무엇을 바꾸든 상관이 없다. 그런데도
한도가 차면 배포가 선다.

`retention-days: 7`이 걸려 있었지만 그건 **쌓이는 속도를 늦출 뿐** 한도 자체를
없애지 못한다. 푸시가 잦아지면 언제든 다시 만난다.

### 왜 아티팩트가 필요했나 — 그리고 왜 필요 없었나

아티팩트는 **잡 경계를 넘어 파일을 옮기는** 수단이다. 잡이 나뉘어 있었으니 필요했다.
그러면 왜 나눴는가? 원래 의도는 두 가지였다.

1. PR에서는 빌드·테스트만, master push에서만 배포
2. 배포끼리 겹치지 않게 직렬화

**둘 다 잡을 나누지 않고도 된다.** 1은 스텝 단위 `if`로, 2는 잡 단위 `concurrency`로
해결된다. 잡을 나눈 것은 관심사 분리로는 자연스러웠지만, 그 대가로 **저장 한도라는
외부 의존성**을 배포 경로에 끌어들였다.

---

## 조치 — 잡을 합쳐 아티팩트를 없앤다

```
build-deploy ── 빌드 → 테스트 → (master push면) 전송 → 배포
```

jar가 러너를 떠나지 않으므로 아티팩트를 아예 쓰지 않는다. **저장 한도가 다시 차도
배포는 돈다.**

```yaml
jobs:
  build-deploy:
    runs-on: ubuntu-latest
    concurrency:
      group: deploy-${{ github.ref }}
      cancel-in-progress: false
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
      - uses: gradle/actions/setup-gradle@v4
      - name: Build & test
        run: ./gradlew build

      # ── 여기부터 master push일 때만 ──
      - name: Setup SSH
        if: github.event_name == 'push' && github.ref == 'refs/heads/master'
        ...
      - name: Transfer files
        if: github.event_name == 'push' && github.ref == 'refs/heads/master'
        run: |
          JAR=$(ls build/libs/*.jar | grep -v -- '-plain' | head -1)
          if [ -z "$JAR" ]; then echo "실행 jar를 찾지 못했습니다"; exit 1; fi
          ...
```

### 지켜야 했던 성질과 그 처리

| 성질 | 예전 | 지금 |
|---|---|---|
| PR은 빌드·테스트까지만 | 잡 단위 `if` | 배포 스텝 3개에 각각 `if` |
| 동시 배포 방지 | `concurrency: deploy` | `concurrency: deploy-${{ github.ref }}` |
| jar 선택 | `!build/libs/*-plain.jar` (업로드 필터) | `grep -v -- '-plain'` + **못 찾으면 실패** |

`concurrency` 그룹에 ref를 붙인 이유: 잡이 하나가 되면서 PR 실행도 같은 그룹에 들어간다.
그대로 두면 PR 검증이 배포를 기다린다. 배포는 master에서만 도니 ref별로 나눠도
동시 배포 방지는 그대로다.

jar 선택에서 **못 찾으면 조용히 넘어가지 않고 실패시킨 것**이 중요하다. 예전에는
업로드 필터가 걸러 줬지만 이제는 셸이 고른다. 빈 값으로 `scp`가 돌면 엉뚱한 파일이
올라가거나 배포가 절반만 진행된다.

부수 효과로 잡이 하나 줄어 러너 시작과 checkout이 한 번씩 절약된다.

---

## 근본 원인 ② — 고치다가 워크플로를 깨뜨렸다

`8474095`를 푸시하자 GitHub이 워크플로 자체를 거부했다.

```
Invalid workflow file: .github/workflows/ci-cd.yml#L79
You have an error in your yaml syntax on line 79
```

편집 과정에서 정규식 끝의 `$'`가 잘리며 한 줄이 두 줄로 쪼개져 들어갔다.

```yaml
JAR=$(ls build/libs/*.jar | grep -v -- '-plain\.jar     ← 여기서 끊김
 | head -1)                                              ← 들여쓰기도 어긋남
```

**첫 조치보다 나쁜 상태였다.** 앞의 실패는 워크플로가 돌다가 한 스텝에서 멈춘 것이지만,
이건 워크플로가 아예 시작되지 않는다.

### 조치

앵커 없이도 같은 일을 하므로 `-plain` 부분 일치로 바꿔 한 줄로 되돌렸다.
`build/libs`에 `-plain`이 들어간 다른 jar가 생길 일이 없다.

### 왜 놓쳤나

**눈으로만 확인하고 푸시했다.** 사람이 읽으면 자연스러워 보이는 줄바꿈이었고,
들여쓰기 차이도 한 칸이라 눈에 안 띄었다.

수정 후에는 파서로 검증했다.

```bash
npx js-yaml .github/workflows/ci-cd.yml
```

그리고 파싱 결과에서 잡·스텝·조건이 의도대로 읽히는지 확인했다.

```
잡: build-deploy
스텝: checkout / setup-java / setup-gradle / Build & test
      Setup SSH      [master push일 때만]
      Transfer files [master push일 때만]
      Deploy         [master push일 때만]
아티팩트 사용: 없음
```

---

## 검증

배포 성공 후 운영 API의 OpenAPI 스펙에서 이번 배포분이 실제로 올라갔는지 확인했다 —
빌드 성공이 곧 배포 반영은 아니므로 **결과물 쪽에서** 본다.

```
LiveMatchState 필드: ..., scorers, substitutions, kickoff, periods, updatedAt
LiveSubstitution 스키마: minute, addedTime, isHome, playerOut, playerIn, injury
```

---

## 남은 것

**쌓인 아티팩트는 그대로다.** 새로 만들지 않으니 급하지 않지만, 저장 공간을 되찾으려면
지난 실행에서 `app-jar`를 지워야 한다. GitHub 사용량 집계는 6~12시간마다 갱신되므로
지워도 즉시 반영되지 않는다.

**배포 실패를 사람이 발견했다.** 지금은 Actions 탭을 봐야 안다. 실패 알림(메일·웹훅)이
없어서, 아무도 안 보면 배포가 안 된 채로 시간이 흐른다.

**워크플로 문법 검증이 파이프라인에 없다.** 이번엔 손으로 파서를 돌렸다. 워크플로 파일이
바뀌는 PR에서 자동으로 검증하면 같은 사고를 막을 수 있다 — 다만 워크플로가 깨지면
그 검증 워크플로도 같이 안 돌 수 있어서, 로컬 훅이나 별도 린터 쪽이 확실하다.

---

## 배운 것

**배포 경로에는 배포와 무관한 의존성을 두지 않는다.** 아티팩트 저장 한도는 코드 품질과
아무 상관이 없는데 배포를 막았다. 잡을 나누는 것은 깔끔해 보였지만 그 경계를 넘기 위해
외부 저장소가 끼어들었다. **경계를 만들 때는 그 경계를 넘는 비용도 같이 본다.**

**설정 파일은 눈으로 검증하지 않는다.** 코드는 컴파일러가 잡아 주지만 YAML은 아무도
안 잡아 준다. 파서를 돌리는 데 몇 초면 되고, 그걸 안 해서 배포를 한 번 더 세웠다.
