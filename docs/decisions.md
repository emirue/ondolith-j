# 결정 기록

요청서(`docs/request-core.md`) 2절의 결정 항목에 사용자가 답한 것. 스펙을 쓸 때 이 내용을 옮긴다.

## D-1 테마 — (가) 런타임 토큰·레이아웃 설정 (2026-11-11)

- 테마는 색·글꼴·간격 토큰 + 코어가 미리 만든 레이아웃 변형 선택 + `admin.json` 같은 선언이다. API 가 내려주고 SPA 가 런타임에 적용한다. 재빌드 없이 바뀐다.
- 업로드한 JS 를 실행하는 테마(안 나)는 만들지 않는다.
- 그 이상의 자유도는 React 소스를 직접 고치는 길로 얻는다. 주 사용자가 사용자 본인(개발자)일 가능성이 높아서, 코드 수준의 자유도는 높아도 된다.
- FR-301~313 영향(사용자에게 보인 표): 304 함수맵은 없어지고, 301·302·306·307·308·312 는 바뀌고, 303·305·309·310·311·313 은 유지된다.

## D-2 검색 노출·링크 미리보기 — ① 서버가 HTML 껍데기에 메타만 넣는다 (2026-11-11)

- 공개 화면의 HTML 문서 요청은 CloudFront 가 Spring 으로 보낸다. Spring 은 빌드된 `index.html` 에 그 주소의 제목·OG·canonical·JSON-LD 를 넣어 돌려준다. JS·CSS 자산은 S3 에서 받는다.
- 사람과 로봇에게 같은 HTML 을 준다(로봇만 따로 렌더링하지 않는다).
- `sitemap.xml`·`robots.txt` 는 API 가 동적으로 낸다.
- 근거: Google 은 JS 를 렌더링하지만 서버 렌더링·사전 렌더링을 권하고 dynamic rendering 은 임시방편이라 한다(Google Search Central). 카카오·페이스북 스크랩봇의 JS 실행 여부와 네이버의 SPA 수집 지침은 공식 문서로 확인하지 못했다. 배포 뒤 카카오 공유 디버거로 확인한다.
- 지킬 것:
  - `<head>` 와 JSON-LD 에 넣는 제목·설명은 사용자가 쓴 값이다. React 이스케이프가 닿지 않으니 서버에서 문맥(HTML 속성·JSON-LD 의 `</script>`)에 맞게 이스케이프한다.
  - 비로그인 방문자가 볼 수 있는 값만 넣는다. 초안·비밀글이 메타로 새지 않는다.
  - 공개 HTML 응답이 세션에 따라 달라지는 형태로 캐시되지 않게 한다.

## D-3 배포 단위 — (b) 프런트는 S3 에만 둔다 (2026-11-11)

- 설치·배포하는 사람은 개발자다(비개발자 운영자가 아니다). AWS 없이 도는 길을 따로 만들지 않는다.
- 산출물은 둘이다: 서버용 jar(Lightsail), 프런트 묶음(S3 + CloudFront). jar 에 프런트 빌드를 넣지 않는다.
- D-2 ① 때문에 Spring 이 S3 의 `index.html` 껍데기를 가져와 메타를 넣는다. 가져오는 방법과 jar·프런트 판 어긋남 처리는 스펙에서 정한다.
- NFR 재작성 (사용자 승인):

