# Phase 1-A 인증·세션·권한 기반 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 설치 때 만든 관리자가 React 로그인 화면으로 로그인·로그아웃하고, 권한 표시 없는 API 는 앱이 뜨지 않으며, 관리자 API 는 권한·횟수 제한으로 막힌다.

**Architecture:** 운영 컨텍스트에 Spring Session JDBC 와 메서드 보안을 붙인다. 세션에는 사용자 ID·`authAt` 두 JDK 값만 두고, 요청마다(권한이 필요할 때만) DB 에서 `Authentication` 을 만든다. 모든 요청 처리 메서드는 `@RequiresPermission` 또는 `@PublicApi` 를 붙여야 하고 기동 점검이 이를 강제한다.

**Tech Stack:** Spring Boot 4.1.2(Security 7.1, Session JDBC), Flyway, PostgreSQL 18, React 19.3.1 + React Router 8.5.1, TanStack Query, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-11-14-phase1a-auth-design.md`

## Global Constraints

- 저장소는 PUBLIC. 실제 메일·홈 경로·비밀을 커밋하지 않는다. 시험 값은 누가 봐도 가짜(`*.ondolith.test`, `pg-pass-…`).
- 코어는 최신 GA. 판·API 는 context7·공식 문서로 확인한다. 새 의존성은 OSV 확인 후 판 명시(백엔드는 Boot 관리 판, 프런트는 `package-lock.json`).
- TDD: 실패를 눈으로 본 뒤 구현. 버그 수정 시험은 수정을 되돌리면 Red.
- 무거운 작업은 한 번에 하나, `nice -n 19`. Java 명령은 `mise x --` 로(셸 기본 Java 가 25 가 아니다).
- 시험 HTTP 는 `127.0.0.1`. 마이그레이션은 앞으로만(V1 은 고치지 않는다).
- 오류 응답은 Problem Details(`application/problem+json`), JSON 필드는 snake_case.
- 인증·세션·CSRF 는 Spring Security 클래스로 한다. 직접 만드는 것은 세션 어댑터·권한 조회·기동 점검·횟수 제한뿐.
- 로그에 비밀번호·이메일을 남기지 않는다.
- **경로를 더하는 작업(2·4·7)은 같은 커밋에서** `OpenApiFileTest` 의 경로 목록 단언을 고치고 `src/front/openapi.json`·`src/front/src/shared/api/schema.d.ts` 를 다시 만든다(CI 가 커밋된 파일과의 차이로 실패한다).
- 횟수 제한(Task 6)이 들어간 뒤로, 횟수 제한을 보는 시험이 아니면 한도를 크게 준 속성(`--ondolithj.rate-limit.login-account=1000` 등)으로 띄운다.

## Review Focus

1. **로그인 전 세션 생성** — 익명 요청(특히 `Accept: text/html` 의 401)·로그인 실패·CSRF 거부가 `SPRING_SESSION` 행을 만들지 않는다(Task 4).
2. **회수 경합** — `authAt` 이 앱 시계가 아니라 DB 의 `sessions_valid_from` 이다. 같은 값이면 유효, 옮기면 다음 요청부터 401(Task 4·5).
3. **메서드 보안 우회** — package-private 메서드, 클래스에 붙인 표시, 표시 두 개가 실제로 강제·거부된다(Task 2·3).
4. **예외 변환** — 거부 예외가 `ApiErrors` 의 catch-all 에 잡혀 500 이 되지 않는다(Task 2).
5. **열린 리디렉트** — `next=//evil.test`, `/\evil.test`, `https://evil.test` 는 `/` 가 된다(Task 8).

---

### Task 1: 세션 저장소와 V2 마이그레이션

**Files:**
- Modify: `src/back/pom.xml` (`spring-boot-starter-session-jdbc`, Boot 관리 판)
- Create: `src/back/src/main/resources/db/migration/V2__auth.sql`
- Modify: `src/back/src/main/resources/application.properties`
- Modify: `src/back/src/main/java/dev/ondolithj/install/InstallApplication.java` (세션 자동 구성 제외가 필요하면)
- Test: `src/back/src/test/java/dev/ondolithj/MigrationTest.java`, `LauncherTest.java`

