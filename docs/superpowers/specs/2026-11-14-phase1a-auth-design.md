# Phase 1-A 설계 — 인증·세션·권한 기반

작성 2026-11-14. 사용자가 브레인스토밍에서 승인한 내용을 옮겼다(세션 저장 (가), 권한 검사 (A), 설계 전체).
근거: 요청서 `docs/request-core.md`, 결정 `docs/decisions.md`(「Phase 1 나누기」), ondolith 원문 D15(권한 모델)·D60(보안)·D10(FR-201~218).
기능 상세의 단일 출처는 ondolith 원문이다. 이 문서는 SPA·Spring 에 맞게 바꾼 것과 Phase 1-A 의 경계를 적는다.

## 0. 범위

| 넣는다 | 뺀다(해당 하위 Phase 로) |
|---|---|
| 이메일+비밀번호 로그인·로그아웃 (FR-201·203·204) | 재인증 15분 창 (D15 5.3-1) — 쓰는 화면이 1-B 에 있다 |
| DB 세션 (FR-202, NFR-204) | 마지막 최고 관리자 보호 (D15 5.2, FR-704) — 1-B |
| RBAC 표·시드·판정 (FR-205, D15 P1~P3) | 작업 로그 표 `operation_logs` (D15 7) — 첫 관리 작업이 생기는 1-B |
| 권한 표시 강제와 기동 점검 (D15 4.4) | 관리자 화면 내용(메뉴·설정·사용자·역할) — 1-B |
| 로그인·관리자 경로 횟수 제한 (FR-209, D15 4.3-2) | 회원가입·이메일 인증·비밀번호 재설정·내 계정·소셜 — 1-E |
| 비활성 계정·`sessions_valid_from` 이전 세션 거부 (D15 5.4) | 게시판별 권한 칸 `board_id` — Phase 2 |
| 401·403·429·CSRF 거부를 Problem Details 로 통일 | 실제 클라이언트 IP(CloudFront 뒤) 판정 — Phase 4 |
| 프런트: React Router, `/login`, `/admin` 문, 로그인 상태 | 백업에서 세션 데이터 빼기 — 백업 기능(Phase 6) |

1-A 가 끝나면: 설치 때 만든 관리자가 React 로그인 화면으로 로그인·로그아웃하고, 권한 없는 사용자는 관리자 API 에서 403 을, 로그인 시도가 많으면 429 를 받는다. 모든 API 는 권한 표시가 없으면 앱이 뜨지 않는다.

## 1. 기술

| 항목 | 선택 | 근거 |
|---|---|---|
| 세션 저장 | Spring Session JDBC (`spring-boot-starter-session-jdbc`, Boot 4.1.2 관리 판) | FR-202 「상태는 DB」. 재시작·배포에도 로그인이 남는다. 사용자 승인 (가) |
| 세션 표 | Flyway 로 만든다. `spring.session.jdbc.initialize-schema=never` | 스키마는 마이그레이션 하나로만 바뀐다(NFR-302) |
| 비밀번호 대조 | Spring Security `AuthenticationManager` + `DaoAuthenticationProvider` + `BCryptPasswordEncoder` | 인증을 직접 만들지 않는다(NFR-201). 없는 계정도 BCrypt 비교를 해 시간 차를 줄이는 것은 Spring 기본 동작이다 |
| 권한 검사 | Spring Security 메서드 보안(`@EnableMethodSecurity`) | 사용자 승인 (A). 판정이 컨트롤러에 있다(D15 P3) |
| 횟수 제한 | 메모리 안 1분 고정 창, 직접 만든 작은 클래스(의존성 없음) | D15 4.3-2 「메모리 기반, 재시작 시 초기화는 받아들인다」. 인증 자체가 아니라 셈이다 |
| 프런트 라우팅 | React Router(최신 GA, 판은 계획 때 context7 로 확인) | `/login`·`/admin` 주소가 생긴다. 결정 문서의 「1-C 에서 들인다」를 1-A 로 당긴다 |

새 의존성은 위 둘(세션 JDBC 스타터, React Router)뿐이다. 넣기 전에 OSV 와 유지 상태를 확인한다.

## 2. 데이터 (V2 마이그레이션, 앞으로만)

