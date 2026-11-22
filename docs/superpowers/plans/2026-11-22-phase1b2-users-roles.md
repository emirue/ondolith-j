# Phase 1-B2 재인증·사용자·역할 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자가 `/admin/users` 에서 계정을 만들고 역할을 주고 빼며 비활성·활성으로 바꾸고, `/admin/roles` 에서 역할의 권한을 바꾼다. 로그인한 지 15분이 지났으면 같은 대화상자가 비밀번호를 한 번 더 묻는다. R 규칙 위반은 거절되고 작업 로그에 남는다.

**Architecture:** 세션 셋째 속성 `ondolithj.reauth_at` 과 `auth/Reauth.java` 가 재인증 창을 판정하고, 재인증 대상 처리기 여섯은 첫 줄에서 `reauth.require(request)` 를 부른다. 사용자·역할 처리기는 `admin/UsersController`·`admin/RolesController` 두 파일이고, 마지막 관리자 판정은 `admin/LastAdmin` 하나가 `TransactionTemplate` 안에서 잠금·다시 읽기·셈·쓰기를 한다(기록은 트랜잭션 밖). 프런트는 `useSave` 가 재인증 403 을 받아 값을 남기고, 확인 단추가 같은 대화상자에서 비밀번호를 받아 원 요청을 한 번 다시 보낸다.

**Tech Stack:** Spring Boot 4.1.2(Security 7.1), Flyway, PostgreSQL 18, React 19.3.1 + React Router 8.5.1, TanStack Query 5, zod 4, shadcn AlertDialog(`radix-ui` 1.8.1), Vitest, Playwright 1.65.

**Spec:** `docs/superpowers/specs/2026-11-22-phase1b2-users-roles-design.md`

## Global Constraints

- 저장소는 PUBLIC. 실제 메일·홈 경로·비밀을 쓰지 않는다. 시험 값은 누가 봐도 가짜(`*@ondolith.test` 소문자, `…-pass-…`).
- 새 의존성 없음(스펙 1절). 판·API 는 기억으로 쓰지 않는다. 「구현 때 확인」은 context7(없으면 공식 문서)로 확인하고, 다르면 결정 문서에 적는다.
- TDD: 실패를 눈으로 본 뒤 구현. 단언 완화·skip 금지. 이미 통과하는 시험은 구현을 잠시 되돌리거나 망가뜨려 Red 를 한 번 본다.
- 무거운 작업은 한 번에 하나, `nice -n 19`. Java 명령은 `mise x --` 로.
- 백엔드 한 클래스: `cd src/back && nice -n 19 mise x -- ./mvnw -q -B test -Dtest=<클래스>`. 프런트: `cd src/front && npx vitest run <경로>`. 전체: `nice -n 19 mise run test`. E2E: `mise run e2e`. 셸: `for t in tests/*.test.sh; do bash "$t" || exit 1; done`.
- 시험 HTTP 는 `127.0.0.1`. 마이그레이션은 앞으로만(V1~V3 는 고치지 않는다 — 주석도).
- 오류는 Problem Details, JSON 은 snake_case, 422 는 최상위 `errors{칸: 메시지}`. 403·404·409 의 `detail` 은 코드 상수(원문 D19 문구)이고 요청 값이 섞이지 않는다.
- `operation_logs` 는 시험도 비울 수 없다. 행 단언은 **시험마다 새 계정**·증분으로 한다.
- 기록기를 부르는 처리기에 `@Transactional` 을 달지 않는다. 트랜잭션은 `LastAdmin` 안의 `TransactionTemplate` 하나뿐이다.
- `summary` 에는 코드 상수, enum 으로 검증된 권한 키, DB 에서 읽은 역할 키만 넣는다. 요청 본문 문자열은 넣지 않는다(스펙 8절).
- 재인증 대상 여섯(비활성·활성, A-404 둘, A-405 둘)은 본문 첫 줄이 `reauth.require(request)` 이고 `@Valid` 를 쓰지 않는다(칸 검사는 본문에서).
- **API 모양을 바꾸는 커밋(Task 2·4·5·7)은 같은 커밋에서** `OpenApiFileTest` 단언을 고치고 `src/front/openapi.json`·`src/front/src/shared/api/schema.d.ts` 를 다시 만든다(`OpenApiFileTest` 실행 → `cd src/front && npm run gen:api`).
- V4 와 `Permission` 여섯은 같은 커밋(Task 3) — 아니면 기동 점검의 DB·코드 키 대조가 기동을 멈춘다.
- **동시 요청 시험의 모든 대기에 상한**: 시험 연결 `lock_timeout 5s`·`statement_timeout 30s`, 앱 연결 `lock_timeout 20s`, HTTP 요청 시한 30초, `Future.get` 30초, `pg_locks` 관찰 최대 10초(넘으면 단언 실패), 메서드 `@Timeout(90)`. 시험 연결의 rollback·close 는 `finally`. 멈추지 말고 실패해야 한다.
- 프런트: **관리자 쿼리 키는 모두 `["admin", …]` 아래(1-B1 최종 검토 I-1)**, 1-B1 화면처럼 `gcTime: 0`. 재인증 비밀번호는 상태·쿼리 캐시·변경 캐시에 두지 않는다.
- 성공·오류 문구는 프런트 상수다. 성공 문구: 생성 「사용자를 만들었습니다」, 비활성 「사용자를 비활성화했습니다」, 활성 「사용자를 활성화했습니다」, 역할 부여 「역할을 부여했습니다」, 역할 빼기 「역할을 뺐습니다」, 권한 부여 「권한을 부여했습니다」, 권한 빼기 「권한을 뺐습니다」.

## Ruling — 측정 `PLATFORM`

`PLATFORM` 기본값은 Docker 서버의 `os/arch`(호스트)이고, 호스트와 다르게 주면 하네스 실패(exit 2)다. 근거: `docs/measure-lightsail.md` 는 대상 Lightsail 의 아키텍처를 정하지 않는다 — 있는 것은 측정 머신 arm64(조건 표), 「Lightsail 과 아키텍처가 다르면 숫자가 조금 다를 수 있다」(조건 표·한계 절), 번들 이름 `nano_3_0` 뿐이다(원문 `72-deploy-lightsail.md` 도 「amd64 또는 arm64」). 측정이 흉내 내는 것은 티어의 메모리 상한이고 아키텍처 차이는 이미 적힌 한계다. 다른 아키텍처를 고르면 에뮬레이션(M-11)이 값을 틀어 측정 목적에 어긋난다.

## 스펙과 다르게(또는 스펙이 침묵해서) 정한 것

1. 측정: 이미지 대 `PLATFORM` 에 더해 **Docker 서버 대 `PLATFORM`** 도 `platform_ok` 로 본다(스펙 10절은 이미지만). 다이제스트는 측정 때가 아니라 Task 1 에서 넣는다 — 측정 세션은 같은 값을 쓴다.
2. R6 의 403 `detail` 은 「권한이 없습니다」 — D19 A-402 오류표에 R6 행이 없고 원문 코드는 일반 403(`forbid`)이다. A-404 빼기의 R4 문구는 부여와 같은 상수다.
3. 본문의 `role_id`·`permission` 이 null·빠짐이면 422 「필수입니다」, 기록하지 않는다(재인증 뒤).
4. A-403 응답의 `is_superuser` 는 술어 값(`anonymous`·`member` 의 표시는 false)이다. R3·R5 의 「superuser 역할」 판정도 같은 술어다.
5. 시험 배분: 재인증 창 통합 시험과 「재인증 뒤 비활성화 통과」는 비활성화가 생기는 Task 4, 교차 동시 시험은 A-405 빼기가 생기는 Task 7 이다. Task 4·5 의 비활성화는 잠금 없이 쓰고 Task 6 이 `LastAdmin` 으로 바꾼다.
6. 이름: 재인증 예외는 `ReauthRequired` 가 아니라 `Reauth.Required`(속성·판정·예외를 `auth/Reauth.java` 한 파일에). 403·404·409 의 상수 문구는 공용 예외 `shared/Refused` 로 낸다.
7. 조회 화면의 403 은 1-B1 처럼 `null` 로 받지 않고 `LoadError` 를 던져 공용 `LoadFailure` 가 401·403·그 밖을 가른다(스펙 9절 「조회 화면 공용」을 한 부품으로).

## Review Focus

1. **공백만인 검색어** — `q=%20%20` 은 검색 없이 1쪽 전체 목록(200), 422·400 이 아니다. Task 5 시험.
2. **검색 중 쪽 이동** — 「다음」 링크가 `q` 를 잃지 않는다(`?page=2&q=…`). Task 9 시험.
3. **본문 칸이 null·빠짐** — `{}`·`{"role_id": null}`·`{"permission": null}` 은 500 이 아니라 422 「필수입니다」이고 행을 남기지 않는다. Task 7 시험.
4. **다시 보낸 원 요청이 401** — 재인증 204 뒤 원 요청이 401 이면 대화상자를 닫고 「로그인이 끝났습니다」·로그인 링크. Task 8 시험.
5. **재인증 429** — 대화상자 안에 「잠시 뒤 다시 시도하세요」, 남긴 값은 그대로이고 다시 입력해 204 면 원 요청이 한 번 간다. Task 8 시험.

---

### Task 1: 측정 하네스 — 이미지 다이제스트·플랫폼 고정 (기능과 무관)

**Files:**
- Modify: `measure.sh`, `tests/measure.test.sh`, `docs/measure-lightsail.md`(조건 표의 이미지 줄, 「다시 재기」의 빌드 명령)

**Interfaces:**
- Produces: `platform_ok <검사값> <PLATFORM>` — 둘 다 비지 않고 같으면 0, 아니면 1. 순수 함수(Docker 를 부르지 않는다).
- Produces: 변수 `DB_IMAGE`·`JRE_IMAGE`·`HTTPD`(기존 이름 유지) = `태그@sha256:<인덱스 다이제스트>`. 모든 `docker run` 에 `--platform "$PLATFORM"`.
- `PLATFORM` 은 `main` 안에서만 계산한다: `PLATFORM=${PLATFORM:-$(docker version -f '{{.Server.Os}}/{{.Server.Arch}}')}`. 최상위에서 `docker` 를 부르면 `tests/measure.test.sh`(source 한다)가 Docker 없이 돌지 못한다.
- 검사값은 `{{.Os}}/{{.Architecture}}` 로만 만든다(variant 를 넣지 않는다 — imagetools 는 `linux/arm64/v8`, `docker version` 은 `linux/arm64` 다). `docker image inspect` 에 `--platform` 을 주지 않는다(주면 그 판을 골라 늘 같다).

