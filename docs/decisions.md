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
| (e) A-402 상세의 질의 수 시험 없음, (f) 권한 빼기 E2E 없음 | 1-C1(1-C1 스펙 9절) — 1-C1 Task 6 에서 고침 |
| (g) 빌드 chunk 500kB 경고(AlertDialog) | 1-C2(공개·관리자 코드 분할, Q21) — 1-C2 에서 고침(Task 7) |
| (h) 자기 역할 권한을 바꿔도 `["me"]` 를 무효화하지 않는다 | 1-C2(페이지 단추를 `can` 으로 그리는 단계) — 1-C2 에서 고침(Task 5) |
| M-9 Docker 가 꺼졌을 때 측정 하네스 문구가 「PLATFORM()이 Docker 서버와 다르다」로 원인이 안 보인다 | 다음 측정 때 |
| M-11 M-7 검사가 첫 마디 와일드카드(`/{x}/admin/y`, `/**`)를 놓친다(지금 그런 매핑 없음) | 1-C1(1-C1 스펙 9절) — 1-C1 Task 6 에서 고침 |
| M-12 화면이 늘 실패하는 선택지를 내놓는다(비-superuser 의 「줄 역할」에 admin, member·anonymous 부여는 늘 422) — 누를 때마다 지울 수 없는 거절 행 | 1-C1(1-C1 스펙 9절) — 1-C1 Task 6 에서 고침 |
| M-13 로그인·재인증 주석의 「인코더가 던진다」가 이 판(7.1.2)에서 사실이 아니다 | 1-C1(1-C1 스펙 9절) — 1-C1 Task 6 에서 고침 |
| M-14 A-404 빼기의 R4 거절 문구가 「부여할 수 없습니다」 | 1-C1(1-C1 스펙 9절) — 1-C1 Task 6 에서 고침 |
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

## Phase 1-C 스펙 (2026-11-24, 설계 담당 — 사용자 확인 전)

스펙 `docs/superpowers/specs/2026-11-24-phase1c1-pages-sanitize-design.md`(1-C1). 위 「Phase 1-C 사용자 결정」과 역할 에이전트 결정(1-C 결정 Q1~Q22, 리치 텍스트 후속 결정 — 사용자 확인 전)을 옮기고, 결정 밖의 설계는 아래처럼 정했다. 1-C2·1-C3·1-C4 는 각자의 스펙에서 정한다.

정한 것(1-C1):

- 나누기: 1-C1 정화·페이지 API·공개 화면 → 1-C2 편집기·관리 화면 → 1-C3 페이지 사진 → 1-C4 메뉴·공개 셸. 1-C1 에는 관리 화면이 없고 시험은 API 로 HTML 을 넣는다.
- 새 의존성 둘: jsoup 1.24.1(Maven Central `maven-metadata.xml`, MIT), DOMPurify 3.5.0(npm `latest`, MPL-2.0 OR Apache-2.0). OSV 2026-11-24 둘 다 0건. context7 은 월 한도 초과로 쓰지 못했다.
- 정화: 함수 둘. `clean`(내보낼 때·저장 때, 예외 없음)과 `forSave`(저장 때만 — 200,000자·제어 문자·정화 뒤 길이·빈 본문 422). 허용 요소 `p br strong em h2 h3 ul ol li blockquote a img`, 속성 `href`·`src`·`alt`·`ol start`. 실패한 `href` 는 지우고 글자는 남기며, `/api/media/<소문자 uuid>` 가 아닌 `img` 는 요소째 지운다.
- 링크 검사기는 서버 함수 하나(`LinkCheck.ok`). 본문 링크가 1-C1 에서 쓰고 메뉴(1-C4)가 같은 함수를 쓴다. 프런트 판정 `linkHref` 는 첫 소비자(1-C2 편집기)와 함께 만든다.
- API 여덟: 관리 일곱(목록·상세 GET, 작성·수정·삭제·발행·발행 취소 POST)과 공개 `GET /api/pages/{slug}`. 상태 변경은 1-B 관례대로 모두 POST 다. 재인증 대상이 아니다(D15:545). 작업 로그 동작 다섯(`page.create`·`page.update`·`page.delete`·`page.publish`·`page.unpublish`), 값은 남기지 않는다.
- 공개 화면 `/:slug`(형식이 틀리면 요청 없이 404 화면), SPA 404 에 `noindex`(Google Search Central 「Avoid soft 404 errors in single-page apps」 확인), 문서 제목은 React 19 `<title>`.
- 빌드 산출물에만 `<meta>` CSP, E2E 는 `vite preview` 로 빌드 산출물을 상대로 돈다. 모든 E2E 가 CSP 위반 0건을 단언하고, CSP 가 실제로 걸렸는지 따로 본다.
- 첫 Task 후보: CI E2E 시간 조사. PR #6 의 「Playwright」 단계 8분 25초는 `npm ci`·`playwright install --with-deps`·`playwright test` 셋의 합이다. 원인은 추정하지 않고 로그 시각·시험별 시간·이미지 내려받기·단계별 시각으로 가른다. 빌드 E2E 로 바꾸기 전에 기준선을 잰다.
- 메모리 측정: 1-C1 에서는 재지 않는다. 1-C 를 다 마친 뒤 1-B 와 같은 방법으로 다시 잰다. 1-C3 의 180m 사진 측정은 1-C3 완료 기준이다.

결정 문서와 다른 곳(역할 에이전트 결정을 고친 것, 스펙 8.1):

- DOMPurify 설정에 `ALLOW_DATA_ATTR: false`·`ALLOW_ARIA_ATTR: false`·`ADD_URI_SAFE_ATTR: ["start"]` 를 더했다. 3.5.0 배포 소스에서 확인: `data-*`·`aria-*` 판정이 기본 `true` 이고 `ALLOWED_ATTR` 보다 앞이다. URI 안전 목록에 없는 속성은 값을 `ALLOWED_URI_REGEXP` 로 보므로, 결정의 정규식이면 `ol start="3"` 이 떨어진다.
- `img src="data:…"` 는 DOMPurify 가 정규식과 무관하게 통과시킨다(`DATA_URI_TAGS` 기본에 `img`, `ADD_DATA_URI_TAGS` 는 더하기만). 결정 문서의 「미확인」을 「확인」으로 바꾼다. 스크립트 자리가 아니라 훅으로 지우지 않는다 — 서버가 지우고 CSP `img-src 'self'` 가 막는다.
- 그래서 XSS 벡터 파일은 하나지만 기대 열을 같이 쓰지 않는다. 서버는 바이트 일치, 프런트는 불변식(`on*`·허용 밖 요소·허용 밖 주소 없음), 그리고 `DOMPurify.sanitize(서버 출력) == 서버 출력` 의 짝 시험이다.
- M-12 는 `/api/me` 에 superuser 칸을 더하지 않고 서버가 거른다: A-402 상세의 `assignable_roles` 에서 비-superuser 에게 superuser 역할을 뺀다. A-403 이 역할의 `visitor_only` 와 권한의 `visitor` 를 내고, 화면은 모두가 갖는 역할에 방문자 권한만 고르게 한다.

원문과 다른 곳:

| 원문 | 1-C1 | 이유 |
|---|---|---|
| 정화 HTML 허용: 표·밑줄·취소선·색·정렬·크기·유튜브 iframe·지도 블록(D60) | `p br strong em h2 h3 ul ol li blockquote a img` 만 | 사용자 답은 「굵게·링크·사진」. 필요하면 허용 목록과 시험 표를 함께 넓힌다 |
| 링크 `http`·`https`·`mailto`·`tel`, 외부 링크에 `rel="nofollow noopener"`·새 창(D60:201) | `http`·`https`·내부 경로만, `rel`·`target` 을 받지도 붙이지도 않는다 | 메뉴와 검사기 하나(Q9). 새 창이 없으니 `noopener` 가 필요 없다. `nofollow` 는 UGC 가 생기는 Phase 2 게시판에서 다시 본다 |
| 이미지 주소 제한 없음 | `/api/media/<uuid>` 만 | 외부 이미지는 추적 픽셀이다. CSP `img-src 'self'` |
| `pages.body`(평문)·`template` | 없음. `body_html` 하나 | 평문은 쓸 곳이 생기면 `text()` 로. 템플릿은 1-D(Q15) |
| `pages.slug`·`title` 에 DB CHECK 없음 | 슬러그 형식·100자, 제목 1~200자 CHECK | 공개 주소·API 경로에 들어가는 값의 마지막 둑 |
| 예약 경로 스물하나(D19:2615) | `api`·`assets`·`error` 를 더한다 | SPA·빌드 산출물·Boot 오류 처리기의 첫 마디 |
| 저장·발행 성공 303(폼 PRG) | 201 `{id}`·204, 오류는 Problem Details | SPA 관례(1-B Q3) |
| 같은 상태로 발행: 문서는 멱등(D19:2688), 원문 코드는 422 | 2xx 이고 기록하지 않는다(Q7) | 문서를 따른다. 실제로 바꾼 요청만 기록 |
| 수정 본문의 발행 상태: 원문 코드는 무시 | 422 `errors.status`(Q8) | 문서 「무시가 아니라 거부」 |
| 페이지 삭제가 그 주소의 메뉴를 지운다(원문 코드) | 메뉴를 건드리지 않는다(Q14) | `page.delete` 만으로 `menu.manage` 몫을 바꾸지 못하게 |
| A-301 은 전부 읽는다(원문 코드) | 쪽당 50, 한 행 더 읽어 `has_next`(Q19) | SC-4 쪽 상한 |
| 공개 페이지가 `/{slug}` 의 서버 HTML, 없으면 404 상태 | SPA `/:slug` + `GET /api/pages/{slug}`. 문서 상태는 200 + `noindex` | HTML 404 상태·메타는 Phase 4(사용자 답 Q2) |
| A-404 R4 거절 문구 하나(D19:3184) | 빼기는 「보유하지 않은 권한은 뺄 수 없습니다」(M-14) | 빼기에 「부여할 수 없습니다」가 어색하다 |
| CSP 에 `script-src` 없음(D86 3.8, 테마 인라인 때문) | 빌드 `<meta>` CSP 에 `script-src 'self'` | 서버 템플릿이 없어 그 이유가 사라졌다 |

결정 필요(스펙 17절, 추천 없음 — 답이 오기 전에는 원문 동작으로 둔다):

1. 발행된 페이지의 수정 권한 — `page.update` 만으로 발행된 페이지를 바로 고친다(원문) / `page.publish` 도 요구 / 고치면 초안으로 내림.
2. 인증 전 요청 본문 크기 상한 — 두지 않음(지금) / 요청 크기 상한 / Jackson 문자열 상한만.
3. 동시 편집 — 나중 저장이 이긴다(원문) / 읽은 `updated_at` 이 다르면 409.

1-C1 스펙 결정 필요 셋(2026-11-24, 역할 에이전트 결정 — 사용자 확인 전): ① 발행된 페이지의 수정·슬러그 변경은 `page.publish` 도 요구(원문과 다르다 — 발행 승인 분리가 첫 발행에만 걸리지 않게), ② 모든 요청 본문 1 MiB(맨 앞 필터, 두 컨텍스트, multipart 해석기 끔, 1-C3 업로드 한 경로만 10 MiB) — 권한 검사보다 앞서는 본문 해석으로 익명이 힙을 쓰는 길을 막는다, ③ 동시 편집은 나중 저장이 이긴다(원문). 스펙 17절.

## Phase 1-C1 구현 중 결정과 보안 점검 (2026-11-24)

스펙 `docs/superpowers/specs/2026-11-24-phase1c1-pages-sanitize-design.md`, 계획 `docs/superpowers/plans/2026-11-24-phase1c1-pages-sanitize.md`. Task 마다 개발 에이전트가 순서대로 맡았다. 스펙에는 아래 「고친 것」과 「구현 때 확인」의 결과를 반영했다(0·1·3.1·3.2·3.3·5.1·5.2·6·8.3·8.4·8.5·13.2·15·17절 2·18절).

구현하며 찾아 고친 것(시험이 잡았다):