- `permissions (id uuid, key text UNIQUE CHECK (key ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'), description text, created_at)`.
  1-A 가 쓰는 `admin.access` 하나만 넣는다(D15 W1-04: 쓰지 않는 권한을 미리 심으면 「미사용 권한」 경고가 매번 쏟아져 무시된다).
- `role_permissions (id uuid, role_id → roles ON DELETE CASCADE, permission_id → permissions ON DELETE RESTRICT, UNIQUE (role_id, permission_id))`.
  시드: `editor`·`operator` 에 `admin.access`(D15 2.5). `admin` 은 `is_superuser` 라 행이 없어도 모든 검사를 통과한다.
- Spring Session 표 2개(`SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`): Spring Session 이 내는 PostgreSQL 스키마를 그대로 옮긴다(판은 구현 때 jar 안의 `schema-postgresql.sql` 과 대조).
- `users` 는 V1 그대로 쓴다(`is_active`, `sessions_valid_from` 이 이미 있다).

## 3. 인증과 세션

### 3.1 로그인 `POST /api/auth/login`

- 입력 `{ email, password }`. 이메일은 앞뒤 공백을 빼고 소문자로(설치와 같은 규칙). 길이 상한: 이메일 254자, 비밀번호 1000자 — 넘으면 422.
- 처리 순서:
  1. 횟수 제한(4절) — 넘으면 429. 비밀번호를 대조하기 전에 거른다.
  2. `AuthenticationManager.authenticate`.
     **비활성 계정은 「없는 계정」으로 조회한다** — `DaoAuthenticationProvider` 는 비활성 검사를 비밀번호 대조 전에 해서, 그대로 두면 비활성 계정만 BCrypt 를 건너뛰어 응답 시간으로 존재가 드러난다.
     BCrypt 72바이트를 넘는 비밀번호에서 인코더가 던지는 예외도 실패로 다룬다(500 이 아니다).
  3. 성공: 세션 ID 재발급(`ChangeSessionIdAuthenticationStrategy`)과 CSRF 토큰 교체(`CsrfAuthenticationStrategy`)를 Spring 클래스로 건다 — REST 컨트롤러 로그인에서는 Spring 이 이것을 자동으로 걸지 않는다. 세션에 사용자 ID 와 `authAt` 을 넣고 204.
     **`authAt` 은 앱 시계가 아니라, 비밀번호 해시와 같은 질의에서 읽은 그 계정의 `sessions_valid_from` 값이다**(D15 5.4, ondolith 2026-10-21 5차 점검). 앱 시각을 찍으면 회수(기준 시각을 앞으로 옮기는 트랜잭션)와 겹친 로그인이 회수 뒤에도 살아남고, 앱·DB 시계가 어긋나면 막 로그인한 세션이 거부된다.
  4. 실패: 원인과 관계없이 **같은 401**(상태·본문·헤더). 본문 `detail` 은 「이메일 또는 비밀번호가 맞지 않습니다」.
- 로그: 성공·실패를 INFO 로 남긴다. 이메일·비밀번호는 남기지 않는다(사용자 ID 는 성공 때만).

### 3.2 로그아웃 `POST /api/auth/logout`

- 공개(로그인하지 않아도 204). CSRF 는 필요하다. 세션을 무효화해 `SPRING_SESSION` 행이 지워진다(FR-203).

### 3.3 세션에 담는 것

- 담는다: 속성 둘 — `ondolithj.user_id`(`String`, UUID), `ondolithj.auth_at`(`java.time.Instant`). **JDK 타입만** 넣는다 — Spring Session 은 속성을 Java 직렬화로 저장하므로, 우리 클래스를 넣으면 다음 판에서 모양이 바뀔 때 jar 교체 뒤 세션을 못 읽는다((가)를 고른 이유가 jar 교체 뒤 로그인 유지다). 속성이 없거나 타입이 다르면 그 세션을 버리고 익명으로 이어진다.
- 담지 않는다: 권한·역할·판정 결과·비밀번호(D15 5.4).
- Spring 기본 `HttpSessionSecurityContextRepository` 는 권한 목록이 든 `Authentication` 을 통째로 세션에 넣는다. 그래서 `SecurityContextRepository` 를 구현한 작은 어댑터가 저장 때는 위 두 속성만 쓰고, 읽을 때 DB 에서 `Authentication` 을 새로 만든다.
- **로그인에 성공하기 전에는 세션(`SPRING_SESSION` 행)을 만들지 않는다.** 익명 요청·로그인 실패·401·403·CSRF 거부 어느 것도 세션을 만들지 않는다. 401 때 원래 요청을 세션에 저장하는 Spring 기능(request cache)은 끈다 — 익명 요청마다 행이 쌓이면 세션 표가 공격으로 커진다.
- 설치 컨텍스트에는 세션 저장소와 로그인이 없다(DataSource 가 없다). 설치 API 는 Phase 0 그대로다.
- 쿠키: `HttpOnly`, `SameSite=Lax`, `Secure` 는 설정 파일의 `cookies.secure`(Phase 0 그대로). 수명 12시간(`server.servlet.session.timeout`, 기동 인자로 바꿀 수 있다). 만료 세션 정리는 Spring Session 기본 주기(1분).

