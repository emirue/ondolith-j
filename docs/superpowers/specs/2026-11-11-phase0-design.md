# Phase 0 설계 — 설치, 뼈대, CI, 보안 기본값

작성 2026-11-11. 사용자가 브레인스토밍에서 절마다 승인한 내용을 옮겼다.
근거: 요청서 `docs/request-core.md`, 결정 `docs/decisions.md`, 처리표 `docs/appendix-a.md`·`docs/appendix-b.md`. 기능 상세의 단일 출처는 ondolith 원문이다.

## 0. 범위

| 넣는다 | 뺀다(해당 Phase 로) |
|---|---|
| 측정 앱을 `bench/` 로 옮김 | 로그인·세션 저장소·RBAC·rate limit·클라이언트 IP 판정 (Phase 1) |
| `mise.toml`·`mise.lock`, 백엔드·프런트 뼈대 | 테마 토큰 API (Phase 1, D-1) |
| 설치(FR-101~110, P-001)와 설치→운영 전환, 전환 중 대기 화면 | 공개 HTML 메타 주입 (D-2, 공개 화면이 생기는 Phase) |
| 운영 컨텍스트의 `/api/health`·`/api/site`, 홈 임시 화면 | 원본 우회 차단·CloudFront 구성 (Phase 4, NFR-106) |
| 보안 기본값(CSRF·쿠키·헤더·오류 형식) | `dangerouslySetInnerHTML`·`localStorage` 금지 검사 (그 위험이 처음 생기는 Phase) |
| CI, GitHub 보안 설정, `SECURITY.md` | |

**완료 기준**: 빈 DB 와 jar·프런트로 시작해 브라우저에서 설치 폼을 내면 대기 화면을 거쳐 홈에 사이트 이름이 보인다. 이 흐름이 Playwright 로 자동 검증되고, 아래 5절의 시험이 CI 에서 모두 통과한다.

## 1. 저장소 구조와 기술 스택

```
bench/            측정 앱(src/back 에서 git mv) + measure.sh 경로 수정
src/back/         코어 백엔드 (Spring Boot, Maven)
src/front/        코어 프런트 (React SPA)
mise.toml, mise.lock   Java 25, Node 26.13.0
docs/             요청서, 결정, 부록, 스펙, 계획
```

- 백엔드 패키지는 도메인 단위. Phase 0 은 `install`, `ops`(헬스·사이트 정보), `shared`(보안·오류 형식)만 만든다.
- 프런트 폴더는 기능 단위. Phase 0 은 `install`, `home`, `shared`(ui·api·lib).

판(2026-11-11, 공식 저장소에서 확인한 최신 안정판. Java·Node 는 최신 LTS):

| 영역 | 선택 | 판 | 확인한 곳 |
|---|---|---|---|
| 런타임 | Java(Temurin) / Node | 25 / 26.13.0 | Adoptium API / nodejs.org |
| 백엔드 | Spring Boot (Spring Security 7.1 계열) | 4.1.2 | start.spring.io |
| 빌드 | Maven(Maven Wrapper `./mvnw`) | 3.10.0 (Wrapper 3.3.4) | start.spring.io 뼈대·Maven Central(2026-11-12) |
| 웹·검증·보안 | `spring-boot-starter-webmvc`(내장 Tomcat)·`-validation`·`-security` | Boot 관리 | Boot 4.1 문서 |
| 코드 줄이기 | Lombok(JDK 25 지원은 1.18.40 부터) | 1.18.48 (Boot 관리) | Boot 4.1.2 BOM·Lombok 변경 기록 |
| DB | Spring Data JPA, Flyway, PostgreSQL 18 | Boot 관리 | Boot 4.1 문서 |
| API 문서 | springdoc-openapi | 3.2.0 | Maven Central |
| DB 시험 | Testcontainers PostgreSQL | 2.0.5 | Maven Central |
| 프런트 | React / Vite / TypeScript(strict) | 19.3.1 / 8.3.8 / 7.0.2 | npm |
| 라우팅·상태 | React Router / TanStack Query / Zustand | 8.5.1 / 5.107.1 / 5.1.0 | npm |
| 스타일·폼 | Tailwind CSS / React Hook Form + Zod | 4.3.5 / 7.92.0 + 4.6.7 | npm |
| UI 컴포넌트 | shadcn/ui(Radix 기반 `radix-ui`, CLI 가 소스를 복사) | CLI 4.23.0 / 1.8.1 | npm(2026-11-12) |
| API 타입 | openapi-typescript + openapi-fetch | 7.13.0 / 0.17.0 | npm |
| 프런트 시험 | Vitest + Testing Library / Playwright | 5.1.0 + 16.3.3 / 1.65.0 | npm |