1. **폼 본문이 1 MiB 필터를 우회했다.** 필터만 있으면 chunked 폼 1,500,000바이트가 `getParameter` 로 1,499,998자 다 해석됐다(Tomcat 이 자기 입력 버퍼에서 폼을 읽어 세는 래퍼를 지나지 않는다). `server.tomcat.max-http-form-post-size=1MB` 를 넣었다 — 계획 「다르게 정한 것」 1 의 「확인: 우회 있음 → 폼 상한 1MB」(`RequestSizeLimitTest` 「폼_chunked_초과는_1MiB_넘게_해석되지_않는다」).
2. **chunked 폼 초과가 500·경로 든 오류 본문이 됐다.** PUT·PATCH·DELETE 폼은 `FormContentFilter` 가 MVC 밖에서 세는 스트림을 읽어 500, CSRF 헤더 없는 POST 폼은 `CsrfFilter` 의 `getParameter` 가 Tomcat 11 `InvalidParameterException`(413)을 내 Boot 기본 오류 본문(application/json, `path` 포함)이었다. 필터가 `chain.doFilter` 를 감싸, 응답이 미커밋이고 원인 사슬에 세는 스트림의 예외나 `InvalidParameterException`(errorCode 413)이 있으면 지우고 413 Problem Details 를 쓴다. Tomcat 클래스에 기대는 곳은 이 한 줄이다(「POST_가_아닌_chunked_폼_초과도_500_이_아니라_413」).
3. **정화 한 번은 멱등이 아니다.** 허용 밖 껍데기가 빠지면 `<p>a<object><p>b</p></object></p>` → `<p>a<p>b</p></p>`(파서가 만들 수 없는 꼴)이고, 다시 걸면 `<p>a</p><p>b</p><p></p>` 다(`ul>li>section>li`·`a>object>a`·`h2>div>h2` 도 같고 셋째부터 안정). `clean = once(once(x))`, `ponytail:` 수렴하지 않으면 고정점까지(`RichTextTest` 「빠진_껍데기가_자동_닫힘을_막던_꼴도_멱등이다」). 고치지 않으면 저장값·내보낸 값·DOMPurify 결과가 서로 달라진다(스크립트 자리는 아니다).
4. **엔티티로 쓴 제어 문자가 저장될 뻔했다.** jsoup 1.24.1 은 `&#0;` 을 U+FFFD 로 바꾸지만, 그 밖의 C0 는 출력에서 `&#x8;` 같은 엔티티로 다시 쓰고, DEL·C1 일부(U+007F·0081·008D·008F·0090·009D)는 날 문자로 낸다. 출력 문자열만 보면 엔티티 꼴이 지나간다. `forSave` 가 출력을 `Parser.unescapeEntities(out, false)` 로 푼 값을 검사한다 — `<p>&#8;</p>`·`alt="&#8;"`·`&#127;`·`&#129;` → 422 「쓸 수 없는 문자가 있습니다」, `&#0;` → U+FFFD 로 201(`RichTextTest` 「forSave_엔티티로_쓴_제어_문자는_푼_값으로_본다」, `PagesApiTest` 「엔티티_제어_문자는_422_이거나_무해한_치환이고_저장값에_제어_문자가_없다」).
5. **빌드 CSP 아래 모든 화면에 `script-src` `eval` 위반이 났다**(첫 빌드 E2E 223건, 15개 모두 실패). zod 4.6.7 이 객체 스키마를 만들 때 `new Function("")` 으로 JIT 가능 여부를 떠본다(`v4/core/util.js` `allowsEval`). `src/zod-jitless.ts`(`z.config({ jitless: true })`)를 `main.tsx` 첫 import 로 두었다. **`script-src` 는 넓히지 않았다.** CSP 아래에서는 탐지가 원래 실패해 JIT 가 꺼지므로 빌드 동작은 같다(개발 서버에서도 꺼진다).
6. **Radix 대화상자가 `<style>` 을 넣는다**(스펙 8.3 이 예상한 길). `style-src-elem` `blocked: "inline"` 5건 — `/admin/roles` 1, `/admin/users/{id}` 4, 시험 `users.spec.ts` 「재인증 창이 닫히면 같은 대화상자가 비밀번호를 묻고 비활성화된다」·「확인 단추를 두 번 눌러도 POST 는 하나」·「키보드만으로 대화상자를 열고 비밀번호를 넣고 확인한다」. `style-src` 에만 `'unsafe-inline'` 을 더했다(플러그인과 E2E 기대 문자열 두 곳). React 의 `style` 속성 쓰기는 위반이 없었다.
7. **스펙 8.5-2 「`script-src` 계열 위반 0건」은 그대로 성립하지 않았다.** Chrome 은 DOMPurify 가 쓰는, 붙이지 않은 DOMParser 문서를 파싱하기만 해도 처리기 속성·`<base>` 를 위반으로 보고한다(탐침: 빌드 산출물에서 `new DOMParser().parseFromString('<p onclick="x">a</p><base href="https://evil.example/">', "text/html")` 만 불러도 `script-src-attr` 1 + `base-uri` 1). 실행이 아니다(`__xss` 0, 대화상자 0, DOM 불변식 0). 일곱 줄만 경로 완전 일치로 받고(`script-src-attr` inline: `/v-x3`·`/v-x4`·`/v-x5`·`/v-x11`·`/v-x14`·`/v-x15`, 줄마다 2건; `base-uri` `https://evil.example/`: `/v-x9`), 끝 단언을 `script-src`·`script-src-elem` 0건으로 바꿨다. `RichHtml` 에서 `sanitize` 를 빼면 X1 「허용 밖 요소 script」로 실패한다(주 세션 승인).
8. **DOMPurify 3.5.0 은 빈 `href`·`src` 값을 설정과 무관하게 남긴다**(`dist/purify.es.mjs` 2226줄 `return !value`). 프런트 불변식이 X23-40(`<a href="">x</a>`)에서 깨졌다. 설정 여섯 줄로는 못 막고 훅은 스펙 8.1 이 금지한다. 불변식에 「값이 정확히 빈 문자열」 절을 더했다 — 빈 링크는 현재 문서로 풀려 스크립트 자리가 아니고 서버는 지운다(주 세션 승인, 스펙 13.2).

정한 것:

- 계획의 「스펙과 다르게 정한 것」 여덟의 결과
  - 1 폼 상한: 우회 있음 → `max-http-form-post-size=1MB`(위 1).
  - 2 chunked 초과: JSON 은 400(`detail` 「Failed to read request」, 운영·설치 같음). 폼은 1 MiB 넘게 해석되지 않는다 — PUT·PATCH·DELETE 와 CSRF 헤더 없는 POST 는 413(위 2), CSRF 헤더 있는 POST 는 `getParameter` 가 던져 탐침이 잡는다(200·`length` -2, 처리기 안에서 던질 때의 상태는 시험 없음). 스펙 허용 범위 「413 또는 400, 500 아님」 안이다.
  - 3 `한글_200000자_본문은_상한_안에서_201`: 계획은 Task 4 로 옮긴다고 했으나 `RequestSizeLimitTest` 에 있다(Task 4 가 작성 API 를 만든 뒤 그 클래스에서 편집자로 `POST /api/admin/pages`).
  - 4 X23·X24 는 속성 이스케이프해 펼쳤다 — X23 은 13.3 거부 40값 모두(`X23-01`~`X23-40`), X24 는 허용 9값, `vectors.json` 92줄.
  - 5 E2E 는 `alert(1)` 을 `window.__xss=1` 로 바꿔 쓰고 `dialog` 도 모은다.
  - 6 벡터는 Task 2(정화)·Task 3(링크) 순서로 더했다.
  - 7 `<title>`: React 19.3.1 이 올린 `<title>` 을 기존 `head > title` 앞에 끼워 이긴다(E2E `toHaveTitle("소개 – E2E 사이트")`). `useEffect` 는 넣지 않았다.
  - 8 413 본문은 필터가 직접 쓰는 Problem Details 다.
- `PagesController.validate(PageInput)` 가 칸을 모아 검사하고 통과하면 정화된 본문을 돌려준다(계획의 `errors` Map 대신 — 정화를 두 번 더 돌지 않게). 작성 201 은 `UsersController.Created` 를 다시 쓴다. `PageInput.status` 는 OpenAPI 에서 숨겼다.
- V5 가 editor 에 `page.*` 를 주어 「editor = admin.access 만」을 단언하던 `RolesApiTest` 셋의 권한 목록에 `page.*` 다섯을 더했다(원래 뜻 그대로).
- `ReservedSlugsTest` 는 `org.springdoc` 처리기(`/v3/api-docs`, `scope test`)를 빼고 센다. 모은 집합에 `api`·`error` 가 있는지도 단언해 공허 통과를 막는다.
- 벡터 저장 시험은 `<p>z</p>` 를 **앞에** 붙인다. 뒤에 붙이면 X13·S1·S2·S3 이 달라진다(붙인 문단이 열린 요소 안에 들어간다 — 스크립트 자리 아님).
- 공개 화면 `PageView` 는 바깥(형식·100자 검사)과 안쪽(`useSite`·`useQuery`) 둘로 나눴다 — 한 부품이면 `useSite` 가 `/api/site` 를 보내 「형식이 틀린 슬러그는 요청 0」이 깨진다. 초점은 `useFocusOnMount(page.data)`, `NotFound` 도 h1 에 초점.
- HTML 싱크 검사는 스펙보다 한 단계 엄격하다 — `RichHtml.tsx` 에서 `dangerouslySetInnerHTML` 이 든 줄마다 같은 줄에 `DOMPurify.sanitize(` 가 있어야 한다. 예외 표는 `tests/html-sinks.test.sh` 의 `EXCEPTIONS` 한 곳(1-C4 가 `href={` 한 줄을 더한다).
- E2E: 브라우저 판정은 복사하지 않고 `frontInvariant` 를 그대로 `locator.evaluate` 로 넘긴다(바깥 변수를 읽지 않게 정규식을 함수 안으로, 허용 태그를 인자로). 스택 없는 두 시험의 나머지 `/api/` 는 404 로 받는다(`pathname.startsWith("/api/")` — 글로브 `**/api/**` 는 개발 서버 모듈 주소까지 잡는다). 기존 12개 중 `login.spec.ts` 한 줄만 포트를 4173 으로 바꿨다.
- 「구현 때 확인」의 결과
  - jsoup 1.24.1(`prettyPrint(false)`·UTF-8·escapeMode 기본): 상대 `href="/about"` 남는다, `&nbsp;` → `&nbsp;`, `&` → `&amp;`, `<br>` 꼴, 속성 따옴표 `"`, A1 은 바이트 그대로. 요지 줄: X11 `""`, X12 `<p></p>`, X13 `""`, S3 `<p><strong>a</strong></p><p><strong>b</strong></p>`, I10 `<img src="/api/media/U" alt="&quot; onerror=&quot;x">` — 모두 스크립트 자리 없음. `&#0;` → U+FFFD, `&#8;` 은 위 4.
  - 깊은 중첩: `<em>`×40000 `clean` 153 ms(`RichTextTest` 단독 실행 출력), `<em>`×50000(200,000자) 작성 422 「200,000자 이하」 232 ms(`PagesApiTest` 출력). 각 한 번 찍힌 값이고 따로 재지 않았다.
  - `body_html` 이 `[]`·`{}` → 400·행 없음, `123`·`true` → 201·저장값 `123`·`true`(Jackson 스칼라 강제 변환, 전역 설정 그대로).
  - 질의 수(`pg_stat_statements`, `pages` 가 든 문장): 공개 조회 익명 1(200·404 모두), 형식이 틀린 슬러그(`Foo`·`a.b`·`-a`·`a_b`·101자) 0, A-301 목록 1(3행·54행 같음), A-402 상세 4(`users`·`roles` 가 든 문장, 인증 1 + 상세 셋, 역할 1개·5개 같음 — 넘어온 (e)).
  - `page=99999999999` 400, `page=2147483647` 200·빈 `items`(offset long). 상세 응답 `Cache-Control: no-store`.
  - 미사용 권한 WARN: Task 4 뒤 `page.view`·`page.update`·`page.delete` 셋 → Task 5 뒤 전체 백엔드 0건.
  - M-11: 전체 백엔드의 모든 컨텍스트 기동이 새 정규식 `^/(api/)?[^/]*[{*?]` 을 통과했다(운영 매핑 중 첫 마디가 변수인 것 없음).
  - `<meta>` CSP 는 `dist/index.html` 의 `head` 첫 자식이다(`<meta charset>` 앞). 값의 `'` 는 `&#39;` 로 나가고 브라우저가 풀어 E2E 의 `toHaveAttribute` 가 스펙 문자열과 같다. 산출물에 인라인 `<script>`·`<style>` 0, CSS `data:` 0(폰트는 woff2 파일 다섯). `assetsInlineLimit` 은 손대지 않았다.
  - `addInitScript` 리스너는 CSP 아래에서도 붙는다(CSP 적용 시험이 위반 1건을 받는다).
  - `vite preview` 는 `preview.proxy ?? server.proxy` 로 `/api` 를 넘기고, `/login`·`/admin/users`·`/about` 은 index.html(CSP 포함)을 낸다.
  - Playwright `webServer.timeout` 기본 60000ms(`playwright/types/test.d.ts`) → 180000ms.
  - 빌드 묶음 JS 527.92 kB(gzip 167.11) → 560.10 kB(gzip 179.77), +32.18 kB — DOMPurify 와 새 화면 둘(PageView·NotFound). CSS 35.07 → 35.13 kB.
  - 시간: `npm run build`(tsc + vite build) 3.34s, `mise run e2e` 15 passed (2.5m, Playwright 출력, webServer 빌드 포함), 그중 DOMPurify 겹 51s. 벽시계 시간은 재지 못했다. CI `timeout-minutes: 20` 은 그대로이고, PR CI 의 단계 시간은 아직 보지 않았다(PR 전).