### 3.4 요청마다

- 권한이 필요한 순간에만(지연 로드) 사용자와 유효 권한 집합을 **쿼리 한 번**으로 읽는다(D15 4.3-1). 공개 API 는 읽지 않는다.
- 유효 역할: 로그인 `{anonymous, member} ∪ user_roles`(D15 1.1). **`anonymous`·`member` 역할의 부여는 방문자 권한만 센다**(ondolith D15 R9, 2026-11-14 추가) — 두 역할은 모든 요청이 가지므로, 거기 관리 권한이 들어가면 가입한 누구나 그 권한을 갖는다. 방문자 권한(`post.read`·`post.write`·`comment.write`)은 Phase 2 에서 생기므로 1-A 에서는 두 역할의 부여를 모두 버린다. 부여 화면의 거절(422)과 「효력 없음」 표시는 1-B. 익명 요청에 익명 역할 권한을 주는 일은 그 권한을 처음 쓰는 Phase 로 미룬다 — 1-A 에는 익명에게 줄 권한이 없고, 익명 요청마다 질의하지 않는다. 권한 = 그 역할들의 `role_permissions`. 역할 하나라도 `is_superuser` 면 코드의 권한 목록 전부.
- 거부하고 세션을 무효화하는 경우: 사용자가 없음, `is_active=false`, `authAt < sessions_valid_from`(같으면 유효 — 로그인 때 그 값을 찍었다). 그 요청은 익명으로 이어진다(공개 API 는 그대로 응답, 권한 API 는 401).

## 4. 횟수 제한

| 대상 | 기준값(기본) | 키 |
|---|---|---|
| 로그인 | IP당 10회/분, 계정당 5회/분 | `login-ip:<ip>`, `login-account:<정규화 이메일>` — 없는 계정도 똑같이 센다(셈으로 존재가 드러나지 않게) |
| 관리자 경로 `/api/admin/**` | IP당 60회/분 | `admin-ip:<ip>` |

- 1분 고정 창. 창이 바뀌면 지난 창의 셈을 버린다(메모리는 1분 동안의 키 수까지만). 시계를 주입해 시험한다.
- 기준값은 설정 속성(`ondolithj.rate-limit.*`)으로 바꾼다 — 사내망처럼 IP 가 하나로 뭉치는 곳이 있다(D15 4.3-2).
- 넘으면 429 Problem Details + `Retry-After`(창이 끝날 때까지 초).
- IP 는 `request.getRemoteAddr()`. `ponytail:` CloudFront 뒤의 실제 IP 는 Phase 4 에서 믿을 프록시를 정해 바꾼다. `X-Forwarded-For` 를 그대로 믿으면 위조로 제한을 피한다.

## 5. 권한 검사와 기동 점검

- 권한 목록은 코드의 enum `Permission` 이 정하고 마이그레이션이 심는다(D15 P1). 관리 UI 로 늘리지 않는다.
- 모든 API 메서드(클래스에 붙여도 된다)에 둘 중 하나를 붙인다:
  - `@RequiresPermission(Permission.ADMIN_ACCESS)` — `@PreAuthorize` 를 메타 애너테이션으로 쓴다. enum 을 받아 오타를 컴파일 때 막는다(Spring Security 의 애너테이션 템플릿. 판에서 되는지 계획 때 확인하고, 안 되면 문자열 + 기동 점검으로 막는다).
  - `@PublicApi` — `@PreAuthorize("permitAll()")` 메타 애너테이션.
  - `@PreAuthorize` 를 직접 쓰지 않는다(점검이 표현식을 해석하지 않게).