- [ ] **Step 1: arm64 판 확인** — `docker buildx imagetools inspect <이미지>` 를 `postgres:18-alpine`·`eclipse-temurin:25-jre`·`httpd:2.4-alpine`·`eclipse-temurin:25-jdk` 에 돌려, 각 `Platform:` 줄에 호스트 판(`linux/arm64/v8`)이 있는지와 첫 `Digest:` 줄(인덱스 다이제스트)을 적는다. **하나라도 없으면 다이제스트 고정 단계(Step 4)에서 멈추고 보고한다.** 참고(2026-11-22 계획 담당 관찰, 넣을 값은 조회 결과로): 넷 모두 `linux/arm64/v8` 이 있었다. 로컬 `RepoDigests` 는 postgres 만 레지스트리와 같았고 jre·jdk·httpd 는 낡았다 — 로컬 값을 쓰지 않는다.
- [ ] **Step 2: 실패하는 시험**(`tests/measure.test.sh`)
  ```bash
  chk "플랫폼 같으면 통과" "0|" "$(run platform_ok linux/arm64 linux/arm64)"
  chk "아키텍처가 다르면 실패" "1|" "$(run platform_ok linux/amd64 linux/arm64)"
  chk "검사값이 비면 실패" "1|" "$(run platform_ok '' linux/arm64)"
  chk "PLATFORM 이 비면 실패" "1|" "$(run platform_ok linux/arm64 '')"
  ```
- [ ] **Step 3: Red** — `bash tests/measure.test.sh` → 함수가 없어 넷 모두 `127|` 로 실패.
- [ ] **Step 4: 구현**
  - `platform_ok`, 이미지 변수 셋(Step 1 의 다이제스트), 모든 `docker run`(DB·앱 두 갈래·기동 확인·데우기·`run_ab`·`env`)에 `--platform`.
  - `measure()` **맨 앞**(jar 확인·`rm -rf "$OUT"`·`cleanup` 보다 앞 — 늦으면 jar 없음으로 거짓 exit 2 가 나거나 지난 결과 폴더를 비운 뒤 멈춘다): `platform_ok "$(docker version -f …)" "$PLATFORM" || hf "PLATFORM($PLATFORM)이 Docker 서버와 다르다 — 에뮬레이션 값은 남기지 않는다"`. 세 이미지를 `docker pull --platform "$PLATFORM" <참조>` 한 뒤 `platform_ok "$(docker image inspect -f '{{.Os}}/{{.Architecture}}' <참조>)" "$PLATFORM" || hf "<참조> 가 $PLATFORM 이 아니다"`. 태그와 다이제스트를 함께 쓴 참조로 pull·inspect 가 되는지는 구현 때 확인(안 되면 `저장소@sha256:…` 만 쓰고 태그는 주석에).
  - `env`: `PLATFORM`, 서버 `os/arch`, 세 이미지의 참조·`{{.Os}}/{{.Architecture}}`·`{{index .RepoDigests 0}}`, `java -version`(JRE), DB 판(`docker run --rm --platform … "$DB_IMAGE" postgres --version`)을 `results/env.txt` 에.
  - 측정 문서: 조건 표의 빌드·실행·DB·부하 이미지 줄을 「`measure.sh` 의 고정 참조(태그@다이제스트), `--platform` 호스트」로, 「다시 재기」의 빌드 명령을 `--platform` + `eclipse-temurin:25-jdk@sha256:<Step 1 값>` 으로. 위 Ruling 을 조건 표 아래 한 줄로 옮긴다. 1-A·Phase 0 절의 숫자는 고치지 않는다.
- [ ] **Step 5: Green** — `bash tests/measure.test.sh` → `measure: 통과 N / 실패 0`. `./measure.sh env` 를 한 번 돌려 `results/env.txt` 의 세 이미지가 모두 `linux/arm64` 이고 다이제스트가 Step 1 값과 같은지 본다. 확인 뒤 `git checkout -- results/env.txt`(측정 세션이 새로 쓴다). `LABEL=platform-check PLATFORM=linux/amd64 ./measure.sh S5 1` 이 exit 2 이고 stderr 가 「Docker 서버와 다르다」인지(종료 코드만 보지 않는다), `results/platform-check/` 가 생기지 않았는지 확인하고 `./measure.sh clean`.
- [ ] **Step 6: 커밋** `측정: 이미지를 다이제스트로 고정하고 플랫폼이 다르면 하네스 실패`

### Task 2: 재인증 창, `POST /api/admin/reauth`, 로그인 때 시각 찍기

**Files:**
- Create: `src/back/src/main/java/dev/ondolithj/auth/Reauth.java`, `auth/ReauthController.java`
- Modify: `auth/AuthConfig.java`(빈), `auth/LoginController.java`, `auth/RateLimitProperties.java`, `shared/ApiErrors.java`, `OpenApiFileTest.java`, `src/front/openapi.json`(생성), `schema.d.ts`(생성)
- Create(시험): `src/back/src/test/java/dev/ondolithj/auth/ReauthTest.java`(단위), `auth/ReauthApiTest.java`, `auth/ReauthTargetsTest.java`, `fixture/guard/UnclassifiedPost.java`, `fixture/guard/UnclassifiedPostConfig.java`
- Modify(시험): `auth/LoginTest.java`, `auth/AuthFixture.java`

**Interfaces:**
- Produces: `public class Reauth` — `static final String AT = "ondolithj.reauth_at"`, 창 15분. `public static boolean open(Object value, Instant now)`: `value instanceof Instant t && !t.isAfter(now) && now.isBefore(t.plus(15분))`. `public void require(HttpServletRequest request)`: `getSession(false)` 의 속성으로 판정, 닫혀 있으면 `throw new Reauth.Required()`. `public void stamp(HttpServletRequest request)`: 기존 세션에 `clock.instant()`. 생성자 `Reauth(Clock clock)`, `AuthConfig` 가 `new Reauth(Clock.systemUTC())` 로 빈을 낸다(`RateLimiter` 와 같다).
- Produces: `public static final class Reauth.Required extends RuntimeException` — **`AccessDeniedException` 을 상속하지 않는다**(`ApiErrors` 의 catch-all 이 다시 던져 `about:blank` 403 이 된다). `ApiErrors` 의 전용 `@ExceptionHandler` 가 403, `type` = `urn:ondolithj:problem:reauth-required`, `detail` 「비밀번호를 다시 입력하세요」.
- Produces: `POST /api/admin/reauth` — `@RequiresPermission(ADMIN_ACCESS)`, `@Valid record ReauthRequest(@NotEmpty(message = "필수입니다") @Size(max = 1000, message = "1000자 이하") String password)`, `toString` 은 `"ReauthRequest[]"`. 순서: `limiter.hit("reauth:" + actor.id(), limits.reauth())` 막히면 `LoginController.tooMany` → `AuthenticationManager.authenticate(actor.email(), password)` (`InternalAuthenticationServiceException` 은 로그인처럼 `IllegalStateException`, `AuthenticationException | IllegalArgumentException` 은 `Reauth.Required`) → `stamp` → 204. 세션 ID 를 바꾸지 않는다. INFO 「재인증 성공: 사용자 {id}」·「재인증 실패: 사용자 {id}」. `@ApiResponse` 204·401·403·422·429.
- `RateLimitProperties` 에 `@DefaultValue("5") int reauth`(`ondolithj.rate-limit.reauth`).
- 로그인: `contexts.saveContext(...)` 바로 뒤 `reauth.stamp(request)`.
- `SessionContextRepository` 는 고치지 않는다 — 필수 속성 둘만 본다(스펙 3.1).
- Produces(시험): `AuthFixture.reauthAt(TestHttp http, Object value)` — 쿠키의 세션을 `SessionRepository` 로 읽어 `Reauth.AT` 를 `value` 로(null 이면 지운다) 저장.
- Produces(시험): `ReauthTargetsTest` — `static final Set<String> TARGETS`(이 Task 에서는 빈 집합), `NOT_TARGETS = {"POST /api/admin/settings", "POST /api/admin/reauth"}`. 키는 `<메서드> <패턴>`, 메서드 제한 없는 매핑은 `ANY <패턴>`. `static Set<String> unclassified(ConfigurableApplicationContext ctx)` — 모든 `RequestMappingHandlerMapping` 에서 패턴이 `/api/admin` 으로 시작하고 GET 이 아닌 키 중 두 표에 없는 것. `static Call call(String key, Ids ids)` — 키마다 실제 요청(경로·본문), 모르는 키는 `IllegalStateException("표에 요청을 더한다: " + key)`. `record Ids(UUID user, UUID role)`. `static String state()` — `users`(id·`is_active`·`sessions_valid_from`)·`user_roles`·`role_permissions` 의 md5 와 `operation_logs` 건수를 이은 문자열.
- `fixture.guard.UnclassifiedPost` — `@RequiresPermission(ADMIN_ACCESS) @PostMapping("/api/admin/unclassified-probe")` → 204. `UnclassifiedPostConfig` 가 그 빈과 `GuardCheck.Allowed("fixture.guard")` 를 낸다.