| 원문 | 새 문장 | 통과 기준 |
|---|---|---|
| NFR-102 | 서버에 설치할 것은 JRE 와 PostgreSQL 뿐. 프런트는 S3 에 올린다 | 서버에 jar 하나 + 설정 파일, AWS 에 S3 버킷 하나 + CloudFront 배포 하나 |
| NFR-103 | 크론·큐는 같은 JVM 안의 스케줄러로 돈다 | 별도 프로세스·systemd 유닛·외부 큐가 없다 |
| NFR-106 | CloudFront 뒤에서 같은 출처로 동작한다. Nginx 등 별도 프록시는 필요 없다 | CloudFront 를 거치지 않은 요청을 API 가 거부한다 |
| NFR-301 | ① 새 프런트 묶음을 S3 에 올린다(이전 자산 유지) ② jar 교체 + 재시작 | 업그레이드 문서가 3단계를 넘으면 실패 |
| NFR-304 | 업그레이드가 설정·업로드·테마 값을 건드리지 않는다. 교체 대상은 jar 와 S3 자산뿐 | 업그레이드 앞뒤로 설정 파일·업로드·테마 값이 같다 |
| NFR-305 | jar 와 프런트 묶음이 각자 버전을 보고한다 | `--version`, 헬스 API 가 두 버전을 함께 보인다 |
| NFR-306 | JVM 이라 크로스 컴파일이 필요 없다 | linux/amd64·arm64 JRE 에서 같은 시험이 통과한다 |
| FR-101~110 | 미설치면 API 에는 설치 API 만 있다. 프런트가 S3 에 있어야 마법사가 시작된다. 나머지는 원문 그대로 | 원문 기준 + 미설치 때 설치 API 밖의 경로는 404 |

- 자산을 먼저 올리고 jar 를 나중에 바꾸는 순서, HTML 문서 요청을 모두 Spring 으로 보내 CloudFront 무효화를 없애는 방식은 설계 판단이다. 스펙에서 AWS 문서로 확인한다.
  - **수정(Phase 0 스펙 2.5)**: 「HTML 문서 요청을 모두 Spring 으로」는 설치·전환 중 대기 화면(Spring 이 없을 때 떠야 함)과 부딪힌다. 운영 경로는 Phase 4 에서 AWS 문서로 정한다.

## D-4 Lightsail 티어 — 보류 (2026-11-11)

- 실제로 설치해 시험한 뒤 정한다. 사용자와 Claude 모두 1GB 는 돼야 한다고 본다.
- 근거: NFR-101 기준(유휴가 티어의 절반 이하)에 측정값을 대면 512MB 는 224.5–226.1 MiB 로 숫자상 통과하지만 앱이 상한 180m 에 붙어 있고, 1GB 는 303.9–314.7 MiB 로 여유 있게 통과한다(`docs/measure-lightsail.md`). 측정 앱은 API 두 개라 코어는 더 클 수 있다.

## NFR-303 마이그레이션 되돌리기 — (다) 앞으로만 가고, 되돌리기는 백업 복원 (2026-11-12)

- 도구는 Flyway 무료판(FR-103). Flyway 의 undo 실행은 유료판(Teams 이상)에만 있어 쓰지 않는다.
- 근거: ondolith 도 `-- +goose Down` 을 적기만 하고 실행하는 코드·명령·시험이 없다. 실제 다운그레이드 경로는 백업 복원이다(ondolith D72, NFR-308). 실행하지 않는 Down SQL 을 옮겨 쓰지 않는다.
- Flyway 는 마이그레이션마다 트랜잭션을 쓰고 PostgreSQL 은 DDL 도 트랜잭션 안에서 돈다. 그래서 도중에 실패한 마이그레이션은 자동으로 원복된다(Flyway FAQ).
- 버린 안: (가) Flyway + 직접 쓴 Down SQL(실행 경로가 문서뿐), (나) Liquibase(5.0 부터 FSL — 소스 공개지만 OSI 오픈소스가 아니다).
- NFR 재작성 (사용자 승인):

| 원문 | 새 문장 | 통과 기준 |
|---|---|---|
| NFR-303 | 마이그레이션은 앞으로만 간다. 실패하면 자동 원복되고, 되돌리기는 업그레이드 전 백업 복원으로 한다 | 일부러 실패하는 마이그레이션을 실제 PostgreSQL 에 넣으면 스키마·이력이 그대로이고 앱이 운영 모드로 뜨지 않는다 |

- 업그레이드 문서는 ① 백업 ② 프런트 묶음을 S3 에 올림 ③ jar 교체 + 재시작이 된다(D-3 의 NFR-301 「3단계를 넘으면 실패」 안). 데이터가 사라지거나 트랜잭션 밖에서만 도는 마이그레이션(예: `CREATE INDEX CONCURRENTLY`)은 CHANGELOG 에 표시한다(NFR-307·308).