- 호환은 뼈대를 만들 때 실제 빌드로 확인한다(특히 springdoc 3.2 ↔ Boot 4.1, TypeScript 7 ↔ 도구들). 안 맞으면 맞는 최신 안정판 조합으로 내리고 이유를 이 표 아래에 적는다.
- 의존성마다 추가 전에 OSV 로 취약점을 확인한다. 프런트는 `package-lock.json` 을 커밋한다. Maven 은 잠금 파일이 없으므로 `pom.xml` 에 판을 명시하고 판 범위 표기를 쓰지 않는다(Boot 가 관리하는 것은 Boot BOM 판을 따른다).

백엔드 기본 선택(2026-11-12 사용자 승인 — 「보편적인 방향」):

| 항목 | 선택 | 메모 |
|---|---|---|
| 웹 | Spring MVC. WebFlux 는 쓰지 않는다 | JPA 가 블로킹이다 |
| 입력 검증 | Bean Validation(`spring-boot-starter-validation`) | 설치 API 의 422 칸별 오류 |
| JSON | Jackson 3(Boot 기본) | |
| 비밀번호 | Spring Security `BCryptPasswordEncoder` | |
| 가상 스레드 | 끔(Boot 기본) | 메모리 재측정 때 켠 경우도 함께 잰다 |
| 헬스 | Actuator 없이 `/api/health` 직접 | 노출면을 줄인다 |
| 로그 | Logback 기본 텍스트 | 구조화 로그는 필요할 때 설정만 켠다 |
| 시험 | JUnit + AssertJ + `MockMvcTester`, 실제 서버는 `RestTestClient` + Testcontainers | Boot 4.1 제공 |
| Lombok | 쓴다. DTO 는 `record`, 엔티티·설정 클래스의 반복 코드에 Lombok | |

WAS(내장 서버):

| 항목 | Phase 0 | 메모 |
|---|---|---|
| 서버 | 내장 Tomcat(Boot 기본). 별도 WAS 설치 없음 | Boot 4.1 지원: Tomcat·Jetty·Reactor Netty |
| 듣는 주소·포트 | 기본 `8080`, `--server.port`·`--server.address` 로 바꾼다 | ondolith `-addr :8080` 과 같다 |
| 정상 종료 | 기본값(켜짐) | 전환·재시작 때 처리 중 요청을 마친다 |
| 스레드 수 | Tomcat 기본값 | 측정과 같은 조건. 바꾸면 다시 잰다 |
| Phase 4 로 | CloudFront→원본 TLS·인증서, `X-Forwarded-*` 신뢰(원본 우회 차단과 짝), JVM 힙·스택(측정 J1·J2, D-4), systemd 유닛, 응답 압축 위치 | 업로드 크기(기본 파일 1MB·요청 10MB)는 업로드 Phase |
- shadcn/ui: Phase 0 은 Button·Input·Label 만 들이고, 나머지 컴포넌트는 쓰는 Phase 에서 하나씩 추가한다. 복사 위치는 `components.json` 별칭으로 `shared/ui` 에 맞춘다. 색·반경은 `:root` CSS 변수로 둬서 D-1 의 런타임 테마 토큰이 그 변수만 바꾸게 한다. CLI 가 함께 설치하는 패키지는 뼈대를 만들 때 확인해 잠금 파일에 남긴다.
- 서버 상태는 TanStack Query 에만, Zustand 는 UI 상태에만 둔다. 화면 컴포넌트는 직접 fetch 하지 않고 기능별 훅을 거친다.