- jsoup 판: OSV `org.jsoup:jsoup@1.24.1` 0건, Maven Central `maven-metadata.xml` `release`·`latest` 1.24.1, 전이 의존 없음.
- DOMPurify 판과 권고 대조
  - npm `latest` 3.5.0(2026-11-24 조회). OSV `dompurify@3.5.0` 0건이지만 OSV 에는 GHSA-hrxw·GHSA-qm7q 가 아직 없다. 그래서 근거는 GitHub 권고 목록의 patched 값이다. 전이 의존 `@types/trusted-types@2.0.7`(optional) OSV 0건. 타입은 패키지에 있다(`@types/dompurify` 불필요). context7 은 월 한도 초과 — 설정 이름은 설치된 `dist/purify.cjs.d.ts`·`purify.es.mjs` 와 설정 줄별 변이로 확인했다.
  - 판정: GitHub 권고 30건 모두 patched ≤ 3.5.0 이고, 우리 설정(기본 모드 + 스펙 8.1 여섯 줄, 훅·`IN_PLACE`·`RETURN_DOM` 없음, 결과를 `div` 에 바로 넣음)에 닿고 고쳐지지 않은 것은 0이다. 한 줄씩 남긴 것은 29건이다(30건과의 차이 1건은 기록에 이름이 없다 — 최종 검토에 넘긴다).

    | 권고 | 우리 설정에서 |
    |---|---|
    | GHSA-87xg(`selectedcontent` 재복제, 기본 설정·문자열 경로, 3.4.4 만) | 기본 모드라 닿는 종류였으나 3.4.5 에서 고쳐짐. `select`·`selectedcontent` 는 `ALLOWED_TAGS` 밖 |
    | GHSA-v9jr(기본 설정 + 앞선 프로토타입 오염) | 전제가 오염이라 직접은 아님, 3.4.0 고침 |
    | GHSA-h8r8(재맥락화 mXSS)·GHSA-v2wj·GHSA-v8jm(`SAFE_FOR_XML` rawtext) | 정화 결과를 `xmp`·`noscript`·`textarea` 같은 rawtext 감싸개 안에 다시 넣을 때만 — 우리는 `div` 에 바로 넣는다. 3.3.2·3.2.7 고침 |
    | GHSA-cj63(`USE_PROFILES`)·GHSA-cjmm(`ADD_ATTR` 함수) | 그 옵션을 쓰지 않는다. 3.3.2 고침 |
    | `IN_PLACE` 계열 GHSA-4w3q·x4vx·rp9w·hpcv·r47g·55q2·6688·p98j·mrv2 | 쓰지 않는다. 4w3q 는 목록 patched 3.4.4(본문 머리말은 「고칠 계획 없음」), 나머지 ≤ 3.4.17 고침 |
    | `SAFE_FOR_TEMPLATES` 계열 GHSA-crv5·gvmj·cg2g·qm7q·hrxw | 쓰지 않는다. hrxw 가 3.5.0 에서 고쳐진 마지막 것 |
    | `CUSTOM_ELEMENT_HANDLING`·함수형 `ADD_TAGS`·`ADD_ATTR` 계열 GHSA-c2j3·39q2·h7mw·9p3w | 쓰지 않는다. 9p3w 는 목록 patched 3.4.0(본문 머리말은 「고칠 계획 없음」) |
    | 훅·`setConfig`·Trusted Types·XHTML 계열 GHSA-76mc·cmwh·vxr8·c6gr | 쓰지 않는다 |

  - 읽은 깊이: 본문을 끝까지 읽은 것은 87xg(Impact 포함)·h8r8·hrxw·c6gr 넷, 앞 1,500자만 읽은 것은 v9jr·9p3w·4w3q 셋이다. 나머지는 제목·영향 판만 봤다.
- DOMPurify 프런트 시험: 계획 시험 넷에 「서버_허용_목록_밖의_속성은_남지_않는다」를 더했다 — `ALLOWED_ATTR`·`ALLOW_DATA_ATTR`·`ALLOW_ARIA_ATTR` 를 빼도 다른 시험은 실패하지 않는다. 변이 일곱(설정 여섯 줄을 하나씩 빼기와 `sanitize` 빼기)이 모두 실패한다. 짝 시험 96줄(92 + W1~W4)은 처음부터 일치했다 — 서버 `OutputSettings`·`vectors.json` 은 고치지 않았다(jsdom 직렬화 기준, Chromium 은 E2E).

D86(ondolith 보안 점검 목록) 대조 — 1-C1 범위(스펙 14절):

| D86 | 결과 |
|---|---|
| **1.12 프록시 뒤 IP** | **미해결 — Phase 4 배포 전 필수.** 새 작업 로그 행의 `ip` 도 같은 한계. 공개 조회는 쓰기가 없어 새 영향이 없다 |
| 2.1 처리기 권한·기동 점검 | ✓ `PagesApiTest` 「page_update_만_가진_역할은_작성_발행_발행취소가_403이고_기록이_없다」·「page_update_만_가진_역할은_삭제가_403」·「page_create_만_있고_admin_access_가_없으면_403」·「익명은_401」·「상세는_편집자_200_page_view_만이면_403」·「page_view_만이면_목록_200_권한이_없으면_403」·「발행_권한_없는_역할은_발행된_페이지를_고칠_수_없다_403」(스펙 17절 1, 판정을 빼면 204), `GuardCheckTest` 「첫_마디의_변수와_와일드카드도_문제다」·「첫_마디에_변수가_있으면_기동이_멈춘다」(M-11). 「어느 API 도 쓰지 않는 권한」 WARN 0건 |
| 2.5 대량 할당 | ✓ `PagesApiTest` 「status_가_실리면_422_이고_다른_모르는_칸은_무시」·「수정의_status_는_422_이고_상태가_그대로」 |
| 2.7 `{id}` 는 uuid 만, 형식 불량 404 | ✓ `PagesApiTest` 「없는_id_와_UUID_가_아닌_경로는_404」(발행·발행 취소 `abc`·`%20`·`1`)·「없는_id_는_404_틀린_본문이면_422」(상세·수정·삭제 같은 셋), 「형식이_틀린_슬러그는_404_이고_질의가_0」 |
| 2.8 관리자 60/분 | 새 관리 API 일곱 모두 `/api/admin/**` 라 1-A 필터에 든다. 새 경로를 직접 친 횟수 시험은 없다 |
| 3.1 CSRF | ✓ `PagesApiTest` 「CSRF_없으면_403」·「수정_삭제_상세는_익명_401_CSRF_없으면_403」 |
| 3.3~3.5 이스케이프 우회 지점의 수와 출처 | ✓ `tests/html-sinks.test.sh`(고정 입력 27건, 실제 트리를 두 번 깨 실패 확인 — 다른 파일에 한 줄·`sanitize` 빼기), `RichHtml.test` 「사유_주석이_있다」·「img_onerror_와_javascript_링크가_DOM_에_남지_않는다」 |
| 3.6 리치 텍스트 정화 두 번, 열린 태그를 본문 안에서 닫는다 | ✓ `RichTextTest` 「벡터_표의_모든_줄이_server_와_바이트까지_같다」·「멱등이다」·「빠진_껍데기가_자동_닫힘을_막던_꼴도_멱등이다」·「모든_출력은_허용_목록만_남는다」, `PagesApiTest` 「벡터_입력을_저장하면_저장값과_공개_응답이_server_와_같다」·「DB_에_넣은_옛_HTML_도_공개와_상세에서_정화된다」(내보낼 때 `clean` 을 빼면 실패), `RichHtml.test` 「모든_입력에서_프런트_불변식이_성립한다」·「서버_출력은_DOMPurify_가_바꾸지_않는다」, E2E 「API 로 만든 페이지가 비로그인 방문자에게 정화된 채로 보인다」·「DOMPurify 겹: 서버를 거치지 않은 오염 HTML 도 스크립트가 돌지 않는다」. 한계: 서버 두 겹은 같은 구현(jsoup)이라 같은 우회에 함께 진다(스펙 16절 1) |
| 3.7 보안 헤더 | ✓ 공개 HTML 은 빌드 `<meta>` CSP — E2E 「CSP 가 실제로 걸려 있다」(내용 일치·`head` 첫 자식·끼운 인라인 스크립트가 돌지 않고 위반 1건). API 헤더는 그대로. 한계: `frame-ancestors`·`X-Frame-Options` 는 Phase 4 CloudFront(`<meta>` 로 걸리지 않는다 — 추정) |
| 3.8 CSP 에 `script-src` 없음 | **바뀐다** — `script-src 'self'` 를 건다. 모든 E2E 가 받지 않은 위반 0건을 단언한다(zod `jitless`, 위 5). `style-src` 에는 `'unsafe-inline'`(위 6) |
| 3.10 오류가 내부를 말하지 않는다 | ✓ `PagesApiTest` 의 거절 단언(`refused`)이 상태·`application/problem+json`·`detail` 을 코드 상수와 정확히 비교한다, `PageView.test` 「500_이면_고정_문구이고_서버_detail_을_그리지_않는다」·「연결_실패도_고정_문구」. 한계: Problem Details `instance` 에 요청 경로가 실린다(아래 「넘긴 것」) |
| 4.1 SQL 바인딩 | 직접 친 시험은 없다. 새 질의는 모두 상수 문자열 + `JdbcClient.param`(`PagesController`·`PageController`) |
| 7.1 요청당 고정 질의 수 | ✓ `PagesApiTest` 「익명_요청의_질의는_하나」(1), 「형식이_틀린_슬러그는_404_이고_질의가_0」 |
| 7.3 목록 질의 한 번 | ✓ `PagesApiTest` 「목록_질의는_행_수와_관계없이_같다」(1), `UsersApiTest` 「상세_질의_수는_역할_수와_관계없이_같다」(4, 넘어온 (e)) |

스펙 17절 2(모든 요청 본문 1 MiB): ✓ `RequestSizeLimitTest` 열 개 — 「익명_1MiB_넘는_Content_Length_는_처리기_전에_413」·「정확히_1MiB_는_막지_않는다」·「한글_200000자_본문은_상한_안에서_201」·「chunked_1MiB_초과도_끊기고_500_이_아니다」·「폼_Content_Length_초과는_413」·「폼_chunked_초과는_1MiB_넘게_해석되지_않는다」·「POST_가_아닌_chunked_폼_초과도_500_이_아니라_413」·「multipart_해석기가_없다」·「필터가_세션과_보안_체인보다_앞이다」·「설치_컨텍스트에도_걸린다」. 남은 위험: 요청당 상한이라 동시 요청의 합은 묶지 않는다(Phase 4 앞단 제한).

시험(2026-11-24, 6a2acaf 트리 — 이 커밋은 문서만 바꾼다. 시험은 `docs/` 를 읽지 않는다): `nice -n 19 mise run test` → 셸 132 통과(check-public 17·html-sinks 27·measure 67·pre-commit 4·summarize 17)·백엔드 325 통과(surefire 합: 실패 0, 오류 0, skip 0)·프런트 190 통과(파일 23)·typecheck 0, exit 0. `mise run e2e` → 이번에는 미실행(문서만 바꿨다). Task 9(6a2acaf)에서 15 통과(admin 3·install 2·login 2·users 5·pages 3, 2.5m). 메모리 측정은 1-C 끝(1-C4 뒤, 스펙 11절) — 미실행.

최종 검토에 넘긴 것(고치지 않았다):