## 스펙 질문 (Phase 0 브레인스토밍, 2026-11-11)

- 경로: 측정 앱을 `bench/` 로 옮기고(`git mv`), 코어는 `src/back`(백엔드)·`src/front`(프런트). 옮기는 일은 Phase 0 계획의 첫 작업.
- Java: 최신 LTS 25 를 쓴다(최신 GA 는 27 이지만 LTS 가 아니다 — Adoptium API). Spring Boot 는 최신 GA 4.1.2(start.spring.io 기본값, 4.2.0 은 RC2).
- 도구 판 관리: mise(`mise.toml` + `mise.lock`, CI 는 `jdx/mise-action` 을 SHA 로 고정). Maven 은 Maven Wrapper, 백엔드 라이브러리는 `pom.xml` 의 명시 판(Maven 은 잠금 파일이 없다), 프런트는 `package-lock.json`, PostgreSQL 은 Docker 이미지 태그.
- 빌드 도구(2026-11-12): 코어 백엔드는 **Maven**. 사용률 1위(JetBrains State of Java 2025, 67%), GitHub 의존성 그래프가 `pom.xml` 만으로 간접 의존성까지 보고(Gradle 은 정적으로 못 본다), 상주 데몬이 없다. 대가: Gradle 의 잠금 파일·체크섬 검증이 없다. 측정 앱(`bench/`)은 측정 조건 고정을 위해 Gradle 그대로.
- 백엔드 기본 선택(2026-11-12, 「보편적인 방향」): Spring MVC(내장 Tomcat), Bean Validation, Lombok 사용, 가상 스레드 끔(Boot 기본), Actuator 없이 헬스 직접. 상세와 WAS 표는 Phase 0 스펙 1절, 후속 Phase 후보는 7절.
- 사전 측정 뒤(2026-11-12, `docs/measure-lightsail.md` 「코어 스택 사전 측정」):
  - springdoc 은 시험 의존성으로만 둔다. 실행 jar 에 넣으면 S5 유휴가 약 13–21 MiB 늘었다. 시험이 OpenAPI 파일을 쓰고 프런트 타입은 그 파일에서 만든다. 운영 서버에 API 문서 주소가 없어 노출면도 준다.
  - 가상 스레드는 Phase 0 에서 끔 유지. 켜면 처리량이 2배를 넘었지만 원인을 확인하지 않았고 메모리 이득은 확인되지 않았다. 실제 기능이 생긴 뒤 부하 측정 때 다시 본다.
- DB 접근: Spring Data JPA(Hibernate). JSONB·전문검색은 네이티브 SQL, N+1 은 쿼리 수 시험으로 막는다. QueryDSL 은 필요해질 때 유지 상태를 확인하고 정한다.
- UI 컴포넌트(2026-11-12): Tailwind CSS + shadcn/ui(Radix 기반). 컴포넌트 소스를 저장소에 복사해 우리 코드로 고친다. 테마 색·반경이 CSS 변수라 D-1 (가)와 맞는다. 필요할 때 하나씩 들인다.
- GitHub 보안(사용자 승인, 적용은 Phase 0 CI 작업 때): `SECURITY.md` 제보 경로는 GitHub 비공개 취약점 제보만. private vulnerability reporting·Dependabot alerts 켜기, `main` 보호(force push·삭제 금지, PR 로만 합침, CI 생기면 통과 필수). 2026-11-11 현재: secret scanning·push protection 켜짐, 나머지 꺼짐·없음.

## Phase 0 구현 중 결정 (2026-11-13)

구현하며 정한 것. 스펙에 반영했다. ★ 네 가지는 2026-11-14 사용자가 추천안대로 확정했다(보안 점검 근거는 각 줄).