## 2. 설치 흐름 — 같은 프로세스 안의 컨텍스트 교체

원칙(ondolith D20): 설치 전과 설치 후는 완전히 다른 라우트 트리다. 하나의 라우터에 「설치됐나」 분기를 넣지 않는다.

### 2.1 기동

```
java -jar → 설정 파일 확인 (기본 ./ondolith-j.properties, --config 로 변경)
 ├─ 없음  → 설치 컨텍스트 (DataSource·JPA·Flyway 자동 구성 제외)
 ├─ 깨짐  → 오류 한 줄 + exit 1                         (FR-110)
 └─ 정상  → 운영 컨텍스트 (Flyway 자동 마이그레이션, NFR-302)
```

- 「깨짐」= 파싱 실패, 필수 키 없음, 형식 오류(URL·키 길이 등). properties 형식은 거의 무엇이든 읽히므로 명시적으로 검증한다.
- 설정 파일 키: DB 접속(URL·사용자·비밀번호), `site_url`, secure 쿠키, 봉인 키(`SecureRandom` 32바이트, base64), 업로드·백업 폴더(비우면 설정 파일 옆), 설치 시각(UTC).
- 쓰기는 0600 권한으로 만든 임시 파일에 쓴 뒤 `rename`(원자적).
- 사이트 이름·유형은 관리자가 바꾸는 값이라 DB(`settings`)에 둔다.

### 2.2 설치 API (설치 컨텍스트에만 있다)

- `GET /api/health` → `{state: "install" | "running", version}`. **두 컨텍스트에 모두 있는 유일한 API.**
- `POST /api/install` (CSRF 적용). 입력은 ondolith D19 P-001 을 따른다:

| 필드 | 필수 | 검증 |
|---|:---:|---|
| `db_host` | ✔ | 최대 255자, 공백 불가. 접속 시도로 최종 검증 |
| `db_port` | ✔ | 1~65535 정수, 기본 5432 |
| `db_user` | ✔ | 최대 63자 |
| `db_password` | | 최대 255자. 화면에 다시 보이지 않고 설정 파일에만 기록 |
| `db_name` | ✔ | 최대 63자 |
| `db_sslmode` | ✔ | 허용목록 `disable·require·verify-ca·verify-full` |
| `site_name` | ✔ | 1~100자 |
| `site_type` | ✔ | `cms·shop` 둘만, 기본 `cms` (FR-710) |
| `site_url` | ✔ | http·https 절대 주소. 프런트가 현재 주소로 미리 채우고 사용자가 확인한다. 요청의 Host 로 만들지 않는다 |
| `admin_email` | ✔ | 형식, 최대 254자, 소문자화 |
| `admin_password` | ✔ | 10자 이상(NFR-208), 최대 72바이트. 확인 칸 일치는 프런트 편의 + 서버 검증 |