- 공개 404 를 포함한 모든 Problem Details 의 `instance` 에 요청 경로(`/api/pages/<slug>`)가 실린다. 요청자가 보낸 값을 JSON 문자열로 돌려줄 뿐이고(Content-Type problem+json·nosniff) 프런트는 고정 문구만 그린다. 바꾸면 `ApiErrors` 한 곳에서 비운다(Task 4).
- E2E DOMPurify 겹의 일곱 줄 예외(`script-src-attr`·`base-uri`, 경로 완전 일치) — 진짜 위반을 가릴 수 있다. 실행 회귀는 불변식·`__xss`·대화상자가 잡는다. `script-src-attr` 가 줄마다 2건인 까닭(탐침은 1건)은 확인하지 않았다(Task 9).
- DOMPurify 권고 「30건」 가운데 한 줄씩 남긴 것은 29건이다. 최종 검토가 다시 세어 보니 기록에서 빠진 것은 3건이고 모두 2.x 에서 고쳐졌다(우리 3.5.0 에 닿지 않는다). 본문을 끝까지 읽은 것은 넷뿐이다(Task 7).
- W1~W4 의 `server` 값은 백엔드 시험이 고정하지 않는다. 서버 정화가 바뀌어도 짝 시험은 낡은 값으로 통과한다 — 그때 `clean` 을 다시 돌려 고친다(Task 7).
- 싱크 검사의 「같은 줄」 규칙은 여러 줄로 감싼 포매팅을 막는다(Task 8).
- `GuardCheck` 의 첫 마디 변수 거부는 Phase 4 의 `/{slug}` 껍데기 처리기 전에 좁혀야 한다(`ponytail:`, Task 6).
- `/v3` 는 예약어가 아니다 — springdoc 이 운영 의존이 되면 `/v3` 슬러그 페이지가 문서 경로에 가려진다(Task 3).
- `body_html` 에 `123`·`true` 를 보내면 201 이고 그 글자가 본문이 된다(Jackson 스칼라 강제 변환, 전역 설정은 바꾸지 않는다, Task 4).
- 엔티티 제어 문자는 검사가 출력을 푼 값을 본다 — jsoup 이 판마다 출력 꼴을 바꾸면 이 검사의 전제가 흔들린다. DB 왕복 시험(`PagesApiTest`)이 지킨다(Task 2).
- Red 를 따로 보지 못한 시험: `RichTextTest` 「멱등이다」(벡터가 모두 안정 꼴 — Red 는 「빠진_껍데기…」 가 봤다), 「정규식_끝_개행은_find_에서_맞는다…」(JDK 동작 확인), 공개 조회의 `clean` 은 Task 4 시험으로는 안 보였고 Task 5 「DB_에_넣은_옛_HTML…」 이 봤다. reservedSlugs·`NotFound` noindex 정리는 처음부터 통과해 변이로 Red 를 봤다.
- `mise run e2e` 벽시계 시간과 PR CI 단계 시간은 재지 않았다(Playwright 2.5m 만 있다).

### 1-C1 최종 검토 (2026-11-24, 새 검토자) — 판정 With fixes, Critical 0 · Important 2 · Minor 14

스크립트가 실행되는 길은 찾지 못했다. 검토자가 실행으로 확인했다: jsoup 무작위 입력 50만 개에서 허용 목록·값 검사 위반 0, 서버 출력 6,184개를 Chromium 에 넣어 허용 밖 요소·속성 0·실행 0, DOMPurify 단독(날 입력 3,092 + 링크 거부 값 20)으로 불변식 위반 0·실행 0, 링크 검사기 통과값 1,690만 개를 Node `URL`(표본 60만 개는 Chromium `URL`)로 풀어 `javascript:` 계열 0·다른 출처로 풀리는 내부 경로 0. 서버 정화 겹과 DOMPurify 겹이 CSP 없이도 각각 혼자 선다.

고친 것:

- **I-1** `clean` 을 두 번 거는 것으로는 멱등이 아니었다(`<h3><a><h2><noscript><a>x<br>` 는 세 번째에서 안정, 무작위 50만 개 중 203개). 바뀌지 않을 때까지 다시 건다(상한 8) — `RichTextTest.세_번째_통과에서야_안정하는_꼴도_멱등이다` Red→Green. 상한에 닿아도 모든 통과 결과가 정화된 값이라 안전은 그대로다.

1-C2 로 넘기는 것:

- **I-2 발행 승인 분리의 틈**: 발행 요청이 id 만 받아, 승인자가 읽고 누르는 사이 `page.update` 만 가진 사람이 초안을 바꾸면 바뀐 내용이 공개된다. 관리 화면이 없는 1-C1 에서는 API 를 직접 쓰는 관리자만 닿는다. **1-C2 에서 발행 요청에 승인자가 읽은 `updated_at` 을 싣고 `WHERE … AND updated_at = ?` 로 다르면 409** 를 넣는다(화면이 그 값을 보낸다).

미룬 Minor와 갈 곳:

| 항목 | 갈 곳 |
|---|---|
| m-1 공개 조회가 익명 요청마다 `clean` 을 (이제 고정점까지) 돈다 — 시간은 재지 않았다 | 1-C 끝 메모리 측정 때 함께 잰다 |
| m-2 `alt` 에 `</style>` 같은 닫는 꼴이 있으면 DOMPurify 가 속성째 지운다(서버는 남김) — 짝 시험 밖 | 1-C3(`alt` 칸이 사진 단추와 함께 생긴다 — 1-C2 스펙 10절) |
| m-3 `RichHtml` 이 렌더마다 `sanitize` 를 다시 부른다 — `useMemo` | 1-C2 — 1-C2 에서 고침(Task 8) |
| m-4 E2E 일곱 줄 예외가 건수를 고정하지 않는다(줄마다 2건은 재렌더 — 검토자가 실행으로 확인) | 1-C2 — 1-C2 에서 고침(Task 8, 경로마다 1건. 「줄마다 2건」은 지금 트리에서 재현되지 않았다) |
| m-5 싱크 검사의 금지 이름 보강(`ADD_ATTR`·`ALLOW_UNKNOWN_PROTOCOLS`·`setHTMLUnsafe` 등) | 1-C2 — 1-C2 에서 고침(Task 8) |
| m-6 `style-src-attr 'none'` 으로 더 좁히기(미실행) | 1-C2(편집기가 인라인 스타일을 쓰는지 본 뒤) — 1-C2 에서 판정: 더했다(Task 8, Task 9 흐름에서 유지) |
| m-7 413 응답에 보안 헤더가 붙지 않을 것(추정) | 1-C2 — 1-C2 에서 고침(Task 4, 추정대로 없었다) |
| m-8 발행본 수정 403 을 기록하지 않는다 | 1-C2(I-2 와 함께) — 그대로 둔다 — 1-C2 스펙 6.2 |
| m-9 `body_html` 에 숫자·불리언을 보내면 문자열로 저장(201) | 한계로 둔다 — 정화를 지난다 |
| m-10 W1~W4 기대값이 프런트 시험에만 있다 | 1-C2(백엔드 시험에도 고정) — 1-C2 에서 고침(Task 2, `vectors.json` 96줄) |
| m-11 비-superuser 에게 admin 「빼기」 단추가 보인다(R3 가 늘 거절) | 1-C2 — 1-C2 에서 고침(Task 5) |
| m-13 「사용자 확인 전」 문구가 공개 문서에 남는다 | Phase 1 끝 사용자 확인 뒤 일괄 정리 |
| m-14 시험의 DB 접속에 시한이 없다 — `RequestSizeLimitTest.start` 가 12분 CPU 0% 로 멈췄다(재실행 통과, 원인 미확인. 1-B 의 `SwitchTest` 멈춤과 같은 꼴) | 원인을 모른 채 시한을 넣지 않는다. 다음에 멈추면 `jcmd <pid> Thread.print` 로 원인부터 잡는다 |

## Phase 1-C2 스펙 (2026-11-24, 설계 담당 — 사용자 확인 전)

스펙 `docs/superpowers/specs/2026-11-25-phase1c2-editor-design.md`. 리치 텍스트 후속 결정(1-C2 행, 3절 편집기, 5절 CSP)·1-C 결정 Q16·Q19·Q21 과 「1-C1 최종 검토」의 1-C2 몫을 옮기고, 결정 밖의 설계는 아래처럼 정했다.

정한 것:

- 첫 Task 는 IME 관문이다. 최종 부품 `RichEditor` 와 최소 작성 화면(`/admin/pages/new`)을 빌드 산출물 + CSP 위에서, 스택 없이 `page.route` 로 띄운다. 시험용 라우트는 두지 않는다. Playwright CDP `Input.imeSetComposition` → `Input.insertText` 로 다섯 장면(빈 문단·굵게·목록 안·링크 바로 뒤·받침 넘김)을 치고, 가로챈 저장 요청의 `body_html` 로 판정한다(편집기에 묶이지 않아 Lexical 에도 같은 시험). `compositionstart`·`compositionend` 를 세는 대조 단언과 제목 `<input>` 대조군을 둔다. 실패하면 설정으로 한 번 보고 Lexical, 둘 다 실패하면 멈춘다. 사용자 수동 확인(맥 한글 입력기, Chrome·Safari)은 완료 보고에 결과 또는 「미실행」.
- 새 의존성 다섯(프런트): `@tiptap/react`·`@tiptap/pm`·`@tiptap/starter-kit`·`@tiptap/extension-link`·`@tiptap/extension-list` 모두 3.35.0(npm `latest`, 2026-11-20, MIT). lock 에 새로 드는 패키지 51개, OSV(2026-11-24) 모두 0건. `prosemirror-view` 1.43.1 은 게시 하루째라 구현 때 다시 본다. context7 은 이 세션에 도구가 없어 레지스트리 tarball 의 배포 소스로 확인했다.
- 확장 = 서버 허용 목록. StarterKit 에서 Code·CodeBlock·Strike·Underline·HorizontalRule·TrailingNode 를 끄고, Link 는 속성을 `href` 하나로, OrderedList 는 `start` 하나(`^[0-9]{1,6}$` 가 아니면 1)로 `extend` 한다. 소스 확인: Link 는 `title`·`class`·`target`·`rel` 을 붙여넣은 HTML 에서 그대로 읽고, OrderedList 는 `type` 을 `type`·`style`·첫 `li` 의 `style` 에서 읽으며 `start` 범위를 보지 않는다. TrailingNode 는 문서를 바꾼 뒤에만 빈 문단을 붙여 출력이 정해지지 않는다.
- Link 는 `isAllowedUri: url => linkHref(url) !== null`, `autolink`·`linkOnPaste`·`openOnClick` 끔, `target`·`rel` null. 링크 입력은 `window.prompt` 가 아닌 같은 화면의 칸(E2E 대화상자 0건 단언). 저장값은 입력 문자열 그대로.
- 프런트 링크 판정 `linkHref`(`shared/lib`)는 서버 `LinkCheck` 규칙 + 브라우저 URL 파서(`safeNext`·`new URL`, 예외는 `null`). `links.json` 의 거부 40·허용 9를 그대로 읽는다.
- 짝 시험은 새 고정 파일 `richtext/editor.json`(`{id, input, editor}`): 편집기 출력 = 서버 `clean` 출력 = DOMPurify 출력. A1 은 `img` 가 있고 `li` 안에 `p` 가 없어 1-C2 에서 그대로 쓸 수 없다. Chromium 쪽은 E2E 에서 저장 요청과 상세 응답의 `body_html` 을 비교한다. m-10 으로 W1~W4 를 `vectors.json` 에 옮긴다(96줄).
- I-2: 발행 본문 `{updated_at}`(불투명 문자열, 화면은 상세 응답 값을 그대로 보낸다). `… WHERE id = ? AND status = 'draft' AND updated_at = ?`, 0행이면 없음 404 → 이미 발행 204(Q7, 기록 없음) → 초안인데 다름 409 「그사이 페이지가 바뀌었습니다. 바뀐 내용을 확인한 뒤 발행하세요」(기록 없음). 발행 취소는 그대로(값을 받지 않는다). 발행은 `updated_at = now()` 를 그대로 바꾼다(1-C1·원문 `store.sql:33`).
- 화면 쪽 I-2: 폼과 편집기는 상세 응답의 `updated_at` 이 바뀔 때마다 다시 채우되 고치는 중이면 채우지 않는다. 발행은 「지금 폼에 실린 응답」의 값을 싣는다. 고치는 중이거나 다시 읽는 중이면 발행 단추가 `aria-disabled` 다. 상세 쿼리는 창 초점 다시 읽기를 끈다(남이 고친 본문이 알림 없이 실려 409 를 비켜 가지 않게). 409 뒤에는 다시 읽기만 하고 자동으로 다시 보내지 않는다.
- 수정의 동시성: 나중 저장이 이긴다(그대로). 1-C1 17절 3 의 조건(자동 저장·편집이 사라졌다는 보고)이 서지 않았다 — 1-C2 는 자동 저장을 넣지 않는다(요청서·원문에 「자동 저장」·「임시 저장」 0건).
- m-8: 발행본 수정 403 은 기록하지 않는다(원문 D19:2653, 1-C1 7절). 낡은 `updated_at` 409·발행본 삭제 403(결정 3)도 같다.
- m-7: 413 응답에 API 보안 헤더 넷(`nosniff`·CSP·`X-Frame-Options`·`Cache-Control`)이 없을 것(추정) — Red 로 먼저 보고, 맞으면 필터가 직접 단다.
- 화면: A-301 `PagesPage`(1-B 사용자 목록 꼴), A-302 `PageEditPage`(작성·수정, 422 첫 칸 초점 — 본문이면 편집기), A-303 은 같은 화면의 단추(원문은 따로 주소). 발행·발행 취소·삭제는 확인 대화상자 — `ConfirmButton` 에 `ask`(빨간 단추가 아니어도 묻는다)와 `children`(발행 미리보기)을 더한다. 저장할 수 없으면(`page.update` 없음·발행본인데 `page.publish` 없음) 편집기 대신 `RichHtml` 로 그린다(아래 결정 2). 쿼리 키 `["admin", "pages", page]`·`["admin", "page", id]`, `gcTime: 0`.
- 넘어온 것: (h) 역할 권한 변경 성공 때 `["me"]` 도 무효화, m-11 superuser 역할의 「빼기」는 `assignable_roles` 에 있을 때만, m-3 `useMemo` 를 `dangerouslySetInnerHTML` 과 같은 식 안에(싱크 검사의 같은 줄 규칙 그대로), m-4 E2E 예외를 경로마다 건수 고정, m-5 싱크 검사 금지 이름 보강, m-2 는 1-C3 으로.
- 코드 분할: 경계 하나(`/admin/*` 를 lazy `AdminTree`). 옛 chunk 404 는 `import().catch` 로 대체 화면(오류 경계는 여전히 없다). 검사는 E2E — 공개 화면에서 내려받은 JS 에 `ProseMirror`·`tiptap` 표지가 없고, 편집 화면에서는 있다(반대 단언). 관리자 chunk 가 500kB 를 넘으면 `RichEditor` 를 둘째 경계로.
- CSP: 1-C1 정책 그대로 시작. Tiptap `injectCSS` 의 `<style>` 은 `style-src 'unsafe-inline'`(Radix) 아래라 그대로 둔다. ProseMirror 의 인라인 스타일은 CSSOM 이다(소스 확인). m-6 `style-src-attr 'none'` 은 E2E 실험으로 정한다 — 붙인 DOM 의 위반이 0이면 더하고, 하나라도 있으면 증거를 적고 더하지 않는다.

