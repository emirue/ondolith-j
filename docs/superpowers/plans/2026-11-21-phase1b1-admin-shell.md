# Phase 1-B1 관리자 셸 기반·사이트 설정·작업 로그·시스템 정보 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영자·관리자가 `/admin` 에서 권한에 맞는 메뉴만 보고, 사이트 이름을 바꾸면 알림이 한 번 뜨고 작업 로그에 남으며, 작업 로그와 시스템 정보를 본다.

**Architecture:** 권한 표시에 `@AdminPermission(Permission.X)`(admin.access 와 X 를 함께 요구)를 더하고 기동 점검이 관리자 경로의 표시를 강제한다. V3 가 append-only `operation_logs` 와 권한 셋을 심고, 운영 컨텍스트에만 있는 기록기가 본 작업이 끝난 뒤 문장 하나로 남긴다. 프런트는 화면 표 하나에서 중첩 라우트와 메뉴를 함께 만들고, 상태를 바꾸는 요청은 공용 훅 `useSave` 를 쓴다.

**Tech Stack:** Spring Boot 4.1.2(Security 7.1), Flyway, PostgreSQL 18, React 19.3.1 + React Router 8.5.1, TanStack Query 5, zod 4, Vitest, Playwright 1.65.

**Spec:** `docs/superpowers/specs/2026-11-21-phase1b1-admin-shell-design.md`

## Global Constraints

- 저장소는 PUBLIC. 실제 메일·홈 경로·비밀을 쓰지 않는다. 시험 값은 누가 봐도 가짜(`*@ondolith.test` 소문자, `pg-pass-…`).
- 새 의존성 없음(스펙 1절). 판·API 는 기억으로 쓰지 않는다. 아래 「구현 때 확인」은 context7(없으면 공식 문서)로 확인하고 다르면 결정 문서에 적는다.
- TDD: 실패를 눈으로 본 뒤 구현. 단언 완화·skip 금지. 이미 통과하는 시험은 구현을 잠시 되돌리거나 망가뜨려 Red 를 한 번 본다.
- 무거운 작업은 한 번에 하나, `nice -n 19`. Java 명령은 `mise x --` 로.
- 백엔드 한 클래스: `cd src/back && nice -n 19 mise x -- ./mvnw -q -B test -Dtest=<클래스>`. 프런트: `cd src/front && npx vitest run <경로>`. 전체: `nice -n 19 mise run test`. E2E: `mise run e2e`.
- 시험 HTTP 는 `127.0.0.1`. 마이그레이션은 앞으로만(V1·V2 는 고치지 않는다).
- 오류는 Problem Details, JSON 은 snake_case, 422 는 최상위 `errors{칸: 메시지}`. 422 는 `@Min`·`@Size` 애너테이션이 아니라 기존 `FieldErrors` 를 던져 만든다(`@RequestParam` 검증 실패는 422 가 아닐 수 있다).
- `operation_logs` 는 시험도 비울 수 없다. 행 단언은 **시험마다 새 계정**(`RevocationTest.email()` 방식)·증분으로 한다.
- 기록기를 부르는 처리기·메서드에 `@Transactional` 을 달지 않는다. 기록 INSERT 가 본 작업 트랜잭션에 들어가면 「기록 실패가 일을 되돌리지 않는다」가 깨진다(스펙 1절).
- 기록 실패 ERROR 로그에는 동작 이름과 SQLState 만. 예외 객체·메시지를 넘기지 않는다(스펙 4.1).
- **API 모양을 바꾸는 커밋(Task 3·4)은 같은 커밋에서** `OpenApiFileTest` 단언을 고치고 `src/front/openapi.json`·`src/front/src/shared/api/schema.d.ts` 를 다시 만든다(`OpenApiFileTest` 실행 → `cd src/front && npm run gen:api`). `InstallRequest` 의 애너테이션 변경도 여기에 든다. CI 가 커밋된 파일과의 차이로 실패한다.
- 성공 알림 문구·오류 문구는 프런트 상수다(스펙 6절). 사이트 설정 성공 문구 「사이트 설정을 저장했습니다」.

## Review Focus

1. **하위 주소에서 로그인이 끝남** — `/admin/oplog?page=2` 에서 `/api/admin` 이 401 이면 `next` 가 그 주소를 그대로 담는다(지금은 `/admin` 고정). Task 5 시험.
2. **영역 ID 가 붙은 IPv6** — `fe80::1%eth0` 같은 값도 행은 남고 `ip` 만 NULL 이다. Task 2 시험.
3. **큰 쪽 번호** — `page=2147483647` 은 200 빈 `items`, `page=2147483648` 은 400(500 이 아니다). Task 4 시험.
4. **권한 없이 주소를 직접 침** — `/admin/settings`(can false)·`/admin/oplog`·`/admin/system`(403)이 「권한이 없습니다」를 보인다. Task 6·7 시험.
5. **100자 이름 앞뒤의 공백** — 앞뒤 공백·NBSP 를 붙인 100자 이름은 서버·프런트 모두 통과한다(길이는 뺀 뒤 잰다). Task 3·6 시험.

---

### Task 1: 권한 표시 `@AdminPermission`, V3, 기동 점검 규칙

**Files:**
- Create: `src/back/src/main/resources/db/migration/V3__admin_shell.sql`
- Create: `src/back/src/main/java/dev/ondolithj/auth/AdminPermission.java`
- Modify: `src/back/src/main/java/dev/ondolithj/auth/Permission.java`, `auth/GuardCheck.java`
- Create(시험): `src/back/src/test/java/fixture/guard/AdminProbe.java`, `AdminProbeConfig.java`, `WrongAdminMark.java`, `WrongAdminConfig.java`
- Create(시험): `src/back/src/test/java/dev/ondolithj/auth/AdminPermissionTest.java`
- Modify(시험): `auth/AuthFixture.java`, `auth/GuardCheckTest.java`, `auth/MeTest.java`, `dev/ondolithj/MigrationTest.java`

