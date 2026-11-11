# ondolith-j — ondolith 코어를 Spring Boot + React 로 다시 만든다

ondolith(Go) 의 기능을 백엔드 Spring Boot(API) 와 프런트 React(SPA) 로 다시 만든 **공개 코어**다.
- ondolith 와는 **완전히 분리된 저장소**다. ondolith 는 읽기만 하는 참고 자료이고, 코드·이력을 공유하지 않는다.
- 배포형 제품이다. 설치처마다 DB·PG·도메인이 다르니 우리 환경을 가정한 하드코딩을 하지 않는다.
- 요청서는 `docs/request-core.md`. 기능별 상세(완료 기준·화면·정책값)의 단일 출처는 ondolith 원문 `docs/` 다.
- 앞서 한 메모리 실측(Lightsail 512MB/1GB 에 Spring Boot + PostgreSQL)의 결과는 `docs/measure-lightsail.md`·`results/` 에 있다.

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
- 공격자가 코드를 전부 읽는다고 가정한다. 방어는 숨김이 아니라 코드와 설정에 있어야 한다.
- `.env`·실제 설정 파일·DB 덤프·업로드·백업도 커밋하지 않는다. `.gitignore` 로 막고 `*.example` 만 커밋한다. 테스트·시드에는 누가 봐도 가짜인 값만 쓴다.
- 프런트 빌드 변수(`VITE_*` 등 번들에 들어가는 값)는 전부 공개다. 비밀을 넣지 않는다.
- GitHub 설정 변경도 사용자 승인 후에만 하고, 현재 상태를 먼저 보고한다.
- CI: `pull_request` 트리거만(`pull_request_target` 금지), 권한 read 최소, 서드파티 Action 은 커밋 SHA 고정, 비밀은 GitHub Secrets 에만.

## 결정 — 답을 받기 전에는 그 영역을 만들지 않는다

- 미결: D-1 테마, D-2 검색 노출·링크 미리보기, D-3 배포 단위·NFR 재정의, D-4 Lightsail 티어. 답은 스펙에 적는다.
- 유지: 카드번호·유효기간·CVC 저장 금지 / 게시판별 CREATE TABLE 금지(JSONB) / 관리자 CRUD 자동 생성기 금지 / 런타임 플러그인 로더 없음 / 설치에 콘솔 토큰 요구 금지.

## 구조

| 경로 | 내용 |
|---|---|
| `src/front/` | 코어 프런트(React SPA). S3+CloudFront 로 배포 |
| 코어 백엔드 | `src/back/` 은 측정 앱이 쓰고 있어서, 경로는 스펙에서 정한다 |
| `src/back/` | 측정용 Spring Boot 앱(Gradle Kotlin, 패키지 `dev.measure.bench`). 지우거나 덮지 않는다 |
| `docs/` | 요청서, 스펙, 계획 |
| `measure.sh` | 시나리오 ID 를 받아 측정·판정한다. 판정 실패 시 exit 1 |
| `scripts/` | 보조 스크립트(`check-public.sh` 등) |
| `tests/` | 셸 테스트(`*.test.sh`). Docker 없이 고정 입력으로 돈다 |
| `results/` | 회차별 원본 출력 |

## 개발 원칙 — 간결하고 유지보수하기 쉽게

- 요청받은 것만 만든다. 추상화·설정·기능을 미리 넣지 않는다. 쓰는 곳이 하나면 함수·클래스로 빼지 않는다.
- 의존성은 꼭 필요할 때만 쓴다. 추가 전에 취약점(OSV 등)과 유지 상태를 확인하고 잠금 파일을 커밋한다. 표준 도구로 되는 것은 그걸로 한다.
- **코어의 언어·프레임워크·라이브러리·도구는 최신 안정판(GA)을 쓴다.** 알파·베타·RC·스냅샷은 쓰지 않는다. 판과 API 는 기억으로 쓰지 않고, 고르거나 올릴 때마다 context7 로 확인한다(모자라면 공식 문서). 측정 앱 `src/back` 은 측정 조건을 고정하려고 예외다. Java 는 최신 LTS 를 쓴다(지금 25, 2026-11-11 결정).
- 도구 판(Java·Node 등)은 mise 로 고정한다: `mise.toml` + `mise.lock` 을 커밋하고 `mise install --locked` 로 설치한다. mise 가 못 다루는 것만 다른 방법(Gradle wrapper, 잠금 파일, Docker 이미지 태그)으로 고정한다.
- 인증·세션·CSRF·업로드 검증은 직접 만들지 않는다. Spring Security 와 검증된 라이브러리를 쓴다.
- 새 기능·Phase 는 `superpowers:brainstorming` → 스펙 승인 → `superpowers:writing-plans` → TDD 순서로 한다. 한 번에 다 만들지 않는다.
- 파일은 적게, 한 파일은 한 가지 일만. 기존 관례를 따르고 인접 코드를 "개선"하지 않는다.
- 일부러 줄인 부분이 있으면 `ponytail:` 주석으로 한계와 올릴 때를 적는다.
- **측정용 앱 코드(`src/back`)는 조건을 고정하려고 요청서대로 둔다.** 패키지·의존성·설정을 바꾸지 않는다. 뼈대의 `BenchApplication.java` 도 그대로.

