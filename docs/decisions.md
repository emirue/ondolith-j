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

- 1-E 완료 조건(2026-11-22, 1-B2 최종 검토 I-2 의 Q21 에서 더함 — 사용자 확인 전): 계정 주인이 메일 링크로 비밀번호를 정하고, 관리자는 정하지 않는다. 그때 Q21 제한(계정 생성은 superuser 만)을 푼다.

## ondolith 2026-11-14 변경 확인 (사용자 요청)

ondolith 에 2026-11-14 커밋 36개(v0.6.0·v0.7.0)가 들어왔다. 이 저장소에 닿는 것만 적는다.

| 원문 변경 | 여기서 |
|---|---|
| **D15 R9**(보안): `anonymous`·`member` 에는 방문자 권한만, 그 밖의 부여는 불러올 때 버린다 | 1-A 권한 불러오기에 넣는다(스펙 3.4, 계획 Task 5 시험). 부여 거절 422·「효력 없음」 표시는 1-B |
| FR-723 역할·권한 빼기, 마지막 관리자의 관리자 역할 보호 | 1-B |
| FR-724 저장 성공 알림, FR-725 파괴적 동작 확인 | 1-B(관리자 화면). FR-724 는 SPA 방식으로 다시 정한다 |
| FR-720 관리자 메뉴 숨김, FR-721 파일 끌어다 놓기, FR-722 S3 자동 백업 | 해당 기능이 생기는 Phase(업로드·백업). FR-720 은 관리자 셸(1-B)이 아니라 고정 화면 A-207·A-209 가 생기는 Phase 로 옮긴다(2026-11-21, 「Phase 1-B 결정」 Q6) |
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
- 설치 202 뒤 전환: 전환(설치 컨텍스트 닫기)을 응답 전에 요청해, 닫기가 응답 쓰기와 경쟁하면 연결이 응답 없이 끊겼다(PR #3 CI e2e 의 `socket hang up`). 컨트롤러가 202 를 `Connection: close`·길이 0 으로 비운 뒤 전환한다(`InstallApiTest` 「전환은 202 응답을 다 보낸 뒤에 시작한다」). Phase 0 에서 원인을 못 찾은 「전환 시험이 한 번 200 을 받은 일」도 같은 경쟁이었을 수 있다(확인은 안 함).

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
  - **해결(2026-11-21, 「Phase 1-B 결정」 Q1)**: 배열 대신 관리자 전용 표시 `@AdminPermission(Permission.X)`(`hasAuthority('ADMIN_ACCESS') and hasAuthority('{value}')`, 인자는 enum 하나)를 더한다. `/api/admin/**` 의 표시는 `@AdminPermission(…)` 또는 `@RequiresPermission(ADMIN_ACCESS)` 만 되고, 「정확히 하나」 규칙은 그대로다. 배열을 고르지 않은 근거: Spring Security 7.1 문서의 배열 템플릿 예는 `String[]` 뿐이고, 템플릿이 속성 값을 문자열로 바꿔 넣어 enum 배열은 따옴표 없는 쉼표 문자열이 되는 것으로 보인다(추정 — 동작 미확인).

## Phase 1-B 결정 (2026-11-21, 역할 에이전트 결정 — 사용자 확인 전)

사용자가 제품·보안 역할 에이전트에게 위임해 정했다. 사용자가 확인하면 이 제목의 「사용자 확인 전」을 고친다. 스펙 `docs/superpowers/specs/2026-11-21-phase1b1-admin-shell-design.md`(1-B1).

- **나누기**: 1-B1 기반·설정·조회(표시 규칙, V3 작업 로그, IP 함수, 관리자 셸, A-201·A-601·A-602) → 1-B2 사용자·역할(재인증, A-401~405, R1~R6·R9, 마지막 관리자, FR-723, V4). 재인증은 그것을 처음 쓰는 1-B2 에 둔다. 승인된 1-B 다섯 행은 모두 남고, 행 안의 하위 동작만 줄였다.
- Q1 복수 권한 표시: `@AdminPermission(Permission.X)` 를 더하고, 관리자 경로는 `@AdminPermission` 또는 `@RequiresPermission(ADMIN_ACCESS)` 만 받는다.
- Q2 재인증 시각: 로그인·재인증 성공 때 세션 셋째 속성 `ondolithj.reauth_at`(`Instant`, 앱 시계). 선택 속성이라 없거나 타입이 다르면 「창 닫힘」이고 세션은 유지된다(1-B2).
- Q3 알림·확인·두 번 보내기: 클라이언트만. 성공 문구는 공용 변경 함수의 필수 인자, 확인은 화면 안 대화상자(1-B2 에서 만든다), 보내는 동안 단추 잠금, 65초 시한 없음.
- Q4 작업 로그: 기록 + A-601 화면(쪽당 100, 전체 건수). 기간 조회(FR-712)는 뒤로. `log.view` 를 원문 Phase 2 보다 먼저 `operator` 에 심는다.
- Q5 A-201: `site.name` 만(1~100자, 공백만 거부). 바뀐 키 이름만 작업 로그에, 바뀐 것이 없으면 남기지 않는다.
- Q6 메뉴 숨김(FR-720)·대시보드(FR-702): 둘 다 1-B 밖. 대시보드는 빈 셸.
- Q7 역할 생성·삭제·이름 변경: 1-B 밖. 내장 역할 다섯만(원문 구현에도 없다).
- Q8 A-403 조회 권한: `role.view`(D11 의 `role.manage` 가 아니다).
- Q9 표시 이름: 수정 동작은 1-B 밖, 생성 칸만 1~50자 필수.
- Q10 마지막 관리자: `roles WHERE is_superuser FOR UPDATE` 로 직렬화한 뒤 **활성** 보유자만 센다(원문 D15 SQL 은 비활성도 센다 — 원문 코드와 같은 쪽을 골랐다).
- Q11 타인 비밀번호 재설정 강제: 1-B 밖, 메일과 함께 1-E. `user.reset_password` 를 심지 않는다.
- Q12 버전 노출: **사용자 확인 필요, 임시로 지금 그대로**(헬스가 버전을 보이고 A-602 에도 보인다). 추천은 헬스에서 빼고 A-602·`--version` 에만 — 승인된 NFR-305 통과 기준을 바꾸므로 사용자 몫이다.
- Q13 로그인 기록: 성공만 `operation_logs`(`auth.login`), 실패는 앱 로그 INFO. 원문 D15 7절은 실패도 남기라 한다 — 지울 수 없는 표를 익명 공격자가 키우지 못하게 다르게 했다.
- Q14 사용자 삭제: 비활성만, 삭제는 뒤로. 단 `operation_logs` 의 SET NULL 예외 트리거는 지금 둔다.
- Q15 작업 로그 `ip`: 지금 `getRemoteAddr`. 클라이언트 IP 함수 하나를 횟수 제한과 함께 쓰고 Phase 4 에서 그 한 곳만 고친다.
- Q16 재인증 신호: 창이 닫혀 있으면 403 + 재인증 전용 `type` → 같은 대화상자에 비밀번호 칸 → 별도 `POST /api/admin/reauth`(계정당 5회/분) → 원 요청을 클라이언트가 다시 보낸다. 원문은 재인증을 폼 칸으로 받는다 — 비밀번호가 닿는 처리기를 하나로 모으려고 다르게 했다(1-B2).
- Q17 기간 조회 시간대: 1-B 대상 없음. 그때의 기본안은 JVM 기본 시간대(원문 「서버 시간대」).
- 설계 검토(2026-11-21, advisor): A-602 는 `connected` 칸 없이 DB 서버 판으로 연결을 보인다(false 경로가 없어 시험할 수 없다). 사이트 이름은 설치와 A-201 이 같은 규칙 — 앞뒤 공백(NBSP 포함)을 빼고, 공백·보이지 않는 문자만이면 거부.

1-B 에서 미룬 하위 동작과 갈 곳(Phase 1 이 끝나도 잊지 않게):

| 미룬 것 | 갈 곳 | 이유 |
|---|---|---|
| 사용자 삭제 `user.delete`(Q14, FR-704 「삭제」) | Phase 3(커머스) | 주문 RESTRICT 거부와 함께 시험한다. SET NULL 트리거는 1-B1 에 있다 |
| 표시 이름 수정(Q9) | 1-E(내 정보 FR-211 과 같은 상한) | |
| 타인 비밀번호 재설정 강제 `user.reset_password`(Q11) | 1-E(메일) | SMTP 없이는 늘 409 |
| 역할 생성·삭제·이름 변경(Q7) | Phase 2(게시판별 권한이 생길 때) | 원문 구현에도 없다 |
| 사이트 유형 전환(Q5) | Phase 3(커머스) | 바꿔서 달라지는 라우트가 그때 생긴다 |
| 대시보드 위젯(FR-702) | 보여 줄 지표가 생기는 Phase(2 이후) | 원문 「빈 셸이어도 완료」 |
| 관리자 메뉴 숨김(FR-720) | 고정 화면 A-207·A-209 가 생기는 Phase | |
| 작업 로그 기간 조회(FR-712)와 시간대(Q17) | Phase 3(원문 배정) | |

## Phase 1-B1 구현 중 결정과 보안 점검 (2026-11-21)

스펙 `docs/superpowers/specs/2026-11-21-phase1b1-admin-shell-design.md`, 계획 `docs/superpowers/plans/2026-11-21-phase1b1-admin-shell.md`. Task 마다 개발 에이전트가 순서대로 맡았다.

구현하며 찾아 고친 것(시험이 잡았다):

- **사이트 이름에 U+0000 이 들면 PostgreSQL 이 거부해 A-201 이 500 이었다.** 제어 문자(`\p{Cc}`)가 하나라도 있으면 422 「쓸 수 없는 문자가 있습니다」. 설치도 같은 함수라 함께 막힌다. 프런트 zod 도 같은 규칙·순서(필수 → 제어 문자 → 100자, 첫 위반 하나만)다(`SiteNameTest`, `SettingsApiTest` 「제어_문자가_든_이름은_500_이_아니라_422」, `siteName.test`).
- **영역 ID 가 붙은 IPv6(`fe80::1%eth0`)에서 행을 잃었다.** `CASE WHEN pg_input_is_valid(?, 'inet') THEN ?::inet END` 는 바인드 값이 상수로 접혀 22P02 가 났다. `WITH v AS MATERIALIZED (SELECT ?::text AS ip) INSERT … SELECT` 로 바꿨다. PostgreSQL 이 이 CTE 도 접게 바뀌면 `OperationLogTest` 「inet_으로_읽을_수_없는_IP_도_행은_남고_ip_는_NULL」이 드러낸다.
- **로그아웃·로그인은 `me`·`admin` 쿼리만 지운다.** 그래서 같은 탭에서 다시 들어오면 응답 전에 앞 사람의 작업 로그·시스템 정보가 보였다. 두 쿼리에 `gcTime: 0` 을 넣어 화면을 떠나면 캐시를 지운다. 대가는 쪽을 오갈 때마다 요청 하나다(`OplogPage.test`·`SystemPage.test` 「화면을 떠나면 캐시를 남기지 않는다」).
- **저장 뒤 `/api/site` 다시 읽기가 실패하면 폼과 입력이 사라졌다.** 값이 있으면 오류보다 먼저 본다(`SettingsPage.test` 「저장 뒤 /api/site 를 다시 읽지 못해도 폼과 입력값이 남는다」).
- **E2E 의 jar 출력을 파일(`jar.log`, CI 는 실패 때 산출물)로 남기자 Boot 기동 줄이 jar 절대 경로와 OS 사용자 이름을 찍었다.** `--spring.main.log-startup-info=false` 를 넣었다. 빼면 `check-public.sh` 가 홈 경로 두 줄을 잡는다. 산출물에는 그 밖에 가짜 설치 값, 일회용 서버의 세션·XSRF, 시험 소스 경로가 든다. 모두 가짜·일회용이다(CI 주석).

정한 것:

- 작업 로그 쪽 번호
  - 화면의 `?page=` 는 양의 정수(`^[1-9]\d*$`)가 아니면 1쪽으로 본다.
  - 서버는 `page=0` 이면 422 `errors.page`, `abc`·`2147483648` 이면 400 Problem Details, `2147483647` 이면 200 과 빈 `items` 다(offset 은 long).
  - 「다음」은 `page * page_size < total` 일 때만 보인다.
- 조회 화면(A-601·A-602)
  - 403 이면 「권한이 없습니다」다.
  - 그 밖의 실패는 `role="alert"` 「…를 불러오지 못했습니다」다.
  - 제목(h2)은 모든 분기에서 같은 자리에 남는다.
- 작업 로그 표
  - 주체: 이메일이 비면 「시스템」, `actor_user_id` 가 NULL 이고 이메일이 있으면 「(삭제된 계정) 이메일」이다.
  - 대상: `target_type target_id` 로 보이고, 빈 값은 뺀다.
- `useSave`
  - 성공은 `response.ok` 로 판정한다(스펙은 204). A-201 은 204 뿐이라 결과가 같다.
  - 파일은 `useSave.tsx` 다(스펙은 `.ts`). 오류 칸 `SaveAlert` 를 함께 내보내기 때문이다.
- 422 는 칸 옆 메시지(`aria-invalid`·`aria-describedby`)만 보이고 `role="alert"` 알림은 띄우지 않는다. 계획의 「422 → fieldErrors(알림 없음)」로 해석했다.
- 하위 화면 제목 h2 가 초점을 받는다(`tabIndex={-1}`). `main` 은 관리자 셸 하나뿐이다.
- 시험 컨트롤러는 운영 컨텍스트에 `--spring.main.sources=fixture.guard.AdminProbeConfig` 인자로 넣는다. Boot 가 추가 소스로 묶는 것을 확인했고, `AutoConfiguration.imports` 대안은 쓰지 않았다.
- A-601 의 질의 수는 `pg_stat_statements` 로 센다. `postgres:18-alpine` 에 들어 있고 `shared_preload_libraries` 로 올라간다.
- 「구현 때 확인」의 결과
  - Flyway 12.4.0: `MigrationInfo` 의 `getVersion().getVersion()`·`getInstalledOn()`(`java.util.Date`)·`getState().isFailed()`(javap).
  - `@RequestParam int` 변환 실패: `ApiErrors` 가 상속한 처리기가 400 `application/problem+json` 을 낸다.
  - zod 4: `z.string().overwrite(strip).superRefine(…)` 하나로 쓴다.
  - JS `trim` 은 NBSP 는 빼지만 U+200B 는 남긴다. 그래서 공용 `stripSiteName` 을 쓴다.
  - `actions/upload-artifact` v7.0.2: 태그 SHA 가 계획 값과 같다.

D86(ondolith 보안 점검 목록) 대조 — 1-B1 범위:

| D86 | 결과 |
|---|---|
| **1.12 프록시 뒤에서 IP 별 제한이 사이트 전체 제한이 된다** | **미해결 — Phase 4 배포 전 필수.** 작업 로그 `ip` 도 같은 한계다. 고칠 곳은 `ClientIp` 하나(횟수 제한 둘과 기록기가 함께 쓴다) |
| 2.1 처리기 권한·기동 점검 | ✓ `AdminPermissionTest`(그 권한만·`admin.access` 만 403, 둘 다 200, 익명 401). `GuardCheckTest` 「관리자_경로에_admin_access_가_빠진_표시가_있으면_기동이_멈춘다」(실제 기동), 「관리자_경로에는_admin_access_를_요구하는_표시만_단다」, 「쓰는_권한에_AdminPermission_의_값도_센다」 |
| 2.5 대량 할당 | ✓ `SettingsApiTest` 「본문의_다른_칸은_읽지_않는다」(`type` 을 보내도 `site.type` 그대로) |
| 2.8 관리자 60/분 | 새 API 셋은 1-A 필터의 `/api/admin/**` 패턴에 든다. 새 경로를 직접 친 횟수 시험은 없다(`RateLimitTest` 는 `/api/admin`·`/api/%61dmin`) |
| 3.1 CSRF | ✓ `SettingsApiTest` 「운영자와_관리자는_204_편집자는_403_익명은_401_CSRF_없으면_403」 |
| 3.10 오류가 내부를 말하지 않는다 | ✓ 400 은 `OplogApiTest` 「쪽_번호_검사」가 `application/problem+json` 을 단언. 401·403·422 는 1-A 공통 처리 그대로(새 API 로 본문 모양을 단언하지는 않는다) |
| 4.15 A-201 저장이 작업 로그에 남고 D19 대로 검사 | ✓ `SettingsApiTest` 「바뀌면_행_하나_같은_값이면_행이_늘지_않는다」(`summary = 'site.name'`), 「길이는_1자에서_100자까지_앞뒤_공백은_빼고_잰다」 |
| 5.10 시크릿이 로그·화면에 없음 | ✓ `SettingsApiTest` 「작업_로그_행에_비밀번호_세션_토큰_DB_비밀번호가_없다」, `SystemApiTest` 「본문에_DSN_호스트_포트_DB_계정_비밀번호가_없다」 |
| 5.11 append-only | ✓ `MigrationTest` 「V3_의_작업_로그는_고치거나_지울_수_없고_사용자_삭제만_actor_를_비운다」(UPDATE·DELETE·TRUNCATE·actor 와 다른 칸을 함께 바꾸는 UPDATE 거부, 사용자 삭제 뒤에도 고칠 수 없음). 한계: 앱이 표 소유자라 `DROP TRIGGER` 는 된다(원문과 같다) |
| 6.6 로그에 DSN·비밀번호 없음 | ✓ `OperationLogTest`·`SettingsApiTest` 「기록이_실패해도 … ERROR_줄에_이메일과_IP_가_없다」(출력 전체에서 이메일·거부 값이 없다), A-602 는 고정 칸만 |
| 7.3 목록 질의 한 번 | ✓ `OplogApiTest` 「쪽마다_100행_최신이_먼저_질의는_행_수와_관계없이_둘」(목록·셈) |
| 7.13·7.16 셈·무한 증가 | ✓ 셈은 같은 시험의 `total` 101. 무한 증가는 설계 성질이다 — 지우지 않고, `LIMIT 100` 과 `created_at DESC` 인덱스로 읽는다(시험 없음). 깊은 쪽은 OFFSET 이 표를 훑어 느리다 |
| 1.11·1.13·2.3·2.4·2.4a·2.7·2.10 | 1-B2 |

시험(2026-11-21, 커밋 전 작업 트리 = d528dc9 + 이 문서): `nice -n 19 mise run test` → 셸 100 통과(check-public 17·measure 62·pre-commit 4·summarize 17)·백엔드 135 통과(실패 0, 오류 0, skip 0)·프런트 74 통과·typecheck 0, exit 0. E2E 는 이번에 다시 돌리지 않았다 — Task 8(d528dc9)에서 `mise run e2e` 7 통과. 메모리 측정은 1-B2 뒤(스펙 8절).

최종 검토에 넘긴 것(고치지 않았다):

- 서버 422 뒤 초점을 칸으로 옮기지 않는다. 화면 낭독기가 서버 칸 오류를 바로 듣지 못한다. 422 에도 `role="alert"` 를 띄워야 한다고 보면 고칠 곳은 `SaveAlert` 하나다.
- 작업 로그에서 「다음」을 누르면 새 쪽이 올 때까지 표와 쪽 nav 가 빠져 초점이 body 로 간다. 스펙에 쪽 이동 초점 규칙이 없다.
- Vite 로그에 React Router 경고 「Matched leaf route at location "/admin" does not have an element」가 찍힌다. 빈 index 라우트로 보인다(추정). 동작은 시험대로다.
- 전체 시험에서 `SwitchTest` 가 한 번 5분 넘게 멈췄다(PG 연결 대기, 다시 돌려 통과). 로컬 `postgres:18-alpine` 이미지가 amd64 라 에뮬레이션으로 도는 것과의 관계는 확인하지 않았다.

### 1-B1 최종 검토 (2026-11-21, 새 검토자) — 판정 With fixes, Critical 0 · Important 1 · Minor 11

고친 것:

- **I-1** 계정에 묶인 쿼리의 캐시 보호가 쿼리마다 `gcTime: 0` 을 기억해야 하는 방식이었다. 관리자 데이터 키를 `["admin", …]` 아래로 옮겨, 로그인·로그아웃이 이미 지우는 키에 묶었다(`adminCache.test.tsx` Red→Green). 다른 탭에서 계정이 바뀌는 경우를 위해 `gcTime: 0` 은 그대로 둔다. **1-B2 의 관리자 쿼리도 모두 `["admin", …]` 아래에 둔다.**
- **M-6(보안이라 올려 고침)** `safeNext` 가 `/.//evil.test`·`/a/..//evil.test` 를 통과시켰다(라우터의 정규화가 막고 있었다). 점 마디를 푼 뒤에도 같은 검사를 한다(`safeNext.test.ts` Red→Green).

미룬 Minor와 갈 곳:

| 항목 | 갈 곳 |
|---|---|
| M-1 서버 422 뒤 첫 오류 칸으로 초점(`useSave` 공용) | **1-B2 첫 폼 전**(서버만 아는 오류가 생긴다) → 1-B2 스펙 9절. **1-B2 Task 8 에서 고침**(`useSave.test` 「422_뒤_첫_오류_칸에_초점_M_1_role_alert_없음_M_2」) |
| M-2 422 에 `role="alert"` 를 띄우지 않는다 — 검토자가 개발자 해석을 지지. 스펙 6절 문장만 고친다 | 1-B2 스펙 9절이 대신한다(1-B1 스펙은 고치지 않았다). **1-B2 Task 8·10 에서 고침**(`useSave.test` 「422_뒤_첫_오류_칸에_초점_M_1_role_alert_없음_M_2」, `RolesPage.test` 「R9_422_는_고르기_옆_칸_오류_초점은_고르기」, `UserDetailPage.test` 「422_errors_role_id_는_고르기_옆에_보이고_초점은_고르기」) |
| M-3 쪽을 넘기면 초점이 body 로 — 쪽이 바뀌면 h2 로 | 1-B2(사용자 목록이 같은 쪽 나누기를 쓴다) → 1-B2 스펙 9절. **1-B2 Task 9 에서 고침**(`OplogPage.test`·`UsersPage.test` 「쪽을_넘기면_h2_로_초점_M_3」) |
| M-4 `/admin` index 라우트 경고 — `<Route index element={null} />` | 1-B2 → 1-B2 스펙 9절. **1-B2 Task 9 에서 고침**(`AdminLayout.test` 「/admin 은 경고 없이 빈 대시보드(M-4)」) |
| M-5 사용자가 남아 있어도 `SET actor_user_id = NULL` 이 통과한다(원문과 같다, 이메일 스냅샷은 남는다). 시험 이름·SQL 주석이 사실보다 넓다 | 1-B2 → 1-B2 스펙 2절(시험 이름·V4 주석으로 바로잡고 V3 는 고치지 않는다, 트리거는 강화하지 않는다). **1-B2 Task 3 에서 고침**(`MigrationTest` 「V3_의_작업_로그는_고치거나_지울_수_없다_actor_만_NULL_로_바꾸는_UPDATE_는_사용자가_있어도_통과한다_원문과_같은_한계」) |
| M-7 관리자 경로 판정이 패턴 문자열 비교 — `/api/{x}/…` 꼴 매핑을 기동 점검이 거부 | 1-B2 → 1-B2 스펙 7절. **1-B2 Task 3·4 에서 고침**(`GuardCheckTest` 「API_바로_다음_마디에_변수가_있으면_기동이_멈춘다」·「API_바로_다음_마디의_변수와_와일드카드는_문제다」 — `?` 까지) |
| M-8 서버(Java 25)와 브라우저의 유니코드 판이 다르면 「필수입니다」 판정이 갈린다(U+11B60). 결과는 칸 옆 422 | 한계로 둔다 |
| M-9 작업 로그 화면의 401 이 로그인 안내 없이 「불러오지 못했습니다」, 범위 밖 `?page=` 의 「이전」 | 1-B2 → 1-B2 스펙 9절. **1-B2 Task 9 에서 고침**(`OplogPage.test`·`UsersPage.test` 「401_이면_로그인_안내와_next_링크_M_9」·「빈_쪽이면_첫_쪽으로_M_9」) |
| M-10 `/api/me` 실패가 사이트 설정에서 「권한이 없습니다」로 보인다 | 1-B2 → 1-B2 스펙 9절. **1-B2 Task 9 에서 고침**(`SettingsPage.test` 「/api/me 가 실패하면 「권한이 없습니다」가 아니라 「사이트 설정을 불러오지 못했습니다」(M-10)」) |
| M-11 로컬 `postgres:18-alpine` 이 amd64(에뮬레이션). 1-A 측정의 DB 유휴 약 2배와 `SwitchTest` 멈춤의 원인 후보(확인 안 함) | 1-B2 측정 때 플랫폼·다이제스트를 고정하고 다시 잰다 → 1-B2 스펙 10절(측정만). 시험 쪽은 1-B2 스펙 17절 열린 문제 2 |

## Phase 1-B2 스펙 (2026-11-22, 설계 담당 — 사용자 확인 전)

스펙 `docs/superpowers/specs/2026-11-22-phase1b2-users-roles-design.md`. 「Phase 1-B 결정」의 Q2·Q7~Q11·Q14·Q16 을 옮기고, 결정 밖의 설계는 아래처럼 정했다. Q18·Q19 는 같은 역할 에이전트가 정했다(2026-11-22, 사용자 확인 전) — 아래 표.

정한 것:

- 재인증
  - 재인증 전용 403 의 Problem `type` 은 `urn:ondolithj:problem:reauth-required` 다. 설치처 도메인을 담지 않는다.
  - 재인증은 처리기의 첫 검사다(입력 검사·대상 조회·R 규칙 앞). 원문 코드와 같은 순서다. 창이 닫힌 요청은 작업 로그에 남지 않는다.
  - `reauth.require` 는 세션이 아니라 요청을 받는다. MVC 의 `HttpSession` 인자는 세션을 만든다.
  - 대상 여섯을 시험의 표로 두고 `/api/admin/**` 의 GET 이 아닌 처리기와 대조한다. 분류되지 않은 처리기가 있으면 실패한다.
- API(새 API 열하나: 조회 셋, 상태 변경 여덟)
  - A-401 은 원문 코드 값을 따른다: 쪽당 50, 한 행 더 읽어 `has_next`, 셈 없음, `q` 로 이메일·표시 이름의 일부를 `ILIKE`(이스케이프).
  - A-402 상세는 `user.update` 다(원문 그대로, 운영자는 목록만).
  - 생성 응답은 201 `{id}` 다. 비밀번호 확인 칸은 프런트에만 둔다.
  - 경로 UUID 형식 불량은 경로 변수일 때만 404 다.
- 작업 로그 동작 아홉: `user.create`·`user.deactivate`·`user.activate`·`user.grant_role`·`user.revoke_role`·`role.grant_permission`·`role.revoke_permission`·`user.denied`·`role.denied`. 거절 행의 `summary` 는 「시도한 동작 [키] 규칙 토큰」이고, 모르는 권한 키는 넣지 않는다.
- 마지막 관리자
  - 거절 조건은 「대상이 활성 superuser 보유자이고 활성 보유자 ≤ 1」이다.
  - 잠금·셈·쓰기만 `TransactionTemplate` 으로 묶고, 기록은 커밋·롤백 뒤에 한다.
  - 순차 API 로는 닿지 않는다(R1·R3·R6·자기 계정 금지). 그래서 함수 직접 시험과 동시 시험으로 본다.
- R2·R4 판정 함수 하나. superuser 역할은 「권한 전부」로 본다. 요청자의 superuser 여부는 `Actor` 의 새 칸으로 싣는다.
- 측정
  - `measure.sh` 의 DB·JRE·httpd 이미지를 `태그@다이제스트` 와 `--platform` 으로 고정한다.
  - 이미지 `os/arch` 가 플랫폼과 다르면 하네스 실패(exit 2)다.
  - 측정 앱과 코어를 같은 고정으로 번갈아 다시 잰다. 다이제스트 값은 측정 때 확인해 적는다.

원문과 다른 곳:

- 원문 코드는 A-402 의 자기 계정·R6·마지막 관리자 거절을 작업 로그에 남기지 않는다. 문서(D19 「남김」, D15 5.1)를 따른다. 동작 이름도 원문 코드(`user.update`·`role.assign`)보다 잘게 나눈다.
- 비밀번호 재확인 칸은 폼마다가 아니라 확인 대화상자 안이고, 별도 API 로 보낸다(Q16 그대로).

정한 것(스펙 16절, 역할 에이전트 결정 — 사용자 확인 전):

| 질문 | 어긋남 | 결정 |
|---|---|---|
| Q18 A-405 의 대상 — 비활성 계정과 이메일 입구 | D19 A-405 입력표 「이메일은 활성 회원을 찾는다」 대 거부 조건 「대상이 비활성이어도 거부하지 않는다」. 입구를 A-402 상세에만 두면 `role.assign` 만 가진 역할은 쓸 화면이 없다 | (a) 입구는 A-402 상세 하나, 비활성 대상도 받는다. 이메일 입구 없음(`ponytail:` 그런 역할이 쓰이면 더한다) |
| Q19 R6 이 활성화에도 걸리는가 | D15·D19 의 R6 목록(비활성·삭제·재설정) 대 D19 재인증 행 「활성화도 같다」와 원문 코드(네 동작 모두 R6) | (a) 활성화에도 R6 — 비-superuser 가 막아 둔 관리자 계정을 다시 열지 못하게(원문 R6 목록보다 넓다) |
| Q20 활성화에 R2 를 거는가(1-B2 최종 검토 I-2, 역할 에이전트 결정 — 사용자 확인 전) | R6 은 superuser 보유자만 지킨다. `user.update` 를 받은 비-superuser 가 자기보다 권한이 넓은 잠긴 계정을 다시 연다 — Q19 의 이유(「비활성화가 유일한 탈취 대응」)가 superuser 아닌 계정에도 맞는다 | 건다. 요청자가 대상의 유효 권한을 모두 덮지 않으면 403·`user.denied user.activate r2`. 순서 재인증 → 404 → 자기 계정 → R6 → R2 → 쓰기 → 기록. 판정은 처리기에서만(판정 뒤 부여 경쟁은 합법 순차 순서와 같다). 비활성화에는 걸지 않는다(내리는 방향) |
| Q21 1-E 전 계정 생성(1-B2 최종 검토 I-2, 역할 에이전트 결정 — 사용자 확인 전) | 생성은 만드는 사람이 초기 비밀번호를 정한다. `user.create` 를 받은 비-superuser 가 만든 계정에 더 넓은 부여자가 역할을 주면, 만든 사람이 그 계정으로 로그인해 권한이 오른다. R1~R6 어느 것도 보지 않는 길이다 | 1-E 전에는 superuser 만 만든다. 403 「지금은 관리자만 계정을 만들 수 있습니다」·`user.denied user.create superuser_only`(`target_id` NULL). `ponytail:` 1-E 에서 계정 주인이 메일 링크로 비밀번호를 정하게 되면 풀고 `user.create` 권한으로 돌린다 |

## Phase 1-B2 구현 중 결정과 보안 점검 (2026-11-22)

스펙 `docs/superpowers/specs/2026-11-22-phase1b2-users-roles-design.md`, 계획 `docs/superpowers/plans/2026-11-22-phase1b2-users-roles.md`. Task 마다 개발 에이전트가 순서대로 맡았다. 스펙에는 아래 「고친 것」의 1~4 를 반영했다(4·5·6·7·14절).

구현하며 찾아 고친 것(시험이 잡았다):

1. **R6 판정과 쓰기 사이의 틈.** 처리기가 R6(대상이 superuser 보유자인데 요청자가 superuser 아님)을 본 뒤 대상이 보유자가 되면, 비-superuser 의 비활성화·활성화가 지나갔다(Task 6 관찰, 주 세션 결정). 비활성화는 `LastAdmin` 이 잠금 아래에서 대상 보유 여부를 다시 읽을 때 R6 을 마지막 관리자보다 먼저 본다. 활성화는 `UPDATE … WHERE u.id = ? AND (<요청자 superuser> OR NOT <보유 술어>)` 한 문장이고, 0행이면 대상이 있으면 403 r6, 없으면 404 다(`LastAdminTest` 「처리기가_R6_을_본_뒤에_보유자가_된_대상의_비활성화는_잠금_아래서_403_r6」·「처리기가_R6_을_본_뒤에_보유자가_된_대상의_활성화는_403_r6」, 고치기 전에는 둘 다 204).
2. **M-7 은 `?` 까지 막는다.** Spring `PathPattern` 에서 `?` 도 한 글자 와일드카드라 `/api/admi?/x` 가 `/api/admin/x` 를 받으면서 관리자 경로로 세지지 않았다(Task 3 관찰, 주 세션 결정, `GuardCheckTest` 「API_바로_다음_마디의_변수와_와일드카드는_문제다」).
3. **경로 UUID 가 공백(`%20`)이면 400 이었다.** Spring 의 문자열→UUID 변환이 공백을 null 로 내 `MissingPathVariableException` 이 났다. `ApiErrors.handleMissingPathVariable` 이 「변환 뒤 없음」일 때만 404 로 바꾼다(`UserStatusTest` 「경로가_UUID_가_아니거나_없으면_404_Problem_Details」).
4. **본문 칸이 null·빠짐이면 422 「필수입니다」이고, 재인증 바로 뒤·대상 조회(404) 앞이다**(A-404·A-405 넷). A-405 빼기도 422 가 있어 OpenAPI·스펙 7절 표에 넣었다(`RolesApiTest` 「본문이_비거나_role_id_가_null_이면_422_필수_기록_없음」·「권한이_null_이면_422_필수_기록_없음」).
5. **A-401 의 `q` 에 U+0000 이 있으면 500 이었다.** 422 `errors.q` 「쓸 수 없는 문자가 있습니다」. NUL 만 막는다(`UsersApiTest` 「쪽과_q_검사」).
6. **생성의 앞뒤 공백 이메일이 422 였다.** Hibernate Validator 9.1.5 `@Email` 은 공백을 받지 않는다. 생성 레코드가 먼저 `strip()` 한다(설치는 그대로 422, `UsersApiTest` 「이메일은_소문자로_저장하고_중복은_대소문자가_달라도_409」).
7. **`useMutation` 을 쓰면 보낸 값이 변경 캐시(`Mutation.state.variables`, query-core 5.107.1)에 남는다.** `useSave` 는 `useMutation` 을 쓰지 않고 잠금·상태를 직접 쥔다. 새 사용자 비밀번호와 재인증 비밀번호가 캐시에 들지 않는다(`useSave.test` 「비밀번호가_쿼리_캐시와_변경_캐시에_없다」).
8. **`send` 가 동기로 던지면 잠금이 영영 남았다**(`useMutation` 을 빼며 생김). `Promise.resolve().then(…)` 으로 감쌌다(`useSave.test` 「send 가 동기로 던져도 풀린다」).
9. **`/admin` 빈 index 라우트 경고(M-4)** 는 `element={null}` 로 사라진다(react-router 8.5.1, `AdminLayout.test` 「/admin 은 경고 없이 빈 대시보드(M-4)」).

정한 것:

- 계획의 「스펙과 다르게 정한 것」 일곱(측정의 Docker 서버 대 `PLATFORM`, R6 문구 「권한이 없습니다」, 칸 null 422, A-403 `is_superuser` 는 술어 값, 시험 배분, `Reauth.Required`·`shared/Refused`, 조회 화면의 `LoadError`·`LoadFailure`)은 계획대로 했다.
- `LastAdmin.run(대상, 요청자 superuser, 쓰기)` — 잠금 아래 순서는 대상 없음 → R6(롤백) → 마지막 관리자 → 쓰기(0행이면 404). superuser 역할 빼기는 R3 이 이미 거른 뒤라 `true` 로 부른다.
- `permissionsOf` 는 코드에 없는 DB 권한 키를 버리지 않고 던진다 — 버리면 필요한 집합이 줄어 열린 쪽으로 실패한다. 기동 점검이 그런 DB 를 막으므로 실제로는 나지 않는다(`CoversTest` 「코드에_없는_키는_버리지_않고_던진다」).
- 재인증 요청의 403 은 `type` 이 재인증일 때만 「비밀번호가 맞지 않습니다」다. `type` 없는 403·5xx·연결 실패는 남긴 값을 버리고 대화상자를 닫은 뒤 일반 실패 알림이다(`useSave.test` 「재인증_요청의_type_없는_403_은_일반_실패로_재인증을_끝낸다」).
- 확인 대화상자
  - 확인은 `AlertDialogAction` 이 아니라 폼의 제출 단추다. Action 은 `DialogClose` 라 누르는 즉시 닫힌다. 연 단추는 `AlertDialogTrigger` 다(Radix 는 닫힐 때 Trigger 로만 초점을 돌린다). 보내는 동안 Esc·「취소」는 무시한다.
  - `ConfirmButton` 하나에 `useSave` 하나 — 나눠 쓰면 재인증 403 에 모든 단추의 대화상자가 뜬다. 목록은 줄마다 부품을 둔다.
  - 422 는 대화상자에 칸이 없어 부르는 화면이 고르기(「줄 역할」·「줄 권한」) 옆에 그리고(`role="alert"` 없음, M-2) 초점을 고르기로 준다. 재인증 뒤 422 면 대화상자가 닫히고 연 단추가 `aria-describedby` 로 같은 오류를 읽는다. destructive(빼기) 대화상자는 422 에 닫지 않는다(`RolesPage.test` 「재인증_뒤_R9_422_면_대화상자를_닫고_돌아온_부여_단추가_칸_오류를_읽는다」).
  - 빼기 대화상자에는 설명 한 줄(「이 사용자에게서 <역할 이름> 역할을 뺍니다」·「이 역할에서 <권한 키> 권한을 뺍니다」)을 둔다. 제목만으로는 어느 줄인지 모른다.
- 연 단추가 사라지는 동작(비활성↔활성, 빼기, 남은 마지막 항목 부여)은 다시 읽은 화면을 그린 뒤 h2 로 초점을 옮긴다(`UserDetailPage.test` 「비활성화_성공_뒤_연_단추가_사라지면_초점은_h2」).
- `/api/me` 가 실패하면 사용자 목록·상세·역할 화면은 바꾸는 단추와 링크만 숨기고 데이터는 그린다.
- 사용자 목록 검색은 `setSearchParams` 로 주소만 바꾼다(선언형 `<Routes>` 라 react-router `<Form>` 의 GET 은 새로 고침이 된다). 검색어가 바뀌어도 h2 로 초점을 옮긴다(`UsersPage.test` 「검색 칸에서 Enter 로 제출해도 초점은 h2 로」).
- E2E 의 재인증 창 닫기는 `Stack.sql` 로 DB 컨테이너에서 `DELETE FROM spring_session_attributes WHERE attribute_name = 'ondolithj.reauth_at'` 을 돌리고 `DELETE 1` 을 단언한다. 키보드 시험의 「확인 한 번」은 POST 순서 `deactivate 403`·`reauth 204`·`deactivate 204` 로 단언한다(창이 닫혀 첫 요청은 재인증 403 이다).
- 「구현 때 확인」의 결과
  - Boot 4.1.2 는 `TransactionTemplate` 빈을 낸다. JPA 트랜잭션 관리자 아래에서 `JdbcClient` 가 같은 연결을 쓴다 — `FOR UPDATE` 가 있으면 동시 시험이 [204, 409], 빼면 [204, 204].
  - 앱 연결의 `--spring.datasource.hikari.connection-init-sql=SET lock_timeout = '20s'` 는 `SHOW lock_timeout` = 20s 로 확인했다.
  - `handleTypeMismatch` 는 `@PathVariable` 일 때만 404 다. 질의 매개변수(`page=abc`)는 400 그대로다.
  - 문자열→UUID 는 `UUID.fromString` 이라 짧은 꼴 `0-0-0-0-1` 도 받는다. 값으로 비교하므로 우회는 아니다.
  - spring-security-crypto 7.1.2 의 `BCrypt.checkpw` 는 73바이트 비밀번호에 예외 없이 false 다. 재인증의 `IllegalArgumentException` catch 는 판이 바뀌어 다시 던질 때를 위해 둔다. 로그인의 「인코더가 던진다」 주석은 이 판에서 사실이 아니다(손대지 않음).
  - Radix AlertDialog 의 Action 은 누르는 즉시 `onOpenChange(false)` 다. Portal 이 다음 그리기에 내용을 붙여, 새로 열 때 칸 초점은 `onOpenAutoFocus` 로 준다.
  - 질의 수(`pg_stat_statements`): A-401 은 3명·54명 모두 2(인증 1 + 목록 1), A-403 은 역할·부여를 늘려도 3(인증 1 + 목록 2). A-402 상세(셋)는 시험으로 세지 않았다.
  - 생성된 openapi.json 의 A-601 operationId 가 `list` → `list_2` 로 밀렸다(메서드 이름이 같아서). 프런트는 operationId 를 쓰지 않는다.

D86(ondolith 보안 점검 목록) 대조 — 1-B2 범위(스펙 13절):

| D86 | 결과 |
|---|---|
| 1.11 재인증 계정당 5회/분 | ✓ `ReauthApiTest` 「계정당_5번까지이고_6번째는_429_Retry_After_다른_계정은_따로_센다」(6번째는 맞는 비밀번호 — 제한이 대조 앞) |
| **1.12 프록시 뒤 IP** | **미해결 — Phase 4 배포 전 필수.** 새 작업 로그 행의 `ip` 도 같은 한계 |
| 1.13 재인증 15분, 위험 작업에 요구 | ✓ `ReauthTest` 「찍은_때부터_15분_직전까지_열린다」·「십오_분이면_닫힌다」·「미래_값은_닫힘이다」·「없거나_타입이_다르면_닫힘이다」, `ReauthTargetsTest` 「관리자_경로의_GET_이_아닌_처리기는_모두_분류되고_표의_키는_모두_있다」·「분류되지_않은_POST_가_있으면_잡아낸다」·「대상은_창이_닫히면_재인증_403_이고_DB_가_그대로다」, E2E 「재인증 창이 닫히면 같은 대화상자가 비밀번호를 묻고 비활성화된다」. 한계: 로그인이 창을 열어 막 로그인한 세션은 묻지 않는다(스펙 15절 2) |
| 1.18 재설정 강제 | 1-E(Q11) |
| 1.19 회수 트랜잭션·`GREATEST` | ✓ `UserStatusTest` 「비활성화했다가_활성화해도_그_전_세션은_401」(첫 Red — 기준 시각 이동을 빼면 200) |
| 2.1 처리기 권한·기동 점검 | ✓ `UserStatusTest` 「권한이_없거나_익명이면_403_401_이고_기록하지_않는다」, `UsersApiTest` 「목록은_운영자_200_편집자_403」, `RolesApiTest` 「역할_목록은_운영자_200_편집자_403」, `GuardCheckTest` 「API_바로_다음_마디에_변수가_있으면_기동이_멈춘다」(M-7). 「어느 API 도 쓰지 않는 권한」 WARN 0건 |
| 2.3 R1~R6·superuser 유일·마지막 관리자 | ✓ `RolesApiTest` 「R1_자기에게_역할을_주면_403_이고_r1_행_하나」·「자기_역할_빼기는_403_r1」·「R2_자기에게_없는_권한이_든_operator_를_주면_403_r2」·「R3_비superuser_가_admin_을_주면_403_r3」·「R4_자기에게_없는_user_update_를_주면_403_r4」·「R5_admin_역할의_권한은_403_r5」·「R9_member_에_user_view_는_422_r9_행」, `UserStatusTest` 「R6_비superuser_가_superuser_보유자를_비활성화하면_403_r6」·「R6_은_활성화에도_걸린다_Q19」, `CoversTest` 「superuser_역할의_권한은_전부라_비superuser_는_덮지_못한다」, `RevocationTest` 「superuser_술어는_admin_보유자만_참이고_모두가_갖는_역할의_표시는_세지_않는다_R9」, `LastAdminTest` 「유일한_활성_보유자는_거절되고_쓰지_않는다」·「member_에_superuser_를_켜도_셈이_늘지_않는다」·「두_관리자가_서로를_동시에_비활성화하면_하나만_된다」·「비활성화와_admin_역할_빼기가_엇갈려도_하나만_된다」(`FOR UPDATE` 를 빼면 [204, 204]). 최종 검토 I-2: `UserStatusTest` 「R2_좁은_요청자가_더_넓은_비활성_계정을_활성화하면_403_r2_이고_비활성_그대로」(Q20), `UsersApiTest` 「비superuser_가_user_create_를_받아도_계정을_만들면_403_이고_행이_늘지_않는다」(Q21). 한계: 요청 중 비활성화된 요청자의 마지막 한 번(스펙 15절 3) |
| 2.4 A-404·A-405 폼이 실제 라우트에 닿는다 | ✓ E2E 「사용자 생성 → 운영자 부여 → 그 사용자는 메뉴 다섯·바꾸는 단추 없음 → 역할 빼기 → 작업 로그」·「역할 화면에서 편집자에게 권한을 준다」(다시 읽어 서버 값 확인). 한계: 권한 빼기는 E2E 에 없다(`RolesApiTest` 「editor_에_log_view_를_주고_빼면_204_와_행_둘」, `RolesPage.test` 「권한_빼기는_확인_대화상자」) |
| 2.4a 재인증을 거치고 성공·R 위반을 기록 | ✓ `RolesApiTest` 「창이_닫히면_입력_오류와_R_거절보다_재인증이_먼저이고_기록하지_않는다」·「A_405_는_줄_수_없는_역할_R1_R3_순서」·「A_404_는_모르는_키_R5_R9_R4_순서」, `UserStatusTest` 「창이_닫히면_자기_계정과_R6_거절보다_재인증이_먼저이고_기록하지_않는다」·「이미_비활성이어도_204_이고_기록한다」, `UsersApiTest` 「생성은_관리자_201_과_user_create_행_운영자는_403」 |
| 2.5 대량 할당 | ✓ `UsersApiTest` 「본문의_활성_상태와_역할과_기준_시각은_무시된다」 |
| 2.7 `{id}` 는 uuid 만, 형식 불량 404 | ✓ `UserStatusTest` 「경로가_UUID_가_아니거나_없으면_404_Problem_Details」(`not-a-uuid`·`123`·`%20`·없는 UUID). 한계: 인자 해석이 메서드 보안보다 먼저라 형식이 틀린 경로는 익명·운영자도 401·403 이 아니라 404 다(아래 「넘긴 것」) |
| 2.8 관리자 60/분 | 새 API 열하나는 모두 `/api/admin/**` 라 1-A 필터에 든다. 새 경로를 직접 친 횟수 시험은 없다(`RateLimitTest` 「관리자_경로는_IP_당_60번까지이고_한_번만_센다」) |
| 2.10 uuid 표기를 맞춘 뒤 비교 | ✓ `UserStatusTest` 「자기_계정은_403_이고_self_행_대문자_경로도_같다」 |
| 3.1 CSRF | 새 POST 여덟은 1-A 그대로. 직접 친 시험은 재인증 하나(`ReauthApiTest` 「CSRF_없으면_403_익명은_401」) |
| 3.10 오류가 내부를 말하지 않는다 | ✓ `UserStatusTest`·`RolesApiTest` 의 거절 단언이 `detail` 을 코드 상수와 정확히 비교하고(상세 시험은 `application/problem+json` 도), `RolesApiTest` 「모르는_키는_422_unknown_permission_행에_보낸_키_문자열이_없다」 |
| 5.10 시크릿이 로그·화면에 없다 | ✓ `RolesApiTest` 「생성_재인증_부여_흐름_뒤_작업_로그에_비밀번호_세션_토큰이_없다」, `ReauthApiTest` 「비밀번호가_앱_로그와_작업_로그에_없다」, `useSave.test` 「비밀번호가_쿼리_캐시와_변경_캐시에_없다」 |
| 6.6 로그에 비밀번호 없음 | ✓ `ReauthTest`·`UsersApiTest` 「요청_레코드의_toString_은_비밀번호를_가린다」, `ReauthApiTest` 「비밀번호가_앱_로그와_작업_로그에_없다」 |
| 7.3 목록 질의 한 번 | ✓ `UsersApiTest` 「목록_질의는_행_수와_관계없이_같다」(2), `RolesApiTest` 「질의_수는_역할과_부여_수와_관계없이_같다」(3). A-402 상세는 세지 않았다 |
| 7.13 셈 | ✓ `UsersApiTest` 「쪽당_50_한_행_더_읽어_has_next」(셈 없음) |

시험(2026-11-22, 커밋 전 작업 트리 = 9b6ec1a + E2E·이 문서): `nice -n 19 mise run test` → 셸 105 통과(check-public 17·measure 67·pre-commit 4·summarize 17)·백엔드 231 통과(실패 0, 오류 0, skip 0)·프런트 166 통과(파일 20)·typecheck 0, exit 0. `mise run e2e` → 12 통과(admin 3·install 2·login 2·users 5), exit 0. 새 E2E 는 처음부터 통과해 단언을 일부러 틀려 실패를 봤다(POST 수 2 기대 → 「Expected: 2 Received: 1」, 창 닫기를 빼면 비밀번호 칸이 뜨지 않아 시한 초과). 메모리 측정은 최종 검토 수정 뒤(스펙 10절) — 미실행.

최종 검토에 넘긴 것(고치지 않았다):

- 인자 해석이 메서드 보안보다 먼저라, 형식이 틀린 경로(`/api/admin/users/not-a-uuid`)는 익명·운영자도 401·403 이 아니라 404 다. 올바른 UUID 면 없는 id 도 운영자는 403 이라 존재 여부는 새지 않는다(Task 4).
- `@Valid` 가 메서드 보안보다 먼저라, 생성 본문의 이메일이 틀리면 익명·운영자도 422 다. 새는 것은 상수 검사 문구뿐이다(Task 5).
- 서버는 이메일을 검사 전에 `strip` 하지만 새 사용자 zod 는 그러지 않는다(설치와 같다). 앞뒤 공백 이메일은 프런트에서만 「이메일 형식이 아닙니다」로 막힌다. 잘못 보내는 값은 없다(Task 9).
- 자기 역할의 권한을 바꿔도 `["me"]`(can)를 무효화하지 않는다 — 다음 다시 읽기 전까지 메뉴·단추가 옛 값이다. 스펙대로 `["admin","roles"]` 만 무효화한다(Task 10).
- 프런트 빌드가 「chunk 500kB 넘음」 경고를 낸다(index JS 481→530kB, AlertDialog 가 처음 번들에 들었다). 빌드는 된다. 스펙 14절 위험 표에 적었다(Task 10).
- 성공 뒤 다시 읽기가 실패하면 h2 초점 플래그가 남아 다음 다시 읽기 때 옮긴다(Task 10).
- 설치의 `useInstall` 은 아직 `useMutation` 이라 설치 비밀번호가 변경 캐시에 남는다. 설치는 1-B2 범위 밖이다(Task 8).

### 1-B2 최종 검토 (2026-11-22, 새 검토자) — 판정 With fixes, Critical 0 · Important 2

비-superuser 가 API 만으로 superuser 에 닿는 길은 찾지 못했다(검토자가 권한 전부를 가진 비-superuser 로 실행 확인). R2·R4 경쟁은 어떤 엇갈림도 합법적인 순차 순서의 끝 상태라 조치하지 않는다(검토자 판단).

고친 것:

- **I-1** 「권한 전부 ≠ superuser」를 지키는 시험이 없었다 — `SuperuserLadderTest`(superuser 판정을 「권한을 다 가졌는가」로 바꾸면 403→204 로 실패하는 것을 확인).
- **I-2** R 규칙이 다루지 않는 계정 경로의 권한 상승 — 역할 에이전트 결정 Q20(활성화에 R2)·Q21(1-E 전 계정 생성은 superuser 만), 위 「Phase 1-B2 스펙」 표.
- **M-1(올려 고침)** `revokeRole` 의 분기에 `R6` 이 없어 닿으면 성공으로 떨어졌다 — 500 으로 멈춘다(닿지 않는 갈래라 런타임 시험은 없다).
- **(c)(올려 고침)** 설치가 `useMutation` 이라 DB·관리자 비밀번호가 변경 캐시에 남았다 — 일반 함수 `postInstall`(`InstallPage.test` 캐시 시험 Red→Green).

미룬 Minor와 갈 곳:

| 항목 | 갈 곳 |
|---|---|
| (a) 경로 UUID 형식 불량이 익명·운영자에게도 404(401·403 이 아니다). 새는 것은 공개된 상수 문구뿐 | 한계로 둔다 |
| (b) `@Valid` 가 권한 검사보다 먼저 돌아 익명에게도 422 | 한계로 둔다(같은 이유) |
| (d) 서버는 이메일 앞뒤 공백을 빼고 프런트 zod 는 빼지 않는다(설치와 같다). 잘못 보내는 값은 없다 | 1-E(가입 폼과 함께) |
| (e) A-402 상세의 질의 수 시험 없음, (f) 권한 빼기 E2E 없음 | 1-C 시작 때 시험 보강 |
| (g) 빌드 chunk 500kB 경고(AlertDialog) | 화면이 늘어나는 1-C 에서 경로별 분할을 본다 |
| (h) 자기 역할 권한을 바꿔도 `["me"]` 를 무효화하지 않는다 | 1-C |
| M-9 Docker 가 꺼졌을 때 측정 하네스 문구가 「PLATFORM()이 Docker 서버와 다르다」로 원인이 안 보인다 | 다음 측정 때 |
| M-11 M-7 검사가 첫 마디 와일드카드(`/{x}/admin/y`, `/**`)를 놓친다(지금 그런 매핑 없음) | 1-C |
| M-12 화면이 늘 실패하는 선택지를 내놓는다(비-superuser 의 「줄 역할」에 admin, member·anonymous 부여는 늘 422) — 누를 때마다 지울 수 없는 거절 행 | 1-C |
| M-13 로그인·재인증 주석의 「인코더가 던진다」가 이 판(7.1.2)에서 사실이 아니다 | 1-C |
| M-14 A-404 빼기의 R4 거절 문구가 「부여할 수 없습니다」 | 1-C |
| `user.create` 를 받은 비-superuser 에게 「새 사용자」 링크가 늘 403(Q21, `/api/me` 에 superuser 칸 없음) | 1-E 에서 Q21 을 풀 때 |

## Phase 1-C 사용자 결정 (2026-11-24, 사용자 답)

1-C 결정 질문 22개 가운데 승인된 결정과 부딪히는 다섯을 사용자에게 물었다(Q3 은 Q2 에 묶었다). 나머지 열일곱은 역할 에이전트가 정했다(사용자 확인 전, 스펙에 옮긴다).

| 질문 | 사용자 답 |
|---|---|
| Q1 페이지 본문 형식 | **리치 텍스트를 1-C 로 앞당긴다**(굵게·링크·사진). 요청서의 「페이지 사진 올리기」(A-310)도 함께 1-C 다. HTML 정화가 들어온다 — 공개 화면과 관리자가 같은 출처라, 정화의 구멍 하나가 관리자 권한 탈취로 이어진다 |
| Q2 검색·공유 미리보기 메타(D-2 ①) | 배포 단계(Phase 4)로 미룬다. 없는 페이지의 HTML 404 상태·sitemap·robots·기본 설명 설정도 함께. 그동안 미리보기는 고정 제목 |
| Q3 서버가 HTML 원본을 얻는 곳 | Q2 를 미뤄 지금은 정하지 않는다(그때의 기본안: S3 에서 가져오고 주소는 설정 파일에만) |
| Q15 페이지별 템플릿(FR-404) | 테마 단계(1-D) |
| Q22 공개 API 캐시 | 캐시 없음(`no-store` 그대로) |
| 사진 공개 범위(리치 텍스트 후속 7.1) | **주소를 아는 누구나**(원문과 같다). 사진 주소는 무작위 이름이다. 초안·지운 페이지의 사진도 주소를 알면 열린다 |

리치 텍스트 후속 결정(역할 에이전트, 사용자 확인 전): 1-C 를 넷으로 나눈다 — 1-C1 정화·페이지 API·공개 화면, 1-C2 편집기·관리 화면, 1-C3 페이지 사진(A-310), 1-C4 메뉴·공개 셸. 정화기 jsoup, 편집기 Tiptap(한글 IME 시험 통과가 조건, 실패하면 Lexical), 이미지 JDK ImageIO + TwelveMonkeys. 정화는 저장·내보낼 때 두 번, 프런트는 한 곳(`RichHtml`)에서 DOMPurify 로 한 번 더. 세부는 각 스펙에 옮긴다. 조건부 질문 하나는 1-C3 측정 뒤에 묻는다(512MB 에서 큰 사진이 실패할 때).
