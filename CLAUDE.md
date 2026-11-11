# ondolith-j — Spring Boot 백엔드 Lightsail 메모리 실측

**질문 하나에 답한다**: Spring Boot 백엔드와 PostgreSQL 을 Lightsail 512MB 또는 1GB 인스턴스 한 대에 함께 올리면 도는가, 메모리를 얼마나 쓰는가. 비교 기준은 ondolith(Go) 실측값이다.

## 언어

- 사용자에게 보이는 모든 글(진행 설명·표·질문·커밋 메시지·PR 본문·README)은 **한국어**.
- 코드·명령어·식별자·파일 이름만 영어.

## 이 저장소는 PUBLIC 이다 — 커밋하는 모든 것이 공개된다

- 커밋 금지: 실제 이메일, 사용자 홈 아래 절대 경로(맥은 사용자 폴더 경로), 토큰·키·비밀번호, Spring Security 가 로그에 찍는 생성 비밀번호.
- 커밋 메일은 GitHub noreply(`…@users.noreply.github.com`)여야 한다. 실제 메일이면 push 가 거부된다.
- **`git push`, `gh repo create/edit/delete`, 원격 변경, MCP GitHub 쓰기 도구는 사용자 승인 후에만.** `.claude/settings.json` 이 매번 확인을 걸어 둔다.
- 커밋 전 검사는 `scripts/check-public.sh`, 훅은 `.githooks/pre-commit`. 새 클론에서는 한 번 켠다: `git config core.hooksPath .githooks`
- `results/` 원본 출력은 **눈으로 훑은 뒤** 커밋한다(생성 비밀번호·절대 경로가 섞일 수 있다).
- DB 계정 `m`/`m` 은 포트를 열지 않는 일회용 컨테이너용이다. 실제 비밀번호는 어디에도 쓰지 않는다.

## 구조

| 경로 | 내용 |
|---|---|
| `src/back/` | Spring Boot 앱(Gradle Kotlin, 패키지 `dev.measure.bench`). 측정 대상 |
| `src/front/` | 비워 둔다. 프런트(React)는 S3+CloudFront 로 따로 배포할 계획이라 이번 범위 밖 |
| `measure.sh` | 시나리오 ID 를 받아 측정·판정한다. 판정 실패 시 exit 1 |
| `scripts/` | 보조 스크립트(`check-public.sh` 등) |
| `tests/` | 셸 테스트(`*.test.sh`). Docker 없이 고정 입력으로 돈다 |
| `results/` | 회차별 원본 출력 |

## 개발 원칙 — 간결하고 유지보수하기 쉽게

- 요청받은 것만 만든다. 추상화·설정·기능을 미리 넣지 않는다. 쓰는 곳이 하나면 함수·클래스로 빼지 않는다.
- 새 의존성을 추가하지 않는다. 표준 도구(bash, curl, docker)로 되는 것은 그걸로 한다.
- 파일은 적게, 한 파일은 한 가지 일만. 기존 관례를 따르고 인접 코드를 "개선"하지 않는다.
- 일부러 줄인 부분이 있으면 `ponytail:` 주석으로 한계와 올릴 때를 적는다.
- **측정용 앱 코드(`src/back`)는 조건을 고정하려고 요청서대로 둔다.** 패키지·의존성·설정을 바꾸지 않는다. 뼈대의 `BenchApplication.java` 도 그대로.

## 측정 규칙

- 고정 조건: Spring Boot **4.1.2**, Java **25**, Gradle Kotlin(wrapper 는 뼈대에 포함), 가상 스레드 끔, HikariCP 기본값.
  이미지: 빌드 `eclipse-temurin:25-jdk`, 실행 `eclipse-temurin:25-jre`, DB `postgres:18-alpine`, 부하 `httpd:2.4-alpine`(`ab`).
- **CPU·메모리를 아낀다**: 무거운 작업(빌드·측정)은 한 번에 하나, 호스트 명령은 `nice -n 19`, 빌드 컨테이너는 `--cpus=2`.
- **ondolith 저장소(`~/projects/emirue/ondolith`)는 읽기만 한다.** 기준 숫자의 출처 확인용이며 숫자를 바꾸지 않는다.
- **AWS 자원을 만들지 않는다.** 실제 Lightsail 측정은 범위 밖이다.
- 측정이 끝나면 만든 컨테이너·네트워크·볼륨(`bench-*`)을 지운다.
- **돌리지 않은 측정을 결과로 적지 않는다.** 못 돌린 것은 「미실행」과 이유를 적는다.
- 「떴다」의 기준은 프로세스가 아니라 **HTTP 200 응답**이다. 죽은 컨테이너의 `docker stats` 0B 를 좋은 성적으로 읽지 않는다.

## TDD

- 동작이 바뀌는 변경은 실패하는 테스트 먼저(Red) → 최소 구현(Green) → 정리(Refactor). 테스트가 **옳은 이유로 실패**하는지 실행해서 확인한다.
- 테스트 대상은 `measure.sh` 의 파싱·판정 로직과 `scripts/`. 고정 입력으로 먼저 실패시킨다:
  `Complete requests` 줄 없음=실패, `Non-2xx` 줄 없음=0, `docker stats` 값이 `0B`·비숫자=실패, `OOMKilled=true`=실패, `OutOfMemoryError` 1건 이상=실패.
- `src/back` 앱 코드는 요청서대로 고정이라 Red 를 만들 수 없다. 빌드 성공과 실제 200 응답으로 검증한다.
- 실행: `for t in tests/*.test.sh; do bash "$t" || exit 1; done`
- 완료 보고에는 실행한 명령과 통과·실패 숫자를 적는다. 못 돌렸으면 「미실행」과 이유. skip·삭제·단언 완화로 green 을 만들지 않는다.

## advisor 활용

- 접근을 확정하기 전, 끝났다고 말하기 전(커밋해서 결과를 남긴 뒤), 막혔을 때 advisor 를 부른다.
- advisor 조언이 이미 확인한 사실과 충돌하면 조용히 바꾸지 말고 한 번 더 물어 조율한다.

## 작업 방식

- 기본 브랜치(`main`)에서 직접 작업하지 않는다. feature 브랜치에서 한다.
- 추측하지 않는다. 확인한 사실과 추정을 구분하고, 근거 없는 수치·시간 예측을 만들지 않는다.
- 작업 중 만든 임시 파일은 끝나면 정리한다.