리치 텍스트 후속 결정과 다른 곳:

- IME 장면에 받침 넘김(「하나」)을 더했다(결정 3.3 보다 좁힘).
- 결정 3.2 의 Link 설정에 `linkOnPaste: false` 를 더하고, 결정에 없던 `extend`(Link·OrderedList 속성 줄이기)와 TrailingNode 끄기를 더했다.
- 결정 3.4 의 고정 입력(A1 하나)을 편집기 꼴 고정 파일로 바꿨다(A1 은 1-C3 의 Image 확장 뒤 다시 짝에 쓴다).
- 결정 3.2 「Tiptap 이 내용을 비활성 문서로 파싱하는지」 → 확인(`DOMParser`). 결정 8절 「Tiptap 이 기본으로 `<style>` 을 넣는지」 → 확인(`injectCSS`), 끄는 옵션도 있다.

원문과 다른 곳:

| 원문 | 1-C2 | 이유 |
|---|---|---|
| A-303 은 따로 주소(`/admin/pages/{id}/publish`)의 화면 | A-302 화면의 확인 단추 | 발행은 본문 없는 동작이다(1-B 사용자 상세와 같은 꼴) |
| 발행 입력은 `publish`·`unpublish` 뿐(D19:2663) | 발행에 읽은 `updated_at` | 발행 승인 분리(D13:266)가 「승인자가 읽은 본문」에 걸리게(I-2) |
| 에디터가 서식·사진·표·링크·유튜브를 쓴다(FR-513) | 굵게·기울임·h2·h3·목록·인용·링크. 사진은 1-C3 | 사용자 답 「굵게·링크·사진」, 서버 허용 목록과 같게 |
| 편집 폼 조회 GET = `page.update`(D19:2632) | 상세 GET = `page.view` | 발행 승인자(`page.view` + `page.publish`)가 고칠 권한 없이 본문을 읽어야 2인 승인이 선다(결정 1). D15:112 는 `page.view` 를 「초안 열람」으로 적는다 |
| 삭제 = `page.delete`(D19:2635) | 발행본 삭제는 `page.publish` 도 | 지우기가 공개를 끝낸다 — 승인 없이 공개 페이지를 내리는 길을 막는다(결정 3) |

결정(스펙 16절, 2026-11-25 역할 에이전트 결정 — 사용자 확인 전):

1. 발행 권한만 가진 승인자가 초안 본문을 읽는 곳 → **(다) 상세 GET 을 `page.view` 로**(원문 D19:2632 와 다르다). 발행 승인자 역할은 `page.view` + `page.publish` — 작성자는 혼자 공개하지 못하고 승인자는 본문을 고치지 못한다. (가)는 승인자 혼자 고치고 발행해 2인 승인이 서지 않는다. 화면은 `page.update` 가 없으면 편집기가 아니라 `RichHtml` 로 본문을 그린다(결정 2).
2. **렌더러 — 편집기는 저장 단추가 있을 때만 띄운다.** 그 밖(`page.update` 없음, 또는 발행본인데 `page.publish` 없음)에는 응답의 `body_html` 을 `RichHtml` 로 그린다. 「발행」 확인 대화상자의 설명 아래에 같은 응답의 `body_html` 을 `RichHtml` 로 미리보기(높이 제한·스크롤) — 발행이 싣는 `updated_at` 과 같은 응답. `ConfirmButton` 에 `children` 하나를 더한다. 이유: 편집기는 `img`(Image 확장 없음)와 `linkHref` 가 서버보다 엄격해 떨어지는 링크를 버려, 편집기로 보여 주면 승인자가 보지 못한 것이 공개된다(I-2 의 다른 경로). 시드가 editor·operator 에 `page.*` 를 다 줘 권한 토글로는 막지 못한다. 대가: 관리 chunk 에 DOMPurify 가 든다. 한계: 승인자가 미리보기를 실제로 읽었는지는 보장하지 못한다. 두 권한을 다 가진 편집자는 편집 화면에서 편집기 모양을 본다(대신 발행 대화상자 미리보기가 공개 렌더러).
3. **발행본 삭제는 `page.publish` 도 요구한다**(원문과 다르다). 요청자가 `page.publish` 를 갖지 않으면 `DELETE FROM pages WHERE id = ? AND status = 'draft'` 한 문장, 0행이면 `EXISTS` 로 가른다 — 있으면 403 「발행된 페이지는 발행 권한이 있어야 지울 수 있습니다」(기록 없음), 없으면 404. 화면의 「삭제」는 `can["page.delete"]` 이고 (초안이거나 `can["page.publish"]`), 발행본이라 숨기면 「발행을 취소한 뒤 지울 수 있습니다」. 지우기는 공개를 끝내는 동작이라 발행 취소와 같은 무게다.

## Phase 1-C2 구현 중 결정과 보안 점검 (2026-11-24~25)

스펙 `docs/superpowers/specs/2026-11-25-phase1c2-editor-design.md`, 계획 `docs/superpowers/plans/2026-11-25-phase1c2-editor.md`. Task 마다 개발 에이전트가 순서대로 맡았다(Task 1 은 2026-11-24, 2~10 은 11-25). 스펙에는 아래 「고친 것」과 「구현 때 확인」의 결과를 반영했다(1·4·5.1·6.1·7.4·8·9·17절). 실행 환경은 모두 macOS 로컬이다 — **Linux(CI) 실행은 이 브랜치에서 한 번도 없다.**

### Task 1 — 한글 IME 관문: **통과**

- 판·OSV(넣기 직전, 2026-11-24): `@tiptap/react`·`pm`·`starter-kit`·`extension-link`·`extension-list` 모두 3.35.0, `prosemirror-view` 1.43.1 이 그대로 최신. 새로 드는 패키지 48개를 OSV querybatch 로 조회해 0건(질의 48·결과 48). 스펙 1절의 51 과의 차이 3 은 `@floating-ui/core`·`dom`·`utils` 가 이미 lock 에 같은 판(radix-ui 경유)으로 있어서다.
- 브라우저: E2E 기본 `chromium` 프로젝트(headless shell). 대조군이 통과해 채널을 바꾸지 않았다(`playwright.config.ts`·`ci.yml` 그대로).
- 흉내: 낱자마다 `Input.imeSetComposition`, 확정은 `Input.insertText`. **`insertText` 가 조합을 끝낸다** — 확정마다 `compositionend` 1, `compositionupdate` 는 글자당 4(조합 3 + 확정 1).
- 대조군(제목 `<input>`): 값 「한글 입력」, start 4 · update 16 · end 4. 대조 단언 Red: `imeSetComposition` 을 빼고 `insertText` 만 보내면 start 0 으로 「`compositionstart` ≥ 1」에서 실패한다.
- 장면(① DOM 판정 ② 바이트 ③ 대조 단언 ④ `pageerror` 0·받지 않은 CSP 위반 0·대화상자 0 — 다섯 모두 성립. 조합 수는 start · update · end)

| 장면 | 보낸 `body_html`(바이트 기대) | 조합 이벤트 |
|---|---|---|
| I-1 빈 문단 | `<p>한글 입력x</p>` | 4 · 16 · 4 |
| I-2 굵게 | `<p><strong>한글 입력</strong></p>` | 4 · 16 · 4 |
| I-3 목록 안 | `<ul><li><p>한글 입력</p></li></ul>` | 4 · 16 · 4 |
| I-4 링크 바로 뒤 | `<p><a href="/about">안</a>한글 입력</p>` | 4 · 16 · 4 |
| I-5 받침 넘김 | `<p>하나</p>` | 2 · 8 · 2 |

- 바이트 기대 다섯은 스펙 2.3 표와 실제 출력이 같았다 — 3절 설정을 고치지 않았다.
- 변이 Red: `autolink: true` 면 I-4 가 `<p><a href="/about">안한글</a> 입력</p>` 로 DOM 판정에서, `trailingNode: {}`(켬)면 I-3 이 `…</ul><p></p>` 로 바이트 기대에서 실패한다(둘 다 되돌림).
- 실행 환경: macOS 로컬만. Linux(CI) 실행은 아직 없다.
- I-4 의 시험 순서 두 가지(IME 경로가 아니다)
  - 계획의 `End` 대신 `ArrowRight`. Playwright 는 macOS 에서 `End` 를 `scrollToEndOfDocument:` 로 보내 캐럿이 그대로다(`playwright-core` macEditingCommands). 그대로면 고른 「안」 위에서 조합이 시작돼 `<p>한글 입력</p>` 가 나갔다. `ArrowRight` 는 선택을 끝으로 접는다 — macOS 에서 확인, Linux(CI)에서도 같을 것은 추정(CI 에서 확인).
  - `Shift+ArrowLeft` 직후 곧바로 링크 칸으로 가면 편집기가 `selectionchange` 를 읽기 전이라 선택을 버린다(DOM 선택은 바뀌었고 편집기 선택은 접힌 채였다). 시험이 편집기 선택을 기다린 뒤 다음 동작을 한다. 사람 손 입력에서 나는지는 수동 확인 몫이다.
- jsdom: `new Editor` 가 돈다 — 편집기 단위 시험을 E2E 로 옮기지 않는다. `focus` 명령의 `scrollIntoView` 가 jsdom 에 없는 `Range.getClientRects` 를 불러 시험 파일에서만 빈 값으로 채웠다.
- `linkHref`: `https://a.1/` 은 서버 `LinkCheck` 는 받고 jsdom `new URL` 은 던진다 → `null`(시험 한 줄로 고정). Chromium 에서도 `new URL` 이 던진다(Task 3, E2E 「linkHref 와 LinkCheck 가 갈리는 꼴은 Chromium 에서도 같다」).
- **사용자 수동 확인(맥 한글 입력기, Chrome·Safari): 미실행 — 사용자 확인 요청.**

### Task 8 — CSP 다시 보기(m-3·m-4·m-6·m-5)