- [ ] **Step 1: 실패하는 시험**
  - **첫 Red** `LoginTest`: 기존 `세션에는_사용자_ID_와_DB_의_기준_시각만_담긴다` → `세션에는_사용자_ID_기준_시각_재인증_시각만_담긴다`. 로그인 직전 `before = Instant.now()`, 직후 `after`. 속성 이름에 셋이 있고(`contains`, `SPRING_SECURITY_CONTEXT` 없음은 그대로), `ondolithj.reauth_at` 은 `Instant` 이고 `before ≤ 값 ≤ after`. 나머지 단언 그대로.
  - `ReauthTest`(단위, `t0 = Instant.parse("2026-11-22T00:00:00Z")`): `open(t0, t0)`·`open(t0, t0 + 14분 59.999초)` true, `open(t0, t0 + 15분)` false, `open(t0, t0 - 1ms)`(미래) false, `open(null, t0)`·`open("2026-11-22T00:00:00Z", t0)` false.
  - `ReauthApiTest`(`AuthFixture.start(false)` — 재인증 한도는 기본 5, 계정은 시험마다 새로 `admin` 역할):
    - `맞는_비밀번호는_204_이고_세션_ID_가_그대로다`: `reauthAt(http, null)` 뒤 204, `SESSION` 쿠키 값이 전과 같고 세션 속성이 `Instant` 로 다시 있다.
    - `틀린_비밀번호는_재인증_403`: `type` = URN, `detail` = 「비밀번호를 다시 입력하세요」.
    - `계정당_5번까지이고_6번째는_429_Retry_After_다른_계정은_따로_센다`: `RateLimitTest.withinOneWindow` 안에서 X 의 틀린 비밀번호 5번 403, 6번째 429 + `Retry-After`. 같은 창에서 Y 의 맞는 비밀번호 204.
    - `CSRF_없으면_403_익명은_401`: `postRaw` 403, 로그인 안 한 클라이언트 401.
    - `1000자를_넘거나_비면_422`: `"a".repeat(1001)`·`""` → 422 `errors.password`.
    - `73바이트_비밀번호는_500_이_아니라_403`: `"a".repeat(73)` → 403 URN.
    - `비밀번호가_앱_로그와_작업_로그에_없다`(`CapturedOutput`): 틀린 값 `"reauth-wrong-7c1d"` 와 맞는 값으로 한 번씩 → 출력에 두 값 모두 없다, 그 계정의 `row_to_json(o)` 행 어디에도 없다.
  - `ReauthTargetsTest`(운영 `f.start(false)`):
    - `관리자_경로의_GET_이_아닌_처리기는_모두_분류되고_표의_키는_모두_있다`: `unclassified(f.ctx)` 빈 집합, `TARGETS ∪ NOT_TARGETS` 의 키가 모두 매핑에 있다.
    - `분류되지_않은_POST_가_있으면_잡아낸다`: 두 번째 `AuthFixture().start(false, "--spring.main.sources=fixture.guard.UnclassifiedPostConfig")` → `unclassified` 가 `POST /api/admin/unclassified-probe` 를 담는다. `finally` 에서 닫는다.
    - `대상은_창이_닫히면_재인증_403_이고_DB_가_그대로다`: `TARGETS` 마다 admin 세션 `reauthAt(http, null)` → `call` → 403 URN, `state()` 전후 같다(이 Task 에서는 대상이 없다 — Task 4·7 이 채운다).
    - `대상_아님은_창이_닫혀도_재인증_type_을_내지_않는다`: `NOT_TARGETS` 마다 창을 닫고 `call` → 응답 `type` 이 URN 이 아니다(설정은 `{"name": "이름-n"}` 204, 재인증은 맞는 비밀번호 204).
  - `OpenApiFileTest`: 경로 목록에 `/api/admin/reauth`(정렬 위치), POST 응답 = `204, 401, 403, 422, 429`, 401·403·422·429 는 `ProblemDetail` 참조.
- [ ] **Step 2: Red** — `-Dtest='LoginTest,ReauthTest,ReauthApiTest,ReauthTargetsTest,OpenApiFileTest'` → 컴파일 실패, 그다음 `LoginTest` 속성 셋 실패·`/api/admin/reauth` 404.
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** + 전체 백엔드 `cd src/back && nice -n 19 mise x -- ./mvnw -q -B test` → `npm run gen:api && npm run typecheck`.
- [ ] **Step 5: 커밋** `사용자·역할 2: 재인증 창과 POST /api/admin/reauth, 로그인 때 재인증 시각`

### Task 3: V4 권한 여섯, `Actor.superuser`, superuser 술어 하나, M-5·M-7

**Files:**
- Create: `src/back/src/main/resources/db/migration/V4__users_roles.sql`
- Modify: `auth/Permission.java`, `auth/Actor.java`, `auth/ActorQuery.java`, `auth/GuardCheck.java`
- Create(시험): `fixture/guard/AreaVariable.java`, `fixture/guard/AreaVariableConfig.java`
- Modify(시험): `MigrationTest.java`, `auth/MeTest.java`, `auth/GuardCheckTest.java`, `admin/SystemApiTest.java`, `shared/OperationLogTest.java`(`new Actor(…)` 인자)