- ✔ 설치의 「사용 중 DB」(FR-108)를 강화: 이력 표가 있으면 계정이 하나라도 있을 때 거부한다(원문 D19 는 같은 이메일일 때). 기존 사이트 DB 에 다른 이메일로 덮어 설치하면 사이트 설정이 바뀌고 슈퍼유저가 하나 더 생긴다. 설치 중 실패하면 만든 관리자를 지우므로 재시도는 막히지 않는다.
- ✔ React Router·Zustand 는 Phase 0 에 넣지 않는다. 화면이 주소가 아니라 `/api/health` 로 바뀌고 여러 화면이 함께 쓰는 UI 상태가 없다. 쓰는 Phase 에서 넣는다.
- ✔ Flyway 가 설치 로그에 비밀번호 없는 JDBC URL(호스트·포트·DB 이름)을 찍는다. D19 C5 「DSN 은 로그에 남기지 않는다」는 비밀번호가 든 DSN 을 겨눈 것으로 보고 두었다. 비밀번호(DB·관리자)가 로그에 없음은 `InstallApiTest` 가 확인한다.
- `db_host` 는 `A-Z a-z 0-9 . _ : [ ] -` 만 받고, 설치 접속은 `PGSimpleDataSource` 에 값을 하나씩 넘긴다(pgjdbc 드라이버 옵션 주입 차단).
- `site_url` 은 설치 검증과 설정 파일 읽기가 같은 규칙을 쓴다. 프런트는 URL 로 정규화해 보낸다(한글 도메인 → punycode).
- Phase 0 인가는 `permitAll`, 기본 `/logout` 끔. Phase 1 에서 기본 거부로 바꾼다.
- 요청 본문·검증 실패 값을 찍는 로거(`org.springframework.web.servlet.mvc.method.annotation`)는 INFO 로 고정한다(DEBUG 에서 비밀번호가 찍혔다). `InstallRequest`·`ConfigFile` 의 `toString` 은 비밀을 가린다.
- TypeScript 5.9.3(openapi-typescript 가 `^5.x` 만 받음). OpenAPI 파일의 snake_case 는 생성 시험이 맞춘다.
- Maven Wrapper 에 배포 파일 sha256 을 둔다(Maven Central sha512 로 대조).
- ✔ (2026-11-14) API 응답 코드는 Swagger 애너테이션(`@ApiResponse`)으로 적는다. `swagger-annotations-jakarta` 2.2.55(springdoc 3.2.0 과 같은 판, OSV 취약점 0)는 `provided` 로 두고 실행 jar 에서 뺀다(`spring-boot-maven-plugin` excludes) — 실행 중에는 애너테이션 클래스가 없어 무시된다. 생성 시험은 `ProblemDetail` 의 `properties` 칸을 실제 응답 모양(최상위 `errors`)으로 바꿔 적는다. CSRF 거부 403 은 처음에 Boot 기본 오류 JSON 이라 본문 없이 적었고, Phase 1-A 에서 401·403 거부 처리기를 Problem Details 로 바꾸며 문서도 맞췄다. 실행 jar 에 springdoc·swagger 파일이 0개라 문서 경로(`/v3/api-docs`)는 운영에 없다.

## Phase 1 나누기 (2026-11-14, 사용자 승인)

ondolith Phase 1(D81, 작업 45개)은 서버가 HTML 템플릿을 그리는 구조를 전제로 해서, SPA 에 맞게 다시 나눈다. 하위 Phase 마다 스펙 → 계획 → TDD 로 돈다.

| 순서 | 범위 |
|---|---|
| 1-A | 인증·권한 기반: 로그인·로그아웃, DB 세션, RBAC(스키마·시드·판정), 권한 표시 강제와 기동 점검, rate limit, 401·403·429 Problem Details 통일. `/login` 이 필요해 React Router 를 여기서 들인다. 스펙 `docs/superpowers/specs/2026-11-14-phase1a-auth-design.md` |
| 1-B | 관리자 셸: 사이트 설정, 사용자·역할 관리(마지막 관리자 보호 포함), 재인증 15분 창, 작업 로그, 시스템 정보 |
| 1-C | 페이지·메뉴와 공개 화면 |
| 1-D | 테마(D-1) |
| 1-E | 계정 부가: 회원가입·이메일 인증·내 계정·비밀번호 재설정·소셜(선택) |