- **m-6 `style-src-attr 'none'`: 더했다.** `vite.config.ts` 와 E2E 기대 문자열(`pages.spec.ts`) 두 곳. 판정은 `mise run e2e` 전체(2026-11-25, macOS 로컬, Chromium headless shell).
  - 붙인 DOM 에서 난 `style-src-attr` 위반 0. 범위는 지금 E2E 27개가 지나는 화면이다 — 관리 화면(Radix 확인 대화상자 포함)·작성 화면의 편집기·IME 관문.
  - 수정 화면(`/admin/pages/{id}`)·발행 대화상자·렌더러·미리보기는 Task 9 가 처음 지났다 — **판정 유지.** 흐름 E2E 두 개(「쓰기에서 공개까지」·「I-2: 승인자…」)에서 모든 문맥(관리자·승인자·고치는 사람·방문자)의 CSP 위반 0건(`--repeat-each=3` 세 번 모두, `check()` 가 받기 없이 단언). 한계: 그 흐름의 본문은 서식 요소뿐이다 — `style=` 이 든 본문은 Task 3·8 의 붙여넣기 시험만 본다.
  - 편집기 확장이 `renderHTML` 로 `style` 속성을 내면(1-C3 사진 등) 붙은 DOM 에서 막힐 수 있다(추정) — 넣을 때 E2E 로 본다.
  - 붙이지 않은 문서 파싱 보고 2건. 둘 다 `/admin/pages/new` 의 붙여넣기이고 `blocked` 는 `inline`, 위치는 관리 chunk 의 prosemirror-view `readHTML`(`createHTMLDocument` 본문에 `innerHTML`)이다.
    - `붙여넣기: 오염 HTML 을 붙여도 스크립트가 돌지 않는다` 의 X14 줄(`style="color:red"`)
    - `붙여넣기: 표·색·밑줄은 빠지고 글자는 남는다`(`<p style="color:red">`)
    - 두 시험이 경로(붙여넣기는 줄 id)·지시어·건수 1로 받는다.
  - 가른 근거(임시 탐침, 커밋하지 않음): 위반 이벤트의 대상이 붙은 요소면 그 요소(`setAttribute`·붙은 `div` 의 `innerHTML` 모두 `P`), `createHTMLDocument` 본문이면 주 문서(`#document`)였다. 두 보고는 `#document` 다. `DOMParser` 문서(DOMPurify 겹)는 `style-src-attr` 를 보고하지 않았다 — `DOMPurify 겹` 의 X14 는 `script-src-attr` 1건 그대로다.
  - 대조: `CSP 가 실제로 걸려 있다` 가 붙은 요소의 `setAttribute("style", …)` 는 위반 1건·색 미적용, CSSOM(`el.style.color`)은 위반 0·색 적용을 단언한다. 정책에 지시어가 없으면 실패한다(위반 기대 2, 받은 1).
- **m-4**: `DOMPurify 겹` 의 일곱 줄 예외를 경로·지시어·`blocked` 마다 정확히 1건으로 고정했다(`/v-x3`·`x4`·`x5`·`x11`·`x14`·`x15` `script-src-attr` `inline`, `/v-x9` `base-uri` `https://evil.example/`).
  - 1-C1 검토의 「줄마다 2건」은 지금 트리에서 재현되지 않았다. `useMemo` 를 빼도 1건씩이다 — 이 시험의 공개 화면은 `RichHtml` 을 한 번만 그린다.
  - Chrome 은 같은 보고를 합치지 않는다. `sanitize` 를 두 번 부르는 변이로 줄마다 2건이 돼 실패하는 것을 봤다.
- **m-3**: `useMemo` 를 `dangerouslySetInnerHTML` 과 같은 줄에 뒀다. 공개 chunk 497.74 kB, 500 kB 경고 없음.
- **m-5**: 전역 금지 이름에 3개, `RichHtml.tsx` 금지 이름에 8개를 더했다. 지금 쓰는 `ADD_URI_SAFE_ATTR` 는 걸리지 않는다(고정 입력 한 줄).

### 구현하며 찾아 고친 것(시험이 잡았다)

1. **발행 `updated_at` 을 `Instant` 그대로 바인딩하면 500 이었다.** pgjdbc 42.7.14 가 `java.time.Instant` 의 SQL 형을 추론하지 못한다. `OffsetDateTime.ofInstant(…, ZoneOffset.UTC)` 로 바인딩한다(`PagesApiTest` 「상세의_updated_at_을_그대로_실으면_204」, 바인딩을 밀리초로 자르는 변이면 409). Task 4.
2. **m-7 — 413 응답에 API 보안 헤더가 없었다(추정이 맞았다).** 첫 Red 는 Content-Length 갈래에서 났다. catch 갈래만 옛 꼴로 되돌린 변이에서는 PUT chunked 폼이 `nosniff` 없음으로 실패했다. 필터가 네 헤더를 직접 단다 — `Cache-Control` 은 시험이 `/api/health` 의 값을 읽어 같은지 본다(`RequestSizeLimitTest` 「_413_에도_API_보안_헤더가_붙는다」. 숫자로 시작하는 이름은 Java 식별자가 아니라 앞에 `_`). Task 4.
3. **재연결 때 상세를 다시 읽었다.** TanStack Query 의 `refetchOnReconnect` 기본값이 켜짐이라, 네트워크가 다시 이어지면 남이 고친 본문이 알림 없이 실린다 — 스펙 7.3 「다시 채우기는 내 동작 뒤에서만」의 구멍이다. 상세 쿼리에 `refetchOnReconnect: false` 를 더했다(`PageEditPage.test` 「네트워크가_다시_이어져도_상세를_다시_읽지_않는다」 Red: 요청 2). Task 6.
4. **고치는 중에 「발행 취소」하면 화면이 낡은 상태를 보였다.** 다시 읽은 응답이 dirty 때문에 실리지 않아, 알림은 「발행을 취소했습니다」인데 상태 「발행」과 「발행 취소」 단추가 남았다. 이제 두 응답에서 나눠 읽는다.
   - 가장 최근 응답: 상태와 그것이 고르는 단추·삭제 설명·저장 가능 여부
   - 폼에 실린 응답: 편집 내용·렌더러 본문·미리보기·발행이 싣는 `updated_at`
   - h2 초점 effect 의 의존도 응답 시각으로 바꿨다.
   - 시험: 「고치는_중에_발행을_취소하면_상태와_단추는_새_응답을_따르고_고친_내용은_남는다」 Red(「초안」 없음) → Green. Task 6.
5. **관리자 트리를 lazy 로 떼자 `/admin/없는-주소` 가 빈 화면이 됐다**(`/admin/*` 가 먼저 잡는다). `AdminTree` 의 `<Routes>` 에 문 밖 형제 `<Route path="*">` 를 더해 분할 전처럼 「찾을 수 없습니다」를 보인다. `App.test` 「관리자_아래_없는_주소면_찾을_수_없습니다」 가 분할 전(2fa57d5)에서 통과·793a100 에서 실패·고친 뒤 통과. Task 7.

### 계획의 「스펙과 다르게 정한 것」 — 실제 결과

1. 발행의 422 가 슬러그 조회·예약어보다 앞이다 — 그대로. 없는 id 로 발행하는 기존 시험(`거절은_기록하지_않는다`)은 유효한 `updated_at` 을 싣게 고쳤다(기대 404 그대로). UUID 가 아닌 경로의 발행은 경로 변환이 먼저라 빈 본문 그대로 404 다.
2. 발행 본문 `required = false` — 추정이 맞았다. 권한 없는 역할의 빈 본문은 403, 익명 401, CSRF 없음 403 이고, 기존 `PagesApiTest` 50개를 고치지 않고 통과했다(Task 4 Step 1 탐침).
3. 상세 GET 이 `page.view` 로 바뀌어 뒤집힌 기존 시험은 하나다: `상세는_편집자_200_page_view_만이면_403` → `상세는_page_view_만이면_200_권한이_없으면_403`(page.view 만 200, admin.access 만·page.update 만 403). 스펙 16절 1(역할 에이전트 결정 — 사용자 확인 전)의 귀결이다.
4. 성공 알림 「발행했습니다」·「발행을 취소했습니다」 — 그대로.
5. dirty 면 「발행」 자리에 `aria-disabled="true"` 인 평범한 `Button` 과 안내 「저장하지 않은 수정이 있습니다」(`aria-describedby`). 다시 읽는 중은 `send` 가 무효화를 `await` 해 `pending` 이 막는다 — 그대로(`PageEditPage.test` 「dirty_면_발행이_aria_disabled_이고_안내를_읽는다」).
6. `offline(page, can)` 은 `e2e/stack.ts` 에 있다 — 그대로.
7. 붙여넣기·링크 거부 E2E 는 Task 3 — 그대로. Task 8 의 m-6 실험이 그 표(`PASTE_REPORTS`)를 늘렸다(X14 에 `style-src-attr` 1).
8. IME 대조군을 설치보다 먼저 — 기본 `chromium` 프로젝트(headless shell)에서 통과해 채널을 바꾸지 않았다.
9. A-301 제목은 늘 링크다 — 제목 링크를 `can["page.update"]` 에 묶는 변이로 Red 를 봤다.
10. `key={id ?? "new"}` 재마운트 — `key` 를 빼면 「작성_201_뒤_상세로_옮기면_상세_응답으로_채워지고_발행이_막히지_않는다」 가 실패한다(React Router 8.5.1 은 같은 부품을 다시 쓴다).

계획에 없던 것(가장 작은 변경으로 정하고 ledger 에 남긴 것):

- **`InstallPage` 도 lazy 로 뗐다**(스펙 8절 「경계 하나」와 다르다). 관리자 트리만 떼면 공개 chunk 가 502.58 kB 로 경고가 남았다 — Q21 「틀렸을 때 비용: 더 쪼갠다」 쪽의 가장 작은 변경이다. 이 변경은 793a100 에 들어 있는데 커밋 메시지(계획 문구 그대로)에는 적혀 있지 않다. 설치 chunk 를 받지 못하면 빈 화면이다(문구가 스펙에 없어 대체 화면은 만들지 않고 `ponytail:` 주석).
- `RichEditor` 둘째 lazy 경계는 만들지 않았다 — 스펙 8절의 조건(관리 chunk 가 500 kB 를 넘음)이 서지 않았다(473.76 kB).
- `chunkSizeWarningLimit` 은 쓰지 않았다 — 바이트가 그대로다.
- `RichEditor` 가 라벨(`label` 속성)을 그리고, 누르면 `editor.commands.focus()`. contenteditable 은 `<label for>` 대상이 아니다.
- `linkHref`(`shared/lib`)가 `@/features/auth/safeNext` 를 가져온다 — shared → features 로 가져오는 첫 줄이다. 옮기면 LoginPage·시험 이동까지 번져 그대로 뒀다.
- 발행 UPDATE 0행 뒤 다시 읽기는 `status` 만 본다(스펙 6.1 의 `updated_at` 은 쓰지 않는 칸).
- 확인 단추 셋(발행·발행 취소·삭제)은 페이지 `<form>` 밖이다 — 대화상자의 submit 이 페이지 폼까지 올라가 저장을 함께 보내지 않게. 발행 시험이 보낸 POST 가 `/publish` 하나뿐인지 본다.
- 발행·발행 취소 성공 뒤 초점은 h2(`UserDetailPage` 의 관례). 연 단추가 다른 단추로 바뀌어 초점이 body 로 떨어지기 때문이다.
- `ConfirmButton` 의 `ask` 는 「묻는다」만 정한다. 확인 단추 색은 지금처럼 `destructive` 가 고른다(발행 대화상자는 기본 색).
- 메뉴 「페이지」가 생겨 E2E `admin.spec.ts`·`users.spec.ts` 의 메뉴 단언이 다섯에서 여섯이 됐다(스펙 7.1 의 귀결).
- m-11 의 시드는 V1 이다(스펙 7.4 의 「V2」는 틀렸다 — `V1__init.sql` 의 admin 은 `is_superuser=true, is_assignable=true`).
- Task 9 의 쓰기 시험은 저장 요청의 `body_html` 을 기대 문자열 전체와 먼저 비교하고, 그다음 상세 응답과 바이트 비교한다. 본문에 마크 경계·문단 끝 공백을 두지 않았다 — Chromium 의 `&nbsp;` 직렬화를 확인하지 않아서다.

### 「구현 때 확인」의 결과 (스펙 17절 2~9 와 그 밖)

- **17절 2 CDP 확정 방법**: `Input.insertText` 가 조합을 끝낸다. 확정마다 `compositionend` 1, `compositionupdate` 는 글자당 4(위 Task 1).
- **17절 3 jsdom 에서 Tiptap**: `new Editor` 가 돈다. 편집기 단위 시험과 짝 시험의 프런트 쪽은 jsdom 에 남겼다. `focus` 명령 때문에 시험 파일에서 `Range.getClientRects`·`getBoundingClientRect` 를 채운다. Tiptap `focus` 는 requestAnimationFrame 안이라 초점 단언은 기다린다.
- **17절 4 합성 붙여넣기**: `ClipboardEvent("paste")` + `DataTransfer("text/html")` 가 prosemirror-view 에 닿는다(headless shell). 96줄 모두 `dispatchEvent` 가 `false`(편집기가 처리)였고, 시험이 줄마다 이것을 단언한다. CDP 클립보드는 쓰지 않았다.
- **17절 5 붙이지 않은 문서 파싱 보고**
  - 붙여넣기(`readHTML` 의 `createHTMLDocument` + `innerHTML`)
    - `script-src-attr` `inline` 6건: X3·X4·X5·X11·X14·X15
    - `base-uri` 1건: X9
    - `style-src-attr` `inline` 2건: X14, 「표·색·밑줄」
    - `script-src`·`script-src-elem` 0건
  - `DOMParser`(DOMPurify 겹)는 `style-src-attr` 를 보고하지 않았다.
  - 편집기 싣기와 관리 화면 `RichHtml` 은 서버가 정화한 값이라 보고 0 이다(Task 9 흐름에서 위반 0).