- 기동 점검 — 하나라도 어긋나면 **기동을 멈춘다**(요청을 받기 전에):
  1. `dev.ondolithj` 의 모든 요청 처리 메서드에 위 둘 중 하나가 정확히 하나 있다. 메타 애너테이션까지 센 `@PreAuthorize` 도 메서드·클래스 합쳐 하나다(합성 애너테이션이 클래스의 표시를 덮지 못하게). `private`·`final`·`static` 처리 메서드는 거부한다 — 보안 프록시가 못 가로채 표시가 있어도 열린다(구현 중 확인). `dev.ondolithj` 밖의 처리기는 허용 목록(Boot 오류 처리기, 시험 전용 springdoc)에 없으면 거부한다. `/api/admin/**` 에는 `@PublicApi` 를 달 수 없다.
  2. (운영 컨텍스트) DB `permissions.key` 집합이 `Permission` enum 과 같다.
  3. 아무 메서드도 쓰지 않는 권한은 경고 로그(기동은 한다).
- URL 층(`authorizeHttpRequests`)은 `permitAll` 로 둔다. 기본 거부는 1번 점검이 맡는다 — 표시 없는 API 는 앱이 뜨지 않는다. 없는 경로는 그대로 404(D-3).
- `/api/admin/**` 의 메서드는 모두 `admin.access` 를 요구한다(관리자 트리 진입 경계, D15 1.2). 리소스별 권한은 그 위에 메서드마다 더한다(1-B).

## 6. API

| 경로 | 표시 | 응답 |
|---|---|---|
| `POST /api/auth/login` | 공개 | 204 / 401 / 422 / 429 |
| `POST /api/auth/logout` | 공개(CSRF) | 204 |
| `GET /api/me` | 공개 | `{"user": null, "can": {...모두 false}}` 또는 `{"user": {"email", "display_name"}, "can": {"admin.access": true}}`. `can` 의 키는 코드의 권한 목록 전부. `can` 은 화면 표시용이다 — 보안 판정은 서버가 한다(D15 4.3) |
| `GET /api/admin` | `admin.access` | 200 `{}`(1-B 가 채운다) / 401 / 403 / 429 |
| 기존 `/api/health`·`/api/site`·`/api/install` | 공개 | 그대로 |

- 오류는 모두 Problem Details(`application/problem+json`): 401(로그인 안 함·로그인 실패), 403(권한 없음·CSRF 거부), 404, 422, 429. 401 에 `WWW-Authenticate` 를 붙이지 않는다(브라우저 로그인 창). 보안 필터가 내는 응답도 같은 형식으로 쓴다 — Phase 0 의 CSRF 403(Boot 기본 오류 JSON)을 여기서 맞춘다.
- OpenAPI 문서에 실제 응답 코드를 적고 생성 시험이 확인한다(Phase 0 후속과 같은 방식).

## 7. 프런트