- 받지 않는 것(D19): 역할·관리자 플래그, 설정 파일 경로, 설치됨 플래그, 마이그레이션 버전·건너뛰기, 콘솔 토큰(FR-109).
- 처리 순서 — **DB 를 바꾸기 전에 거부할 것은 모두 거부한다**:
  1. 입력 검증 → 422(칸별 오류, Problem Details). D19 는 400 이지만 요청서 5절의 「422 는 폼 필드 오류」를 따른다.
  2. 설정 파일 자리에 0600 임시 파일을 먼저 써 본다 → 실패 시 500 + 경로와 OS 오류 원문. DB 를 건드리기 전에 확인해야, 관리자는 만들어졌는데 설정 파일이 없어 재시도가 「이미 계정이 있음」에 막히는 상태가 생기지 않는다.
  3. DB 접속 → 실패 시 400 + 드라이버 오류 원문(비밀번호·DSN 제외) (FR-107, NFR-210 의 유일한 예외).
  4. 사용 중 감지(FR-108) → 400 「이미 사용 중인 데이터베이스입니다」 + 감지 근거:
     - 마이그레이션 이력 표가 없는데 스키마가 비어 있지 않음(Flyway 도 기본값 `baselineOnMigrate=false` 로 이 경우 멈춘다);
     - 이력 표가 있고 같은 이메일 계정이 있음.
  5. 마이그레이션 적용 → 실패 시 400 + 실패한 파일 이름 + 원문.
  6. 한 트랜잭션에서: 기본 설정(사이트 이름·유형) → 관리자 계정(bcrypt, FR-104) + 관리자 역할. 둘은 함께 성립하거나 함께 없다.
  7. 임시 설정 파일에 내용을 쓰고 `rename` (FR-105). **이 순간이 설치 완료다.** 이후 설치 API 는 닫힌다(재요청 404). 전환이 실패해도 다시 열지 않는다 — 다시 열면 포트에 닿는 누구나 자기 DB 로 설정을 덮어쓴다. 전환 실패는 재시작으로 푼다(ondolith 설치 코드와 같다).
  8. **202** 응답.
- 동시 요청은 하나만 진행하고 나머지는 409.
- DB 비밀번호와 DSN 은 화면에도 로그에도 남기지 않는다(D19 C5).

### 2.3 전환

- 202 응답을 보낸 뒤 별도 스레드가 설치 컨텍스트를 닫고 같은 포트로 운영 컨텍스트를 띄운다(FR-106).
- 운영 컨텍스트가 뜨지 못하면 오류를 남기고 exit 1. 설정 파일이 이미 있으므로 다음 기동은 운영 모드다.

### 2.4 운영 컨텍스트 (Phase 0 범위)

- `GET /api/health`, `GET /api/site`(사이트 이름·유형). `/api/install` 은 없다(404).

### 2.5 프런트 — 첫 진입과 전환 중 대기 화면

**제약: 설치 화면과 대기 화면은 Spring 없이 뜰 수 있어야 한다.** 전환 중에는 Spring 이 응답하지 않으므로, 그동안 HTML 껍데기를 Spring 이 내면 대기 화면을 보일 수 없다.
- 개발·E2E: Vite 가 껍데기와 자산을 내고 `/api` 를 jar 로 넘긴다(Vite proxy).
- 운영: D-3 기록의 「HTML 문서 요청은 모두 Spring 으로」는 이 제약과 부딪힌다. CloudFront 의 오류 응답을 S3 껍데기로 돌리는 방법, 공개 콘텐츠 경로만 Spring 으로 보내는 방법 등을 **Phase 4 에서 AWS 문서로 확인해 정한다(미확인).** 설치 컨텍스트는 S3 위치를 모르므로 껍데기를 낼 수 없다는 점도 같이 푼다.

- 첫 진입에서 `/api/health` 확인: `install` → 설치 화면, `running` → 홈(사이트 이름을 보이는 임시 화면), 응답 없음 → 대기 화면.
- 설치 폼 제출 후 202 를 받으면 「사이트를 준비하고 있습니다」 대기 화면:
  - 1초마다 health 확인. 연결 실패·5xx·`install` 은 「아직 전환 중」.
  - `running` 이면 홈으로 이동.
  - 60초가 지나면 「오래 걸립니다. 서버 로그를 확인하세요」와 다시 시도 버튼.
  - 상태 문구는 `aria-live` 로 읽히고, 화면이 바뀌면 포커스를 옮긴다.