- **17절 6 `style-src-attr 'none'`**: 더했다(위 Task 8, Task 9 에서 유지).
- **17절 7 묶음 크기**(Vite 표시 크기)
  - 편집기를 넣은 단일 chunk 는 967,889바이트였다(넣기 전 크기는 재지 않았다).
  - 관리자 트리만 뗐을 때: 공개 502.58 kB·관리 473.64 kB, 공개 쪽에 경고가 남았다.
  - `InstallPage` lazy 뒤 Task 8 부터 지금(4073be0)까지: 공개 `index-*.js` 497.74 kB(gzip 161.08)·관리 `AdminGate-*.js` 473.76 kB(gzip 149.75)·`InstallPage-*.js` 4.96 kB·`password-*.js` 0.51 kB. 「larger than 500 kB」 경고는 없다.
  - **공개 chunk 의 여유는 약 2.3 kB 다.** 공개 chunk 에서 큰 것: react-dom 207k·zod 83k·react-router 38k·react-hook-form 35k·query-core 31k·DOMPurify 30k(sourcemap 집계). DOMPurify 는 공개 chunk 에만 있다.
  - 표지 문자열(`ProseMirror`·`tiptap`)은 압축 뒤에도 남는다 — 반대 단언이 통과한다. 관리 chunk 이름 꼴은 `assets/AdminGate-<hash>.js`(Vite 8.3.8).
- **17절 8 `linkHref`·`LinkCheck` 가 갈리는 꼴**: `https://a.1/` — 서버는 받고, jsdom·Chromium 의 `new URL` 은 던져 `null` 이다(Vitest 한 줄, E2E 「linkHref 와 LinkCheck 가 갈리는 꼴은 Chromium 에서도 같다」). 잘못된 `xn--` 마디는 보지 않았다. `links.json` 의 `allow` 9값은 편집기에서 모두 링크로 남는다.
- **17절 9 판·OSV**(넣기 직전, 2026-11-24): `npm view` latest 는 Tiptap 다섯 모두 3.35.0, `prosemirror-view` 1.43.1, `prosemirror-model` 1.25.13 이었다. 새로 드는 48개를 OSV querybatch 로 조회해 0건(위 Task 1). 판·API 의 근거는 npm 레지스트리와 `node_modules` 배포 소스다 — `setContent` 의 `emitUpdate`, Link 마크 `priority: 1000`(링크가 바깥), TanStack 5.107.1 `focusManager` 가 `visibilitychange` 를 듣는 것. context7 을 썼다는 기록은 없다.
- **짝 시험에서 실제 출력으로 고정한 줄**(jsdom, Tiptap 3.35.0) — 셋 다 규칙과 맞아 3절 설정을 고치지 않았다.
  - E6 `<p>a &amp; b &lt; c &gt; d&nbsp;e "f"</p>`(입력 그대로, jsoup `clean` 도 같다). **Chromium 직렬화는 확인하지 않았다.**
  - E7 `<p><a href="https://example.com/?a=1&amp;b=2"><strong><em>x</em></strong></a></p>` — 링크 마크가 바깥이다.
  - E8 `<p>a</p><p>b</p><p>cde</p><p>f</p><p></p>` — 사진 문단은 빈 문단으로 남는다.
- **TrailingNode**: 켜면(`trailingNode: {}`) 끝이 목록인 문서에 `<p></p>` 가 붙는다 — 변이로 봤다. 끈 채로 「끝이_목록인_문서에_트랜잭션을_내도_빈_문단이_붙지_않는다」.
- **`updated_at` 의 꼴**: 응답 예 `2026-11-25T00:50:09.332977Z`(소수 6자리, 한 번 봤다). 끝자리가 0 이면 자리가 줄 수 있다는 것은 추정이다 — 화면은 문자열을 그대로 되돌리므로 자리 수에 기대지 않는다.
- **범위 밖 `updated_at`**(탐침, 커밋 안 함): `+99999-…`·`-99999-…` → 409, `1e30` → 400, `+1000000000-12-31T23:59:59.999999999Z`(`Instant.MAX`) → **500**. `page.publish` 와 CSRF 를 가진 사람만 닿는다. 고치지 않았다(아래 「넘길 것」). → 최종 검토(M-1)에서 다시 재 보니 `Instant.MAX` 만이 아니었다 — `timestamptz` 범위(4714-11-24 BC ~ 294276-12-31 AD) 밖은 모두 500(`+294277-…`·`+300000-…` 은 PostgreSQL `timestamp out of range`, `-1000000000-…`·숫자 `31556889864403199`·`Instant.MAX` 는 `OffsetDateTime.ofInstant` 의 `DateTimeException`). 지금은 형식 불량과 같은 400 이다(아래 「Phase 1-C2 최종 검토」).
- **413 헤더**: 처음부터 없었다(위 고친 것 2).
- **m-4 건수**: 경로마다 정확히 1건(위 Task 8).
- **TanStack Query**: 창 초점 다시 읽기는 jsdom 에서도 돈다(`visibilitychange`) — `refetchOnWindowFocus` 를 빼는 변이로 Red 를 봤다.
- **E2E·빌드 시간**: 아래 「시험」.

### D86 대조 — 1-C2 범위(스펙 13절)

| D86 | 결과 |
|---|---|
| **1.12 프록시 뒤 IP** | **미해결 그대로 — Phase 4 배포 전 필수.** 1-C2 에 새 쓰기 경로는 없다 |
| 2.1 처리기 권한·기동 점검 | ✓ 새 처리기 없음. `PagesApiTest` 의 다음 시험들. 화면의 `can` 숨김은 편의다 |
| | 상세 `page.view`(16절 1, 역할 에이전트 결정 — 사용자 확인 전): 「상세는_page_view_만이면_200_권한이_없으면_403」 |
| | 승인자: 「page_view_와_page_publish_만_가진_승인자가_상세를_읽고_그_updated_at_으로_발행하면_204」 |
| | 발행본 삭제(16절 3, 역할 에이전트 결정 — 사용자 확인 전): 「page_delete_만_가진_역할은_발행된_페이지를_지울_수_없다_403_이고_행과_기록이_그대로」·「page_delete_만으로_초안은_지운다_204」·「편집자는_발행본도_지운다_204」·「없는_id_는_두_갈래_모두_404」 |
| 2.5 대량 할당 | ✓ 발행 본문은 `{updated_at}` 하나 — 「updated_at_이_없거나_null_이면_422_형식이_틀리면_400_다른_칸은_무시」 |
| 2.7 `{id}` 는 uuid 만 | ✓ 1-C1 시험 그대로 — 「없는_id_와_UUID_가_아닌_경로는_404」(발행 경로 포함), 「없는_id_와_지워진_뒤는_404」 |
| 2.8 관리자 60/분 | 그대로(`/api/admin/**`). 직접 친 시험은 없다 |
| 3.1 CSRF | ✓ 1-C1 시험 그대로(발행 본문 `required = false` 라 CSRF 없음이 403 으로 남는다 — 위 결과 2). 화면은 `api` 클라이언트로 보낸다 |
| 3.3~3.5 이스케이프 우회 지점 | ✓ 새 싱크 없음. `dangerouslySetInnerHTML` 은 `RichHtml` 한 곳·`useMemo` 도 같은 줄. `tests/html-sinks.test.sh` 고정 입력 39건(m-5 로 27 → 39). 편집기의 HTML 파싱은 라이브러리 안의 비활성 문서다 |
| 3.6 리치 텍스트 정화 | ✓ 편집기 출력 = 서버 `clean` = DOMPurify. `RichEditor.test` 「editor_json_의_모든_줄에서_편집기_출력이_editor_와_같다」·「편집기_출력은_DOMPurify_가_바꾸지_않는다」·「편집기_출력을_다시_실어도_같다」·「vectors_입력을_실은_편집기_출력의_불변식」·「links_allow_값은_편집기에서_링크로_남는다」, `RichTextTest` 「편집기_고정_파일의_editor_는_clean_해도_같다」·「벡터_표의_모든_줄이_server_와_바이트까지_같다」(W1~W4 포함 96줄, m-10), E2E 「붙여넣기: 오염 HTML 을 붙여도 스크립트가 돌지 않는다」·「붙여넣기: 표·색·밑줄은 빠지고 글자는 남는다」·「쓰기에서 공개까지…」(Chromium 저장 요청 = 상세 응답, 바이트). 한계: 서버 두 겹은 같은 구현(jsoup) |
| 3.7 보안 헤더 | ✓ 413 에도 API 헤더 넷 — `RequestSizeLimitTest` 「_413_에도_API_보안_헤더가_붙는다」(m-7). 한계: 공개 HTML 의 `frame-ancestors`·`X-Frame-Options` 는 Phase 4 CloudFront |
| 3.8 CSP | ✓ `style-src-attr 'none'` 을 더했다(m-6). 편집기 화면을 지나는 모든 E2E 가 받지 않은 위반 0건이다. 받는 것은 붙이지 않은 문서 파싱 보고뿐이고 경로·지시어·건수로 고정했다(m-4, `PASTE_REPORTS`). 대조: E2E 「CSP 가 실제로 걸려 있다」(붙은 요소 `style` 속성 위반 1·CSSOM 0). `script-src` 는 넓히지 않았다 |
| 3.10 오류가 내부를 말하지 않는다 | ✓ 새 409·403 `detail` 은 코드 상수 — 「읽은_뒤_본문이_바뀌면_발행이_409_이고_상태와_기록이_그대로」·「예약어_슬러그는_옛_updated_at_이어도_409_시스템_경로」 의 `refused` 단언. 한계였던 범위 밖 `updated_at` 500 은 최종 검토 M-1 에서 400 으로 고쳤다 |
| 4.1 SQL 바인딩 | 발행의 `updated_at` 도 `JdbcClient.param`. 직접 친 시험은 없다 |
| 7.1 요청당 고정 질의 수 | 발행(슬러그 1 + 갱신 1, 0행이면 다시 읽기 1)·삭제(1, 0행이면 `EXISTS` 1)는 코드대로다. 직접 센 시험은 없다. 공개 조회 1 은 1-C1 시험(「익명_요청의_질의는_하나」) 그대로 |

### 시험 (2026-11-25, 4073be0 트리 — 이 커밋은 문서만 바꾼다. 시험은 `docs/` 를 읽지 않는다)

최종 검토 수정 뒤의 숫자는 아래 「1-C2 최종 검토」 절에 있다(백엔드 340·프런트 257·E2E 31).

- `nice -n 19 mise run test` → exit 0, 2분 27초(`time`)
  - 셸 144 통과: check-public 17·html-sinks 39·measure 67·pre-commit 4·summarize 17
  - 백엔드 339 통과(39 클래스, surefire 합: 실패 0·오류 0·skip 0)
  - 프런트 247 통과(파일 27), typecheck 오류 0
- `mise run e2e` → exit 0, 29 통과(admin 3·editor 14·install 2·login 2·pages 3·users 5), Playwright 3.6m(webServer 빌드 포함, Playwright 출력). 벽시계 시간은 재지 못했다. editor 14 는 IME 6(대조군 포함)·붙여넣기 2·링크 입력 1·갈리는 꼴 1·코드 분할 2·흐름 2 다
- 빌드: 이 트리에서 `npm run build`(tsc + vite build) 1.93s(`/usr/bin/time` real, 바로 앞에 한 번 빌드한 뒤의 둘째 실행), 그중 Vite `built in` 163ms. chunk 는 공개 `index-*.js` 497.74 kB(gzip 161.08)·`AdminGate-*.js` 473.76 kB(gzip 149.75)·`InstallPage-*.js` 4.96 kB·`password-*.js` 0.51 kB, 500 kB 경고 없음 — Task 8 기록과 같다.
- 1-C1 과 비교: 백엔드 325 → 339, 프런트 190 → 247, E2E 15(2.5m) → 29(3.6m). CI `timeout-minutes: 20` 은 그대로이고 PR CI 의 단계 시간은 아직 보지 않았다(PR 전).
- **Linux(CI) 실행: 미실행** — 이 브랜치는 macOS 로컬에서만 돌았다. PR CI 가 처음이다.
- 메모리 측정은 1-C 끝(1-C4 뒤) — 미실행.

### 최종 검토에 넘길 것(고치지 않았다 / Red 를 따로 보지 못했다)