**Interfaces:**
- Produces: `Permission` 에 `USER_VIEW("user.view", false)`, `USER_CREATE("user.create", false)`, `USER_UPDATE("user.update", false)`, `ROLE_VIEW("role.view", false)`, `ROLE_ASSIGN("role.assign", false)`, `ROLE_MANAGE("role.manage", false)`.
- Produces: V4 — 머리 주석에 M-5(「V3 의 『WHEN 이 그 하나만 통과시킨다』는 사실보다 넓다: 사용자가 남아 있어도 `SET actor_user_id = NULL` 만 하는 UPDATE 는 통과한다. 원문과 같은 한계, `actor_email` 은 못 바꾼다. V3 는 체크섬 때문에 고치지 않는다」). 권한 여섯(설명은 스펙 2절 표 그대로), `operator` 에 `user.view`·`role.view`.
- Produces: `record Actor(UUID id, String email, String displayName, Instant authAt, boolean superuser, Set<Permission> permissions)`.
- Produces: `ActorQuery.SUPERUSER_ROLE = "(r.is_superuser AND r.key NOT IN ('anonymous', 'member'))"`(별칭 `r` = roles), `ActorQuery.HOLDS_SUPERUSER = "EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = u.id AND " + SUPERUSER_ROLE + ")"`(별칭 `u` = users). `ActorQuery.SQL` 의 superuser 칸이 `SUPERUSER_ROLE` 을 쓴다. R6·`LastAdmin`·A-403·A-405 가 이 둘만 쓴다.
- Produces: `GuardCheck` — 패턴이 `^/api/[^/]*[{*]` 와 맞는 매핑을 문제로 낸다(메시지 「/api/ 바로 다음 마디에 변수·와일드카드를 쓸 수 없다」와 클래스#메서드). 함수 `static List<String> checkApiRoot(Map<RequestMappingInfo, HandlerMethod>)`, `afterSingletonsInstantiated` 가 부른다.
- `fixture.guard.AreaVariable` — `@RequiresPermission(ADMIN_ACCESS) @GetMapping("/api/{area}/probe")`. `AreaVariableConfig` 가 빈만 낸다(허용 빈은 `AllowOutsideConfig`).

- [ ] **Step 1: 실패하는 시험**
  - **첫 Red** `MigrationTest`: `V3_가_관리자_권한_셋을_심고_operator_에만_준다` 는 `.target("3")` 로 띄운다(내용 그대로). 새 `V4_가_사용자_역할_권한_여섯을_심고_operator_에는_보기_둘만_준다`: 권한 키 열(`admin.access`·`settings.view`·`settings.update`·`log.view`·여섯), `operator` = `admin.access`·`settings.view`·`settings.update`·`log.view`·`user.view`·`role.view`, `editor` = `admin.access` 만, `admin`·`member`·`anonymous` 는 없다. `실패한_마이그레이션은_원복된다` 의 기대 이력 `"1", "2", "3", "4"`.
  - `MigrationTest` M-5: `V3_의_작업_로그는_고치거나_지울_수_없고_사용자_삭제만_actor_를_비운다` → `V3_의_작업_로그는_고치거나_지울_수_없다_actor_만_NULL_로_바꾸는_UPDATE_는_사용자가_있어도_통과한다_원문과_같은_한계`. 기존 단언은 모두 그대로 두고, 같은 사용자를 가리키는 둘째 행(`target_id = 'limit'`)을 넣어 `UPDATE operation_logs SET actor_user_id = NULL WHERE target_id = 'limit'` 이 **성공**하고 그 행의 `actor_email` 이 그대로인 단언을 더한다(사용자 삭제 전에).
  - `MeTest`: `can` 키가 열이다. `운영자는_관리자_권한_넷이_모두_true_이다` → `운영자는_보기_권한_여섯이_true_바꾸는_넷은_false`(`user.create`·`user.update`·`role.assign`·`role.manage` false). 편집자는 `admin.access` 만 true(이름 그대로).
  - `SystemApiTest`: 적용 목록 `"1", "2", "3", "4"`.
  - `GuardCheckTest` 단위 `API_바로_다음_마디의_변수와_와일드카드는_문제다`: `checkApiRoot` 가 `/api/{area}/probe`·`/api/*/x`·`/api/**` 에 문제 하나씩, `/api/admin/users/{id}`·`/api/admin` 에 없음. 기동 `API_바로_다음_마디에_변수가_있으면_기동이_멈춘다`: `install(AreaVariableConfig.class, AllowOutsideConfig.class)` 가 던지고 스택에 `/api/` 와 `AreaVariable` 이 있다.
- [ ] **Step 2: Red** — `-Dtest='MigrationTest,MeTest,SystemApiTest,GuardCheckTest'` → V4 없음·키 다름·규칙 없음. `RevocationTest` 「모두가_갖는_역할의_슈퍼유저_표시도_효력이_없다_R9」는 술어를 상수로 옮긴 뒤에도 통과해야 한다 — 옮긴 상수에서 `NOT IN` 을 잠시 빼 Red 를 한 번 본다.
- [ ] **Step 3: 구현** — 위 Interfaces. `ActorQuery.load` 는 `superuser` 칸을 `Actor` 에 싣는다.
- [ ] **Step 4: Green** + 전체 백엔드(미사용 권한 WARN 여섯은 정상, Task 4~7 에서 사라진다).
- [ ] **Step 5: 커밋** `사용자·역할 3: V4 권한 여섯, Actor 의 superuser, superuser 술어 하나, 경로 첫 마디 변수 거부`

### Task 4: A-402 상세·비활성·활성, 경로 UUID 404, 재인증 창 통합

**Files:**
- Create: `src/back/src/main/java/dev/ondolithj/admin/UsersController.java`, `shared/Refused.java`
- Modify: `shared/ApiErrors.java`, `shared/OperationLog.java`, `OpenApiFileTest.java`, `openapi.json`·`schema.d.ts`(생성)
- Create(시험): `src/back/src/test/java/dev/ondolithj/admin/UserStatusTest.java`
- Modify(시험): `auth/ReauthTargetsTest.java`

**Interfaces:**
- Consumes: `Reauth.require`(Task 2), `Actor.superuser()`·`ActorQuery.HOLDS_SUPERUSER`·`USER_UPDATE`(Task 3).
- Produces: `public final class Refused extends RuntimeException { public Refused(HttpStatus status, String detail) }` — `ApiErrors` 가 `ProblemDetail.forStatusAndDetail(status, detail)` 로. 스택을 만들지 않는다(`FieldErrors` 와 같다).
- Produces: `ApiErrors.handleTypeMismatch` 재정의 — `MethodArgumentTypeMismatchException` 이고 그 매개변수에 `@PathVariable` 이 있을 때만 404 「찾을 수 없습니다」, 그 밖은 `super`(A-601 `page=abc` 400 그대로). 이름·시그니처는 구현 때 확인.
- Produces: `OperationLog.Action` 에 `USER_DEACTIVATE("user.deactivate", "user")`, `USER_ACTIVATE("user.activate", "user")`, `USER_DENIED("user.denied", "user")`.
- Produces: `UsersController`(메서드마다 `@AdminPermission(USER_UPDATE)`)
  - `GET /api/admin/users/{id}` → `record UserDetail(UUID id, String email, String displayName, boolean isActive, Instant createdAt, List<RoleRef> roles, List<RoleRef> assignableRoles)`, `record RoleRef(UUID id, String key, String name, boolean isSuperuser)`(`isSuperuser` 는 `SUPERUSER_ROLE`). 질의 셋(사용자, 가진 역할, `is_assignable` 역할 — 정렬 `key`). 없으면 `Refused(404, "사용자를 찾을 수 없습니다")`.
  - `POST …/{id}/deactivate`·`…/activate` — `(@PathVariable UUID id, @AuthenticationPrincipal Actor actor, HttpServletRequest request)`. 순서(스펙 4절): `reauth.require` → 없음 404 → `id.equals(actor.id())` 면 `user.denied` 「<동작> self」 기록 후 `Refused(403, "자기 계정에는 이 작업을 할 수 없습니다")` → 대상이 `HOLDS_SUPERUSER` 이고 `!actor.superuser()` 면 「<동작> r6」 기록 후 `Refused(403, "권한이 없습니다")` → 쓰기 → `user.deactivate`/`user.activate`(`target_id` = `id.toString()`, `summary` `''`) → 204. 비활성 쓰기는 스펙 4절의 `UPDATE … sessions_valid_from = GREATEST(now(), sessions_valid_from + interval '1 microsecond') …` 그대로, 0행이면 404. 활성 쓰기는 `is_active = true, updated_at = now()` 만. 마지막 관리자 잠금은 Task 6 이 붙인다(주석을 남기지 않는다).
  - `@ApiResponse` — 상세 200·401·403·404·429, 비활성 204·401·403·404·409·429(409 는 Task 6 에서 실제로 난다), 활성 204·401·403·404·429.
- `ReauthTargetsTest.TARGETS` 에 `POST /api/admin/users/{id}/deactivate`·`…/activate` 와 `call`(대상 = `Ids.user`, 본문 없음 `""`).

- [ ] **Step 1: 실패하는 시험**(`UserStatusTest`, `AuthFixture.start(false)`, 계정은 시험마다 새로)
  - **첫 Red** `비활성화했다가_활성화해도_그_전_세션은_401`: 대상 T(`editor`)로 로그인해 둔 세션 S, admin 이 T 비활성 204 → 활성 204 → S 의 `GET /api/admin` 401. 기준 시각 이동을 빼면 200 이 되어 실패하는 것을 한 번 본다.
  - `창이_닫힌_세션의_비활성화는_재인증_403_이고_아무것도_바뀌지_않는다`: 값 넷(`null`, 문자열 `"2026-11-22T00:00:00Z"`, `Instant.now().minus(16분)`, `Instant.now().plus(1시간)`)마다 `reauthAt` → 비활성 403 URN, T 의 `is_active` true, admin 의 작업 로그 건수 그대로, 같은 세션 `GET /api/admin` 200.
  - `재인증하면_같은_세션의_비활성화가_통과한다`: 창 닫음 → 403 → `POST /api/admin/reauth` 204 → 비활성 204.
  - `상세는_관리자_200_운영자는_403`: 200 본문에 `roles`(T 의 `editor`)·`assignable_roles`(`admin`·`editor`·`operator`), `password_hash`·`sessions_valid_from` 칸 없음. `operator` 403.
  - `경로가_UUID_가_아니거나_없으면_404_Problem_Details`: `/api/admin/users/not-a-uuid`·`/123`·새 UUID → 404 `application/problem+json`. 같은 fixture 로 `GET /api/admin/oplog?page=abc` 는 여전히 400.
  - `자기_계정은_403_이고_self_행_대문자_경로도_같다`: admin 이 `/api/admin/users/<자기 id 대문자>/deactivate` → 403 「자기 계정에는 이 작업을 할 수 없습니다」, 자기 계정 행 `action='user.denied'`·`summary='user.deactivate self'` 하나, `is_active` true.
  - `R6_비superuser_가_superuser_보유자를_비활성화하면_403_r6`: 시험 역할 `INSERT INTO roles (key, name) VALUES ('probe_users', '시험')` 에 `admin.access`·`user.update`. 그 사용자 → 비활성 403, 행 `user.deactivate r6`(`target_id` = 대상 id). R6 줄을 빼면 이 시험이 실패한다.
  - `R6_은_활성화에도_걸린다_Q19`: 같은 시험 역할 사용자가 비활성 superuser 보유자(`UPDATE users SET is_active = false`)를 활성화 → 403, 행 `user.activate r6`, `is_active` false 그대로. superuser 가 하면 204.
  - `이미_비활성이어도_204_이고_기록한다`: 비활성 두 번 → 둘 다 204, `user.deactivate` 행 둘.
  - `OpenApiFileTest`: 경로 `/api/admin/users/{id}`·`…/activate`·`…/deactivate`, 응답 코드 = 위 Interfaces.
- [ ] **Step 2: Red** — `-Dtest='UserStatusTest,ReauthTargetsTest,OpenApiFileTest'` → 404·분류 안 됨(`ReauthTargetsTest` 의 Red 는 표에 넣기 전 「모두 분류되고」 실패).
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** + 전체 백엔드 → `npm run gen:api && npm run typecheck`.
- [ ] **Step 5: 커밋** `사용자·역할 4: A-402 상세·비활성·활성, 자기 계정·R6 거절과 기록, 경로 UUID 404`

### Task 5: A-401 목록, A-402 생성, 비밀번호·이름 규칙 공유

**Files:**
- Create: `src/back/src/main/java/dev/ondolithj/shared/Password.java`
- Modify: `admin/UsersController.java`, `shared/SiteName.java`, `admin/SettingsController.java`, `install/InstallRequest.java`, `shared/OperationLog.java`, `OpenApiFileTest.java`, `openapi.json`·`schema.d.ts`(생성)
- Create(시험): `src/back/src/test/java/dev/ondolithj/admin/UsersApiTest.java`, `shared/PasswordTest.java`
- Modify(시험): `shared/SiteNameTest.java`, `TestHttp.java`, `auth/ReauthTargetsTest.java`

**Interfaces:**
- Produces: `SiteName.error(String raw, int max)` — 마지막 검사 메시지 `max + "자 이하"`. 부르는 곳(설치·A-201)은 100 을 넘긴다. `strip` 그대로.
- Produces: `public final class Password { public static Optional<String> error(String raw) }` — null 「필수입니다」, 10자 미만 「10자 이상」, UTF-8 72바이트 초과 「72바이트 이하(한글은 약 24자)」. `InstallRequest.adminPassword` 는 `@Size(min = 10)` 를 빼고 `@NotNull` 만, `check()` 가 `Password.error` 를 `admin_password` 로(기존 메시지 그대로 — `InstallApiTest` 가 지킨다).
- Produces: `OperationLog.Action.USER_CREATE("user.create", "user")`.
- Produces: `GET /api/admin/users?page=1&q=` — `@AdminPermission(USER_VIEW)`, `@RequestParam(defaultValue = "1") int page`, `@RequestParam(required = false) String q`. `page < 1` → `FieldErrors(page: "1 이상")`, `q` 를 `strip()` 한 길이 > 200 → `FieldErrors(q: "200자 이하")`, 빈 값이면 조건 없음. `%`·`_`·`\` 를 `\` 로 이스케이프해 `%…%` 로 `ILIKE`(이메일 또는 표시 이름). 질의 하나: 역할 키는 `array_agg`(정렬 `key`), `ORDER BY u.created_at DESC, u.id LIMIT 51 OFFSET (page - 1L) * 50`. 응답 `record UserPage(List<UserItem> items, int page, int pageSize, boolean hasNext)`, `record UserItem(UUID id, String email, String displayName, boolean isActive, List<String> roles, Instant createdAt)`. `@ApiResponse` 200·400·401·403·422·429.
- Produces: `POST /api/admin/users` — `@AdminPermission(USER_CREATE)`(재인증 대상 아님). `@Valid record CreateUser(@NotBlank(message = "필수입니다") @Email(message = "이메일 형식이 아닙니다") @Size(max = 254, message = "254자 이하") String email, String displayName, String password)`, `toString` 은 비밀번호를 뺀다. 본문 검사: `SiteName.error(displayName, 50)` → `display_name`, `Password.error(password)` → `password`. 저장: `email.strip().toLowerCase(ROOT)`, `SiteName.strip(displayName)`, BCrypt, `email_verified_at = now()`, 역할 없음, `RETURNING id`. `DuplicateKeyException`(예외 이름은 구현 때 확인) → `Refused(409, "이미 사용 중인 이메일입니다")`(먼저 세지 않는다, 기록 없음). 성공 → `user.create`(`target_id` = 새 id) → 201 `record Created(UUID id)`. `@ApiResponse` 201·400·401·403·409·422·429.
- Produces(시험): `TestHttp.postAsync(String path, String json, Duration timeout): CompletableFuture<HttpResponse<String>>` — `post` 와 같은 토큰 처리, 요청에 `.timeout(timeout)`.
- `ReauthTargetsTest.NOT_TARGETS` 에 `POST /api/admin/users`(`call` = 새 이메일·표시 이름·`AuthFixture.PASSWORD`, 201).

- [ ] **Step 1: 실패하는 시험**
  - `PasswordTest`: null·`"a".repeat(9)` → 메시지, `"a".repeat(10)` 통과, `"가".repeat(25)`(75바이트) → 「72바이트 이하(한글은 약 24자)」. `SiteNameTest`: `error("가".repeat(51), 50)` = 「50자 이하」, `error("가".repeat(50), 50)` 빈 값, 기존 100 단언은 `error(…, 100)` 로.
  - `UsersApiTest`(`AuthFixture`, OplogApiTest 처럼 `pg_stat_statements` 를 올린다):
    - `목록은_운영자_200_편집자_403`.
    - `쪽당_50_한_행_더_읽어_has_next`: 고유 접두사 `p<n>-` 로 51명을 만들고 `q=p<n>-` → 1쪽 50·`has_next` true·`page_size` 50, 2쪽 1·false. 1쪽이 `created_at` 내림차순.
    - `검색은_이메일과_표시_이름의_일부이고_퍼센트와_밑줄은_글자_그대로`: `a_b` 는 `a_b…` 만(`axb…` 아님), `100%` 는 표시 이름 `100%-…` 만(`1000…` 아님), 표시 이름 일부로 찾는다, 대소문자 무시.
    - `공백만인_q_는_조건_없이_목록`(Review Focus 1): `q=%20%20` → 200, `items` 가 빈 `q` 와 같다.
    - `쪽과_q_검사`: `page=0` 422 `errors.page`, `page=abc` 400, `q` 201자 422 `errors.q`, 200자 200.
    - `목록_질의는_행_수와_관계없이_같다`: `pg_stat_statements` 에서 `users` 가 들고 `pg_stat_statements` 가 들지 않은 문장의 `sum(calls)` 증분이 3명일 때와 54명일 때 같다(1-B1 처럼 「든 것만」 센다). 기대 2(인증 1 + 목록 1) — 다르면 이유를 보고 결정 문서에 적는다.
    - `목록에_비밀번호_해시와_기준_시각이_없다`: 본문에 `password_hash`·`sessions_valid_from`·`$2a$` 가 없다.
    - `생성은_관리자_201_과_user_create_행_운영자는_403`: 201 `id`, 그 id 의 `user.create` 행 하나(`actor_user_id` = admin). `operator` 403.
    - `만든_계정은_인증됐고_역할이_없고_그_비밀번호로_로그인된다`: `email_verified_at IS NOT NULL`, `user_roles` 0, `f.http()` 로그인 204.
    - `본문의_활성_상태와_역할은_무시된다`: `{…, "is_active": false, "roles": ["admin"]}` → 201, `is_active` true, 역할 0.
    - `이메일은_소문자로_저장하고_중복은_대소문자가_달라도_409`: `" New@Ondolith.test "` → 저장값 `new@ondolith.test`, `NEW@ondolith.test` → 409 「이미 사용 중인 이메일입니다」.
    - `같은_이메일_동시_생성은_201_하나_409_하나`(`@Timeout(90)`): `postAsync(…, 30초)` 둘 → `get(30, SECONDS)` → 상태 정렬 `[201, 409]`.
    - `표시_이름은_1자에서_50자`: `""`·`"가".repeat(51)`·`"   "`·`" "`·`"a\u0000b"` 422 `errors.display_name`, `"가".repeat(50)` 201.
    - `비밀번호는_10자_이상_72바이트_이하`: 9자·`"a".repeat(73)` 422 `errors.password`.
  - `OpenApiFileTest`: `/api/admin/users` GET·POST 응답 코드, `InstallRequest` 스키마 갱신.
- [ ] **Step 2: Red** — `-Dtest='PasswordTest,SiteNameTest,UsersApiTest,InstallApiTest,ReauthTargetsTest,OpenApiFileTest'` → 컴파일 실패, 그다음 404·분류 안 됨.
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** + 전체 백엔드(`InstallApiTest` 비밀번호 메시지 그대로) → `npm run gen:api && npm run typecheck`.
- [ ] **Step 5: 커밋** `사용자·역할 5: A-401 사용자 목록, A-402 생성, 비밀번호·이름 규칙을 설치와 함께 쓴다`

### Task 6: 마지막 관리자 잠금 `LastAdmin`

**Files:**
- Create: `src/back/src/main/java/dev/ondolithj/admin/LastAdmin.java`
- Modify: `admin/UsersController.java`(비활성 쓰기)
- Create(시험): `src/back/src/test/java/dev/ondolithj/admin/LastAdminTest.java`

**Interfaces:**
- Consumes: `ActorQuery.HOLDS_SUPERUSER`(Task 3), `TestHttp.postAsync`(Task 5).
- Produces: `@Component public class LastAdmin { public enum Outcome { DONE, REFUSED, GONE } public Outcome run(UUID target, IntSupplier write) }` — `TransactionTemplate` 하나 안에서: `SELECT id FROM roles WHERE is_superuser FOR UPDATE` → 대상 행을 다시 읽는다(`SELECT u.is_active AND <HOLDS_SUPERUSER> FROM users u WHERE u.id = ?`, 없으면 GONE) → `SELECT count(*) FROM users u WHERE u.is_active AND <HOLDS_SUPERUSER>` → 대상이 활성 보유자이고 셈 ≤ 1 이면 `setRollbackOnly()`·REFUSED → 아니면 `write.getAsInt()`, 0 이면 GONE, 아니면 DONE. 기록하지 않는다. Boot 가 `TransactionTemplate` 빈을 내는지, JPA 트랜잭션 관리자 아래서 `JdbcClient` 문장이 같은 연결을 쓰는지는 구현 때 확인(동시 시험이 잡는다).
- 비활성: R6 뒤 `lastAdmin.run(id, () -> <비활성 UPDATE>)` — DONE → 기록·204, REFUSED → `user.denied` 「user.deactivate last_admin」 기록 후 `Refused(409, "마지막 관리자는 비활성화·삭제할 수 없습니다")`, GONE → `Refused(404, "사용자를 찾을 수 없습니다")`. 대상이 보유자가 아니어 보여도 늘 이 함수를 거친다.
- Produces(시험): `LastAdminTest.race(Lock lock, List<Supplier<CompletableFuture<HttpResponse<String>>>> sends): List<Integer>`, `interface Lock { void take(Connection c) throws SQLException; }` — 시험 연결 `autoCommit=false`, `SET lock_timeout = '5s'`·`SET statement_timeout = '30s'`, `lock.take(c)` → `sends` 를 모두 보낸다 → 다른 연결로 `SELECT count(DISTINCT pid) FROM pg_locks WHERE NOT granted` 를 100ms 마다, 10초 안에 2 이상이 아니면 `AssertionError("앱 연결 둘이 10초 안에 잠금을 기다리지 않았다")` → `finally` 에서 `c.rollback(); c.close()` → 각 `get(30, SECONDS)` 의 상태 코드. Task 7 의 교차 시험이 다시 쓴다.
- 시험 fixture: `f.start(false, "--spring.datasource.hikari.connection-init-sql=SET lock_timeout = '20s'")`(속성 바인딩은 구현 때 확인 — 안 되면 결정 문서에 적고 HTTP·`Future` 시한만으로 실패하게 둔다). `@BeforeEach` 에서 `UPDATE users SET is_active = false`(앞 시험의 보유자를 셈에서 뺀다).

- [ ] **Step 1: 실패하는 시험**(`LastAdminTest`)
  - 함수 직접(`f.ctx.getBean(LastAdmin.class)`, 쓰기는 `f.sql` 이 아니라 앱의 `JdbcClient` 빈으로):
    - `유일한_활성_보유자는_거절되고_쓰지_않는다`: A 만 활성 `admin` → `run(A, 비활성 쓰기)` = REFUSED, A 활성 그대로.
    - `비활성_보유자의_회수와_이미_비활성인_대상은_통과한다`: A 활성 admin, B 비활성 admin → `run(B, B 의 admin 행 DELETE)` = DONE. `run(B, 비활성 쓰기)` = DONE.
    - `함수를_부르기_직전에_보유자가_된_대상도_잠금_아래서_다시_읽어_거절한다`: X 활성·역할 없음, 다른 활성 보유자 없음. `f.sql` 로 X 에게 `admin` → `run(X, 비활성 쓰기)` = REFUSED.
    - `member_에_superuser_를_켜도_셈이_늘지_않는다`: `DROP INDEX roles_one_superuser_idx`(슈퍼유저 역할 하나 색인), `member` 의 `is_superuser = true`, M 에게 `member` 행을 직접 넣는다. A 유일 admin → `run(A, …)` = REFUSED. `finally` 에서 M 의 행 삭제, `member` false, `CREATE UNIQUE INDEX roles_one_superuser_idx ON roles ((is_superuser)) WHERE is_superuser`.
    - `없는_대상은_GONE`.
  - **동시**(`@Timeout(90)`) `두_관리자가_서로를_동시에_비활성화하면_하나만_된다`: A·B 활성 admin 둘만, 각자 로그인(창 열림). `race(c → SELECT 1 FROM users WHERE id IN (A, B) FOR UPDATE, [A 가 B 비활성, B 가 A 비활성])` → 상태 정렬 `[204, 409]`, `user.denied`·`summary='user.deactivate last_admin'` 행 하나, 활성 보유자 1명.
- [ ] **Step 2: Red** — `-Dtest=LastAdminTest` → 컴파일 실패, 그다음 동시 시험이 `[204, 204]`. 구현 뒤 `FOR UPDATE` 줄을 잠시 빼 동시 시험이 다시 `[204, 204]` 로 실패하는 것을 본다(스펙 12절 「잠금을 빼면 실패」) — 멈추지 않고 실패해야 한다.
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** + 전체 백엔드.
- [ ] **Step 5: 커밋** `사용자·역할 6: 마지막 관리자 잠금, 비활성화가 잠금 아래서 다시 읽고 센다`

### Task 7: A-403 역할 목록, A-404 권한 부여·빼기, A-405 역할 부여·빼기

**Files:**
- Create: `src/back/src/main/java/dev/ondolithj/admin/RolesController.java`
- Modify: `shared/OperationLog.java`, `OpenApiFileTest.java`, `openapi.json`·`schema.d.ts`(생성)
- Create(시험): `src/back/src/test/java/dev/ondolithj/admin/RolesApiTest.java`, `admin/CoversTest.java`(단위)
- Modify(시험): `admin/LastAdminTest.java`, `auth/ReauthTargetsTest.java`

**Interfaces:**
- Consumes: `Reauth.require`, `Refused`, `LastAdmin.run`, `SUPERUSER_ROLE`·`HOLDS_SUPERUSER`, `Actor.superuser()`.
- Produces: `OperationLog.Action` 에 `USER_GRANT_ROLE("user.grant_role", "user")`, `USER_REVOKE_ROLE("user.revoke_role", "user")`, `ROLE_GRANT_PERMISSION("role.grant_permission", "role")`, `ROLE_REVOKE_PERMISSION("role.revoke_permission", "role")`, `ROLE_DENIED("role.denied", "role")`.
- Produces: `RolesController` 의 판정 함수 둘(패키지 공개 static): `Set<Permission> permissionsOf(boolean superuserRole, Collection<String> keys)` — superuser 역할이면 `EnumSet.allOf`, 아니면 키를 `Permission.ofKey` 로. `boolean covers(Actor actor, Set<Permission> needed)` — `actor.superuser() || actor.permissions().containsAll(needed)`. R2·R4 가 이것 하나를 쓴다.
- Produces: `GET /api/admin/roles` — `@AdminPermission(ROLE_VIEW)`. `record RolesView(List<RoleView> roles, List<PermissionView> permissions)`, `record RoleView(UUID id, String key, String name, String description, boolean isBuiltin, boolean isSuperuser, boolean isAssignable, List<Grant> permissions)`, `record Grant(String key, boolean effective)`, `record PermissionView(String key, String description)`. 질의 둘(역할+부여 집계, 권한 목록), 정렬 `key`. superuser 역할은 `permissions` 빈 목록. `effective` = 역할이 `anonymous`·`member` 가 아니거나 권한이 `visitor()`.
- Produces: `POST /api/admin/roles/{id}/permissions`·`…/permissions/revoke` — `@AdminPermission(ROLE_MANAGE)`, `record PermissionRequest(String permission)`(`@Valid` 없음). 순서는 스펙 6절 그대로, 앞에 재인증. null → 422 `errors.permission` 「필수입니다」(기록 없음). 문구: 역할 없음 404 「찾을 수 없습니다」, 모르는 키 422 「알 수 없는 권한입니다」(행 `role.denied` 「<동작> unknown_permission」 — 키 문자열을 넣지 않는다), R5 403 「이 역할의 권한은 변경할 수 없습니다」, R9 422 「모두가 갖는 역할에는 방문자 권한만 줄 수 있습니다」, R4 403 「보유하지 않은 권한은 부여할 수 없습니다」(빼기도 같은 상수), 없는 부여 404 「그런 부여가 없습니다」. 거절 행 `summary` = `<동작> <권한 키> <규칙>`. 성공 행 `summary` = 권한 키, `target_id` = 역할 id. R9 는 빼기에 없다.
- Produces: `POST /api/admin/users/{id}/roles`·`…/roles/revoke` — `@AdminPermission(ROLE_ASSIGN)`, `record RoleRequest(UUID roleId)`. 순서는 스펙 6절 그대로(앞에 재인증, 빼기는 부여와 같은 R1·R3·R2 뒤 갖지 않음 404). null → 422 `errors.role_id` 「필수입니다」, UUID 가 아니면 400. 문구: 사용자·역할 없음 404 「찾을 수 없습니다」, `is_assignable` false 422 `errors.role_id` 「줄 수 없는 역할입니다」(기록 없음, 부여만), R1 403 「자기 계정의 역할은 변경할 수 없습니다」, R3 403 「이 역할은 관리자만 부여할 수 있습니다」, R2 403 「보유하지 않은 권한이 포함된 역할입니다」, 갖지 않음 404 「그 사용자는 이 역할을 갖고 있지 않습니다」, 마지막 관리자 409 「마지막 관리자의 역할은 회수할 수 없습니다」. 거절 행 `summary` = `<동작> <역할 키> <규칙>`, 성공 행 = 역할 키. 부여는 `INSERT … ON CONFLICT DO NOTHING`(이미 있어도 204·기록). 빼기는 superuser 역할이면 `lastAdmin.run(id, DELETE)`(GONE 은 갖지 않음 404), 아니면 바로 `DELETE`(0행 404).
- `@ApiResponse` 는 스펙 7절 표의 코드 그대로.
- `ReauthTargetsTest.TARGETS` 에 넷과 `call`(권한 `{"permission": "log.view"}` 를 `Ids.role`(editor)에, 역할 `{"role_id": <operator>}` 를 `Ids.user` 에).

- [ ] **Step 1: 실패하는 시험**
  - **첫 Red** `RolesApiTest.R1_자기에게_역할을_주면_403_이고_r1_행_하나`: admin 이 자기에게 `editor` → 403 「자기 계정의 역할은 변경할 수 없습니다」, `user.denied`·`summary='user.grant_role editor r1'` 행 하나, `user_roles` 그대로.
  - `CoversTest`: 비-superuser(권한 `ADMIN_ACCESS`·`USER_VIEW`)와 `permissionsOf(true, List.of())` → false(빈 집합으로 통과하지 않는다). superuser Actor → true. `permissionsOf(false, ["admin.access", "user.view"])` → true, `["admin.access", "user.update"]` → false.
  - `RolesApiTest`(`AuthFixture` + `pg_stat_statements`, 계정·시험 역할은 시험마다 새로):
    - A-405: `R3_비superuser_가_admin_을_주면_403_r3`(시험 역할에 `admin.access`·`role.assign`), `R2_자기에게_없는_권한이_든_operator_를_주면_403_r2`, `anonymous_를_주면_422_줄_수_없는_역할_기록_없음`, `이미_가진_역할을_다시_주면_204_행_하나_그대로`(`user_roles` 1, 기록은 둘), `갖지_않은_역할_빼기는_404`, `자기_역할_빼기는_403_r1`, `주고_나면_다음_요청부터_그_사람의_can_이_바뀐다`(`operator` 를 주면 그 사람의 `/api/me` `can['user.view']` true), `비활성_계정에_editor_를_주고_빼면_각각_204_와_행_is_active_는_false_Q18`, `비superuser_가_비활성_계정에_admin_을_주면_403_r3`, `본문이_비거나_role_id_가_null_이면_422_필수_기록_없음`(Review Focus 3), `role_id_가_UUID_가_아니면_400`.
    - A-404: `R5_admin_역할의_권한은_403_r5`, `R9_member_에_user_view_는_422_r9_행`, `이미_들어간_member_의_user_view_는_뺄_수_있다`(`f.sql` 로 넣고 빼기 204), `R4_자기에게_없는_user_update_를_주면_403_r4`(시험 역할 `admin.access`·`role.manage`), `모르는_키는_422_unknown_permission_행에_보낸_키_문자열이_없다`(보낸 키 `"ghost.key-9f2a"` 가 그 계정 행의 `row_to_json` 어디에도 없다), `없는_부여_빼기는_404`, `권한이_null_이면_422_필수_기록_없음`(Review Focus 3), `editor_에_log_view_를_주고_빼면_204_와_행_둘`.
    - A-403: `역할_목록은_운영자_200_편집자_403`, `member_에_넣어_둔_관리_권한은_effective_false`, `admin_은_permissions_가_비고_is_superuser_true`, `질의_수는_역할과_부여_수와_관계없이_같다`(`roles` 또는 `permissions` 가 든 문장만 센다. 시험 역할 하나·부여 하나 더한 전후의 증분이 같다, 기대 3 = 인증 1 + 목록 2).
    - `생성_재인증_부여_흐름_뒤_작업_로그에_비밀번호_세션_토큰이_없다`: admin 이 새 사용자 생성(비밀번호 `"created-pass-5e8b"`) → 창을 닫고 재인증 → `editor` 부여. admin 과 새 사용자에 관한 행(`actor_user_id` 또는 `target_id`)의 `row_to_json` 에 두 비밀번호·`SESSION` 쿠키 값·`xsrf()` 값이 없다.
  - `LastAdminTest` 교차(`@Timeout(90)`) `비활성화와_admin_역할_빼기가_엇갈려도_하나만_된다`: `race(c → B 의 users 행과 A 의 admin user_roles 행 FOR UPDATE, [A 가 B 비활성, B 가 A 의 admin 빼기])` → `[204, 409]`, `last_admin` 행 하나(`user.deactivate last_admin` 또는 `user.revoke_role admin last_admin`), 활성 보유자 1명.
  - `OpenApiFileTest`: 경로 다섯, 응답 코드 = 스펙 7절.
- [ ] **Step 2: Red** — `-Dtest='RolesApiTest,CoversTest,LastAdminTest,ReauthTargetsTest,OpenApiFileTest'` → 컴파일 실패, 그다음 404. 구현 뒤 `LastAdmin` 의 `FOR UPDATE` 를 잠시 빼 교차 시험도 실패하는 것을 본다.
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** + 전체 백엔드(미사용 권한 WARN 없음) → `npm run gen:api && npm run typecheck`.
- [ ] **Step 5: 커밋** `사용자·역할 7: A-403 역할 목록, A-404·A-405 부여와 빼기, R1~R5·R9 거절과 기록`

### Task 8: 프런트 — `useSave` 재인증, 알림 주소, 확인 대화상자

관리자 쿼리 키는 모두 `["admin", …]` 아래(1-B1 최종 검토 I-1).

**Files:**
- Create: `src/front/src/shared/ui/alert-dialog.tsx`(shadcn 소스), `shared/ui/confirm.tsx`, `shared/ui/confirm.test.tsx`
- Modify: `src/front/src/shared/api/useSave.tsx`, `useSave.test.tsx`, `shared/ui/notice.tsx`, `notice.test.tsx`

**Interfaces:**
- Consumes: `POST /api/admin/reauth`(Task 2 생성 타입), URN.
- Produces: `export const REAUTH_TYPE = "urn:ondolithj:problem:reauth-required"`.
- Produces: `useSave<V, R = unknown>(o: { send: (values: V) => Promise<{ response: Response; data?: R; error?: unknown }>; success: string; onSuccess?: (data: R | undefined) => void; noticePath?: (data: R | undefined) => string; focus?: (field: string) => void }): Save<V>` 와 `export type Save<V> = { save(values: V): void; pending: boolean; fieldErrors: Record<string, string>; failure: SaveFailure | null; done: boolean; reauthNeeded: boolean; reauthError: string | null; reauth(password: string): void; cancel(): void }`.
  - 403 이고 `error.type === REAUTH_TYPE` → `reauthNeeded` true, 보낸 값을 ref 에 남긴다. 그 밖의 403·404·409 → `failure.message` = 서버 `detail`(없으면 지금 문구). 422 → `fieldErrors` 이고 `focus` 가 있으면 첫 오류 칸으로(M-1). 422 는 `role="alert"` 를 띄우지 않는다(M-2, 칸 옆 `aria-describedby` 가 읽는다 — 파일 주석 한 줄). 성공 → `done` true, `notice.show(success, noticePath?.(data))`, `onSuccess(data)`. `save` 를 시작하면 `done` false.
  - `reauth(password)`: 같은 동기 잠금. `api.POST("/api/admin/reauth", { body: { password } })` — `useMutation` 을 거치지 않는다(변경의 `variables` 가 캐시에 남는다 — 구현 때 확인). 204 → 남긴 값으로 원 요청을 **한 번** 다시 보낸다(다시 재인증 403 이면 다시 `reauthNeeded`). 403 → `reauthError` 「비밀번호가 맞지 않습니다」, 429 → 「잠시 뒤 다시 시도하세요」(남긴 값 그대로), 401 → `reauthNeeded` false·`failure` 로그인 안내. `cancel()` → 남긴 값·재인증 상태를 버린다.
- Produces: 알림 `show(message: string, path?: string)` — `path` 가 없으면 지금 주소. 보이는 것은 지금 주소가 그 주소일 때뿐이고, 그 주소를 떠나면 지운다.
- Produces: `shared/ui/alert-dialog.tsx` — `cd src/front && npx shadcn add alert-dialog`. `package.json`·`package-lock.json` 이 바뀌지 않고 `radix-ui` 의 AlertDialog 를 쓰는지 확인(바뀌면 되돌리고 보고). Action 단추의 자동 닫힘과 폼 제출의 관계는 구현 때 확인 — 제어 `open` 을 쓰고 확인 단추는 자동 닫힘을 막는다.
- Produces: `ConfirmButton<V>({ label, title, description, destructive = false, save, values }: { label: string; title: string; description?: string; destructive?: boolean; save: Save<V>; values: V })`.
  - `destructive` → 누르면 대화상자(제목·설명·「취소」·「확인」), 「취소」는 보내지 않고 잠그지 않는다. 아니면 누르면 바로 `save.save(values)`.
  - `save.reauthNeeded` 이면 같은 대화상자가 열리고 「관리자 비밀번호 재확인」 칸(`type="password"`, `autoComplete="current-password"`, 제어하지 않는 칸, 비면 보내지 않고 「필수입니다」)이 더해진다. 확인 → `save.reauth(FormData 의 값)`. 취소 → `save.cancel()`.
  - 보내는 동안 확인 단추 「처리 중…」·`aria-disabled`. `done` 또는 재인증이 아닌 실패면 닫는다. 대화상자 밖에 `SaveAlert`.

- [ ] **Step 1: 실패하는 시험**(가짜 fetch 의 응답을 시험이 직접 끝낸다)
  - `useSave.test`: `재인증_403_이면_재인증_상태가_되고_reauth_204_면_원_요청을_같은_값으로_한_번_더`(원 요청 POST 수 2, 둘째 본문 = 첫째 본문), `재인증이_틀리면_칸_오류_요청은_재인증_하나`, `cancel_뒤에는_요청이_없다`, `비밀번호가_쿼리_캐시와_변경_캐시에_없다`(`JSON.stringify` 한 `getMutationCache().getAll()` 의 `state.variables` 와 `getQueryCache()` 에 비밀번호 문자열 없음), `422_뒤_첫_오류_칸에_초점_M_1`, `409_는_서버_detail_을_role_alert_로`, `다시_보낸_원_요청이_401_이면_로그인_안내`(Review Focus 4), `재인증_429_는_잠시_뒤_문구_남긴_값_그대로_다시_204_면_원_요청_한_번`(Review Focus 5), `onSuccess_가_응답_본문을_받는다`.
  - `notice.test`: `show(글, "/b")` 를 `/a` 에서 → `/a` 에서는 빈 칸, `/b` 로 가면 그 글, `/c` 로 가면 빈 칸.
  - `confirm.test`: `destructive_는_누르면_대화상자_요청_0`, `취소하면_요청_0_다시_누를_수_있다`, `확인하면_요청_1`, `확인을_빠르게_두_번_눌러도_요청_1`, `비destructive_는_바로_요청_1`, `재인증_403_이면_같은_대화상자에_비밀번호_칸_type_password_autocomplete_current_password`, `비밀번호_칸이_비면_보내지_않고_필수입니다`, `성공하면_대화상자가_닫히고_칸이_DOM_에_없다`.
- [ ] **Step 2: Red** — `cd src/front && npx vitest run src/shared` → 모듈 없음·상태 없음.
- [ ] **Step 3: 구현** — 위 Interfaces. 기존 `useSave` 시험·`SettingsPage` 시험은 그대로 통과해야 한다.
- [ ] **Step 4: Green** — `npx vitest run` 전체 + `npm run typecheck`.
- [ ] **Step 5: 커밋** `사용자·역할 8: useSave 재인증과 서버 문구, 알림 주소, 확인 대화상자`

### Task 9: 프런트 — 메뉴·조회 공용·쪽 나누기, 사용자 목록·새 사용자 (M-3·4·9·10)

관리자 쿼리 키는 모두 `["admin", …]` 아래(1-B1 최종 검토 I-1).

**Files:**
- Create: `src/front/src/shared/api/load.tsx`, `shared/lib/password.ts`, `features/admin/UsersPage.tsx`·`UsersPage.test.tsx`, `features/admin/UserNewPage.tsx`·`UserNewPage.test.tsx`, `features/admin/UserDetailPage.tsx`·`RolesPage.tsx`(이 Task 에서는 제목 h2 만 — Task 10 이 채운다)
- Modify: `features/admin/AdminLayout.tsx`·`AdminLayout.test.tsx`, `OplogPage.tsx`·`OplogPage.test.tsx`, `SystemPage.tsx`·`SystemPage.test.tsx`, `SettingsPage.tsx`·`SettingsPage.test.tsx`, `adminCache.test.tsx`, `shared/lib/useFocusOnMount.ts`, `shared/lib/siteName.ts`·`siteName.test.ts`, `features/install/installSchema.ts`, `e2e/admin.spec.ts`(메뉴 단언만, 실행은 Task 11)

**Interfaces:**
- Consumes: `GET`·`POST /api/admin/users`(Task 5 생성 타입), `useSave`·`noticePath`·`focus`(Task 8).
- Produces: `ADMIN_SCREENS` 항목에 `menu: boolean` — 더하는 것: `{ path: "users", name: "사용자", perm: "user.view", menu: true }`, `{ path: "roles", name: "역할", perm: "role.view", menu: true }`, `{ path: "users/new", name: "새 사용자", perm: "user.create", menu: false }`, `{ path: "users/:id", name: "사용자 상세", perm: "user.update", menu: false }`. 표 주석 한 줄: 「관리자 쿼리 키는 모두 ["admin", …] 아래」. `<Route index element={null} />`(M-4).
- Produces: `load.tsx` — `class LoadError extends Error { constructor(readonly status: number) }`, `LoadFailure({ error, failed }: { error: unknown; failed: string })`: 401 → `SaveAlert` 와 같은 모양의 「로그인이 끝났습니다」와 `/login?next=<지금 주소>` 링크, 403 → 「권한이 없습니다」, 그 밖 → `role="alert"` `failed`. 작업 로그·시스템 정보·사용자 목록·상세·역할의 `queryFn` 은 실패하면 `LoadError` 를 던진다(1-B1 의 403 → null 대신).
- Produces: `useFocusOnMount<T>(...deps: unknown[])` — 의존 값이 바뀌면 다시 초점(M-3). 작업 로그·사용자 목록은 `page` 를 넘긴다.
- Produces: 쪽 번호 `parsePage(v: string | null): number` — `^[1-9]\d*$` 이고 2147483647 이하면 그 수, 아니면 1(`OplogPage` 의 지금 판정을 옮긴다, `load.tsx` 에 둔다). 빈 쪽이고 `page > 1` 이면 「이전」 대신 `?page=1` 「첫 쪽으로」(M-9).
- Produces: `nameSchema(max: number)`(`siteName.ts`, 메시지 `${max}자 이하`), `siteNameSchema = nameSchema(100)`. `password.ts` 의 `passwordSchema`(설치의 두 규칙을 옮긴다, `installSchema` 가 쓴다).
- `SettingsPage`: `/api/me` 실패면 「사이트 설정을 불러오지 못했습니다」(M-10).
- `UsersPage`: 쿼리 키 `["admin", "users", page, q]`, `gcTime: 0`. 표(이메일·표시 이름·상태(「활성」/「비활성」)·역할·만든 날), `aria-label="사용자 목록"`. 이메일은 `can['user.update']` 일 때만 `/admin/users/<id>` 링크. `<form role="search">` 칸 「검색」(name `q`, GET 폼이라 잠그지 않는다, 제출하면 `?q=`·1쪽). 쪽 링크는 `q` 를 담는다. `can['user.create']` 면 「새 사용자」 링크.
- `UserNewPage`: 칸 이메일·표시 이름·비밀번호·비밀번호 확인. zod: `z.email` 254, `nameSchema(50)`, `passwordSchema`, 확인이 다르면 「비밀번호 확인이 다릅니다」. `useSave({ success: "사용자를 만들었습니다", noticePath: d => "/admin/users/" + d.id, onSuccess: d => navigate(…), focus: form.setFocus })`, 성공 뒤 `["admin", "users"]` 무효화.

- [ ] **Step 1: 실패하는 시험**
  - `AdminLayout.test`: `can['user.view']`·`can['role.view']` true → 메뉴에 「사용자」·「역할」, false → 없음. 메뉴 아닌 항목(「새 사용자」·「사용자 상세」)은 어떤 `can` 에도 메뉴에 없다. `/admin` 에서 경고 없이 빈 대시보드(M-4 — `console.warn` 스파이 0).
  - `OplogPage.test`·`UsersPage.test` 공통: `쪽을_넘기면_h2_로_초점_M_3`, `page_0_과_2147483648_은_1쪽`, `빈_쪽이면_첫_쪽으로_M_9`, `401_이면_로그인_안내와_next_링크_M_9`, 403 「권한이 없습니다」. `SystemPage.test`: 401 로그인 안내.
  - `UsersPage.test`: 표·상태 이름표, `has_next` true → 「다음」 `?page=2&q=a_b`(Review Focus 2 — `q` 를 담는다), 검색 제출 → `q` 가 실린 요청, `can['user.update']` false 면 이메일이 링크가 아니다, `can['user.create']` 면 「새 사용자」 링크.
  - `UserNewPage.test`: `비밀번호_확인이_다르면_보내지_않는다`, `201_이면_상세_주소로_가고_알림이_보이고_다른_화면에_가면_없다`(라우트 셋 — 새 사용자·상세 자리 `LocationProbe`·다른 화면), `422_errors_email_이면_그_칸에_초점`, `409_이면_서버_문구`, 표시 이름 51자·`" "` 은 보내지 않는다.
  - `SettingsPage.test`: `/api/me` 500 → 「사이트 설정을 불러오지 못했습니다」(M-10).
  - `siteName.test`: `nameSchema(50)` 이 51자를 「50자 이하」로.
  - `adminCache.test`: 그리는 화면에 `UsersPage` 를 더한다(가짜 응답 `/api/admin/users`), 키가 모두 `admin` 아래.
- [ ] **Step 2: Red** — `npx vitest run src/features src/shared` → 모듈 없음·메뉴 없음·초점 그대로.
- [ ] **Step 3: 구현** — 위 Interfaces. `e2e/admin.spec.ts` 의 관리자 메뉴 단언을 `["사이트 설정", "작업 로그", "시스템 정보", "사용자", "역할"]` 로.
- [ ] **Step 4: Green** — `npx vitest run` 전체 + `npm run typecheck`.
- [ ] **Step 5: 커밋** `사용자·역할 9: 메뉴와 조회 공용 실패, 쪽 나누기 초점·범위, 사용자 목록·새 사용자`

### Task 10: 프런트 — 사용자 상세, 역할 화면, 작업 로그 이름표

관리자 쿼리 키는 모두 `["admin", …]` 아래(1-B1 최종 검토 I-1).

**Files:**
- Modify: `src/front/src/features/admin/UserDetailPage.tsx`, `RolesPage.tsx`, `OplogPage.tsx`, `OplogPage.test.tsx`, `adminCache.test.tsx`
- Create: `src/front/src/features/admin/UserDetailPage.test.tsx`, `RolesPage.test.tsx`

**Interfaces:**
- Consumes: `GET /api/admin/users/{id}`·`/api/admin/roles` 와 POST 여섯(Task 4·7 생성 타입), `ConfirmButton`·`useSave`(Task 8), `LoadFailure`(Task 9).
- `UserDetailPage`: 쿼리 키 `["admin", "user", id]`, `gcTime: 0`. 칸(이메일·표시 이름·상태·만든 날). 활성이면 「비활성화」(destructive, 제목 「사용자를 비활성화할까요?」, 설명 「이 계정의 모든 세션이 끝납니다」), 비활성이면 「활성화」(비-destructive) — `can['user.update']`. 가진 역할마다 「빼기」(destructive, 「역할을 뺄까요?」), 가지지 않은 `assignable_roles` 고르기(`<select>` 이름 「줄 역할」) + 「부여」(비-destructive) — `can['role.assign']`. 성공하면 `["admin", "user", id]`·`["admin", "users"]` 무효화.
- `RolesPage`: 쿼리 키 `["admin", "roles"]`, `gcTime: 0`. 역할마다 이름·설명과 권한 목록. superuser 역할은 「모든 권한(편집할 수 없음)」. `effective: false` 인 부여 옆에 「효력 없음 — 빼세요」. `can['role.manage']` 면 superuser 역할 밖의 역할에 가지지 않은 권한 고르기(이름 「줄 권한」) + 「부여」, 부여마다 「빼기」(destructive, 「권한을 뺄까요?」). 성공하면 `["admin", "roles"]` 무효화.
- 작업 로그 이름표 아홉(스펙 8절): `user.create` 사용자 생성, `user.deactivate` 사용자 비활성화, `user.activate` 사용자 활성화, `user.grant_role` 역할 부여, `user.revoke_role` 역할 빼기, `role.grant_permission` 역할에 권한 부여, `role.revoke_permission` 역할에서 권한 빼기, `user.denied` 사용자 작업 거절, `role.denied` 역할 작업 거절.

- [ ] **Step 1: 실패하는 시험**
  - `UserDetailPage.test`: `비활성화는_확인_뒤_POST_하나_알림과_다시_읽기`, `취소하면_POST_0`, `활성_계정에는_비활성화만_비활성에는_활성화만`, `부여는_바로_보내고_가진_역할은_고르기에_없다`, `R1_403_이면_서버_문구`, `재인증_403_이면_비밀번호_칸_입력_뒤_원_요청_한_번`, `can_role_assign_이_false_면_부여와_빼기가_없다`.
  - `RolesPage.test`: `효력_없음_표시`, `admin_은_모든_권한_편집할_수_없음_고르기_없음`, `role_manage_가_없으면_바꾸는_단추가_없다`, `권한_빼기는_확인_대화상자`, `부여_성공_뒤_역할_목록을_다시_읽는다`.
  - `OplogPage.test`: 아홉 키가 이름표로 보인다.
  - `adminCache.test`: `UserDetailPage`(`/admin/users/:id` 라우트)·`RolesPage` 를 더하고 키가 모두 `admin` 아래.
- [ ] **Step 2: Red** — `npx vitest run src/features/admin` → 제목만 있는 화면.
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** — `npx vitest run` 전체 + `npm run typecheck` + `npm run build`.
- [ ] **Step 5: 커밋** `사용자·역할 10: 사용자 상세·역할 화면, 확인과 재인증 대화상자, 작업 로그 이름표`

### Task 11: E2E, 문서, 공개 점검, 전체 시험

**Files:**
- Create: `src/front/e2e/users.spec.ts`
- Modify: `src/front/e2e/stack.ts`, `docs/decisions.md`, `docs/appendix-a.md`

**Interfaces:**
- Produces: `Stack` 에 `sql(statement: string): string` — `docker exec <DB 컨테이너> psql -U app -d app -tAc <문장>` 결과. 컨테이너 이름은 지금처럼 콘솔에 찍지 않는다.

- [ ] **Step 1: E2E 작성**(`users.spec.ts`, 시험마다 새 jar)
  - `사용자_생성_부여_권한_확인_빼기`: 설치 → `loginAdmin` → 「사용자」 → 「새 사용자」(`op@ondolith.test`, 확인 칸 같게) → 알림 「사용자를 만들었습니다」·상세 → 「줄 역할」 `operator` 「부여」 → 로그아웃 → 그 사용자로 로그인 → 메뉴 다섯(사이트 설정·작업 로그·시스템 정보·사용자·역할) → 사용자 목록의 이메일이 링크가 아니고 「새 사용자」가 없다, 역할 화면에 「부여」·「빼기」가 없다 → 관리자로 다시 로그인 → 상세에서 `operator` 「빼기」 → 대화상자 「확인」 → 작업 로그에 「사용자 생성」·「역할 부여」·「역할 빼기」.
  - `창이_닫히면_비밀번호를_묻고_비활성화된다`: 관리자 로그인 → 사용자 생성 → `stack.sql("DELETE FROM spring_session_attributes WHERE attribute_name = 'ondolithj.reauth_at'")` → 상세 「비활성화」 → 「확인」 → 같은 대화상자에 「관리자 비밀번호 재확인」 → 입력·「확인」 → 상태 「비활성」.
  - `역할_화면에서_editor_에_권한을_준다`(D86 2.4): 역할 화면 → `editor` 의 「줄 권한」 `log.view` 「부여」 → 그 역할 목록에 `log.view`.
  - `확인_단추를_두_번_눌러도_POST_는_하나`: 역할 빼기의 「확인」을 `page.route` 로 1초 붙잡고 `dblclick` → POST 수 1(1-B1 「저장 단추를 두 번 눌러도」 방식).
  - `키보드만으로_대화상자를_열고_비밀번호를_넣고_확인한다`: 창을 닫은 뒤 `Tab`·`Enter` 만으로 「비활성화」 → 대화상자 → 비밀번호 칸 입력 → 「확인」 한 번 → POST 하나.
- [ ] **Step 2: E2E 실행** — `mise run e2e` → `login`·`install`·`admin`·`users` 모두 통과. 바로 통과하면 단언 하나를 일부러 틀려 실패를 보고 되돌린다.
- [ ] **Step 3: 결정 문서** — 「Phase 1-B2 스펙」 아래 「1-B2 구현 결과」 절: 구현 중 정한 것(위 「스펙과 다르게 정한 것」, 「구현 때 확인」의 결과 — `TransactionTemplate`·`JdbcClient` 연결, `handleTypeMismatch`, 문자열→UUID 의 짧은 꼴, Hikari 초기 SQL 바인딩, AlertDialog 자동 닫힘, 변경 캐시의 `variables`, 질의 수 실측값), D86 결과 표(스펙 13절 행마다 ✓·시험 이름·한계, 1.12 는 미해결 그대로, 1.18 은 1-E). 1-B1 최종 검토 Minor 표의 M-1·2·3·4·5·7·9·10 에 「1-B2 Task N 에서 고침」.
- [ ] **Step 4: 부록 A** — FR-704(사용자·역할 관리: 1-B2 구현, 삭제·재설정·표시 이름 수정은 미룸), FR-723(빼기·거절·「효력 없음」 1-B2), FR-725(1-B2 대상: 비활성화·역할 빼기·권한 빼기), FR-726(1-B2 구현) 메모.
- [ ] **Step 5: 전체 시험** — `nice -n 19 mise run test` 와 `mise run e2e`. 통과·실패 숫자를 결정 문서에 적는다.
- [ ] **Step 6: 공개 점검** — `bash scripts/check-public.sh $(git diff --name-only main...HEAD)` → 출력 없음, exit 0. `git config user.email` 이 noreply 인지 본다.
- [ ] **Step 7: 커밋** `사용자·역할 11: E2E, 문서와 D86 결과`

---

**측정은 최종 검토와 그 수정이 끝난 뒤** 주 세션이 따로 한다(1-A 때는 수정 전 jar 로 쟀다). 그 jar 로 스펙 10절 절차(측정 앱과 코어를 번갈아 S5 3쌍·S6 3쌍, `LABEL=bench-1b`·`core-1b`, Task 1 의 고정 이미지)를 따르고, 돌리지 않은 측정은 결과로 적지 않는다. 이 계획의 Task 에는 측정이 없다.