- 전환 중 새로고침해도 같은 대기 화면이 나온다.
- 프런트 분기는 편의다. 막는 것은 API 다.
- 설치 화면의 서버 검증 오류(422)는 해당 칸 옆에, DB 접속 실패 원인은 폼 위에 보인다. 클라이언트 검증(Zod)은 편의이고 서버 검증이 기준이다.

## 3. 보안 기본값

| 항목 | 내용 |
|---|---|
| CSRF | 두 컨텍스트 모두 Spring Security 의 `csrf.spa()` (7.1 공식 문서). 프런트 API 클라이언트가 `XSRF-TOKEN` 쿠키를 `X-XSRF-TOKEN` 헤더로 보낸다 |
| 세션 쿠키 | HttpOnly, SameSite=Lax, `site_url` 이 https 면 Secure 로 설정만 해 둔다. Phase 0 은 세션을 만들지 않으므로 세 플래그 단언 시험은 로그인이 생기는 Phase 1 에서 한다 |
| 응답 헤더 | 프레임 금지, `nosniff`, `Referrer-Policy`, API 응답 CSP `default-src 'none'; frame-ancestors 'none'` |
| 오류 응답 | Problem Details(`spring.mvc.problemdetails.enabled`). 스택·내부 메시지를 내보내지 않는다. 예외는 설치의 DB 접속 실패 원인뿐 |
| 비밀 값 | 비밀번호는 bcrypt. 봉인 키는 설정 파일(0600)에만. 비밀번호를 로그에 남기지 않는다 |
| 설치 노출 | 설치 전에는 누구나 설치 API 에 닿는다. 설치 안내에 ondolith D71 의 「방화벽으로 막고 설치」를 넣는다 |

## 4. 개발 환경과 CI

로컬:
- `mise.toml` 도구: Java 25(Temurin), Node 26.13.0. 작업: `mise run test`(셸·백엔드·프런트), `mise run db`(개발용 PostgreSQL 컨테이너), `mise run e2e`.
- CPU·메모리: Maven 은 상주 데몬이 없다. `.mvn/jvm.config` 에 Maven JVM 메모리 상한을 두고, 무거운 작업은 하나씩 `nice -n 19` 로 돌린다. 끝나면 개발 서버·브라우저를 끈다.

CI(GitHub Actions):
- `pull_request` 트리거만, `permissions: contents: read`, Action 은 `actions/checkout`·`jdx/mise-action` 만 커밋 SHA 고정.
- 작업: 셸 시험 / 백엔드 시험(Testcontainers) / 프런트 타입 검사·시험·빌드 / OpenAPI 타입 재생성 후 차이 있으면 실패 / Playwright E2E.
- CI 를 넣을 때 사용자 승인된 GitHub 설정을 적용한다: private vulnerability reporting·Dependabot alerts 켜기, `main` 보호(force push·삭제 금지, PR 로만, CI 통과 필수). `SECURITY.md` 는 GitHub 비공개 제보만 안내한다.

## 5. 검증