**Interfaces:**
- Produces: 표 `permissions(id, key, description, created_at)`, `role_permissions(id, role_id, permission_id, created_at, UNIQUE(role_id, permission_id))`, `spring_session`, `spring_session_attributes`. 속성 `spring.session.jdbc.initialize-schema=never`, `server.servlet.session.timeout=12h`.

- [ ] **Step 1: 실패하는 시험**
  - `MigrationTest`: V2 적용 뒤 `SELECT key FROM permissions` = `["admin.access"]`; `editor`·`operator` 가 `admin.access` 를 갖고 `admin`·`member`·`anonymous` 는 행이 없다; `spring_session`·`spring_session_attributes` 가 있다; `permissions.key` CHECK 가 `'Admin'` 을 거부한다.
  - `LauncherTest`: 설치 컨텍스트에 `org.springframework.session.SessionRepository` 빈이 없다(DataSource 없음은 기존 시험).
- [ ] **Step 2: 실행해 Red 확인** — `cd src/back && nice -n 19 mise x -- ./mvnw -q -B test -Dtest='MigrationTest,LauncherTest'` → 표 없음으로 실패.
- [ ] **Step 3: 구현** — 의존성 추가(OSV 확인), `V2__auth.sql`(세션 표는 jar 안 `org/springframework/session/jdbc/schema-postgresql.sql` 을 그대로 옮기고 출처 주석), 속성. 설치 컨텍스트에 세션 빈이 생기면 `InstallApplication` 의 exclude 에 세션 자동 구성 클래스를 더한다.
- [ ] **Step 4: Green** — 같은 명령 → 통과. 이어서 전체 `nice -n 19 mise run test` 와 `nice -n 19 mise run e2e` → 기존 시험 전부 통과(세션 스타터가 설치 컨텍스트·전환을 깨지 않음).
- [ ] **Step 5: 커밋** `인증 1: Spring Session JDBC 와 권한 표(V2)`

### Task 2: 메서드 보안, 첫 보호 API, 오류 형식

**Files:**
- Create: `src/back/src/main/java/dev/ondolithj/auth/Permission.java`, `RequiresPermission.java`, `PublicApi.java`
- Create: `src/back/src/main/java/dev/ondolithj/admin/AdminController.java`
- Modify: `src/back/src/main/java/dev/ondolithj/shared/SecurityConfig.java`, `ApiErrors.java`, `HealthController.java`; `ops/SiteController.java`; `install/InstallController.java`
- Test: `src/back/src/test/java/dev/ondolithj/auth/GuardTest.java`(운영 컨텍스트, Testcontainers), `shared/SecurityDefaultsTest.java`

**Interfaces:**
- Produces: `enum Permission { ADMIN_ACCESS("admin.access", false); String key(); boolean visitor(); static Optional<Permission> ofKey(String) }` — `visitor` 는 R9 의 방문자 권한 표시(1-A 에는 참인 것이 없다). 권한(authority) 문자열은 **enum 이름**(`ADMIN_ACCESS`)이다.
- `@RequiresPermission(Permission value)` = `@PreAuthorize("hasAuthority('{value}')")` 메타 애너테이션(템플릿 빈 `AnnotationTemplateExpressionDefaults`). `@PublicApi` = `@PreAuthorize("permitAll()")`.
- `GET /api/admin` → 200 `{}`, 클래스에 `@RequiresPermission(Permission.ADMIN_ACCESS)`, 클래스·메서드 package-private.
- SecurityConfig: `AuthenticationEntryPoint` → 401 Problem Details(헤더 `WWW-Authenticate` 없음), `AccessDeniedHandler` → 403 Problem Details(CSRF 거부 포함), `requestCache(NullRequestCache)`. 두 컨텍스트 공통.