- ~~**범위 밖 `updated_at`(`Instant.MAX`)으로 발행하면 500.**~~ → 최종 검토 M-1 에서 고침. `Instant.MAX` 만이 아니라 `timestamptz` 범위 밖 전부였고, `PublishInput` 생성자가 범위를 보아 형식 불량과 같은 400 을 낸다.
- **IME 사용자 수동 확인(맥 한글 입력기 2벌식, Chrome·Safari, I-1~I-5): 미실행 — 사용자 확인 요청.** I-4 의 선택 경합(Shift+화살표 직후 링크 칸으로 가면 선택을 버림)이 사람 손에서도 나는지 함께 본다.
- **Linux(CI) 미실행.** 특히 I-4 의 `ArrowRight` 가 Linux 에서도 선택을 끝으로 접는지(추정), 합성 붙여넣기 경로, 붙여넣기 CSP 표의 50ms 양보가 느린 CI 에서 모자라지 않은지(모자라면 헛통과가 아니라 실패).
- **E6 의 Chromium 직렬화(`&amp;`·`&nbsp;`)는 확인하지 않았다.** jsdom·jsoup 만 봤다. 흐름 E2E 의 본문은 그 꼴을 피했다 — 사람이 링크 앞뒤에 공백을 친 본문의 왕복은 시험이 보지 않는다.
- **공개 chunk 497.74 kB — 500 kB 경고까지 여유 약 2.3 kB.**(최종 검토 뒤 497.83 kB, 여유 약 2.2 kB — 아래 절) 1-C4(공개 셸·메뉴)에서 경고가 다시 나면 `/login` lazy(zod·react-hook-form·resolvers 약 121 kB)를 Q21 과 함께 다시 본다(사용자 결정 몫).
- **설치 chunk 를 받지 못하면 빈 화면이다**(`InstallPage` lazy, 대체 화면 없음, `ponytail:`).
- Red 를 따로 보지 못한 시험
  - Task 9 쓰기 시험의 474행 짝 단언(상세 응답 == 저장 요청). 변이는 471행의 기대 문자열 비교에서 먼저 떨어졌다. 그 줄만 실패시키려면 서버 변이와 jar 재빌드가 두 번 든다.
  - Task 6 다시 채우기 effect 의 `dirty` 의존. 빼도 Red 가 나지 않는다 — TanStack `notifyManager` 의 `setTimeout(0)` 보다 저장 `onSuccess` 가 먼저 돌아 새 응답과 dirty 해제가 한 렌더에 온다. 의존은 그대로 둔다. 최종 검토 뒤에도 같다(아래 절).
  - Task 5 의 E2E 메뉴 단언(다섯 → 여섯). Green 만 봤다.
  - Task 3 「linkHref 와 LinkCheck 가 갈리는 꼴은 Chromium 에서도 같다」 — 망가뜨릴 구현이 없다(브라우저 파서를 본다).
- m-6 「유지」의 근거인 흐름 본문은 서식 요소뿐이다. 1-C3 의 사진처럼 편집기 확장이 `renderHTML` 로 `style` 속성을 내면 붙은 DOM 에서 막힐 수 있다(추정, 미확인).
- 1-C1 의 「줄마다 2건(재렌더)」은 지금 트리에서 재현되지 않아 원인을 확인하지 않았다(m-4 는 1건으로 고정했다).
- `linkHref` 가 `features/auth/safeNext` 를 가져온다(shared → features). 옮기면 작은 이동이다.
- 동시 편집은 나중 저장이 이긴다(스펙 6.3, 자동 저장 없음) — 화면이 생겨 실제로 부딪힌다(스펙 15절 3).

### 1-C2 최종 검토 (2026-11-25, fable 검토자) — Critical 0 · Important 4 · Minor 5

검토자가 실제로 재현한 장면이다.

| 지적 | 처리 |
|---|---|
| **I-1** 창에 돌아오면 쓰던 글이 사라진다 — 관문 `["admin"]` 이 창 초점·재연결마다 다시 읽고 200 이 아니면(401 → `/login`, 503·429·403 → 메시지) 관리자 트리를 내린다 | **고침**. 관문 쿼리에 `refetchOnWindowFocus: false`·`refetchOnReconnect: false`. 관문의 초점 다시 확인을 기대하는 시험은 없었다(grep). 서버가 요청마다 권한을 본다 — 만료는 다음 요청 때 안다. `AdminGate.test` 「편집_중_창에_돌아오거나_다시_이어져도_관문이_%i_로_바뀌었다고_편집기를_내리지_않는다」(401·503, `/api/me` 도 함께 바꿈) RED→GREEN, 옵션 하나씩 빼도 Red |
| **I-2** 저장 응답을 기다리는 동안 친 글자가 사라진다 — 저장 `onSuccess` 가 늘 dirty 를 지워 다시 채우기가 서버 응답으로 덮는다 | **고침**. 보낸 순간의 제목·주소·본문과 응답 뒤 값이 같을 때만 dirty 를 지운다(편집기는 잠그지 않는다 — 조합 중 IME). 「저장_중에_본문_에_더한_글자가_…」·「저장_중에_제목_에_…」 RED→GREEN. 작성(201) 뒤 상세로 옮기는 경로도 같은 꼴로 재현돼(다시 마운트) 더 쓴 값을 메모리로 한 번 넘겨 dirty 로 싣는다 — 「작성_중에_본문에_더한_글자가_201_뒤_상세로_옮겨도_…」 RED→GREEN. 넘기는 값은 새 초안을 고칠 수 있을 때(`page.update`)만 만들고, 상세 화면이 마운트 때 꺼내 비운다 — 「page_update_가_없어_렌더러로_뜨면_dirty_가_켜지지_않는다」·「상세가_실리기_전에_떠나면_남지_않아_…」 RED→GREEN. 캐럿과 그 순간 조합 중 글자는 다시 마운트로 여전히 끊긴다(`ponytail:`) |
| **I-3** 편집 영역에서 제목·목록·인용이 보통 글자처럼 보인다 — 서식 CSS 가 `.rich` 에만 있다 | **고침**. 클래스를 공유하지 않고 선택자를 `:is(.rich, .ProseMirror)` 로 넓혔다(편집 화면에 `.rich` 가 둘이면 렌더러를 가리키는 시험 선택자가 흐려진다). E2E 「I-3: 편집 영역에서도 제목·목록·인용이 서식대로 보인다」 RED(h2 16px = 문단, ul `none`, 테두리 0)→GREEN(24px, disc, 3px). IME 관문 6 다시 통과 |
| **I-4** 저장하지 않고 떠나도 묻지 않는다, 저장 401 의 「다시 로그인」을 누르면 본문을 잃는다 | **일부 고침**. dirty 면 `beforeunload`(탭 닫기·새로고침) — 「dirty_일_때만_beforeunload_가_묻고_저장하면_묻지_않는다」·「본문만_고쳐도_묻는다」 RED→GREEN. 401 링크를 새 탭(`target="_blank" rel="noopener noreferrer"`)으로 열고 「(새 탭). 로그인한 뒤 다시 저장하세요」 — `useSave` 의 `SaveAlert` 는 공용이라 **모든 폼의 401 링크가 바뀐다**(결정에 따른 변경, 기존 href 단언은 그대로 통과). E2E 「I-4: 세션이 끊긴 편집 탭에서 새 탭으로 다시 로그인하면 원래 탭의 저장이 본문 그대로 204」: 서버 세션 행을 지움 → 첫 저장 401(CSRF 403 아님) → 새 탭 로그인(XSRF-TOKEN 값이 바뀜) → 원래 탭 저장 204·DB 본문 일치. 링크를 같은 탭으로 되돌리면 Red. **앱 안 메뉴로 떠나는 장면은 미해결이다** — `main.tsx` 가 선언형 `BrowserRouter` 라 `useBlocker` 를 쓸 수 없고 라우터 교체는 범위가 크다. 메뉴를 누르면 쓰던 글을 말없이 잃는다 |
| **M-1** 발행 `updated_at` 이 표현 범위 밖이면 500 | **고침**. `Instant.MAX` 만이 아니라 `timestamptz` 범위(4714-11-24 BC ~ 294276-12-31 AD) 밖 전부였다(PostgreSQL `timestamp out of range`, `OffsetDateTime.ofInstant` 의 `DateTimeException`). `PublishInput` 생성자가 범위를 보아 Jackson 읽기 실패로 감싸여 형식 불량(`"abc"`)과 같은 400 본문이다. `PagesApiTest.updated_at_이_표현_범위_밖이면_형식_불량과_같은_400`(다섯 값 + 경계 안쪽 409·바로 밖 400, 상태 초안·기록 0) RED(다섯 모두 500)→GREEN |
| M-2 친 연속 공백·문단 끝 공백이 다시 실으면 접힌다(공개 화면에는 보이는 차이 없음) | 미룸 |
| M-3 동시 편집은 나중 저장이 말없이 덮는다 | 미룸 — 스펙 6.3 결정 그대로. 화면이 생겨 실제로 부딪힌다(사용자 확인 몫) |
| M-4 설치 chunk 를 받지 못하면 빈 화면 | 미룸 — 1-C4 에서 `/login` lazy 와 함께 |
| M-5 링크 칸이 기존 링크 주소를 채우지 않는다 | 미룸 |

검토자가 동의하지 않은 Ruling 셋(이유는 검토 원문에서 옮겼다):

- **Task 6 의 dirty 비용** — 이유: 「틀렸을 때 비용: 없음」이 기대는 흐름(저장 성공이면 무조건 dirty 해제 → 다시 채우기)이 I-2 의 원인이라 비용이 없지 않다. 처분: I-2 로 흐름을 고쳤고 의존 배열은 그대로 둔다. 사실만 덧붙인다: 다시 채우기 effect 의존 배열의 `dirty` 를 빼는 변이는 최종 검토 수정 뒤에도 Red 가 나지 않는다(28 통과). 조건의 `dirty` 가드는 빼면 I-2 시험 둘과 기존 「고치는_중에_발행을_취소하면…」이 실패한다.
- **M-1 범위** — 이유: 「`Instant.MAX` 만」이라는 기록이 틀렸다(다섯 값 모두 500). 넘긴 것 자체는 등급상 무방했다. 처분: 위 M-1 로 고쳤다(`Instant.MAX` 만이 아니라 `timestamptz` 범위 밖 전부).
- **Task 7 `InstallPage` lazy** — 이유: 표시 문턱인 빌드 경고를 없애려고 첫 실행 화면에 대체 없는 빈 화면 갈래를 넣었고, 여유가 약 2.3 kB 라 오래가지도 않는다. 되돌릴 정도는 아니다. 처분: 1-C4 에서 `/login` lazy(Q21)와 함께 다시 정한다(M-4 와 같은 곳).

시험 (2026-11-25 — 이 커밋은 문서만 바꾼다. 0327dd0 → ea6d332 는 E2E 파일만 바뀌었고 ea6d332 에서 typecheck 0 을 다시 봤다):

- `nice -n 19 mise run test`(0327dd0) → exit 0, 140초. 셸 144(check-public 17·html-sinks 39·measure 67·pre-commit 4·summarize 17), 백엔드 340(39 클래스, 실패·오류·skip 0), 프런트 255(파일 27). 그 뒤 `carried` 빈틈 두 시험을 더한 트리에서 `npm test` 257 통과(파일 27)·typecheck 0, `e2e/editor.spec.ts` 16 통과(55초)를 다시 봤다(셸·백엔드는 프런트만 바뀌어 다시 돌리지 않았다), typecheck 오류 0
- `mise run e2e` → exit 0, 31 통과(admin 3·editor 16·install 2·login 2·pages 3·users 5), Playwright 3.9m(벽시계 238초, jar 빌드 포함), ea6d332 트리. editor 16 은 앞 14 + I-3 서식 1 + I-4 다시 로그인 1. 첫 실행(0327dd0)은 30 통과·1 실패였다 — 「링크 입력이 …」가 201 뒤 옮기기 전에 goto 해 dirty 작성 화면의 `beforeunload` 대화상자 1건(앱은 맞게 동작). 단언은 그대로 두고 상세 제목 대기를 더했다(ea6d332, `--repeat-each=3` 3 통과)
- 빌드: 공개 `index-*.js` 497.83 kB(gzip 161.13)·`AdminGate-*.js` 474.40 kB(gzip 149.96)·`InstallPage-*.js` 4.96 kB·`password-*.js` 0.51 kB, 500 kB 경고 없음. 공개 chunk 는 `useSave` 문구로 +0.09 kB, 여유 약 2.2 kB.
- 앞 절(4073be0 트리)과 비교: 백엔드 339 → 340, 프런트 247 → 257, E2E 29 → 31.
- **Linux(CI) 실행: 미실행.**