- React Router: `/`(홈), `/login`, `/admin`. 설치·전환 대기 흐름(`/api/health` 로 화면을 고르는 것)은 그대로 두고, 운영 상태일 때 라우터가 화면을 고른다.
- `/login`: 이메일·비밀번호 폼(Phase 0 설치 폼과 같은 shadcn 부품). 401 은 서버 문구 그대로, 429 는 「잠시 뒤 다시 시도하세요」. 성공하면 `next` 로 간다.
- `next` 는 `/` 로 시작하고 `//`·`/\` 로 시작하지 않는 경로만 받는다. 아니면 `/`(열린 리디렉트 방지).
- `/admin`: 로그인 안 했으면(`/api/me` 의 `user` 가 null 또는 `GET /api/admin` 401) `/login?next=/admin`. 403 이면 「권한이 없습니다」. 통과하면 빈 관리자 셸 + 로그아웃 버튼.
- 머리글에 로그인 상태(이메일, 로그아웃). 로그아웃 뒤 `/`.
- 공유 UI 상태는 서버 상태(`/api/me`)뿐이라 TanStack Query 로 둔다. Zustand 는 여전히 넣지 않는다.

## 8. 검증

- 통합 시험(Testcontainers PostgreSQL):
  - 없는 계정·틀린 비밀번호·비활성 계정의 로그인 응답이 상태·본문까지 같다.
  - 로그인 성공 전에는 `SPRING_SESSION` 행이 0 이다: 익명 `/api/admin`(`Accept: application/json` 과 `text/html` 둘 다), `/api/me`, 로그인 실패, CSRF 거부 뒤.
  - 세션 고정: 미리 심은 세션 쿠키(공격자가 정한 값)로 로그인해도 응답의 세션 ID 가 다르다. 로그인한 세션으로 다시 로그인하면 세션 ID 가 바뀌고 옛 ID 로는 로그인 상태가 아니다. CSRF 토큰도 바뀐다.
  - `authAt` 은 DB 의 `sessions_valid_from` 값이다(앱 시계와 무관). 세션 속성이 없거나 타입이 다르면 익명으로 이어지고 세션이 지워진다.
  - 로그아웃 뒤 `SPRING_SESSION` 행이 없다.
  - 로그인 뒤 `is_active=false` 또는 `sessions_valid_from` 을 앞으로 옮기면 다음 요청이 401 이고 세션이 지워진다.
  - `editor` 역할을 회수하면 다음 요청부터 `/api/admin` 403.
  - 익명 `/api/admin` 401, 권한 없는 회원 403, CSRF 없는 POST 403 — 모두 Problem Details.
  - 로그인 11번째(IP)·6번째(계정) 429 와 `Retry-After`. 창이 지나면 회복(가짜 시계).
  - 공개 API(`/api/health`)는 사용자·권한 질의를 하지 않는다.
  - 세션 쿠키 속성(`HttpOnly`·`SameSite=Lax`·`Secure` 설정 반영).
- 기동 점검 시험: 표시 없는 컨트롤러, 두 표시가 함께 붙은 메서드, DB 와 enum 불일치를 일부러 넣으면 기동이 실제로 실패한다.
- 프런트 시험(Vitest): 로그인 폼 401·429 문구, `next` 정리, `/admin` 문의 401·403 분기.
- E2E(Playwright, 실제 jar): 설치 → 로그인 → `/admin` → 로그아웃. 키보드만으로도 한 번.
- 메모리: 1-A 를 마친 코어 jar 를 `TARGET=core` 로 다시 잰다(측정 앱과 번갈아, S5·S6 각 3회).
- 보안 점검: D86 표를 Java·React 판으로 이 범위만큼 확인한다(요청서 7절).

## 9. 위험과 확인할 것

| 위험 | 대응 |
|---|---|
| 메서드 보안의 거부 예외를 `ApiErrors` 의 `@ExceptionHandler(Exception.class)` 가 먼저 잡아 500·ERROR 로그가 된다 | 첫 보호 API 에서 익명 401·회원 403 시험을 먼저 Red 로 둔다 |
| package-private 컨트롤러 메서드에 붙인 표시가 실제로 강제되지 않는다(프록시가 못 가로챔) | 첫 보호 API 시험으로 확인. 안 되면 기동 점검이 표시 붙은 메서드의 공개 여부도 본다 |
| 세션 스타터가 설치 컨텍스트(DataSource 없음)를 깬다 | 의존성을 넣은 직후 `LauncherTest`·E2E 로 확인 |
| 지연 로드가 실제로 지연되지 않아 공개 API 마다 DB 를 친다 | 8절 「공개 API 는 질의하지 않는다」 시험 |
| Spring Session 이 세션 속성을 Java 직렬화로 저장한다 | JDK 타입(`String`·`Instant`)만 넣는다. 이 저장소에서만 읽는다 |
| 로그인 응답 시간으로 계정 존재가 드러난다 | 비활성 계정도 「없는 계정」 경로(BCrypt 가짜 비교)로 보낸다. 시간 측정 시험은 흔들려서 두지 않는다 |
| 애너테이션 템플릿이 enum 을 받지 못한다 | 계획 때 판의 문서로 확인. 안 되면 문자열 + 기동 점검 2번으로 오타를 막는다 |
| `X-Forwarded-For` 위조로 횟수 제한 우회 | Phase 4 전까지 헤더를 믿지 않는다 |
| CloudFront 뒤에서는 모든 요청이 CloudFront IP 라 IP 별 제한이 사이트 전체 제한이 된다(ondolith D86 1.12) | **Phase 4 배포 전 필수**: 믿을 프록시에서 온 연결만 헤더의 클라이언트 IP 를 쓴다 |
| 세션 표가 커진다 | Spring Session 만료 정리(1분 주기). 측정 때 DB 메모리를 함께 본다 |