## ondolith 2026-11-14 변경 확인 (사용자 요청)

ondolith 에 2026-11-14 커밋 36개(v0.6.0·v0.7.0)가 들어왔다. 이 저장소에 닿는 것만 적는다.

| 원문 변경 | 여기서 |
|---|---|
| **D15 R9**(보안): `anonymous`·`member` 에는 방문자 권한만, 그 밖의 부여는 불러올 때 버린다 | 1-A 권한 불러오기에 넣는다(스펙 3.4, 계획 Task 5 시험). 부여 거절 422·「효력 없음」 표시는 1-B |
| FR-723 역할·권한 빼기, 마지막 관리자의 관리자 역할 보호 | 1-B |
| FR-724 저장 성공 알림, FR-725 파괴적 동작 확인 | 1-B(관리자 화면). FR-724 는 SPA 방식으로 다시 정한다 |
| FR-720 관리자 메뉴 숨김, FR-721 파일 끌어다 놓기, FR-722 S3 자동 백업 | 해당 기능이 생기는 Phase(관리자 셸·업로드·백업) |
| FR-710 일반 사이트가 빼 둔 쇼핑몰 화면·권한은 부팅 로그에 INFO 한 줄 | 커머스(Phase 3). 1-A 의 「미사용 권한」 경고와 같은 자리다 |
| 설치 직후 폴더가 작업 디렉터리 기준이던 결함(FR-106) | 해당 없음 — 설치가 설정 파일의 절대 경로(`InstallTarget` 이 `toAbsolutePath`) 옆에 폴더를 정한다 |
| 설치 직후 사이트 설정의 이름 칸이 비던 결함(FR-102) | 해당 없음 — 설치가 `site.name` 을 DB `settings` 에 바로 쓴다 |
| Go·x/net 취약점, 사진 축소 메모리, 테마 활성화 경고 | 해당 없음(Go·서버 렌더링 테마 전용) — 사진 업로드가 생길 때 FR-514 의 메모리 검사를 다시 본다 |

## Phase 1-A 구현 중 결정과 보안 점검 (2026-11-14)

스펙 `docs/superpowers/specs/2026-11-14-phase1a-auth-design.md`, 계획 `docs/superpowers/plans/2026-11-14-phase1a-auth.md`. 사용자가 「자동으로」 진행을 맡겨 advisor 검토로 관문을 넘었다.

구현하며 찾아 고친 것(시험이 잡았다):

- **private·final 처리 메서드는 `@RequiresPermission` 이 있어도 익명에게 열렸다**(보안 프록시가 못 가로챈다, 일회성 시험으로 확인). 기동 점검이 그런 메서드를 거부한다.
- **요청 처리 이벤트(`ServletRequestHandledEvent`)가 `getUserPrincipal()` 로 권한 지연 로드를 깨워, 공개 API 까지 요청마다 DB 를 읽었다.** `spring.mvc.publish-request-handled-events=false`.
- 메서드 보안의 거부 예외를 `ApiErrors` 의 catch-all 이 500 으로 바꾸지 않게 다시 던진다.
- `dev.ondolithj` 밖의 처리기(딸려 오는 의존성의 컨트롤러)는 허용 목록에 없으면 기동을 멈춘다. springdoc(시험 의존성)은 시험 자원의 자동 구성으로만 허용한다.

정한 것:

- `authAt` 은 로그인 때 비밀번호 해시와 같은 질의에서 읽은 `sessions_valid_from`(앱 시계 아님). 세션 속성은 JDK 타입(`String`·`Instant`)만.
- `NullRequestCache` 는 이중 방어로 둔다 — 켜도 이 경로들에서는 세션이 생기지 않았다(망가뜨려 확인). 「로그인 전 세션 없음」 시험은 익명 세션 생성을 잡는다.
- 관리자 횟수 제한 필터는 서블릿 등록을 끈다(`OncePerRequestFilter` 라 켜도 두 번 세지는 않았다).
- 익명 `/api/me` 의 `can` 은 모든 권한 `false`.
- 로그인 중 계정을 읽지 못하면(DB 장애) 401 이 아니라 500 + ERROR 로그(최종 검토).
- R9: `anonymous`·`member` 의 `is_superuser` 표시도 세지 않는다(최종 검토).