## 측정 규칙

- 고정 조건: Spring Boot **4.1.2**, Java **25**, Gradle Kotlin(wrapper 는 뼈대에 포함), 가상 스레드 끔, HikariCP 기본값.
  이미지: 빌드 `eclipse-temurin:25-jdk`, 실행 `eclipse-temurin:25-jre`, DB `postgres:18-alpine`, 부하 `httpd:2.4-alpine`(`ab`).
- **ondolith 저장소(`~/projects/emirue/ondolith`)는 읽기만 한다.** 기준 숫자의 출처 확인용이며 숫자를 바꾸지 않는다.
- **AWS 자원을 만들지 않는다.** 실제 Lightsail 측정은 범위 밖이다.
- 측정이 끝나면 만든 컨테이너·네트워크·볼륨(`bench-*`)을 지운다.
- **돌리지 않은 측정을 결과로 적지 않는다.** 못 돌린 것은 「미실행」과 이유를 적는다.
- 「떴다」의 기준은 프로세스가 아니라 **HTTP 200 응답**이다. 죽은 컨테이너의 `docker stats` 0B 를 좋은 성적으로 읽지 않는다.
- 코어에 기능을 붙이면 같은 방법으로 다시 잰다. 측정 앱은 API 두 개짜리라 코어가 더 클 수 있다.

## TDD

- 동작이 바뀌는 변경은 실패하는 테스트 먼저(Red) → 최소 구현(Green) → 정리(Refactor). 테스트가 **옳은 이유로 실패**하는지 실행해서 확인한다.
- 코어 테스트 명령은 뼈대를 만들 때 여기에 적는다.
- 측정 쪽 테스트 대상은 `measure.sh` 의 파싱·판정 로직과 `scripts/`. 고정 입력으로 먼저 실패시킨다:
  `Complete requests` 줄 없음=실패, `Non-2xx` 줄 없음=0, `docker stats` 값이 `0B`·비숫자=실패, `OOMKilled=true`=실패, `OutOfMemoryError` 1건 이상=실패.
- `src/back` 앱 코드는 요청서대로 고정이라 Red 를 만들 수 없다. 빌드 성공과 실제 200 응답으로 검증한다.
- 실행: `for t in tests/*.test.sh; do bash "$t" || exit 1; done`
- 완료 보고에는 실행한 명령과 통과·실패 숫자를 적는다. 못 돌렸으면 「미실행」과 이유. skip·삭제·단언 완화로 green 을 만들지 않는다.

## advisor 활용

- 접근을 확정하기 전, 끝났다고 말하기 전(커밋해서 결과를 남긴 뒤), 막혔을 때 advisor 를 부른다.
- advisor 조언이 이미 확인한 사실과 충돌하면 조용히 바꾸지 말고 한 번 더 물어 조율한다.

## 작업 방식

- 기본 브랜치(`main`)에서 직접 작업하지 않는다. feature 브랜치에서 한다.
- **CPU·메모리가 튀지 않게 한다.**
  - 무거운 작업(빌드·측정·브라우저 시험)은 한 번에 하나만 돌린다. 호스트 명령은 `nice -n 19`, 빌드 컨테이너는 `--cpus=2` 로 돌린다.
  - Gradle 데몬·개발 서버·브라우저는 다 쓰면 끈다.
  - 서브에이전트를 여러 개 동시에 띄우지 않는다.
- 추측하지 않는다. 확인한 사실과 추정을 구분하고, 근거 없는 수치·시간 예측을 만들지 않는다.
- 작업 중 만든 임시 파일은 끝나면 정리한다.
