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
src/back/         코어 백엔드 (Spring Boot, Gradle Kotlin)
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
| DB | Spring Data JPA, Flyway, PostgreSQL 18 | Boot 관리 | Boot 4.1 문서 |
| API 문서 | springdoc-openapi | 3.2.0 | Maven Central |
| DB 시험 | Testcontainers PostgreSQL | 2.0.5 | Maven Central |
| 프런트 | React / Vite / TypeScript(strict) | 19.3.1 / 8.3.8 / 7.0.2 | npm |
| 라우팅·상태 | React Router / TanStack Query / Zustand | 8.5.1 / 5.107.1 / 5.1.0 | npm |
| 스타일·폼 | Tailwind CSS / React Hook Form + Zod | 4.3.5 / 7.92.0 + 4.6.7 | npm |
| API 타입 | openapi-typescript + openapi-fetch | 7.13.0 / 0.17.0 | npm |
| 프런트 시험 | Vitest + Testing Library / Playwright | 5.1.0 + 16.3.3 / 1.65.0 | npm |

- 호환은 뼈대를 만들 때 실제 빌드로 확인한다(특히 springdoc 3.2 ↔ Boot 4.1, TypeScript 7 ↔ 도구들). 안 맞으면 맞는 최신 안정판 조합으로 내리고 이유를 이 표 아래에 적는다.
- 의존성마다 추가 전에 OSV 로 취약점을 확인하고, 잠금 파일(`package-lock.json`, Gradle 의존성 잠금)을 커밋한다.
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
- `POST /api/install` (CSRF 적용): DB 접속 정보, 관리자 이메일·비밀번호, 사이트 이름, 사이트 유형(일반/쇼핑몰), `site_url`.
  - `site_url` 은 프런트가 현재 주소로 미리 채우고 사용자가 확인한다. 요청의 Host 로 만들지 않는다(재설정 링크 오염 방지).
- 처리 순서:
  1. 입력 검증 → 실패 시 422(칸별 오류, Problem Details).
  2. DB 접속 → 실패 시 원인 메시지를 그대로 보인다(FR-107, NFR-210 의 유일한 예외).
  3. 마이그레이션 적용.
  4. 같은 이메일의 계정이 있으면 거부(FR-108).
  5. 관리자 생성(bcrypt, FR-104), 사이트 이름·유형 저장.
  6. 설정 파일 기록(FR-105).
  7. **202** 응답.
- 동시 요청은 하나만 진행하고 나머지는 409.
- 그 밖의 경로는 404. 콘솔 토큰을 요구하지 않는다(FR-109).

### 2.3 전환

- 202 응답을 보낸 뒤 별도 스레드가 설치 컨텍스트를 닫고 같은 포트로 운영 컨텍스트를 띄운다(FR-106).
- 운영 컨텍스트가 뜨지 못하면 오류를 남기고 exit 1. 설정 파일이 이미 있으므로 다음 기동은 운영 모드다.

### 2.4 운영 컨텍스트 (Phase 0 범위)

- `GET /api/health`, `GET /api/site`(사이트 이름·유형). `/api/install` 은 없다(404).

### 2.5 프런트 — 첫 진입과 전환 중 대기 화면

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
| 세션 쿠키 | HttpOnly, SameSite=Lax, `site_url` 이 https 면 Secure |
| 응답 헤더 | 프레임 금지, `nosniff`, `Referrer-Policy`, API 응답 CSP `default-src 'none'; frame-ancestors 'none'` |
| 오류 응답 | Problem Details(`spring.mvc.problemdetails.enabled`). 스택·내부 메시지를 내보내지 않는다. 예외는 설치의 DB 접속 실패 원인뿐 |
| 비밀 값 | 비밀번호는 bcrypt. 봉인 키는 설정 파일(0600)에만. 비밀번호를 로그에 남기지 않는다 |
| 설치 노출 | 설치 전에는 누구나 설치 API 에 닿는다. 설치 안내에 ondolith D71 의 「방화벽으로 막고 설치」를 넣는다 |

## 4. 개발 환경과 CI

로컬:
- `mise.toml` 도구: Java 25(Temurin), Node 26.13.0. 작업: `mise run test`(셸·백엔드·프런트), `mise run db`(개발용 PostgreSQL 컨테이너), `mise run e2e`.
- CPU·메모리: `gradle.properties` 에 `org.gradle.workers.max=2` 와 Gradle JVM 메모리 상한. 무거운 작업은 하나씩 `nice -n 19`. 끝나면 Gradle 데몬·개발 서버·브라우저를 끈다.

CI(GitHub Actions):
- `pull_request` 트리거만, `permissions: contents: read`, Action 은 `actions/checkout`·`jdx/mise-action` 만 커밋 SHA 고정.
- 작업: 셸 시험 / 백엔드 시험(Testcontainers) / 프런트 타입 검사·시험·빌드 / OpenAPI 타입 재생성 후 차이 있으면 실패 / Playwright E2E.
- CI 를 넣을 때 사용자 승인된 GitHub 설정을 적용한다: private vulnerability reporting·Dependabot alerts 켜기, `main` 보호(force push·삭제 금지, PR 로만, CI 통과 필수). `SECURITY.md` 는 GitHub 비공개 제보만 안내한다.

## 5. 검증

| 무엇 | 어떻게 |
|---|---|
| 설정 파일: 없음/깨짐(exit 1)/정상, 0600, 원자적 쓰기 | 단위 시험 |
| 설치: bcrypt, 같은 이메일 거부, DB 접속 실패 메시지, 422, 409, CSRF 없으면 403 | Testcontainers 실제 PostgreSQL |
| 라우트: 설치 컨텍스트엔 설치 API(+health)만, 운영 컨텍스트엔 설치 API 없음 | 두 컨텍스트에 요청 |
| 실제 전환 | 설치 모드로 기동 → 설치 요청 → health 가 `running` 이 될 때까지 확인 → `/api/install` 404, `/api/site` 200 |
| 보안 기본값 | 쿠키 세 플래그, 응답 헤더, 오류 본문에 내부 정보 없음, 로그에 비밀번호 없음 |
| 대기 화면: 전환 중/성공/60초 초과 | Vitest 컴포넌트 시험(fetch 대역) |
| 브라우저 전체 흐름 | Playwright: 실제 jar + DB. 첫 화면 → 설치 화면 → 폼 → 대기 화면이 보임 → 홈에 사이트 이름. 키보드만으로도 |
| 측정 앱 이동 | `bench/` 이동 뒤 셸 시험 통과, `measure.sh` 가 새 경로를 쓴다 |

TDD: 동작마다 실패하는 시험을 먼저 보고(Red) 구현한다. 무거운 시험(Testcontainers·Playwright)은 한 번에 하나씩 돌린다.

## 6. 위험과 확인할 것

- 같은 포트로 컨텍스트를 다시 띄울 때 포트 해제 시점: 전환 시험으로 확인한다.
- `csrf.spa()` 의 첫 토큰 발급 시점(지연 로딩): 프런트가 첫 POST 전에 토큰 쿠키를 받는지 시험으로 확인한다.
- 판 호환(1절)은 뼈대 빌드에서 확인한다.