- [ ] **Step 1: 실패하는 시험**
  - `GuardTest`(운영): 익명 `GET /api/admin` → 401, `Content-Type` 에 `problem+json`, `WWW-Authenticate` 없음. 같은 요청을 `Accept: text/html` 로도 401.
  - `GuardTest`: 위 401 이 서버 로그에 `ERROR` 를 남기지 않는다(`OutputCaptureExtension`).
  - `SecurityDefaultsTest`: CSRF 없는 POST 403 의 `Content-Type` 에 `problem+json`, 본문 `status` 403.
  - `GuardTest`: 표현식이 깨져 500 이 나지 않는다(익명 401 이 그 증거). enum 이 `ADMIN_ACCESS` 로 바뀌어 권한과 맞는지는 Task 4 의 「관리자 로그인 뒤 `/api/admin` 200」이 증명한다 — 맞지 않으면 그때 403 이다.
- [ ] **Step 2: Red 확인** — `-Dtest='GuardTest,SecurityDefaultsTest'` → `/api/admin` 404, CSRF 403 은 `application/json`.
- [ ] **Step 3: 구현** — 위 Interfaces. `ApiErrors.unexpected` 는 `AccessDeniedException`·`AuthenticationException` 을 다시 던진다(필터의 진입점·거부 처리기가 응답한다). 기존 컨트롤러에 `@PublicApi`. 템플릿이 enum 을 받지 못하면(Step 4 에서 확인) `@RequiresPermission(String)` 로 바꾸고 결정 문서에 Ruling 을 남긴다.
- [ ] **Step 4: Green** — **전체 백엔드**(`nice -n 19 mise x -- ./mvnw -q -B test`) → 통과. 메서드 보안과 `ApiErrors` 수정은 모든 컨트롤러에 닿는다. package-private 메서드가 실제로 막히는지는 익명 401(200 이 아님)이 증거다. `/api/admin` 을 더했으니 OpenAPI 단언·생성 파일도 고친다.
- [ ] **Step 5: 커밋** `인증 2: 메서드 보안, /api/admin, 401·403 Problem Details`

### Task 3: 기동 점검

**Files:**
- Create: `src/back/src/main/java/dev/ondolithj/auth/GuardCheck.java`
- Test: `src/back/src/test/java/dev/ondolithj/auth/GuardCheckTest.java`