**Interfaces:**
- Produces: `Permission` 에 `SETTINGS_VIEW("settings.view", false)`, `SETTINGS_UPDATE("settings.update", false)`, `LOG_VIEW("log.view", false)`. 같은 커밋에 V3(아니면 기동 점검의 DB·코드 키 대조가 기동을 멈춘다).
- Produces: `@AdminPermission(Permission value)` — `@Target({METHOD, TYPE})`, `RUNTIME`, `@PreAuthorize("hasAuthority('ADMIN_ACCESS') and hasAuthority('{value}')")`.
- Produces: V3 = 스펙 2절 그대로 — `operation_logs`(칸·CHECK·FK `ON DELETE SET NULL`), 인덱스 둘, 함수 `operation_logs_append_only()` 와 트리거 넷(`no_delete`·`no_update`(WHEN)·`no_update_cols`(`UPDATE OF` 여덟 칸)·`no_truncate`), 권한 셋 씨앗과 `operator` 부여(editor 없음). 원문 출처 주석(D30).
- Produces: `GuardCheck` 규칙 — 표시 수(`marks`)에 `@AdminPermission` 을 센다. `checkAdminPaths` 는 `/api/admin`·`/api/admin/**` 처리기에 `@PublicApi` 또는 값이 `ADMIN_ACCESS` 가 아닌 `@RequiresPermission`(메서드 먼저, 없으면 클래스)이 있으면 문제로 낸다(메시지에 `/api/admin` 과 클래스#메서드). `used()` 는 `@AdminPermission` 의 값도 센다.
- Produces(시험): `AuthFixture` 를 `public` 으로(클래스·`pg`·`ctx`·`http()`·`db()`·`user()`·`sql()`·`PASSWORD`·`loginBody()`). `start(boolean secureCookies, String... extra)` — 큰 한도 인자 뒤에 `extra` 를 붙인다. `public TestHttp loginAs(String email)` — 로그인 204 를 단언하고 그 클라이언트를 돌려준다. 컨테이너는 `.withUsername("fixture_owner").withPassword("pg-pass-2b7e")` — 기본값 `test` 는 시험 이메일(`…@ondolith.test`)에 들어 있어 「DB 비밀번호·계정 없음」 단언이 거짓 실패한다.
- Produces(시험): `fixture.guard.AdminProbe` — `@RestController`, `@AdminPermission(Permission.SETTINGS_VIEW) @GetMapping("/api/admin/probe") public Map<String, Object> probe()` → `{}`. `AdminProbeConfig` 는 이 빈과 `GuardCheck.Allowed("fixture.guard")` 를 낸다. 운영 컨텍스트에는 인자 `--spring.main.sources=fixture.guard.AdminProbeConfig` 로 넣는다(구현 때 확인: Boot 가 이 속성을 추가 소스로 묶는지. 안 되면 시험 자원의 `AutoConfiguration.imports` 에 `@ConditionalOnProperty("ondolithj.test.admin-probe")` 를 단 구성으로 바꾼다 — 조건 없이 넣으면 `OpenApiFileTest` 문서에 섞인다).
- Produces(시험): `fixture.guard.WrongAdminMark` — `@RequiresPermission(Permission.SETTINGS_VIEW) @GetMapping("/api/admin/wrong")`. `WrongAdminConfig` 가 빈을 낸다.

- [ ] **Step 1: 실패하는 시험**
  - `AdminPermissionTest`(운영, `f.start(false, "--spring.main.sources=fixture.guard.AdminProbeConfig")`): 시험 역할 `INSERT INTO roles (key, name) VALUES ('probe', '시험')` 에 `settings.view` 만 주고 그 역할의 사용자 → `GET /api/admin/probe` 403. `editor` → 403. `operator` → 200. `admin`(슈퍼유저) → 200. 익명 → 401.
  - `GuardCheckTest`: 관리자 경로 기동 멈춤 — `install(WrongAdminConfig.class, AllowOutsideConfig.class)` 가 던지고 스택에 `/api/admin` 과 `WrongAdminMark` 가 있다(허용 빈이 있으니 「패키지 밖」 때문이 아니다).
  - `GuardCheckTest` 단위: `@AdminPermission(SETTINGS_VIEW)` 만 단 처리기는 문제 없음. `@AdminPermission` + 클래스 `@RequiresPermission` → 「둘 이상」. `checkAdminPaths`: `/api/admin/x` 에 `@RequiresPermission(SETTINGS_VIEW)` → 문제 하나, `@RequiresPermission(ADMIN_ACCESS)`·`@AdminPermission(SETTINGS_VIEW)` → 없음, `@PublicApi` → 여전히 문제(기존). `used()` 가 `@AdminPermission(LOG_VIEW)` 처리기에서 `LOG_VIEW` 를 낸다.
  - `MigrationTest`: 기존 V2 시험은 `.target("2")` 로 띄운다(내용 그대로). 실패 원복 시험의 기대 이력 `"1", "2", "3"`. 새 시험(DML 용 `exec(sql)` 도우미 추가):
    - `SELECT key FROM permissions` = `admin.access`·`settings.view`·`settings.update`·`log.view`(순서 무관). `operator` 의 키 = 넷, `editor` = `admin.access` 만, `admin`·`member`·`anonymous` 는 새 셋이 없다.
    - 사용자 하나와 그를 가리키는 로그 행 하나를 넣은 뒤: `UPDATE operation_logs SET summary = 'x'`, `DELETE FROM operation_logs`, `TRUNCATE operation_logs`, `UPDATE operation_logs SET actor_user_id = NULL, summary = 'x'` 가 모두 `SQLException`.
    - `DELETE FROM users WHERE id = ?` 는 성공, 그 행의 `actor_user_id` 는 NULL, `actor_email` 은 그대로. 그 뒤 `UPDATE … SET summary = 'x'` 와 `UPDATE … SET actor_user_id = NULL` 도 `SQLException`.
    - `action = 'Auth.Login'`, `target_type = 'User'` 인 INSERT 는 `SQLException`.
  - `MeTest`: `operator` 의 `can` 네 키가 모두 `true`. `editor` 는 `admin.access` 만 `true`, 나머지 셋 `false`.
- [ ] **Step 2: Red** — `-Dtest='AdminPermissionTest,GuardCheckTest,MigrationTest,MeTest'` → 컴파일 실패(`AdminPermission`·enum 상수 없음). 애너테이션·enum·V3 만 넣고 다시 → 기동 점검 단위·기동 멈춤 시험이 실패(규칙 없음). 403 시험이 이때 통과하면 템플릿을 잠시 `hasAuthority('{value}')` 만으로 바꿔 `probe` 사용자 200 으로 실패하는 것을 한 번 보고 되돌린다.
- [ ] **Step 3: 구현** — 위 Interfaces. 메타 포함 조회는 기존처럼 `AnnotatedElementUtils`. `AdminController` 는 그대로 둔다.
- [ ] **Step 4: Green** — 같은 명령 → 통과. 이어서 전체 백엔드 `cd src/back && nice -n 19 mise x -- ./mvnw -q -B test` → 통과. 특히 `InstallApiTest` 「설정 파일 교체가 실패하면 관리자를 지우고 재시도 가능」이 V3 와 함께 통과해야 한다(스펙 2절 ①~③). 새 권한 둘이 아직 쓰이지 않아 WARN 이 찍히는 것은 정상이다(Task 3·4 에서 사라진다).
- [ ] **Step 5: 커밋** `관리자 1: @AdminPermission, V3 작업 로그 표와 권한 셋, 관리자 경로 표시 규칙`

### Task 2: 클라이언트 IP 함수, 작업 로그 기록기, 로그인 성공 기록

**Files:**
- Create: `src/back/src/main/java/dev/ondolithj/shared/ClientIp.java`, `shared/OperationLog.java`
- Modify: `auth/LoginController.java`(IP·기록), `auth/AdminRateLimitFilter.java`(IP)
- Test: `src/back/src/test/java/dev/ondolithj/shared/OperationLogTest.java`, `LauncherTest.java`

**Interfaces:**
- Consumes: `AuthFixture`(Task 1), `Actor`(1-A).
- Produces: `public final class ClientIp { public static String of(HttpServletRequest request) }` — 지금은 `getRemoteAddr()`. `ponytail:` 주석은 스펙 4.4 문장 그대로(Phase 4, 이 한 곳만, `X-Forwarded-For` 를 믿지 않음). 로그인 횟수 제한(`LoginController.limit` 호출부)·관리자 필터·기록기 호출이 모두 이것을 쓴다.
- Produces: `@Component public class OperationLog` — `public enum Action { AUTH_LOGIN("auth.login", "user"), SETTINGS_UPDATE("settings.update", "settings"); public String key(); public String targetType(); }` 와 `public void record(Actor actor, Action action, String targetId, String summary, String ip)`. `JdbcClient` 문장 하나: `actor.id()`·`actor.email()`·`action.key()`·`action.targetType()`·`targetId`·`summary`, `ip` 칸은 `CASE WHEN pg_input_is_valid(?, 'inet') THEN ?::inet END`(구현 때 확인: PostgreSQL 16+ 의 이 함수 모양과 NULL 입력. 바인드 값이 상수로 접혀 `CASE` 가지 안의 캐스트가 먼저 평가되면 잘못된 값에서 문장이 실패한다 — 영역 ID 시험이 Red 로 남으면 캐스트 대상이 상수가 되지 않는 모양(예: `MATERIALIZED` CTE)으로 바꾼다). `DataAccessException` 을 잡아 `log.error("작업 로그를 남기지 못했습니다: 동작={} SQLState={}", action.key(), sqlState)` — `sqlState` 는 가장 안쪽 `SQLException` 의 값(없으면 `-`). 예외를 로그 인자로 넘기지 않는다.
- 로그인: 세션 저장 뒤, 204 를 돌려주기 직전에 `record(actor, AUTH_LOGIN, actor.id().toString(), "", ClientIp.of(request))`. 실패 경로는 기록하지 않는다(INFO 그대로).

- [ ] **Step 1: 실패하는 시험**(`OperationLogTest`, `AuthFixture`, 계정은 시험마다 새로)
  - `로그인_성공은_그_계정의_auth_login_행_하나를_남긴다`: `actor_user_id = id` 인 행이 정확히 하나, `action='auth.login'`, `actor_email` = 그 이메일, `target_type='user'`, `target_id` = id 문자열, `summary=''`, `host(ip)='127.0.0.1'`.
  - `로그인_실패는_행을_남기지_않는다`: 틀린 비밀번호 401 뒤 그 이메일·그 id 의 행 0.
  - `inet_으로_읽을_수_없는_IP_도_행은_남고_ip_는_NULL`: `f.ctx.getBean(OperationLog.class).record(new Actor(id, email, "", Instant.now(), Set.of()), AUTH_LOGIN, 새 UUID 문자열, "", "fe80::1%eth0")` → 그 `target_id` 의 행 하나, `ip IS NULL`.
  - `기록이_실패해도_로그인은_204_이고_ERROR_줄에_이메일과_IP_가_없다`(`CapturedOutput`): 시험이 `BEFORE INSERT` 트리거를 건다 — 함수는 `RAISE EXCEPTION 'rejected: %', row_to_json(NEW)`(행 값이 메시지에 들어가 원문 「Failing row contains」와 같은 위험을 재현한다). `finally` 에서 `DROP TRIGGER`. 로그인 204, 이어 `/api/admin` 200. 출력에서 「작업 로그를 남기지 못했습니다」가 든 줄만 골라: 하나 이상, `auth.login` 포함, 이메일·`127.0.0.1`·`rejected` 를 포함하지 않는다(`127.0.0.1` 은 다른 줄에도 나오므로 그 줄만 본다).
  - `LauncherTest`: 설치 컨텍스트에 `OperationLog` 빈이 없다.
- [ ] **Step 2: Red** — `-Dtest='OperationLogTest,LauncherTest'` → 컴파일 실패, 그다음 행 0 으로 실패.
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** + 전체 백엔드(로그인·횟수 제한 시험이 IP 함수로 바뀐 뒤에도 그대로).
- [ ] **Step 5: 커밋** `관리자 2: 클라이언트 IP 함수, 작업 로그 기록기, 로그인 성공 기록`

### Task 3: 사이트 이름 규칙(설치와 공유)과 A-201 `POST /api/admin/settings`

**Files:**
- Create: `src/back/src/main/java/dev/ondolithj/shared/SiteName.java`, `admin/SettingsController.java`
- Modify: `install/InstallRequest.java`, `install/InstallService.java`, `src/back/src/test/java/dev/ondolithj/OpenApiFileTest.java`, `src/front/openapi.json`(생성), `src/front/src/shared/api/schema.d.ts`(생성)
- Test: `src/back/src/test/java/dev/ondolithj/shared/SiteNameTest.java`(단위), `install/InstallApiTest.java`, `admin/SettingsApiTest.java`

**Interfaces:**
- Consumes: `OperationLog.record`·`Action.SETTINGS_UPDATE`·`ClientIp.of`(Task 2), `@AdminPermission`·`SETTINGS_UPDATE`(Task 1).
- Produces: `public final class SiteName` — `public static String strip(String raw)`: 앞뒤 `[\p{Z}\t\n\x0B\f\r]+` 를 뺀다(`String.strip()` 은 NBSP 를 남긴다), `null` 이면 `""`. `public static Optional<String> error(String raw)`: 뺀 값이 `[\p{Z}\p{C}]*` 전체와 맞으면(빈 값·공백만·U+200B 만) `"필수입니다"`, 뺀 값의 `length()` 가 100 을 넘으면 `"100자 이하"`, 아니면 빈 값. 프런트(Task 6)가 같은 두 정규식을 쓴다.
- `InstallRequest.siteName`: `@NotBlank`·`@Size` 를 빼고 `@NotNull(message = "필수입니다")` 만 둔다. `check()` 가 `SiteName.error(siteName)` 을 `site_name` 으로 넣는다. `InstallService.createAdmin` 은 `SiteName.strip(req.siteName())` 을 저장한다.
- Produces: `POST /api/admin/settings` — `@AdminPermission(Permission.SETTINGS_UPDATE)`, 본문 `record SettingsRequest(String name)`(다른 칸은 읽지 않는다), `@AuthenticationPrincipal Actor`. 오류면 `FieldErrors(Map.of("name", 메시지))`. 저장은 문장 하나:
  ```sql
  INSERT INTO settings (key, value) VALUES ('site.name', ?)
  ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
  WHERE settings.value IS DISTINCT FROM EXCLUDED.value
  RETURNING key
  ```
  행이 오면 `record(actor, SETTINGS_UPDATE, null, "site.name", ClientIp.of(request))`. 204. `@ApiResponse` 204·400·401·403·422·429(204 는 본문 없음, 나머지 Problem Details). `ponytail:` 주석은 스펙 5절(조회 API 없음, 비공개 칸이 생기면 `GET /api/admin/settings`).

- [ ] **Step 1: 실패하는 시험**
  - `SiteNameTest`: `strip("  이름\u00A0 ")` = `"이름"`, `strip("a b")` = `"a b"`. `error` 가 `null`·`""`·`"   "`·`"\u00A0\u00A0"`·`"\u200B"` 에 `필수입니다`, `" \u00A0" + "가".repeat(100) + " "` 에 빈 값, `"가".repeat(101)` 에 `100자 이하`, `"가"` 에 빈 값.
  - `InstallApiTest`: `site_name` 이 `"\u00A0\u00A0"`·`"\u200B"` 면 각각 422 `site_name`, `users` 0. `" 새 사이트\u00A0"` 로 설치하면 202 이고 `settings` 의 `site.name` = `"새 사이트"`.
  - `SettingsApiTest`(`AuthFixture`, 시작 때 `site.type='cms'` 행을 넣는다, 계정은 시험마다 새로):
    - `operator`·`admin` 204, `editor` 403, 익명 401, `postRaw`(CSRF 없음) 403.
    - `"가"`·`"가".repeat(100)` 204; `"가".repeat(101)`·`""`·`"   "`·`"\u00A0"`·`"\u200B"` 422 이고 `errors.name` 이 있다; 본문 `"not json"` 400.
    - `"  새 이름 "` 저장 뒤 `GET /api/site` 의 `name` = `"새 이름"`.
    - 시험마다 새 값(`"이름-" + 순번`)으로: 첫 저장 → 그 계정의 `settings.update` 행이 하나 늘고 `summary='site.name'`, `target_type='settings'`, `target_id IS NULL`. 같은 값을 다시 저장 → 204 이고 행 수 그대로.
    - 본문 `{"name": "x", "type": "shop"}` → 204, `/api/site` 의 `type` 은 `cms`.
    - 비밀 검사(스펙 4.3): 로그인·저장 뒤 `SELECT row_to_json(o)::text FROM operation_logs o WHERE actor_user_id = ?` 의 모든 행에 `AuthFixture.PASSWORD`, `SESSION` 쿠키 값, `xsrf()` 값, `f.pg.getPassword()` 가 없다.
    - 기록 실패: Task 2 와 같은 거부 트리거(`finally` 에서 지움) 아래 저장 204, `/api/site` 에 새 값, 「작업 로그를 남기지 못했습니다」 줄에 `settings.update` 가 있고 이메일·`127.0.0.1` 이 없다.
  - `OpenApiFileTest`: 경로 목록에 `/api/admin/settings` 를 정렬 위치에 넣고, 그 POST 응답 코드 = `204, 400, 401, 403, 422, 429`, 401·403·422 는 `ProblemDetail` 참조.
- [ ] **Step 2: Red** — `-Dtest='SiteNameTest,InstallApiTest,SettingsApiTest,OpenApiFileTest'` → 컴파일 실패, 그다음 NBSP 설치 202·`/api/admin/settings` 404 로 실패.
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** + 전체 백엔드 → `cd src/front && npm run gen:api && npm run typecheck`.
- [ ] **Step 5: 커밋** `관리자 3: 사이트 이름 규칙(설치와 공유), A-201 사이트 설정 저장과 기록`

### Task 4: A-601 작업 로그 목록, A-602 시스템 정보

**Files:**
- Create: `src/back/src/main/java/dev/ondolithj/admin/OplogController.java`, `admin/SystemController.java`
- Modify: `OpenApiFileTest.java`, `src/front/openapi.json`(생성), `schema.d.ts`(생성)
- Test: `src/back/src/test/java/dev/ondolithj/admin/OplogApiTest.java`, `admin/SystemApiTest.java`

**Interfaces:**
- Produces: `GET /api/admin/oplog` — `@AdminPermission(LOG_VIEW)`, `@RequestParam(defaultValue = "1") int page`. `page < 1` 이면 `FieldErrors(Map.of("page", "1 이상"))`. 숫자가 아니거나 int 를 넘으면 Spring 기본 400(구현 때 확인: `ApiErrors` 가 상속한 처리기가 Problem Details 로 낸다). 응답 `record OplogPage(List<OplogItem> items, int page, int pageSize, long total)`, `record OplogItem(UUID id, Instant createdAt, UUID actorUserId, String actorEmail, String action, String targetType, String targetId, String summary, String ip)`. 질의 둘: `SELECT id, created_at, actor_user_id, actor_email, action, target_type, target_id, summary, host(ip) AS ip FROM operation_logs ORDER BY created_at DESC, id DESC LIMIT 100 OFFSET ?`(offset 은 `(page - 1L) * 100`), `SELECT count(*) FROM operation_logs`. `@ApiResponse` 200·400·401·403·422·429.
- Produces: `GET /api/admin/system` — `@AdminPermission(SETTINGS_VIEW)`. `record SystemInfo(String version, List<MigrationRow> migrations, int pendingCount, Database database)`, `record MigrationRow(String version, String description, Instant installedOn, boolean success)`, `record Database(String serverVersion)`. 출처: Boot 의 `Flyway` 빈 `info().applied()`·`info().pending()`(구현 때 확인: `getVersion().getVersion()`·`getInstalledOn()` 타입·성공 판정 `!getState().isFailed()`), 버전은 `HealthController` 와 같이 `ObjectProvider<BuildProperties>`(없으면 `"dev"`), `SHOW server_version`. 칸은 이것뿐(`installed_by` 등을 내지 않는다). `@ApiResponse` 200·401·403·429.

- [ ] **Step 1: 실패하는 시험**
  - `OplogApiTest`(`AuthFixture`. 시작 전에 `f.pg.withCommand("postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_stat_statements")`, 시작 뒤 `CREATE EXTENSION pg_stat_statements`. 구현 때 확인: `postgres:18-alpine` 에 확장이 있는지 — 없으면 `-c log_statement=all` 과 `f.pg.getLogs()` 의 `operation_logs` 줄 수로 센다):
    - `operator` 200, `editor` 403.
    - 쪽 나누기(한 시험 메서드 안에서): `operator` 로그인 뒤 `count(*)` 를 세고 `101 - 그 수` 만큼 `INSERT INTO operation_logs (action, target_type, summary, created_at) SELECT 'test.fill', 'test', 'n' || g, now() + g * interval '1 millisecond' FROM generate_series(1, ?) g`. `page=1` → `items` 100개, `created_at` 이 내림차순, `total` 101, `page_size` 100. `page=2` → 1개이고 표의 `min(created_at)` 행. `page=3` → 빈 `items`. `auth.login` 항목의 `ip` = `"127.0.0.1"`(넷마스크 없음). `created_at` 은 `OffsetDateTime.parse` 로 읽히는 문자열.
    - `page=0` 422 `errors.page`. `page=abc` 400. `page=2147483647` 200 빈 `items`. `page=2147483648` 400.
    - 질의 수: `SELECT coalesce(sum(calls), 0) FROM pg_stat_statements WHERE query ILIKE '%operation_logs%' AND query NOT ILIKE '%pg_stat_statements%'` 의 증분이 행 몇 개일 때의 `GET page=1` 과 101 행일 때의 `GET page=1` 에서 같고 2 다.
  - `SystemApiTest`(`AuthFixture`): `operator` 200, `editor` 403. `migrations` 의 `version` = `1, 2, 3` 이고 모두 `success` true, `installed_on` 이 시각 문자열. `pending_count` 0. `database.server_version` 이 비지 않는다. `version` = `GET /api/health` 의 `version`(값을 고정하지 않는다 — pom 은 `0.0.1-SNAPSHOT`). 본문에 `f.pg.getJdbcUrl()`, `"jdbc:"`, `f.pg.getHost() + ":" + f.pg.getMappedPort(5432)`, `f.pg.getUsername()`, `f.pg.getPassword()`, `"installed_by"` 가 없다.
  - `OpenApiFileTest`: 경로 `/api/admin/oplog`·`/api/admin/system` 추가, GET 응답 코드 = 위 Interfaces.
- [ ] **Step 2: Red** — `-Dtest='OplogApiTest,SystemApiTest,OpenApiFileTest'` → 404·경로 없음.
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** + 전체 백엔드(미사용 권한 WARN 이 더 없다) → `npm run gen:api && npm run typecheck`.
- [ ] **Step 5: 커밋** `관리자 4: A-601 작업 로그 목록, A-602 시스템 정보`

### Task 5: 프런트 관리자 셸 — 화면 표에서 라우트와 메뉴

**Files:**
- Create: `src/front/src/features/admin/AdminLayout.tsx`, `AdminLayout.test.tsx`
- Create: `src/front/src/features/admin/SettingsPage.tsx`, `OplogPage.tsx`, `SystemPage.tsx` — 이 Task 에서는 화면 이름 제목(`h2`)만. Task 6·7 이 채운다
- Modify: `src/front/src/features/auth/AdminGate.tsx`, `AdminGate.test.tsx`, `src/front/src/app/App.tsx`

**Interfaces:**
- Consumes: `/api/me` 의 `can`(Task 1), `useMe`·`useLogout`(1-A).
- Produces: `AdminLayout.tsx` 에 `export const ADMIN_SCREENS` — `{ path: "settings" | "oplog" | "system"; name: string; perm: "settings.update" | "log.view" | "settings.view"; element: ReactElement }` 의 배열, 값은 스펙 6절 표(사이트 설정·작업 로그·시스템 정보). `export const adminRoutes` — `<Route index>`(빈 대시보드) + 표의 각 항목 `<Route path={path} element={element}>`. `export function AdminLayout()` — 제목 `h1` 「관리자」(마운트 때 포커스, 1-A 그대로), `<nav aria-label="관리자 메뉴">` 에 `can[perm] === true` 인 항목만 `NavLink to={"/admin/" + path}`, `main` 안의 「로그아웃」 단추, `<Outlet />`. `can` 이 없으면(응답에 키 없음) 메뉴 없음.
- `App.tsx`: `<Route path="/admin" element={<AdminGate />}>{adminRoutes}</Route>`. `AdminGate` 는 200 이면 `<AdminLayout />`, 401 이면 `/login?next=` + 지금 `pathname + search` 를 인코딩한 값. React Router 8.5.1 의 중첩 라우트·`Outlet`·`NavLink` 는 구현 때 확인.
- 라우트는 권한과 관계없이 모두 있다. 숨김은 메뉴만(주소 직접 진입은 각 화면이 서버 403 으로 「권한이 없습니다」 — Task 6·7).

- [ ] **Step 1: 실패하는 시험**
  - `AdminLayout.test`: `can = {"admin.access": true, "settings.update": true, "log.view": false, "settings.view": true}` → 「관리자 메뉴」 안에 링크 「사이트 설정」·「시스템 정보」가 있고 `queryByRole("link", { name: "작업 로그" })` 가 `null`. `editor` 모양(`admin.access` 만 true) → 메뉴 안 링크 0. `/admin/oplog` 로 시작하면 「작업 로그」 제목이 보인다(표에서 만든 라우트).
  - `AdminGate.test`: `/admin/oplog?page=2` 에서 401 → 위치 `/login?next=%2Fadmin%2Foplog%3Fpage%3D2`. 기존 세 시험(401 → `/login?next=%2Fadmin`, 403, 셸·로그아웃)은 고치지 않고 통과해야 한다 — 기존 시험의 `/api/me` 응답 `{}` 에는 `can` 이 없다.
- [ ] **Step 2: Red** — `cd src/front && npx vitest run src/features/admin src/features/auth src/app` → 모듈 없음·`next` 다름.
- [ ] **Step 3: 구현** — 위 Interfaces. `AdminGate` 안의 `AdminShell` 은 지운다(`AdminLayout` 이 대신한다).
- [ ] **Step 4: Green** — 같은 명령 + `npm run typecheck`. E2E `login.spec` 이 기대는 것(`main` 안의 로그아웃 단추, 로그인 뒤 「관리자」 제목 포커스)이 그대로인지 화면 코드로 확인한다(실행은 Task 8).
- [ ] **Step 5: 커밋** `관리자 5: 프런트 관리자 셸, 화면 표에서 라우트와 메뉴`

### Task 6: 공용 저장 훅·알림, 사이트 설정 화면, 프런트 사이트 이름 규칙

**Files:**
- Create: `src/front/src/shared/api/useSave.tsx`, `useSave.test.tsx` — 확장자는 `.tsx`(오류 알림 부품 `SaveAlert` 를 함께 둔다. 스펙의 `useSave.ts` 와 확장자만 다르다)
- Create: `src/front/src/shared/ui/notice.tsx`, `notice.test.tsx`
- Create: `src/front/src/shared/lib/siteName.ts`, `siteName.test.ts`
- Modify: `src/front/src/features/admin/SettingsPage.tsx`, Create `SettingsPage.test.tsx`
- Modify: `src/front/src/features/install/installSchema.ts`, `InstallPage.test.tsx`, `src/front/src/app/App.tsx`(알림 제공자), `src/front/src/test-api.tsx`(`renderWithQuery(ui, route, { notice: true })` 일 때만 `MemoryRouter` 안에서 `NoticeProvider` 로 감싼다 — 늘 감싸면 `WaitingScreen.test` 의 `getByRole("status")` 가 둘을 만난다)

**Interfaces:**
- Consumes: `POST /api/admin/settings`(Task 3 생성 타입), `useSite`·`['site']`(Phase 0), `useMe`.
- Produces: `siteName.ts` — `stripSiteName(v: string): string`(앞뒤 `/^[\p{Z}\t\n\v\f\r]+|[\p{Z}\t\n\v\f\r]+$/gu` 제거 — `String.prototype.trim` 은 다른 집합이라 쓰지 않는다), `siteNameSchema`: 뺀 값이 `/^[\p{Z}\p{C}]*$/u` 이면 「필수입니다」, 100자 넘으면 「100자 이하」, 출력은 뺀 값(zod 4 의 transform·pipe 모양은 구현 때 확인). `installSchema.site_name` 과 사이트 설정 폼이 이것을 쓴다.
- Produces: `notice.tsx` — `NoticeProvider({ children })`: 자식보다 먼저 `<p role="status" data-testid="notice">` 를 늘 그린다(빈 칸 포함). 시험은 `getByTestId("notice")` 로 찾는다 — `WaitingScreen` 의 `role="status"` 와 `LocationProbe` 의 `<output>`(암묵적 status)이 있어 역할로는 하나로 정해지지 않는다. `location.pathname` 이 바뀌면 지운다. `useNotice(): { show(message: string): void; clear(): void }`. 세션·저장소를 쓰지 않는다.
- Produces: `useSave.tsx` — `useSave<V>(o: { send: (values: V) => Promise<{ response: Response; error?: unknown }>; success: string; onSuccess?: () => void }): { save(values: V): void; pending: boolean; fieldErrors: Record<string, string>; failure: SaveFailure | null }`, `type SaveFailure = { message: string; login?: string }`, `SaveAlert({ failure }: { failure: SaveFailure | null })` — `role="alert"`, `login` 이 있으면 「다시 로그인」 링크. `success` 는 필수 속성이다. TanStack Query `useMutation` + `useRef` 동기 잠금: 잠겨 있으면 `save` 는 아무것도 하지 않는다, 시작할 때 `notice.clear()`, 끝나면(성공·실패·예외) 푼다. 204 → `notice.show(success)`·`onSuccess`. 422 → `fieldErrors` = 응답 `errors`(알림 없음). 401 → 「로그인이 끝났습니다」, `login` = `/login?next=` + 지금 `pathname + search` 인코딩(자동 이동 없음). 403 「권한이 없습니다」, 429 「잠시 뒤 다시 시도하세요」, 그 밖·연결 실패 「저장하지 못했습니다」. `ponytail:` 65초 시한 없음(스펙 6절 문장).
- 저장 단추: 보내는 동안 글이 「처리 중…」, `disabled` 대신 `aria-disabled="true"`.
- `useNotice` 를 쓰는 화면의 시험은 모두 `{ notice: true }` 로 그린다. Task 5 의 `AdminLayout.test` 「`/admin/oplog` 제목」 시험은 화면이 데이터를 부르게 되면 가짜 응답만 고친다(단언은 그대로) — Task 6·7 에서.
- `SettingsPage`: `can['settings.update']` 가 `true` 가 아니면 폼 대신 「권한이 없습니다」. 칸 「사이트 이름」(처음 값 `/api/site` 의 `name`), 단추 「저장」, 성공 문구 「사이트 설정을 저장했습니다」, 성공 뒤 `['site']` 무효화. 클라이언트 검증 실패는 `save` 를 부르지 않는다(잠그지 않는다).

- [ ] **Step 1: 실패하는 시험**
  - `siteName.test`: `stripSiteName("  이름\u00A0 ")` = `"이름"`. `siteNameSchema` 가 `"\u00A0\u00A0"`·`"\u200B"`·`""` 를 「필수입니다」로, `" " + "가".repeat(100) + "\u00A0"` 를 통과(출력 100자), `"가".repeat(101)` 을 「100자 이하」로.
  - `InstallPage.test`: 사이트 이름에 `"\u00A0\u00A0"` 를 넣고 제출 → 그 칸에 「필수입니다」, `/api/install` 요청 없음.
  - `notice.test`: 처음부터 빈 `role="status"` 가 있다. `show("저장했습니다")` → 그 글. 다른 경로로 갔다 돌아오면 빈 칸.
  - `useSave.test`(시험용 작은 폼, 가짜 fetch 의 응답을 시험이 직접 끝낸다): 빠르게 두 번 누름 → 요청 하나, 단추 글 「처리 중…」·`aria-disabled="true"`. 204 로 끝나면 풀려 다시 누를 수 있다. 500 으로 끝나도 풀린다. 필수 칸이 빈 제출 뒤 값을 넣은 제출은 요청이 간다. `// @ts-expect-error success 가 필수다` 아래 `useSave({ send })` 호출 — 이 항목은 `npm run typecheck` 가 검증한다(`success` 가 선택이 되면 쓰이지 않은 지시문 오류로 실패).
  - `SettingsPage.test`(`can['settings.update']: true`): 204 → 알림 칸(`notice`)에 「사이트 설정을 저장했습니다」. 422 `errors.name` → 칸 옆 메시지, 알림 칸 빈 칸. 401 → 「로그인이 끝났습니다」 알림과 `href="/login?next=%2Fadmin%2Fsettings"` 링크, 입력값 그대로. 403 「권한이 없습니다」, 429 「잠시 뒤 다시 시도하세요」 알림. `can['settings.update']: false` → 「권한이 없습니다」, 「사이트 이름」 칸 없음.
- [ ] **Step 2: Red** — `cd src/front && npx vitest run src/shared src/features/admin src/features/install` → 모듈 없음·NBSP 통과.
- [ ] **Step 3: 구현** — 위 Interfaces. `App.tsx` 의 `Running` 은 머리글과 라우트 전체를 `NoticeProvider` 로 감싼다(알림 칸이 화면 위쪽).
- [ ] **Step 4: Green** — `npx vitest run` 전체 + `npm run typecheck`.
- [ ] **Step 5: 커밋** `관리자 6: 공용 저장 훅과 알림, 사이트 설정 화면, 프런트 사이트 이름 규칙`

### Task 7: 작업 로그·시스템 정보 화면

**Files:**
- Modify: `src/front/src/features/admin/OplogPage.tsx`, `SystemPage.tsx`
- Create: `src/front/src/features/admin/OplogPage.test.tsx`, `SystemPage.test.tsx`

**Interfaces:**
- Consumes: `GET /api/admin/oplog`·`GET /api/admin/system`(Task 4 생성 타입).
- Produces: `OplogPage` — `useSearchParams` 의 `page`(없으면 1), 쿼리 키 `['oplog', page]`. 표 머리 시각·주체·동작·대상·요약·IP. 주체: `actor_email === ''` 면 「시스템」, `actor_user_id` 가 null 이고 이메일이 있으면 「(삭제된 계정) 이메일」, 그 밖은 이메일. 동작 이름표 `{"auth.login": "로그인", "settings.update": "사이트 설정 변경"}`, 모르는 키는 그대로. 시각은 `new Date(created_at).toLocaleString()`(브라우저 시간대). 「이전」(page > 1)·「다음」(`page * page_size < total`)은 `?page=` 링크. 403 → 「권한이 없습니다」.
- Produces: `SystemPage` — 버전, DB 서버 판, 마이그레이션 표(버전·설명·적용 시각·성공), 대기 수. 403 → 「권한이 없습니다」.

- [ ] **Step 1: 실패하는 시험**
  - `OplogPage.test`: `actor_email: ""` 행 → 「시스템」. `actor_user_id: null, actor_email: "gone@ondolith.test"` → 「(삭제된 계정) gone@ondolith.test」. `auth.login` → 「로그인」, `settings.update` → 「사이트 설정 변경」, `x.unknown` → 그대로. `total: 101, page: 1` → 「다음」 링크가 `?page=2`, 「이전」 없음. 「다음」을 누르면 `page=2` 요청이 가고 「이전」이 생긴다. 403 → 「권한이 없습니다」.
  - `SystemPage.test`: 버전·`server_version`·마이그레이션 세 행·대기 수 0 이 보인다. 403 → 「권한이 없습니다」.
- [ ] **Step 2: Red** — `npx vitest run src/features/admin` → 화면에 표 없음.
- [ ] **Step 3: 구현**
- [ ] **Step 4: Green** — `npx vitest run` 전체 + `npm run typecheck` + `npm run build`.
- [ ] **Step 5: 커밋** `관리자 7: 작업 로그·시스템 정보 화면`

### Task 8: E2E, jar 출력 파일, CI 실패 산출물

**Files:**
- Create: `src/front/e2e/admin.spec.ts`
- Modify: `src/front/e2e/stack.ts`, `.github/workflows/ci.yml`

**Interfaces:**
- Produces: `stack.ts` 의 `loginAdmin(page: Page)` — `/login?next=/admin` 에서 `ADMIN_EMAIL`·`ADMIN_PASSWORD` 로 로그인하고 「관리자」 제목까지 기다린다.
- jar 출력: `stdio: "ignore"` 를 `info.outputPath("jar.log")` 파일로 바꾼다 — 폴더가 없으면 `mkdirSync(dirname, { recursive: true })`, `openSync(…, "w")` 의 fd 를 `stdio: ["ignore", fd, fd]` 로, `finally` 에서 `closeSync`. 경로를 콘솔에 찍지 않는다. jar 인자에 `--spring.main.log-startup-info=false` 를 더한다 — Boot 기동 첫 줄은 jar 절대 경로와 OS 사용자 이름을 찍어, 로컬에서는 홈 경로가 파일에 남는다(속성 이름은 구현 때 확인). `test-results/` 는 루트 `.gitignore` 에 있어 `src/front/test-results/` 가 커밋되지 않는다(확인함). 통과한 시험의 파일이 남는지(`preserveOutput` 기본값)는 구현 때 확인 — 실패한 시험의 파일만 남아도 목적에 맞다.
- CI: `e2e` 작업의 Playwright 단계 뒤에 넣는다. `actions/upload-artifact` 는 아직 쓰지 않아 재사용할 SHA 가 없다 — 2026-11-21 `gh api repos/actions/upload-artifact/git/ref/tags/v7.0.2` 로 확인한 커밋을 쓴다(2026-10-07 배포, 2주 넘음). 권한은 `contents: read` 그대로.
  ```yaml
      - name: 실패하면 jar 출력과 trace 를 올린다
        if: failure()
        uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7.0.2
        with:
          name: e2e-test-results
          path: src/front/test-results/
          retention-days: 7
          if-no-files-found: ignore
  ```
  공개 저장소의 산출물은 GitHub 로그인한 누구나 받는다. 기동 정보 줄을 끄면 남는 것은 설치 완료 로그의 임시 폴더 경로와 가짜 시험 값이다(두 컨텍스트 모두 `UserDetailsServiceAutoConfiguration` 을 빼 생성 비밀번호가 없다).

- [ ] **Step 1: 시험 작성**(`admin.spec.ts`, 시험마다 새 jar 라 횟수 제한은 시험마다 새로 센다)
  - `관리자 셸 흐름`: 설치 → `loginAdmin` → `navigation "관리자 메뉴"` 안에 링크 셋 → 「사이트 설정」 → 이름 「바뀐 이름」 저장 → `role=status` 에 「사이트 설정을 저장했습니다」 → `/` 에 제목 「바뀐 이름」 → `/admin/oplog` 에 「로그인」·「사이트 설정 변경」 → `/admin/system` 에 마이그레이션 `3` 행.
  - `저장 단추를 두 번 눌러도 POST 는 하나`: `page.route("**/api/admin/settings", …)` 로 첫 POST 의 응답을 잠시 붙잡아(예: 1초 뒤 `route.continue()`) 둘째 클릭이 보내는 중에 떨어지게 한다 — 로컬 jar 가 둘째 클릭보다 먼저 204 를 내면 잠금이 풀려 둘이 된다. `page.on("request")` 로 `POST …/api/admin/settings` 를 센다, 저장 단추 `dblclick()`, 알림을 기다린 뒤 1.
  - `키보드만으로 메뉴 이동과 저장`: 로그인 뒤 `Tab` 으로 「사이트 설정」 링크까지 가서 `Enter`, `Tab` 으로 칸에 가서 이름 입력, `Enter` → 알림.
- [ ] **Step 2: 실행** — `mise run e2e` → `login.spec`·`install.spec` 포함 통과. 앞 Task 가 끝나 바로 통과할 수 있으므로 단언 하나를 일부러 틀려 실패를 보고, 그때 `src/front/test-results/` 아래 `jar.log` 가 생기고 Spring 로그(예: 「Tomcat started」)가 들어 있으며 홈 경로가 없는지(`bash scripts/check-public.sh <그 파일>`) 확인한 뒤 되돌린다.
- [ ] **Step 3: 커밋** `관리자 8: 관리자 흐름 E2E, jar 출력 파일, CI 실패 산출물`

### Task 9: 문서, 공개 점검, 전체 시험

**Files:**
- Modify: `docs/decisions.md`, `docs/appendix-a.md`

- [ ] **Step 1: 결정 문서** — 「Phase 1-B 결정」 아래에 「1-B1 구현 결과」 절. 구현 중 정한 것(「구현 때 확인」의 결과, `useSave.tsx` 확장자, 시험 컨트롤러 주입 방식, 질의 수 세는 방법)과 D86 결과 표. 스펙 9절은 구현 전에 쓴 계획 행이라 결과로는 부족하다 — 1-A 와 같이 행마다 ✓·시험 이름·한계를 적는다(1.12 는 미해결 그대로, 1-B2 행은 「1-B2」).
- [ ] **Step 2: 부록 A** — FR-701·703·724 비고에 1-B1 결과, FR-707 의 Phase 를 1-B1 로(스펙: 원문 Phase 2 를 앞당김). 부록 A 에 FR-726·727 행이 없다 — ondolith 원문 D10 에서 이름·필수 여부를 읽어(원문은 읽기만) 행을 더하고 5행의 행 수를 고친다.
- [ ] **Step 3: 전체 시험** — `nice -n 19 mise run test` 와 `mise run e2e`. 통과·실패 숫자를 결정 문서에 적는다. 메모리 측정은 하지 않는다(스펙 8절: 1-B2 뒤).
- [ ] **Step 4: 공개 점검** — `bash scripts/check-public.sh $(git diff --name-only main...HEAD)`(인자 없이 돌리면 아무것도 보지 않는다) → 출력 없음, exit 0.
- [ ] **Step 5: 커밋** `관리자 9: 문서와 D86 결과`