D86(ondolith 보안 점검 목록) 대조 — 1-A 범위:

| D86 | 결과 |
|---|---|
| 1.1 세션 쿠키 속성·12시간 | ✓ `LoginTest`(쿠키 속성, DB 세션의 `maxInactiveInterval` 12시간) |
| 1.2 로그인 뒤 세션 재발급 | ✓ 로그인 경로(가입·소셜·비밀번호 변경은 1-E) |
| 1.3 로그아웃이 서버 세션 파기 | ✓ 행 삭제 시험 |
| 1.4 매 요청 기준 시각·비활성 검사, 세션 파기, 속성 없으면 파기 | ✓ `RevocationTest` |
| 1.5 BCrypt(기본 강도 10), 없는 계정도 같은 비교 | ✓ Spring `DaoAuthenticationProvider`, 비활성도 없는 계정 경로 |
| 1.8 `next` 의 `//`·`\`·스킴·`javascript:` 거부 | ✓ `safeNext` 시험(서버는 리다이렉트하지 않는다) |
| 1.11 로그인 IP 10/분·계정 5/분 | ✓ (재설정·가입·재인증 제한은 1-B·1-E) |
| **1.12 프록시 뒤에서 IP 별 제한이 사이트 전체 제한이 된다** | **미해결 — Phase 4 배포 전 필수.** 지금은 `getRemoteAddr` 라, CloudFront 뒤에 두면 모든 요청이 CloudFront IP 로 보여 익명 한 명이 로그인을 막을 수 있다. 믿을 프록시에서 온 연결만 헤더의 IP 를 쓰게 한다 |
| 1.19 로그인이 자격증명과 같은 질의의 기준 시각을 찍는다 | ✓ (자격증명 쓰기·회수 트랜잭션·`GREATEST` 는 1-B·1-E) |
| 2.1 모든 처리기가 권한을 직접 확인, 기동 점검 | ✓ `GuardCheck`. 최종 검토가 찾은 우회(static 처리 메서드, 합성 애너테이션의 `@PreAuthorize`)를 막았다. 한계: `RequestMappingHandlerMapping` 의 처리기만 본다(함수형 라우트·actuator 는 지금 없다) |
| 2.3 R1~R6·마지막 최고 관리자 | 1-B. R9 는 불러오기 쪽 ✓ |
| 2.8 관리자 경로 60/분 | ✓ (1.12 의 한계 같음). 최종 검토가 찾은 퍼센트 인코딩 우회(`/api/%61dmin`)를 MVC 와 같은 경로 해석으로 막았다 |
| 3.1 CSRF 가 두 컨텍스트 전체 | ✓ 공통 `SecurityConfig` |
| 3.7 보안 헤더 | ✓ Phase 0 시험 그대로 |
| 3.10 오류가 내부를 말하지 않는다 | ✓ 401·403·429 도 Problem Details |
| 3.11 HSTS | Phase 4(CloudFront) |

**1-B 로 넘기는 설계 문제**: 기동 점검의 「권한 표시는 메서드·클래스 합쳐 정확히 하나」는 스펙 5절의 「`/api/admin/**` 는 `admin.access` 를 요구하고 리소스별 권한은 그 위에 메서드마다 더한다」와 부딪힌다. 1-B 에서 메서드에 다른 권한을 붙이면 표시가 둘이 되어 기동이 멈추거나, 클래스 표시를 빼면 그 메서드가 `admin.access` 없이 뜬다. 1-B 스펙에서 「여러 권한을 모두 요구하는 표시」(예: `@RequiresPermission({ADMIN_ACCESS, PAGE_EDIT})`)와 「`/api/admin/**` 의 표시는 `ADMIN_ACCESS` 를 포함해야 한다」 점검 규칙을 함께 정한다.