**Interfaces:**
- `GuardCheck implements SmartInitializingSingleton` — 웹 서버가 요청을 받기 전에 돈다. `RequestMappingHandlerMapping` 의 모든 처리 메서드 중 빈 클래스가 `dev.ondolithj` 패키지인 것에 대해: 메서드 또는 클래스(메타 포함)에 `@RequiresPermission`·`@PublicApi` 가 **정확히 하나**, `@PreAuthorize` 직접 사용 금지. 어기면 `IllegalStateException`(메시지에 클래스#메서드).
- 표시가 있어도 프록시가 가로채지 못하는 메서드(`private`·`final`, 또는 빈이 프록시가 아님)는 기동 실패. 먼저 시험용 컨트롤러로 그런 메서드에 익명 요청이 통과하는지 본 뒤(통과하면 이 규칙이 필요하다는 증거) 규칙을 넣는다.
- `dev.ondolithj` 밖의 처리 메서드는 허용 목록에 없으면 기동 실패. 기본 허용은 Boot 오류 컨트롤러(`BasicErrorController`). 허용을 더하는 길은 `GuardCheck.Allowed` 빈(시험 전용 — 시험 클래스패스의 springdoc 경로)뿐이고 운영 설정 속성으로는 열 수 없다.
- 운영 컨텍스트에서만(DataSource 가 있을 때): `SELECT key FROM permissions` 집합 == `Permission` 의 key 집합, 아니면 예외. 어느 메서드도 쓰지 않는 `Permission` 은 WARN.
- 설치 컨텍스트는 `install` 패키지만 스캔하므로 `InstallApplication` 의 `@Import` 에 `GuardCheck` 를 더한다.

- [ ] **Step 1: 실패하는 시험** — 시험 전용 설정으로 컨텍스트를 띄워 기동 실패를 본다:
  - 표시 없는 `@RestController` → 기동 실패, 메시지에 그 메서드 이름.
  - 메서드에 `@PublicApi` + 클래스에 `@RequiresPermission` → 실패(정확히 하나).
  - `@PreAuthorize("permitAll()")` 직접 → 실패.
  - DB 에 `permissions` 행 `x.y` 추가 → 운영 기동 실패.
  - `private`(또는 `final`) 처리 메서드에 `@RequiresPermission` → 실패.
  - `dev.ondolithj` 밖 패키지의 시험용 컨트롤러 → 실패. `GuardCheck.Allowed` 빈으로 허용하면 성공.
  - 설치 컨텍스트에서도 표시 없는 컨트롤러가 기동을 멈춘다(설치 컨텍스트에 `@Import` 된 시험용 설정으로).
  - 정상 코어(기존 컨트롤러 전부) → 두 컨텍스트 모두 기동 성공.
- [ ] **Step 2: Red** — `-Dtest=GuardCheckTest` → 표시 없는 컨트롤러로도 기동 성공해 실패.
- [ ] **Step 3: 구현** — `AnnotatedElementUtils.hasAnnotation`/`findMergedAnnotation` 으로 메타 포함 검사.
- [ ] **Step 4: Green** + 전체 백엔드 시험.
- [ ] **Step 5: 커밋** `인증 3: 권한 표시를 기동 때 강제한다`

### Task 4: 로그인·로그아웃과 세션 어댑터

**Files:**
- Create: `src/back/src/main/java/dev/ondolithj/auth/AuthConfig.java`(운영 전용: `UserDetailsService`, `AuthenticationManager`, `SessionAuthenticationStrategy`, 세션 어댑터 빈), `SessionContextRepository.java`, `Actor.java`, `ActorQuery.java`, `LoginController.java`, `LoginRequest.java`
- Modify: `shared/SecurityConfig.java` (있으면 `SecurityContextRepository` 빈을 쓴다 — `ObjectProvider`)
- Test: `src/back/src/test/java/dev/ondolithj/auth/LoginTest.java`

**Interfaces:**
- 세션 속성: `ondolithj.user_id`(String), `ondolithj.auth_at`(Instant).
- `record Actor(UUID id, String email, String displayName, Instant authAt, Set<Permission> permissions)` — 인증 주체. authority = `permissions` 의 enum 이름. `saveContext` 는 `id`·`authAt` 을 세션 속성으로 쓴다. 로그인 때 `authAt` 은 `UserDetailsService` 가 읽은 `sessions_valid_from` 이다.
- `ActorQuery.load(UUID id, Instant authAt) → Optional<Actor>`: 쿼리 1회로 사용자·`is_active`·`sessions_valid_from`·유효 권한(`{anonymous, member} ∪ user_roles`, 슈퍼유저면 전부). 없음·비활성·`authAt < sessions_valid_from` 이면 빈 값. (익명 요청에 익명 역할 권한을 주는 일은 그 권한을 처음 쓰는 Phase 로 미룬다 — 스펙 3.4.)
- `UserDetailsService`: `is_active=true` 인 계정만 찾는다(비활성은 없는 계정). 같은 질의로 `sessions_valid_from` 을 읽어 인증 결과에 담는다 → `authAt`.
- `SessionContextRepository implements SecurityContextRepository`: `loadDeferredContext` 는 세션이 있으면 두 속성을 읽어 지연 공급자로 `ActorQuery.load`; 실패·타입 불일치·빈 값이면 세션 무효화 후 빈 컨텍스트. `saveContext` 는 `Actor` 인증일 때만 두 속성을 쓴다(익명이면 세션을 만들지 않는다). 세션은 `request.getSession(false)` 로만 읽는다.
- `POST /api/auth/login`(`@PublicApi`) `LoginRequest(@NotBlank @Size(max=254) email, @NotNull @Size(max=1000) password)` → 204 / 401 Problem「이메일 또는 비밀번호가 맞지 않습니다」 / 422. `POST /api/auth/logout`(`@PublicApi`) → 204.

- [ ] **Step 1: 실패하는 시험**(`LoginTest`, 설치 API 로 관리자를 만든 운영 DB)
  - 올바른 로그인 204, 이어서 `GET /api/admin` 200.
  - 없는 이메일·틀린 비밀번호·비활성 계정·73바이트 넘는 비밀번호: 모두 401, 본문 동일(`detail`), 500 없음.
  - 이메일 대소문자·앞뒤 공백 무시.
  - 세션 고정: 임의 `SESSION` 쿠키를 심고 로그인 → 응답 쿠키 값이 다르다. 로그인한 세션으로 재로그인 → ID 바뀌고 옛 ID 로 `/api/admin` 401. 로그인 앞뒤 `XSRF-TOKEN` 이 다르다.
  - 로그인 전 세션 없음: 익명 `/api/admin`(json·html), 로그인 실패, CSRF 거부 뒤 `SELECT count(*) FROM spring_session` = 0.
  - 세션 속성 `ondolithj.auth_at` 이 DB `users.sessions_valid_from` 과 같다(앱 시계와 무관).
  - 로그아웃 204 뒤 `spring_session` 행 0, `/api/admin` 401.
  - 쿠키 속성: `HttpOnly`, `SameSite=Lax`; `cookies.secure=true` 설정이면 `Secure`.
  - 로그 출력에 시험 이메일·비밀번호가 없다.
- [ ] **Step 2: Red** — `-Dtest=LoginTest` → `/api/auth/login` 404.
- [ ] **Step 3: 구현** — Spring 문서의 REST 로그인 방식(`AuthenticationManager.authenticate` → 전략 `ChangeSessionIdAuthenticationStrategy`+`CsrfAuthenticationStrategy` 의 `CompositeSessionAuthenticationStrategy` → `SecurityContextRepository.saveContext`). 인코더 예외(72바이트)·모든 `AuthenticationException` → 같은 401.
- [ ] **Step 4: Green** + 전체 백엔드.
- [ ] **Step 5: 커밋** `인증 4: 로그인·로그아웃, DB 세션, 세션에는 사용자 ID 와 authAt 만`

### Task 5: 요청마다 권한·회수 반영

**Files:**
- Test: `src/back/src/test/java/dev/ondolithj/auth/RevocationTest.java`
- Modify: Task 4 의 `ActorQuery`·`SessionContextRepository`(필요하면)

**Interfaces:** Task 4 그대로.

- [ ] **Step 1: 실패하는 시험**
  - 회원(역할 없음) 로그인 → `/api/admin` 403 Problem Details.
  - R9: `member`(또는 `anonymous`) 역할에 `admin.access` 를 넣어도 회원은 403(방문자 권한이 아닌 부여는 불러올 때 버린다).
  - `editor` 부여 → 다음 요청 200, 회수 → 다음 요청 403(로그아웃 없이).
  - 로그인 뒤 `is_active=false` → 다음 요청 401, `spring_session` 행 사라짐.
  - 로그인 뒤 `sessions_valid_from = sessions_valid_from + interval '1 microsecond'` → 401. 같은 값이면 200.
  - 세션 속성 `ondolithj.auth_at` 을 다른 타입으로 바꿔 둔 세션(시험이 JDBC 로 행 조작이 어려우면 세션 저장소 빈으로 조작) → 401·세션 삭제.
  - 공개 API 무질의: 시험 설정에서 `ActorQuery` 를 감싼 빈(호출 수를 센다)으로 바꿔, 로그인한 세션 쿠키로 `/api/health`·`/api/site` 를 부른 뒤 호출 수 0, `/api/me` 뒤 1 임을 본다.
- [ ] **Step 2: Red** — 해당 시험이 실패하는지(Task 4 구현이 이미 만족하면 그 단언은 Red 를 못 본 것이므로 구현을 잠시 되돌려 Red 를 확인한다).
- [ ] **Step 3: 구현·보완**
- [ ] **Step 4: Green** + 전체 백엔드.
- [ ] **Step 5: 커밋** `인증 5: 권한 회수·비활성·기준 시각이 다음 요청에 반영된다`

### Task 6: 횟수 제한

**Files:**
- Create: `src/back/src/main/java/dev/ondolithj/auth/RateLimiter.java`, `RateLimitProperties.java`, `AdminRateLimitFilter.java`
- Modify: `LoginController.java`, `SecurityConfig.java`(필터 등록, 운영만)
- Test: `src/back/src/test/java/dev/ondolithj/auth/RateLimiterTest.java`(단위), `LoginTest`·`GuardTest` 에 429 사례

**Interfaces:**
- `RateLimiter(Clock clock)`: `Decision hit(String key, int limitPerMinute)` → `record Decision(boolean allowed, long retryAfterSeconds)`. 1분 고정 창, 창이 바뀌면 지난 창의 맵을 버린다. 스레드 안전.
- `@ConfigurationProperties("ondolithj.rate-limit") record RateLimitProperties(int loginIp = 10, int loginAccount = 5, int adminIp = 60)`.
- 로그인: 비밀번호 대조 전에 `login-ip:<ip>` 를 먼저 세고, 넘었으면 계정 키는 세지 않는다(한 IP 가 1분에 만드는 키 수를 묶는다). 그다음 `login-account:<정규화 이메일>`.
- 관리자 필터는 Spring 빈으로 만들면 Boot 가 서블릿 필터로도 등록한다 — 보안 체인에만 넣고 서블릿 등록은 끈다(`FilterRegistrationBean.setEnabled(false)` 또는 빈이 아닌 객체로 체인에 추가). 관리자: `/api/admin/**` 에 `admin-ip:<ip>`. 넘으면 429 Problem Details + `Retry-After`.
- IP 는 `request.getRemoteAddr()` (`ponytail:` Phase 4 에서 믿을 프록시).

- [ ] **Step 1: 실패하는 시험** — 단위: 10번 허용·11번째 거부·`retryAfter` = 창 끝까지 초·창 지나면 회복(가짜 시계)·다른 키 독립·동시 100 스레드 정확히 한도만 허용. 통합: 같은 IP 로 로그인 10번째까지 401·11번째 429(+`Retry-After`), 같은 계정 5번째까지 401·6번째 429(없는 계정도), IP 한도를 넘은 요청은 계정 키를 세지 않는다, 설정 속성으로 한도 바꾸면 따른다, `/api/admin` 60번째 200·61번째 429(두 번 세면 31번째에 막힌다). 다른 시험 클래스는 큰 한도로 띄우도록 고친다. E2E 의 1분 안 로그인 횟수가 한도 안인지 센다.
- [ ] **Step 2: Red** — `-Dtest='RateLimiterTest,LoginTest,GuardTest'`.
- [ ] **Step 3: 구현**
- [ ] **Step 4: Green** + 전체 백엔드.
- [ ] **Step 5: 커밋** `인증 6: 로그인·관리자 경로 횟수 제한`

### Task 7: `/api/me` 와 OpenAPI

**Files:**
- Create: `src/back/src/main/java/dev/ondolithj/auth/MeController.java`
- Modify: `LoginController.java`·`AdminController.java`(응답 코드 `@ApiResponse`), `OpenApiFileTest.java`, `src/front/openapi.json`(생성), `src/front/src/shared/api/schema.d.ts`(생성)
- Test: `MeTest`(통합), `OpenApiFileTest`

**Interfaces:**
- `GET /api/me`(`@PublicApi`) → `{"user": null}` 또는 `{"user": {"email", "display_name"}, "can": {"admin.access": true|false}}` — `can` 의 키는 `Permission.key()` 전부.

- [ ] **Step 1: 실패하는 시험** — 익명 `user` null; 관리자 `can.admin.access` true; 회원 false; 이 요청은 세션을 만들지 않는다. `OpenApiFileTest`: 경로 `/api/admin`·`/api/auth/login`·`/api/auth/logout`·`/api/me` 가 있고 login 응답 코드 = `204, 401, 403, 422, 429`, 401·429 는 `ProblemDetail`.
- [ ] **Step 2: Red** → **Step 3: 구현** → **Step 4: Green**(+ `npm run gen:api`, `npm run typecheck`)
- [ ] **Step 5: 커밋** `인증 7: /api/me 와 OpenAPI 문서`

### Task 8: 프런트 — 라우터, 로그인, 관리자 문

**Files:**
- Modify: `src/front/package.json`·`package-lock.json`(`react-router` 8.5.1, OSV 0 확인함)
- Modify: `src/front/src/app/App.tsx`(운영 상태에서 라우터)
- Create: `src/front/src/features/auth/LoginPage.tsx`, `loginSchema.ts`, `useLogin.ts`, `useMe.ts`, `safeNext.ts`, `AdminGate.tsx`, `UserMenu.tsx`
- Test: 같은 폴더 `*.test.tsx`/`safeNext.test.ts`

**Interfaces:**
- `safeNext(raw: string | null): string` — `/` 로 시작하고 `//`·`/\` 로 시작하지 않으며 제어 문자가 없으면 그대로, 아니면 `"/"`.
- `useMe()` → TanStack Query `['me']`. 로그인·로그아웃 성공 시 무효화.

- [ ] **Step 1: 실패하는 시험** — `safeNext`: `/admin`→`/admin`, `//evil.test`→`/`, `/\evil.test`→`/`, `https://evil.test`→`/`, `null`→`/`, `"/a\n"`→`/`. `LoginPage`: 401 이면 서버 `detail` 표시·포커스, 429 면 「잠시 뒤 다시 시도하세요」, 성공이면 `next` 로 이동. `AdminGate`: `/api/admin` 401 → `/login?next=/admin`, 403 → 「권한이 없습니다」, 200 → 셸과 로그아웃 버튼. `App`: 운영 상태에서 `/login` 경로가 로그인 화면.
- [ ] **Step 2: Red** — `cd src/front && nice -n 19 mise x -- npx vitest run src/features/auth src/app`.
- [ ] **Step 3: 구현** — 기존 shadcn 부품(`button`·`input`·`label`), 설치 폼과 같은 오류 표시·포커스 방식.
- [ ] **Step 4: Green** — `npm test && npm run typecheck && npm run build`.
- [ ] **Step 5: 커밋** `인증 8: 프런트 라우터, 로그인 화면, 관리자 문`

### Task 9: E2E

**Files:**
- Create: `src/front/e2e/login.spec.ts`
- Modify: `src/front/e2e/stack.ts`(필요하면 설치를 끝낸 상태로 시작하는 도우미)

- [ ] **Step 1: 시험 작성** — 설치 → `/admin` 접근 → `/login?next=/admin` 로 감 → 틀린 비밀번호 오류 → 올바른 로그인 → `/admin` 셸 → 로그아웃 → `/admin` 다시 막힘. 키보드만으로 같은 흐름 한 번.
- [ ] **Step 2: 실행** — `nice -n 19 mise run e2e` → 새 시험이 먼저 실패하는지(구현 전 커밋으로 되돌려 볼 필요는 없다: Task 8 까지 끝난 상태라 바로 통과할 수 있다. 통과하면 단언 하나를 일부러 틀려 실패를 본다).
- [ ] **Step 3: 커밋** `인증 9: 로그인 흐름 E2E`

### Task 10: 문서·측정·보안 점검

**Files:**
- Modify: `docs/decisions.md`(구현 중 결정), `docs/appendix-a.md`(FR-201~205·209 행), `docs/measure-lightsail.md`, `README.md`(로그인 안내 한 줄이 필요하면), `results/core-1a/`·`results/bench-1a/`

- [ ] **Step 1** — 전체 `nice -n 19 mise run test`, `nice -n 19 mise run e2e` 통과 숫자 기록.
- [ ] **Step 2** — 코어 jar 빌드 후 측정 앱과 번갈아 S5·S6 각 3회(`TARGET=core LABEL=core-1a`, `LABEL=bench-1a`). 결과 원본을 눈으로 훑고 `check-public`. `docs/measure-lightsail.md` 에 Phase 0 와 나란히 적는다(처리량은 비교하지 않는다).
- [ ] **Step 3** — 보안 점검: 스펙 6·8절과 ondolith D86 표 중 이 범위(인증·세션·CSRF·오류·로그) 항목을 하나씩 대조해 결과를 결정 문서에 적는다.
- [ ] **Step 4: 커밋** `인증 10: 문서·측정·보안 점검`