| 무엇 | 어떻게 |
|---|---|
| 설정 파일: 없음/깨짐(exit 1)/정상, 0600, 원자적 쓰기 | 단위 시험 |
| 설치: bcrypt, 사용 중 DB 거부(두 경우, DB 를 바꾸기 전), 설정 파일 자리에 못 쓰면 DB 를 건드리지 않음, DB 접속 실패 원문(비밀번호 없음), 422, 409, 설치 뒤 재요청 404, CSRF 없으면 403 | Testcontainers 실제 PostgreSQL |
| 실패한 마이그레이션 자동 원복(NFR-303) | 시험 전용으로 일부러 실패하는 마이그레이션을 넣고 Testcontainers 실제 PostgreSQL 에 적용 → 스키마·이력 표가 그대로, 운영 컨텍스트가 뜨지 않음(설치 중이면 400) |
| 라우트: 설치 컨텍스트엔 설치 API(+health)만, 운영 컨텍스트엔 설치 API 없음 | 두 컨텍스트에 요청 |
| 전환(같은 JVM) | 설치 모드로 기동 → 설치 요청 → health 가 `running` 이 될 때까지 확인 → `/api/install` 404, `/api/site` 200. JUnit 의 JVM 은 스스로 살아 있으므로 이것만으로는 「프로세스가 두 컨텍스트 사이에서 죽지 않는다」를 증명하지 못한다 |
| 보안 기본값 | 응답 헤더, 오류 본문에 내부 정보 없음, 로그에 비밀번호·DSN 없음 |
| 대기 화면: 전환 중/성공/60초 초과 | Vitest 컴포넌트 시험(fetch 대역) |
| 브라우저 전체 흐름(**필수**) | Playwright: `java -jar` 로 띄운 실제 jar + DB + Vite 껍데기. 첫 화면 → 설치 화면 → 폼 → 대기 화면이 보임 → 홈에 사이트 이름. 키보드만으로도. 실제 프로세스가 전환을 살아 넘기는지는 이 시험만 증명한다 |
| 측정 앱 이동 | `bench/` 이동 뒤 셸 시험 통과, `measure.sh` 가 새 경로를 쓴다 |

TDD: 동작마다 실패하는 시험을 먼저 보고(Red) 구현한다. 무거운 시험(Testcontainers·Playwright)은 한 번에 하나씩 돌린다.

## 6. 위험과 확인할 것

- 마이그레이션 되돌리기(NFR-303, 2026-11-12 결정): Flyway 무료판으로 앞으로만 간다. 실패하면 트랜잭션으로 자동 원복되고, 되돌리기는 백업 복원이다(`docs/decisions.md`). 트랜잭션 밖에서만 도는 문장(예: `CREATE INDEX CONCURRENTLY`)은 자동 원복이 안 되므로 쓰면 CHANGELOG 에 표시한다.

- 같은 포트로 컨텍스트를 다시 띄울 때 포트 해제 시점: 전환 시험으로 확인한다.
- `csrf.spa()` 의 첫 토큰 발급 시점(지연 로딩): 프런트가 첫 POST 전에 토큰 쿠키를 받는지 시험으로 확인한다.
- 판 호환(1절)은 뼈대 빌드에서 확인한다.

## 7. 후속 Phase 기술 후보 (확정 아님)

ondolith `go.mod` 의 의존성을 Java 로 옮긴 후보다. 각 Phase 스펙에서 판·유지 상태·취약점을 다시 확인하고 정한다. 쓰지 않을 의존성을 미리 넣지 않는다. 갱신일은 2026-11-12 Maven Central 기준.

| ondolith(Go) | 용도 | Java 후보 | Phase |
|---|---|---|---|
| scs + pgxstore | 세션 DB 저장 | Spring Session JDBC(Boot 관리) | 1 |
| goth, x/oauth2 | 소셜 로그인 | `spring-boot-starter-oauth2-client` | 1 이후 |
| bluemonday, x/net/html | 본문 HTML 정화 | OWASP Java HTML Sanitizer(2026-11) 또는 jsoup(2026-11) | 글쓰기 |
| x/image | 썸네일, WebP 읽기 | JDK ImageIO + TwelveMonkeys imageio-webp(2026-10) | 업로드 |
| rsc.io/qr | 재고 스티커 QR(SVG) | ZXing core(2025-11) | 쇼핑 |
| aws-sdk-go-v2 s3 | 업로드 파일 S3 저장 | AWS SDK for Java 2.x | 업로드 |
| net/smtp | 메일 | `spring-boot-starter-mail` | 1 이후 |
| net/http | 결제사·웹훅 호출 | Spring `RestClient` | 쇼핑 |
| 내부 스케줄러 | 자동 백업, 기한 만료 | `@Scheduled`(NFR-103) | 해당 기능 |
| — | 요청 제한 / 캐시 | Bucket4j(2026-11) / Caffeine(2026-10) | 1 / NFR-104 |
