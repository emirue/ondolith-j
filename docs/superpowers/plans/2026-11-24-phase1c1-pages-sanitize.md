# Phase 1-C1 정화·페이지 API·공개 화면 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 편집자 권한을 가진 사람이 API 로 페이지를 만들고 발행하면, 비로그인 방문자가 `/{슬러그}` 에서 굵게·제목·목록·링크가 든 본문을 본다. 서버는 저장 때·내보낼 때 HTML 을 정화하고, 브라우저는 DOMPurify 로 한 번 더 거르며, 빌드 산출물의 `<meta>` CSP 가 인라인 스크립트를 막는다. 모든 요청 본문은 1 MiB 로 묶인다.

**Architecture:** 맨 앞 서블릿 필터 `shared/RequestSizeLimit` 이 두 컨텍스트의 모든 요청 본문을 1 MiB 로 묶는다. 정화는 `shared/RichText`(`clean`·`forSave`) 하나이고, 링크 값은 `shared/LinkCheck.ok` 가, 슬러그는 `shared/Slug` 가 판정한다. 관리 API 는 `admin/PagesController`, 공개 조회는 `ops/PageController` 다. 프런트는 `RichHtml` 한 곳만 `dangerouslySetInnerHTML` 을 쓰고, E2E 는 `vite preview` 로 빌드 산출물을 CSP 아래에서 본다.

**Tech Stack:** Spring Boot 4.1.2(Security 7.1, Tomcat), Flyway, PostgreSQL 18, jsoup 1.24.1(새), React 19.3.1 + React Router 8.5.1, TanStack Query 5, DOMPurify 3.5.0(새), Vite 8.3.8, Vitest 5.1.0, Playwright 1.65.0.

**Spec:** `docs/superpowers/specs/2026-11-24-phase1c1-pages-sanitize-design.md` — **17절이 다른 절보다 우선한다.** 시험 표 13.2·13.3 은 여기에 베끼지 않고 줄 id(X1·I3·S7 …)로 가리킨다.

## Global Constraints

- 저장소는 PUBLIC. 실제 메일·홈 경로·비밀을 쓰지 않는다. 시험 값은 누가 봐도 가짜(`*@ondolith.test`, `…-pass-…`, `evil.example`, `00000000-0000-4000-8000-000000000001`).
- 새 의존성은 jsoup 1.24.1(`pom.xml` 에 판 명시)과 DOMPurify 3.5.0(`--save-exact`, `package-lock.json`) 둘뿐. 넣기 직전에 OSV(`api.osv.dev/v1/query`)를 다시 보고 그때의 최신 GA 를 확인한다. 2026-11-24 계획 담당 확인: Maven Central `maven-metadata.xml` `release` 1.24.1, npm `latest` 3.5.0. context7 은 월 한도 초과였다 — API 이름은 구현 때 context7(안 되면 공식 문서·배포 소스)로 확인한다.
- TDD: 실패를 눈으로 본 뒤 구현. 단언 완화·skip 금지. 이미 통과하는 시험은 구현을 잠시 되돌리거나 망가뜨려 Red 를 한 번 본다.
- 무거운 작업은 한 번에 하나, `nice -n 19`. Java 명령은 `mise x --` 로.
- 백엔드 한 클래스: `cd src/back && nice -n 19 mise x -- ./mvnw -q -B test -Dtest=<클래스>`. 전체 백엔드: `… ./mvnw -q -B test`. 프런트: `cd src/front && npx vitest run <경로>`. 전체: `nice -n 19 mise run test`. E2E: `mise run e2e`. 셸: `for t in tests/*.test.sh; do bash "$t" || exit 1; done`.
- 시험 HTTP 는 `127.0.0.1`. 마이그레이션은 앞으로만(V1~V4 는 주석도 고치지 않는다).
- 오류는 Problem Details, JSON 은 snake_case, 422 는 최상위 `errors{칸: 메시지}`. 403·404·409 의 `detail` 은 코드 상수이고 요청 값(슬러그·제목·본문)이 섞이지 않는다.
- `operation_logs` 는 시험도 비울 수 없다. 행 단언은 **시험마다 새 계정**·증분으로 한다.
- 기록기를 부르는 처리기에 `@Transactional` 을 달지 않는다. 작업 로그에는 슬러그·제목·본문을 넣지 않는다(`summary` `''`).
- 스펙의 정규식(슬러그·`img src`·`ol start`·링크)은 모두 `Matcher.matches()`(전체 일치)로 판정한다. 프런트 TS 정규식은 `^…$` 에 `m` 플래그를 쓰지 않는다.
- **API 모양을 바꾸는 커밋(Task 4·5·6)은 같은 커밋에서** `OpenApiFileTest` 단언을 고치고 `src/front/openapi.json`·`src/front/src/shared/api/schema.d.ts` 를 다시 만든다(`OpenApiFileTest` 실행 → `cd src/front && npm run gen:api`).
- V5 와 `Permission` 다섯은 같은 커밋(Task 4) — 아니면 기동 점검의 DB·코드 키 대조가 기동을 멈춘다.
- `/api/admin` 의 GET 이 아닌 새 처리기는 그 처리기를 만드는 커밋에서 `ReauthTargetsTest.NOT_TARGETS` 에 넣는다 — 아니면 「모두 분류」 시험이 깨진다.
- 프런트 공개 쿼리 키는 `["page", slug]`. 관리 쿼리 키를 만들면 `["admin", …]` 아래(1-B1 I-1). 1-C1 에는 관리 화면이 없다.
- 고정 문구: 404 「페이지를 찾을 수 없습니다」, 슬러그 중복 409 「이미 사용 중인 주소입니다」, 발행 예약어 409 「이 주소는 시스템 경로와 충돌합니다」, 발행본 수정 403 「발행된 페이지는 발행 권한이 있어야 고칠 수 있습니다」, 413 「요청 본문이 너무 큽니다」, 화면 404 h1 「찾을 수 없습니다」·본문 「없는 페이지이거나 주소가 바뀌었습니다. 주소를 확인하세요.」, 불러오기 실패 「페이지를 불러오지 못했습니다」.
- 메모리 측정은 1-C 끝(1-C4 뒤)에 따로 한다. 이 계획의 Task 가 아니다.

## 스펙과 다르게(또는 스펙이 침묵해서) 정한 것

1. **Tomcat 폼 해석 상한을 1 MiB 로 내린다**(`server.tomcat.max-http-form-post-size=1MB`). 스펙 17절 2 는 「기본값 그대로(추정)」다. Tomcat 은 `getParameter` 때 자기 입력 버퍼에서 폼 본문을 직접 읽어 필터의 세는 래퍼를 지나지 않는다(추정 — Task 1 Step 5 의 폼 탐침이 확인). CSRF 헤더 없는 POST 는 `CsrfFilter` 가 `_csrf` 매개변수를 찾느라 그 경로를 연다. 탐침이 우회를 보이지 않으면 이 줄을 넣지 않고 결정 문서에 「확인: 우회 없음」으로 적는다.
2. **chunked 초과는 400 이다**(413 이 아니다). 세는 스트림이 `IOException` 을 던지면 Jackson 이 읽기 실패로 감싸 `ApiErrors` 기본 400 이 된다. 스펙은 「413 또는 400, 500 아님」이라 허용 범위 안이다. 실제 값은 Task 1 이 고정한다.
3. **시험 이름 `한글_200000자_본문은_상한_안에서_201` 은 Task 4 로 옮긴다.** 페이지 작성 API 가 Task 4 에서 생긴다. Task 1 은 같은 뜻을 경계 시험(정확히 1,048,576바이트는 413 이 아니다)으로 본다.
4. **X23·X24 줄은 값을 HTML 속성 이스케이프(`&`→`&amp;`, `"`→`&quot;`, `<`→`&lt;`)해 넣는다.** 날 값을 `href="…"` 에 넣으면 `/a"b` 가 따옴표에서 끊겨 `href="/a"` 로 남는다(스펙의 「모두 `<a>x</a>`」가 틀린다). 이스케이프하면 jsoup 이 푼 값이 원래 링크 값이다. 줄은 `links.json` 에서 한 번 만들어 `vectors.json` 에 펼쳐 커밋한다(id `X23-01`…, `X24-01`…) — 프런트·E2E 가 같은 파일만 읽게.
5. **E2E 는 벡터 입력의 `alert(1)` 을 `window.__xss=1` 로 바꿔 쓴다.** `vectors.json` 은 스펙 표 그대로 둔다(서버 바이트 일치가 그 문자열로 고정된다). 브라우저에서는 표시가 남는지와 `dialog` 이벤트를 함께 본다.
6. **벡터 파일은 Task 2 가 정화 규칙 줄만, Task 3 이 링크 줄(X16~X21·X23·X24)을 더한다.** `clean` 의 `a[href]` 검사는 `LinkCheck` 가 필요하고, 정해진 순서(정화기 → 링크 검사기)를 지키기 위해서다.
7. **`<title>` 이 정적 `index.html` 의 것에 지면** `PageView` 가 `useEffect` 로 `document.title` 을 쓴다(글자라 싱크가 아니다). React 19 `<title>` 이 이기면 그대로 쓴다. Task 9 E2E 의 `page.title()` 이 가른다.
8. 413 응답은 필터가 직접 쓰는 Problem Details(`type` `about:blank`, `title` 「Content Too Large」, `status` 413, `detail` 「요청 본문이 너무 큽니다」)다. `ApiErrors` 를 지나지 않는다(`AdminRateLimitFilter` 의 429 와 같은 방식).

## Review Focus

1. **정확히 1 MiB 인 본문** — 1,048,576바이트는 통과(처리기의 422·204), 1,048,577바이트는 413. Task 1 시험.
2. **자기 슬러그를 그대로 둔 수정** — 슬러그를 바꾸지 않은 수정은 409 가 아니라 204(UNIQUE 가 자기 행과 부딪히지 않는다). Task 5 시험.
3. **`NotFound` 에서 유효한 페이지로 SPA 이동** — `<meta name="robots" content="noindex">` 가 `document.head` 에 남지 않는다. Task 8 시험.
4. **`body_html` 이 문자열이 아닌 JSON 값**(`123`·`[]`·`{}`·`true`) — 500 이 아니다. `[]`·`{}` 는 400 또는 422 이고 행이 생기지 않는다. `123`·`true` 는 Jackson 이 문자열로 바꿔 줄 수 있어(구현 때 확인) 201 이면 저장값이 `clean` 을 지난 값이다. 전역 Jackson 설정은 바꾸지 않는다(스펙 5.2). Task 4 시험.
5. **목록의 끝을 넘은 쪽** — 51개일 때 `page=3` 은 200·`items` 빈 목록·`has_next` false. Task 5 시험.

---

### Task 1: 모든 요청 본문 1 MiB (스펙 17절 2)

**Files:**
- Create: `src/back/src/main/java/dev/ondolithj/shared/RequestSizeLimit.java`
- Modify: `src/back/src/main/java/dev/ondolithj/install/InstallApplication.java`(`@Import` 에 더한다), `src/back/src/main/resources/application.properties`
- Create(시험): `src/back/src/test/java/dev/ondolithj/shared/RequestSizeLimitTest.java`, `src/back/src/test/java/fixture/size/FormProbe.java`, `fixture/size/FormProbeConfig.java`
- Modify(시험): `src/back/src/test/java/dev/ondolithj/TestHttp.java`

**Interfaces:**
- Produces: `@Component @Order(Ordered.HIGHEST_PRECEDENCE) public class RequestSizeLimit extends OncePerRequestFilter` — `public static final long MAX = 1_048_576`. 생성자 `RequestSizeLimit(JsonMapper json)`. 서블릿 필터로 **등록한다**(`AdminRateLimitFilter` 와 반대 — 보안 체인에 넣지 않는다). 운영 컨텍스트는 `shared` 스캔으로, 설치 컨텍스트는 `@Import` 로 얻는다.
  - `getContentLengthLong() > MAX` → 413(위 「다르게 정한 것」 8), `chain` 을 부르지 않는다. 모든 메서드·경로.
  - 그 밖은 `HttpServletRequestWrapper` 로 감싼다: `getInputStream()`·`getReader()` 가 읽은 바이트를 세고 `MAX + 1` 바이트째에 `RequestSizeLimit.TooLarge extends IOException` 을 던진다. `getReader` 는 세는 스트림 위에 요청 문자 인코딩(없으면 UTF-8)으로 만든다.
  - `ponytail:` 1-C3 업로드는 「POST 이고 원본 `getRequestURI()` 가 정확히 `/api/admin/pages/media` 일 때만 10 MiB」 한 행을 더한다(17절 2). 지금은 표를 만들지 않는다.
- `application.properties`: `spring.servlet.multipart.enabled=false`, 그리고 Step 5 결과에 따라 `server.tomcat.max-http-form-post-size=1MB`(「다르게 정한 것」 1).
- Produces(시험): `TestHttp.send(String method, String path, String contentType, byte[] body, boolean chunked, boolean csrf): HttpResponse<String>` — `chunked` 면 `BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))`(길이 모름 → chunked), 아니면 `ofByteArray`. `csrf` 면 `post` 와 같은 토큰 헤더. 클라이언트가 HTTP/1.1 로 보내 실제로 `Transfer-Encoding: chunked` 가 되는지는 `FormProbe` 의 `content_length` = -1 로 확인한다.
- `fixture.size.FormProbe` — `@PublicApi @PostMapping("/api/form-probe")` → `Map.of("length", x == null ? -1 : x.length(), "content_length", request.getContentLengthLong())`(`x` = `request.getParameter("x")`). `getParameter` 가 던지면(상한 초과를 예외로 내는 Tomcat 판이면) 잡아서 `length` -2 — 탐침 탓의 500 이 제품의 500 으로 보이지 않게. `FormProbeConfig` 가 그 빈과 `GuardCheck.Allowed("fixture.size")` 를 낸다.

- [ ] **Step 1: 시험 도구** — `TestHttp.send`, `fixture.size.FormProbe`·`FormProbeConfig`(위 Interfaces). 필터 없이 `FormProbe` 에 chunked 폼 `x=` + `a` × 1,500,000(CSRF 헤더 있음)을 보내 `length` 1,500,000·`content_length` -1 이 나오는지 본다 — 탐침이 실제로 chunked 를 받고 Tomcat 기본 상한(2 MB)까지 해석하는 출발점이다.
- [ ] **Step 2: 실패하는 시험**(`RequestSizeLimitTest`, 운영은 `AuthFixture.start(false, "--spring.main.sources=fixture.size.FormProbeConfig")`, 설치는 `Launcher.startInstall`(`InstallApiTest` 와 같은 방식))
  - **첫 Red** `익명_1MiB_넘는_Content_Length_는_처리기_전에_413`: 로그인·CSRF 없이 `POST /api/admin/settings` 에 JSON 1,048,577바이트 → 413, `application/problem+json`, `detail` 「요청 본문이 너무 큽니다」(403·401 이 아니다 — 보안 체인보다 앞). `/api/auth/login` 도 같다. 작업 로그 건수 그대로.
  - `정확히_1MiB_는_막지_않는다`(Review Focus 1): 관리자 로그인 + CSRF, `{"name": "<1,048,576바이트가 되게 채운 문자열>"}` → 413 이 아니라 422 `errors.name`.
  - `chunked_1MiB_초과도_끊기고_500_이_아니다`: CSRF 토큰만 가진 익명이 `POST /api/admin/settings` 에 chunked JSON 2 MiB → 400 또는 413, `application/problem+json`. 실제 값을 단언으로 고정한다(「다르게 정한 것」 2).
  - `폼_Content_Length_초과는_413`: `application/x-www-form-urlencoded`, 길이 있음, CSRF 없음, 1,048,577바이트 → 413.
  - `폼_chunked_초과는_1MiB_넘게_해석되지_않는다`: `FormProbe` 에 chunked 폼 1,500,000바이트(CSRF 헤더 있음) → 500 이 아니고, 200 이면 `length` 가 -1 또는 -2 이고 `content_length` 가 -1. 같은 요청 512 KiB 는 `length` 524,286(`x=` 뺀 길이)이라 탐침이 실제로 매개변수를 읽는 것을 보인다. CSRF 헤더 없는 같은 chunked 요청도 500 이 아니다.
  - `multipart_해석기가_없다`: 운영 `f.ctx.getBeanNamesForType(MultipartResolver.class)` 가 비었다.
  - `설치_컨텍스트에도_걸린다`: 설치 컨텍스트 `POST /api/install` 에 1,048,577바이트 → 413, chunked 2 MiB → 400 또는 413(운영 컨텍스트의 chunked 시험과 같은 값).
- [ ] **Step 3: Red** — `-Dtest=RequestSizeLimitTest` → 403·401·422(필터 없음).
- [ ] **Step 4: 구현** — 위 Interfaces.
- [ ] **Step 5: 순서와 폼 경로 확인(구현 뒤)**
  - 필터 순서: 운영 컨텍스트를 띄워 `ServletContextInitializerBeans`(또는 `FilterRegistrationBean`·`DelegatingFilterProxyRegistrationBean` 빈들)의 순서를 찍는다. `RequestSizeLimit` 가 `springSecurityFilterChain`(Boot 기본 -100)·Spring Session `SessionRepositoryFilter`(`MIN_VALUE + 50`)보다 앞인지 본다. `OrderedCharacterEncodingFilter` 와 같은 `HIGHEST_PRECEDENCE` 여도 그 필터는 본문을 읽지 않는다. 증명은 아래 시험의 「CSRF 없이 413(403 이 아니다)」이 한다.
  - 폼 경로: 필터만 넣은 상태(Tomcat 상한 기본값)에서 `폼_chunked_초과는_1MiB_넘게_해석되지_않는다` 를 돌린다. `length` 가 1,500,000 이면 Tomcat 이 래퍼를 지나지 않고 읽은 것이다 → `server.tomcat.max-http-form-post-size=1MB` 를 넣는다. CSRF 헤더 없는 요청은 `CsrfFilter` 의 `_csrf` 찾기가 같은 경로를 연다. 결과를 결정 문서 메모로 남긴다(Task 10 이 옮긴다).
- [ ] **Step 6: Green + 회귀** — `RequestSizeLimitTest` → 전체 백엔드 → `mise run e2e`(설치·로그인 E2E 12개). 통과 숫자를 적는다.
- [ ] **Step 7: 커밋** `페이지 1: 모든 요청 본문을 1 MiB 로 묶는다(두 컨텍스트, 맨 앞 필터)`

### Task 2: 서버 정화기 `RichText` (링크 값 검사 전)

**Files:**
- Modify: `src/back/pom.xml`(jsoup 1.24.1)
- Create: `src/back/src/main/java/dev/ondolithj/shared/RichText.java`
- Create(시험): `src/back/src/test/java/dev/ondolithj/shared/RichTextTest.java`, `src/back/src/test/resources/richtext/vectors.json`

**Interfaces:**
- Produces: `public final class RichText`
  - `public static String clean(String html)` — 예외를 던지지 않는다(null → `""`). 스펙 3.1 의 1~3: `Jsoup.parseBodyFragment` → `new Cleaner(SAFELIST).clean(doc)` → 값 검사(`img` 는 `src` 가 `^/api/media/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` 가 아니면 요소째, `ol[start]` 는 `^[0-9]{1,6}$` 가 아니면 속성만) → `prettyPrint(false)`·UTF-8 로 `body().html()`. 값 검사는 `select("img")`·`select("ol[start]")` 로 걷는다(재귀 없음 — 깊은 중첩). `a[href]` 검사는 Task 3 이 더한다.
  - `SAFELIST` = 스펙 3.2 표 그대로(`p br strong em h2 h3 ul ol li blockquote a img`, `a`: `href`, `img`: `src`·`alt`, `ol`: `start`). `addProtocols`·`preserveRelativeLinks`·`addEnforcedAttribute` 를 쓰지 않는다.
  - `public static Result forSave(String raw)` 와 `public sealed interface Result permits Ok, Bad` · `record Ok(String html)` · `record Bad(String message)` — 스펙 3.1 의 1~5 순서와 문구(「필수입니다」·「200,000자 이하」·「쓸 수 없는 문자가 있습니다」·「본문을 입력하세요」). 제어 문자는 `[\p{Cc}&&[^\n\r\t]]`, 보이지 않는 문자는 `SiteName` 과 같은 `[\p{Z}\p{C}]*`.
- Produces(시험): `vectors.json` — 배열, 줄마다 `{"id", "input", "server"}`. `U` 는 `00000000-0000-4000-8000-000000000001`. 이 Task 는 X1~X15·X22·I1~I11·S1~S8·A1 만 넣는다(S8 은 두 줄 `S8a`·`S8b`). A1 의 입력(태그 사이 공백·개행 없음, 1-C2 가 다시 쓴다):
  `<h2>제목</h2><h3>작은 제목</h3><p>문단 <strong>굵게</strong> <em>기울임</em><br>다음 줄</p><ul><li>하나</li></ul><ol start="3"><li>셋</li></ol><blockquote><p>인용</p></blockquote><p><a href="/about">안</a> <a href="https://example.com/a?b=1&amp;c=2#d">밖</a></p><p><img src="/api/media/00000000-0000-4000-8000-000000000001" alt="사진"></p>`
  jsoup 출력이 규칙이 아니라 표기만 다르면(속성 따옴표·`<br>` 꼴) A1 입력을 그 표기로 맞추고 결정 문서 메모에 적는다. 요소·속성이 빠지면 멈추고 보고한다. 백엔드는 `src/test/resources`, 프런트·E2E 는 같은 파일을 경로로 읽는다.

- [ ] **Step 1: jsoup 의 실제 출력 보기(의존성을 넣기 전)**
  - OSV: `curl -s -d '{"package":{"name":"org.jsoup:jsoup","ecosystem":"Maven"},"version":"1.24.1"}' https://api.osv.dev/v1/query` → `{}` 인지, `maven-metadata.xml` 의 `release` 가 아직 1.24.1 인지 본다. 다르면 멈추고 보고한다.
  - 스크래치 폴더에 `jsoup-1.24.1.jar` 를 Maven Central 에서 받아 `jshell --class-path` 로 위 SAFELIST·`Cleaner`·`OutputSettings` 를 그대로 만들고, 이 Task 의 모든 입력과 `<p>a&nbsp;b</p>`·`<p>&#0;</p>`·`<p>&#8;</p>`·`<a href="/about">x</a>` 를 넣어 출력을 찍는다. 볼 것: 상대 `href` 가 남는가(남지 않으면 멈추고 보고 — 허용 꼴이 깨진다), `&nbsp;`·`&amp;`·`<br>` 표기, `&#0;`(U+FFFD 인가)·`&#8;`(U+0008 인가), X11·X12·X13·S3·I10 의 출력. API 이름이 다르면(context7 이 안 되면 jsoup javadoc) 맞춘다.
  - 「요지」 줄(X11·X12·X13·S3·I10)은 그 출력으로 `server` 를 채운다. **요지와 다르면(스크립트가 실릴 자리 — `on*` 속성, 허용 밖 요소, `script`·`style` 내용 — 가 남으면) 멈추고 보고한다.** 규칙 줄은 스펙 표의 바이트다. 출력이 다르면 멈추고 보고한다(정화 규칙의 문제다).
  - 스크래치 파일은 끝나면 지운다.
- [ ] **Step 2: 의존성** — `pom.xml` 에 `org.jsoup:jsoup:1.24.1`(scope 기본). `./mvnw -q -B dependency:tree -Dincludes=org.jsoup` 로 전이 의존이 없는지 본다.
- [ ] **Step 3: 실패하는 시험**(`RichTextTest`, 단위)
  - **첫 Red** `벡터_표의_모든_줄이_server_와_바이트까지_같다`: 줄마다 `clean(input)` = `server`. `clean` 이 입력을 그대로 내는 껍데기면 X1 부터 실패한다.
  - `모든_출력은_허용_목록만_남는다`(성질, 모든 줄): `Jsoup.parseBodyFragment(clean(input))` 의 모든 요소가 SAFELIST 태그이고 모든 속성이 그 태그의 허용 속성이며, 이름이 `on` 으로 시작하는 속성이 없고, 출력 문자열에 `javascript:`(대소문자 무시)·`<script`·`<style` 이 없다. `img` 의 `src` 는 위 정규식과 전체 일치한다.
  - `멱등이다`: 모든 줄에서 `clean(clean(input)) == clean(input)`, `clean(server) == server`. A1 은 `clean(input) == input`.
  - `null_과_빈_값은_빈_문자열이고_던지지_않는다`, `깊은_중첩_200000자도_던지지_않는다`(`"<em>".repeat(40_000)` → `StackOverflowError` 없음, 걸린 시간을 찍어 결정 문서에 적는다).
  - `forSave`: null → `Bad("필수입니다")`, 200,001자 → 「200,000자 이하」, `"<p>a\u0000</p>"`·`"<p>a\u0008</p>"` → 「쓸 수 없는 문자가 있습니다」, `"<p>a\n\r\tb</p>"` → `Ok`, `"<".repeat(200_000)` → 「200,000자 이하」(정화 뒤 `&lt;` 로 는다), `<p>&#0;</p>`·`<p>&#8;</p>`·`<img src="/api/media/U" alt="&#0;">` → `Bad` 이거나 `Ok` 인데 결과에 `\p{Cc}`(개행·탭 밖)가 없다, `<p> </p>`·`<p>&nbsp;</p>`·`<p>​</p>`·`<script>x</script>` → 「본문을 입력하세요」, `<img src="/api/media/U">` → `Ok`. 정규식 끝 개행은 I11 이 본다.
- [ ] **Step 4: Red** — `-Dtest=RichTextTest` → 컴파일 실패, 그다음 껍데기로 X1 실패.
- [ ] **Step 5: 구현** — 위 Interfaces.
- [ ] **Step 6: Green** — `-Dtest=RichTextTest` → 전체 백엔드.
- [ ] **Step 7: 커밋** `페이지 2: jsoup 정화기 RichText(허용 목록·img·ol start·forSave)와 XSS 벡터 표`

### Task 3: 링크 검사기 `LinkCheck`, 정화기의 `a[href]`, 슬러그·예약어

**Files:**
- Create: `src/back/src/main/java/dev/ondolithj/shared/LinkCheck.java`, `shared/Slug.java`, `src/back/src/main/resources/reserved-slugs.txt`
- Modify: `shared/RichText.java`, `src/back/src/test/resources/richtext/vectors.json`
- Create(시험): `src/back/src/test/java/dev/ondolithj/shared/LinkCheckTest.java`, `shared/SlugTest.java`, `shared/ReservedSlugsTest.java`, `src/back/src/test/resources/richtext/links.json`
- Modify(시험): `shared/RichTextTest.java`(필요하면)

**Interfaces:**
- Produces: `public final class LinkCheck { public static boolean ok(String value) }` — 스펙 4절 1~4 그대로. 글자 집합은 `[A-Za-z0-9._~!$&()*+,;=:@/?#%-]`, 내부 `^/(?!/).*$` + `?`·`#` 앞 경로를 `/` 로 나눈 마디에 `.`·`..` 없음(`%2e` 대소문자 무시를 `.` 으로), 외부 `^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?([/?#].*)?$` + 포트 1~65535. `java.net.URI` 를 쓰지 않는다. null → false.
- `RichText.clean`: 값 검사에 `a[href]` 를 더한다 — `LinkCheck.ok(el.attr("href"))` 가 false 면 `removeAttr("href")`(글자는 남는다).
- Produces: `public final class Slug` — `public static final Pattern FORMAT = ^[a-z0-9][a-z0-9-]*$`, `public static boolean valid(String s)`(1~100자 + `FORMAT` 전체 일치), `public static boolean reserved(String s)`, `public static Optional<String> error(String raw)`(스펙 5.2 순서·문구: 「필수입니다」 → 「100자 이하」 → 「소문자·숫자·하이픈만 쓸 수 있습니다」 → 「사용할 수 없는 주소입니다」). 예약어는 클래스 로딩 때 `reserved-slugs.txt` 를 한 번 읽는다(한 줄 한 단어, `#` 주석·빈 줄 무시).
- `reserved-slugs.txt`: 스펙 5.1 의 스물넷(원문 스물하나 + `api`·`assets`·`error`). 머리 주석에 「프런트 시험(`PageView`/예약어 대조)도 이 파일을 읽는다」.
- Produces(시험): `links.json` — `{"reject": [...], "allow": [...]}`, 스펙 13.3 의 값 그대로(탭·개행·NBSP 는 JSON 이스케이프). 1-C2·1-C4 가 열을 더한다.

- [ ] **Step 1: 실패하는 시험**
  - **첫 Red** `LinkCheckTest.거부_표의_모든_값은_false`: 늘 `true` 인 껍데기면 `javascript:alert(1)` 줄이 실패한다. `허용_표의_모든_값은_true`, `null_은_false`, `501자는_false_500자_내부_경로는_true`.
  - 정규식 끝 개행: `Pattern.compile("^/(?!/).*$").matcher("/about\n").find()` 가 true 인지 한 줄 단언으로 남겨(스펙 3.1 의 「구현 때 확인」) 왜 `matches()` 인지 보인다.
  - `vectors.json` 에 X16~X21 과 X23-NN(거부 값, 기대 `<a>x</a>`)·X24-NN(허용 값, 기대 = 입력)을 더한다 — 값은 「다르게 정한 것」 4 처럼 이스케이프해 넣는다. Task 2 의 세 시험(바이트·성질·멱등)이 그대로 새 줄을 돈다 → X16 부터 실패.
  - `SlugTest`: `Foo`·`-a`·`a/b`·`a.b`·`a_b`·`한글`·`about\n`·101자·`""`·null → `error` 가 형식·길이·필수 문구, `admin`·`api`·`assets`·`error`·`login` → 「사용할 수 없는 주소입니다」, 100자·`a`·`0-a` 통과.
  - `ReservedSlugsTest.운영_컨텍스트의_모든_MVC_매핑_첫_마디가_예약어다`(`AuthFixture.start(false)`): 모든 `RequestMappingHandlerMapping` 패턴의 첫 마디(`/x/…` 의 `x`)가 `Slug.reserved` 다. 지금은 `api`·`error`.
- [ ] **Step 2: Red** — `-Dtest='LinkCheckTest,SlugTest,ReservedSlugsTest,RichTextTest'` → 컴파일 실패, 그다음 껍데기·X16 실패.
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** + 전체 백엔드.
- [ ] **Step 5: 커밋** `페이지 3: 링크 검사기, 정화기의 href 검사, 슬러그 형식과 예약어 목록`

### Task 4: V5·권한 다섯, 작성·발행·발행 취소, 공개 조회

**Files:**
- Create: `src/back/src/main/resources/db/migration/V5__pages.sql`, `src/back/src/main/java/dev/ondolithj/admin/PagesController.java`, `src/back/src/main/java/dev/ondolithj/ops/PageController.java`
- Modify: `auth/Permission.java`, `shared/OperationLog.java`, `OpenApiFileTest.java`, `src/front/openapi.json`·`schema.d.ts`(생성)
- Create(시험): `src/back/src/test/java/dev/ondolithj/admin/PagesApiTest.java`
- Modify(시험): `MigrationTest.java`, `auth/MeTest.java`, `admin/SystemApiTest.java`, `auth/ReauthTargetsTest.java`, `shared/RequestSizeLimitTest.java`, 설치 실패 원복의 기대 이력을 단언하는 곳(`MigrationTest.실패한_마이그레이션은_원복된다` 등 — `grep -rn '"1", "2", "3", "4"' src/back/src/test` 로 모두), 시드 역할의 권한 목록을 단언하는 곳(`grep -rn "editor" src/back/src/test` 로 찾는다 — `MeTest` 의 편집자 단언 등)

**Interfaces:**
- Produces: V5 — 스펙 2절 `CREATE TABLE pages` 그대로, 권한 다섯(설명은 스펙 2절 표), `editor`·`operator` 에 다섯. 머리 주석에 「enum Permission 과 같은 커밋」.
- Produces: `Permission` 에 `PAGE_VIEW("page.view", false)`, `PAGE_CREATE("page.create", false)`, `PAGE_UPDATE("page.update", false)`, `PAGE_DELETE("page.delete", false)`, `PAGE_PUBLISH("page.publish", false)`. (`page.view`·`page.update`·`page.delete` 의 미사용 WARN 은 Task 5 에서 사라진다.)
- Produces: `OperationLog.Action` 에 `PAGE_CREATE("page.create", "page")`, `PAGE_UPDATE("page.update", "page")`, `PAGE_DELETE("page.delete", "page")`, `PAGE_PUBLISH("page.publish", "page")`, `PAGE_UNPUBLISH("page.unpublish", "page")`.
- Produces: `PagesController`(`@AdminPermission` 메서드마다, `@Transactional` 없음)
  - `record PageInput(String slug, String title, String bodyHtml, Object status)` — `@Valid` 없음. 칸 검사 `static Map<String, String> errors(PageInput in)` 은 Task 5 의 수정도 쓴다: `status != null` → 「발행 상태는 발행 단추로 바꿉니다」, `Slug.error`, `SiteName.error(title, 200)`, `RichText.forSave(bodyHtml)` 의 `Bad`. 모아서 `FieldErrors`. `bodyHtml` 이 문자열이 아닌 JSON 일 때의 동작은 Review Focus 4 시험이 고정한다(전역 Jackson 설정을 바꾸지 않는다).
  - `POST /api/admin/pages`(`PAGE_CREATE`) → `INSERT … RETURNING id`(저장값은 `Slug` 원값·`SiteName.strip(title)`·`Ok.html`) → 201 `record Created(UUID id)`, 기록 `page.create`. `DuplicateKeyException` → `Refused(409, "이미 사용 중인 주소입니다")`.
  - `POST /api/admin/pages/{id}/publish`(`PAGE_PUBLISH`), `…/unpublish`(`PAGE_PUBLISH`) — 스펙 5.4 순서 그대로. 예약어 409 는 `Refused(409, "이 주소는 시스템 경로와 충돌합니다")`. 같은 상태 재요청은 204·기록 없음.
  - `@ApiResponse` 는 스펙 6절 표의 코드.
- Produces: `PageController` — `@PublicApi @GetMapping("/api/pages/{slug}")` → `record PublicPage(String slug, String title, String bodyHtml)`. `Slug.valid` 가 false 면 DB 에 가지 않고 `Refused(404, "페이지를 찾을 수 없습니다")`. 질의 `SELECT slug, title, body_html FROM pages WHERE slug = ? AND status = 'published'`, `bodyHtml` 은 `RichText.clean` 을 지난 값. 세션·`Actor` 를 받지 않는다. `@ApiResponse` 200·404.
- `ReauthTargetsTest.NOT_TARGETS` 에 `POST /api/admin/pages`·`POST /api/admin/pages/{id}/publish`·`…/unpublish` 와 `call`(작성은 새 슬러그 `rt-<n>` 본문, 발행·발행 취소는 표의 `Ids` 에 더한 `page` id — `record Ids` 에 `UUID page` 를 더하고 `@BeforeAll` 에서 하나 만든다).
- Produces(시험): `PagesApiTest` 도우미 `UUID create(TestHttp http, String slug, String body)`, `void publish(TestHttp http, UUID id)`, `long queries(String like)`(`UsersApiTest` 와 같은 `pg_stat_statements` 셈), 시험 역할 `role(String key, String... perms): UUID`(`INSERT INTO roles (key, name)` + `role_permissions`).

- [ ] **Step 1: 실패하는 시험**
  - **첫 Red** `PagesApiTest.작성하고_발행하면_익명이_정화된_본문을_받는다`: 편집자가 `<p><strong>a</strong></p><script>alert(1)</script>` 로 작성 201 → 발행 204 → 익명 `GET /api/pages/<slug>` 200, `body_html` = `<p><strong>a</strong></p>`, `title`·`slug` 그대로. (처리기가 없어 404 로 실패.)
  - `초안은_익명_로그인_모두_404`: 작성만 한 슬러그 → 익명·편집자 세션 모두 404 「페이지를 찾을 수 없습니다」. 구현 뒤 **공개 질의의 `AND status = 'published'` 를 잠시 빼 이 시험이 실패하는 것을 본다**(스펙 13.1).
  - 공개 조회: `형식이_틀린_슬러그는_404_이고_질의가_0`(`Foo`·`a.b` — `pages` 가 든 문장 증분 0), `발행_취소하면_404`, `쿠키가_있어도_없어도_본문이_바이트까지_같다`, `Cache_Control_에_no_store`, `익명_요청의_질의는_하나`(`pages` 가 든 문장 증분, 실제 값을 결정 문서에 적는다).
  - 권한: `편집자는_작성_발행_발행취소가_된다`, `page_update_만_가진_역할은_작성_발행_발행취소가_403이고_기록이_없다`(시험 역할 `admin.access`·`page.update`), `익명은_401`, `CSRF_없으면_403`.
  - 작성 칸: `status_가_실리면_422_이고_다른_모르는_칸은_무시`(`"status": "published"` → 422 `errors.status`, 행 없음; `"author": "x"` → 201), 슬러그 13.4 의 값들 → 422 `errors.slug`(`about\n` 은 500 이 아니다), 100자 201, 제목 `""`·`"   "`·`" "`·`"a\u0000b"`·201자 → 422 `errors.title`, 200자 201. `body_html_이_문자열이_아니면_500_이_아니다`(Review Focus 4: 넷 모두 500 아님, `[]`·`{}` → 400 또는 422·행 없음, `123`·`true` 가 201 이면 저장값 = `RichText.forSave` 의 결과 — 실제 상태를 단언으로 고정한다).
  - 본문(스펙 13.2 아래 API 수준 목록 전부): 200,000자 201·200,001자 422, `<` 200,000자 422, U+0000·U+0008 422, `\n`·`\r`·`\t` 통과, 엔티티 셋 → 422 또는 201 이고 500 이 아니며 저장값에 제어 문자가 없다, 빈 본문 넷 422 「본문을 입력하세요」, 사진만 201, 깊은 중첩 200,000자 → 500·`StackOverflowError` 없이 끝난다(걸린 시간을 적는다).
  - `벡터_입력을_저장하면_저장값과_공개_응답이_server_와_같다`: `vectors.json` 줄마다 `input + "<p>z</p>"` 로 작성·발행 → DB `body_html` = `server + "<p>z</p>"`, 공개 응답도 같다(0절 완료 기준 1).
  - 슬러그 충돌: `작성_슬러그_중복은_409`, `같은_슬러그_동시_작성은_201_하나_409_하나`(`postAsync`, `@Timeout(90)`).
  - 발행: `첫_발행은_204_와_행_하나_다시_발행은_204_행_그대로`, `DB_에_넣은_예약어_슬러그_초안의_발행은_409_발행된_그_행의_재발행도_409_발행_취소는_204`(`f.sql` 로 `slug = 'admin'` 행), `없는_id_는_404`, 경로가 UUID 가 아니면 404.
  - 작업 로그: `작성_발행_발행취소_뒤_그_계정_행에_슬러그_제목_본문이_없다`(`row_to_json`), `거절은_기록하지_않는다`(403·404·409·422).
  - `RequestSizeLimitTest.한글_200000자_본문은_상한_안에서_201`(「다르게 정한 것」 3): 편집자가 `<p>` + `"가".repeat(199_993)` + `</p>` 작성 → 201(요청 약 600 KB).
  - 기존 시험: `MigrationTest` 에 `V5_가_pages_표와_권한_다섯을_심고_editor_operator_에만_준다`(표·CHECK 셋 — 슬러그 형식·길이, 제목 길이, 본문 길이, 상태 — 위반 INSERT 가 실패, `anonymous`·`member` 에 없음, `V4_…` 는 `.target("4")`), 원복 기대 이력 `"1"…"5"`, `SystemApiTest` 적용 목록에 `"5"`, `MeTest` `can` 키 열다섯·편집자와 운영자의 `page.*` true, `ReauthTargetsTest`(위), `OpenApiFileTest`(경로 목록에 `/api/admin/pages`·`/api/admin/pages/{id}/publish`·`…/unpublish`·`/api/pages/{slug}`, 응답 코드 = 스펙 6절, `PageInput` 스키마의 칸 `slug`·`title`·`body_html`(`status` 는 스키마에 나오면 OpenAPI `@Schema(hidden = true)` 로 숨긴다 — 이름은 구현 때 확인), `PublicPage` 칸).
- [ ] **Step 2: Red** — `-Dtest='PagesApiTest,MigrationTest,MeTest,SystemApiTest,ReauthTargetsTest,OpenApiFileTest,RequestSizeLimitTest'` → 컴파일 실패, 그다음 404·V5 없음·분류 안 됨.
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** + 전체 백엔드 → `npm run gen:api && npm run typecheck`.
- [ ] **Step 5: 커밋** `페이지 4: V5 pages 와 권한 다섯, 작성·발행·발행 취소, 공개 페이지 조회`

### Task 5: 목록·상세·수정·삭제, 발행본 수정 403, 내보낼 때 정화

**Files:**
- Modify: `admin/PagesController.java`, `OpenApiFileTest.java`, `openapi.json`·`schema.d.ts`(생성)
- Modify(시험): `admin/PagesApiTest.java`, `auth/ReauthTargetsTest.java`

**Interfaces:**
- Consumes: `PagesController.errors(PageInput)`·`Created`·도우미(Task 4), `RichText.clean`.
- Produces: `GET /api/admin/pages?page=1`(`PAGE_VIEW`) — `record PageList(List<PageItem> items, int page, int pageSize, boolean hasNext)`, `record PageItem(UUID id, String slug, String title, String status, Instant updatedAt)`. 쪽 규칙은 A-601·A-401 과 같다(`page < 1` → `FieldErrors(page: "1 이상")`, 정수가 아니면 400, `LIMIT 51 OFFSET (page - 1L) * 50`, `ORDER BY updated_at DESC, id`). `body_html` 을 SELECT 하지 않는다.
- Produces: `GET /api/admin/pages/{id}`(`PAGE_UPDATE`) — `record PageDetail(UUID id, String slug, String title, String bodyHtml, String status, Instant createdAt, Instant updatedAt)`, `bodyHtml` 은 `clean`. 없으면 404 「페이지를 찾을 수 없습니다」.
- Produces: `POST /api/admin/pages/{id}`(`PAGE_UPDATE`) — 칸 검사(422 가 대상 조회보다 앞) → 스펙 17절 1 의 한 문장: `boolean publisher = actor.permissions().contains(Permission.PAGE_PUBLISH)`(superuser 는 `ActorQuery` 가 권한 전부로 채운다), `UPDATE pages SET slug = ?, title = ?, body_html = ?, updated_at = now() WHERE id = ?` + `publisher` 가 아니면 `AND status = 'draft'`. 0행 → 있으면 `Refused(403, "발행된 페이지는 발행 권한이 있어야 고칠 수 있습니다")`, 없으면 404. 중복 409. 성공 204·기록 `page.update`.
- Produces: `POST /api/admin/pages/{id}/delete`(`PAGE_DELETE`) — `DELETE … WHERE id = ?`, 0행 404, 204·기록 `page.delete`.
- `ReauthTargetsTest.NOT_TARGETS` 에 `POST /api/admin/pages/{id}`·`…/delete` 와 `call`(수정은 `Ids.page` 에 같은 슬러그·제목·본문, 삭제는 `Ids` 에 더한 `UUID doomed` — `@BeforeAll` 에서 둘째 페이지를 만든다. 다른 키의 page 를 지우지 않게).
- `ponytail:` 동시 편집은 나중 저장이 이긴다(17절 3). 편집이 사라졌다는 보고나 1-C2 자동 저장이 생기면 읽은 `updated_at` 으로 409 — 수정 처리기에 주석 한 줄.

- [ ] **Step 1: 실패하는 시험**(`PagesApiTest` 에 더한다)
  - **Red(17절 1)** `발행_권한_없는_역할은_발행된_페이지를_고칠_수_없다_403`: 시험 역할 `admin.access`·`page.update` 사용자 → 편집자가 만든 초안 수정 204, 편집자가 발행한 페이지 수정 403 「발행된 페이지는 발행 권한이 있어야 고칠 수 있습니다」, DB 행(슬러그·제목·본문·`updated_at`) 그대로, 그 계정의 기록 없음. 구현 뒤 `publisher` 판정을 빼 204 로 실패하는 것을 한 번 본다.
  - `편집자와_superuser_는_발행본을_고친다`: 편집자 204, 설치 관리자(superuser) 204, 발행본의 슬러그 변경 204 → 옛 슬러그 공개 404·새 슬러그 200.
  - `슬러그를_그대로_둔_수정은_204`(Review Focus 2), `다른_페이지의_슬러그로_바꾸면_409`, `없는_id_는_404_틀린_본문이면_422`(칸 검사가 먼저), `수정의_status_는_422_이고_상태가_그대로`.
  - **Red(내보낼 때 정화)** `DB_에_넣은_옛_HTML_도_공개와_상세에서_정화된다`: `f.sql` 로 `body_html = '<p onclick="x">a</p><script>alert(1)</script>'`, `status = 'published'` 행 → 공개 응답·관리 상세 `body_html` 모두 `<p>a</p>`(0절 완료 기준 3). 두 처리기의 `clean` 을 잠시 빼 실패를 본다.
  - 삭제: `삭제는_204_와_행_하나_공개는_404_다시_삭제는_404`, `page_update_만_가진_역할은_삭제가_403`.
  - 상세: `상세는_편집자_200_page_view_만이면_403`, 응답 칸이 Interfaces 그대로.
  - 목록: `쪽당_50_한_행_더_읽어_has_next`(시험마다 새 DB 가 아니므로 시작 전 `DELETE FROM pages` 를 이 시험 클래스의 `@BeforeEach` 에 둔다 — 다른 시험의 행이 섞이지 않게; 51개 → 1쪽 50·true, 2쪽 1·false, `updated_at` 내림차순), `끝을_넘은_쪽은_빈_목록`(Review Focus 5: `page=3` → 200·빈 `items`·false), `page_0_은_422_abc_는_400`, `목록에_body_html_이_없다`(본문에 `body_html` 키와 본문 문자열이 없다), `목록_질의는_행_수와_관계없이_같다`(3개와 54개의 `pages` 가 든 문장 증분이 같다), `page_view_만이면_목록_200`.
  - 작업 로그: 수정·삭제 뒤 그 계정 행에 슬러그·제목·본문 문자열이 없다. 403·404·409·422 거절은 기록하지 않는다.
  - `OpenApiFileTest`: 경로 `/api/admin/pages/{id}`(GET·POST)·`/api/admin/pages/{id}/delete`, 목록 GET, 응답 코드 = 스펙 6절(수정 403 설명에 「발행본인데 page.publish 없음」).
- [ ] **Step 2: Red** — `-Dtest='PagesApiTest,ReauthTargetsTest,OpenApiFileTest'` → 404·405·분류 안 됨.
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** + 전체 백엔드(`어느 API 도 쓰지 않는 권한` WARN 0건) → `npm run gen:api && npm run typecheck`.
- [ ] **Step 5: 커밋** `페이지 5: 목록·상세·수정·삭제, 발행본 수정에 발행 권한, 내보낼 때 다시 정화`

### Task 6: 1-B2 에서 넘어온 것 — (e)·(f)·M-11·M-12·M-13·M-14

**Files:**
- Modify: `auth/GuardCheck.java`, `admin/UsersController.java`, `admin/RolesController.java`, `auth/LoginController.java`, `auth/ReauthController.java`, `OpenApiFileTest.java`, `openapi.json`·`schema.d.ts`(생성), `src/front/src/features/admin/RolesPage.tsx`
- Create(시험): `src/back/src/test/java/fixture/guard/RootVariable.java`, `fixture/guard/RootVariableConfig.java`
- Modify(시험): `auth/GuardCheckTest.java`, `admin/UsersApiTest.java`, `admin/RolesApiTest.java`, `src/front/src/features/admin/RolesPage.test.tsx`, `UserDetailPage.test.tsx`, `src/front/e2e/users.spec.ts`

**Interfaces:**
- M-11: `GuardCheck.API_ROOT_VARIABLE` 를 `^/(api/)?[^/]*[{*?]` 로. 메시지 「첫 마디나 /api/ 바로 다음 마디에 변수·와일드카드를 쓸 수 없다」. 주석에 스펙 9절의 `ponytail:`(Phase 4 껍데기 처리기 `/{slug}` 가 생기면 「`/api` 아래를 받을 수 없는 꼴」로 좁힌다).
- `fixture.guard.RootVariable` — `@RequiresPermission(ADMIN_ACCESS) @GetMapping("/{area}/admin/probe")`. Config 는 빈만(허용은 `AllowOutsideConfig`).
- M-12 ①: A-402 상세의 `assignable` 질의에 `AND (? OR NOT <SUPERUSER_ROLE>)`(`?` = `actor.superuser()`). 처리기에 `@AuthenticationPrincipal Actor actor` 를 더한다.
- M-12 ②: `RoleView` 에 `boolean visitorOnly`(= `EVERYONE.contains(key)`), `PermissionView` 에 `boolean visitor`(= `Permission.ofKey(key).map(Permission::visitor).orElse(false)`). `RolesPage`: `role.visitor_only` 면 고르기 후보를 `permissions` 중 `visitor` 인 것만으로 좁히고, 없으면 고르기를 그리지 않는다.
- M-13: 두 처리기의 주석을 스펙 9절 문장으로(「spring-security-crypto 7.1.2 의 `BCrypt.checkpw` 는 73바이트 비밀번호에 false 를 낸다. catch 는 판이 바뀌어 다시 던질 때를 위해 둔다」).
- M-14: A-404 빼기의 R4 문구 「보유하지 않은 권한은 뺄 수 없습니다」. 부여는 그대로.

- [ ] **Step 1: 실패하는 시험**
  - **M-11(Red)** `GuardCheckTest`: 단위 `첫_마디의_변수와_와일드카드도_문제다` — `checkApiRoot` 가 `/{x}/admin/y`·`/**`·`/*`·`/{*rest}` 에 문제 하나씩, `/api/pages/{slug}`·`/api/admin/users/{id}`·`/error` 에 없음. 기동 `첫_마디에_변수가_있으면_기동이_멈춘다` — `install(RootVariableConfig.class, AllowOutsideConfig.class)` 가 던지고 스택에 `RootVariable`. 지금은 기동을 통과해 실패한다.
  - **(e)** `UsersApiTest.상세_질의_수는_역할_수와_관계없이_같다`: 역할 1개 사용자와 5개(시험 역할 넷 추가) 사용자의 `GET /api/admin/users/{id}` 에서 `users` 또는 `roles` 가 든 문장 증분이 같고 4(인증 1 + 상세 셋)다. 다르면 이유를 결정 문서에 적는다.
  - **M-12 ①** `UsersApiTest.비superuser_의_상세에는_superuser_역할이_없다`: 시험 역할(`admin.access`·`user.update`) 사용자의 상세 `assignable_roles` 에 `admin` 없음, 관리자의 상세에는 있다.
  - **M-12 ②** `RolesApiTest.역할의_visitor_only_와_권한의_visitor`: `anonymous`·`member` 가 `visitor_only` true, 나머지 false. 모든 권한 `visitor` false(1-C1 에는 방문자 권한이 없다).
  - **M-14** `RolesApiTest` 의 R4 빼기 단언을 「보유하지 않은 권한은 뺄 수 없습니다」로.
  - 프런트 `RolesPage.test`: `visitor_only_역할에는_부여_고르기가_없다`(가짜 응답 `member` `visitor_only: true`, 모든 권한 `visitor: false` → 그 역할 절에 「줄 권한」 없음, `editor` 절에는 있다). `UserDetailPage.test`: `상세_응답에_superuser_역할이_없으면_고르기에도_없다`.
  - **(f)** `users.spec.ts` 의 「역할 화면에서 편집자에게 권한을 준다」 끝에 이어서: 같은 `log.view` 「빼기」 → 대화상자 「확인」 → 알림 「권한을 뺐습니다」 → `page.reload()` 뒤 그 역할 목록에 `log.view` 없음.
  - `OpenApiFileTest`: `RoleView`·`PermissionView` 칸 목록에 `visitor_only`·`visitor`.
- [ ] **Step 2: Red** — `-Dtest='GuardCheckTest,UsersApiTest,RolesApiTest,OpenApiFileTest'`, `npx vitest run src/features/admin` → 기동 통과·질의 수(시험이 먼저 통과하면 셈 대상을 잠시 바꿔 Red 를 본다)·칸 없음·문구 다름.
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** — 전체 백엔드 → `npm run gen:api && npm run typecheck && npx vitest run` → `mise run e2e`(아직 개발 서버 위다).
- [ ] **Step 5: 커밋** `페이지 6: 1-B2 에서 넘어온 것 — 첫 마디 와일드카드 거부, 늘 실패하는 선택지 빼기, 상세 질의 수, 권한 빼기 E2E, 주석·문구`

### Task 7: 프런트 `RichHtml` 과 DOMPurify

**Files:**
- Modify: `src/front/package.json`·`package-lock.json`(dompurify 3.5.0), `src/front/src/index.css`
- Create: `src/front/src/shared/ui/RichHtml.tsx`, `src/front/src/shared/ui/RichHtml.test.tsx`, `src/front/src/test-vectors.ts`

**Interfaces:**
- Produces: `export function RichHtml({ html }: { html: string })` — `<div className="rich" dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(html, CONFIG) }} />`, `CONFIG` 는 스펙 8.1 의 여섯 줄 그대로(파일 안 상수). 사유 주석 그대로: 「서버가 저장·내보낼 때 허용 목록으로 정화한 본문(1-C1 스펙 3절). 브라우저 파서로 한 번 더 거른다」. 8.1 「쓰지 않는다」 이름을 쓰지 않는다. `ponytail:` 내부 링크는 문서를 다시 불러온다(SPA 이동이 필요하면 여기서 클릭을 가로챈다).
- Produces(시험): `test-vectors.ts` — `export type Vector = { id: string; input: string; server: string }`, `export const vectors: Vector[]`(`node:fs` 로 `../back/src/test/resources/richtext/vectors.json` 을 `import.meta.dirname` 기준으로 읽는다), `export const ALLOWED_TAGS`, `export function frontInvariant(root: Element): string[]`(어긋난 점 목록 — `on` 속성, 허용 밖 요소, `href`·`src` 가 `/^(https?:\/\/|\/(?![\/\\]))/i` 도 아니고 `img` 의 `data:`(대소문자 무시)도 아닌 것 — 8.1 `ALLOWED_URI_REGEXP` 처럼 `i` 플래그라 X23 의 `HTTPS://example.com` 이 DOMPurify 를 지나도 판정과 어긋나지 않는다). Task 9 의 E2E 는 같은 판정(같은 `i` 플래그)을 브라우저에서 돌린다 — 두 곳 중 한쪽만 고치지 않는다.
- `index.css`: `.rich` 아래 몇 줄(문단 간격, `h2`·`h3` 크기, `ul`·`ol` 기호, `blockquote` 왼쪽 선, `a` 밑줄, `img` `max-width: 100%`).

- [ ] **Step 1: 판 고르기 전에 권고 읽기**
  - GitHub advisories(`https://github.com/cure53/DOMPurify/security/advisories`)·OSV(`{"package":{"name":"dompurify","ecosystem":"npm"}}`)의 2026년 권고 본문을 읽는다. 각각 「8.1 설정(기본 모드 + 여섯 줄, 훅 없음)에 닿는가」를 한 줄씩 적는다(결정 문서 메모, Task 10 이 옮긴다). 닿는 것이 있고 3.5.0 에서 고쳐지지 않았으면 멈추고 보고한다.
  - npm `latest` 와 OSV `dompurify@3.5.0` 0건을 다시 본다. 더 새 GA 가 있으면 그 판으로 하고 「다르게 정한 것」에 적는다.
- [ ] **Step 2: 설치** — `cd src/front && npm install --save-exact dompurify@3.5.0`. 타입이 패키지에 들어 있는지(`@types/dompurify` 가 필요 없는지) 확인한다. `package.json` 에 판이 정확히 적혔는지 본다.
- [ ] **Step 3: 실패하는 시험**(`RichHtml.test.tsx`)
  - **첫 Red** `img_onerror_와_javascript_링크가_DOM_에_남지_않는다`: `<img src=x onerror=alert(1)>`·`<a href="javascript:alert(1)">x</a>` → 렌더한 DOM 에 `onerror` 속성·`javascript:` 없음(부품이 없어 실패).
  - `모든_입력에서_프런트_불변식이_성립한다`: `vectors` 줄마다 `frontInvariant` 가 빈 목록(실패 메시지에 id).
  - `서버_출력은_DOMPurify_가_바꾸지_않는다`: 줄마다 `DOMPurify.sanitize(server, CONFIG) === server` — `CONFIG` 는 `RichHtml.tsx` 에서 내보내지 않고, 시험은 렌더한 `.rich` 의 `innerHTML === server` 로 본다.
  - `사유_주석이_있다`: `RichHtml.tsx` 소스에 사유 주석 문장이 있다(싱크 검사도 본다).
- [ ] **Step 4: Red** — `npx vitest run src/shared/ui/RichHtml.test.tsx` → 모듈 없음.
- [ ] **Step 5: 구현** — 위 Interfaces.
- [ ] **Step 6: Green** — 짝 시험이 깨지면(jsoup 과 브라우저 직렬화 차이 — `&nbsp;`·속성 순서·`<br>` 등) 이 순서로 고친다: ① 서버 `OutputSettings`(escapeMode 등)를 맞춘다 → ② `vectors.json` 의 그 줄 `server` 를 새 출력으로 바꾼다 → ③ `RichTextTest`·`PagesApiTest.벡터_입력을_저장하면…` 을 다시 돌린다 → ④ 바꾼 줄과 이유를 결정 문서 메모에. 설정 여섯 줄을 늘려 맞추지 않는다. 그다음 `npx vitest run` 전체 + `npm run typecheck`.
- [ ] **Step 7: 커밋** `페이지 7: RichHtml 한 곳과 DOMPurify, 서버 출력과의 짝 시험`

### Task 8: 공개 화면 `PageView`·`NotFound`, 작업 로그 이름표, HTML 싱크 검사

**Files:**
- Create: `src/front/src/features/page/PageView.tsx`, `PageView.test.tsx`, `src/front/src/shared/ui/NotFound.tsx`, `src/front/src/features/page/reservedSlugs.test.ts`, `tests/html-sinks.test.sh`
- Modify: `src/front/src/app/App.tsx`, `App.test.tsx`(필요하면), `src/front/src/features/admin/OplogPage.tsx`·`OplogPage.test.tsx`

**Interfaces:**
- Consumes: `RichHtml`(Task 7), `GET /api/pages/{slug}`(Task 4 생성 타입), `useSite`, `useFocusOnMount`.
- Produces: `App.tsx` `Running` 에 `<Route path="/:slug" element={<PageView />} />`(`/login`·`/admin` 뒤), `*` 를 `<NotFound />` 로.
- Produces: `PageView` — `const SLUG = /^[a-z0-9][a-z0-9-]*$/`(1~100자, 서버 `Slug` 와 같다). 틀리면 요청 없이 `NotFound`. 쿼리 키 `["page", slug]`, `queryFn` 은 404 면 `null`, 그 밖 실패는 던진다. 200: `<h1 ref={useFocusOnMount(slug)} tabIndex={-1}>` 제목 + `<RichHtml html={body_html} />` + `<title>{`${title} – ${site.name}`}</title>`(한 문자열). 404 → `NotFound`. 실패 → `role="alert"` 「페이지를 불러오지 못했습니다」(서버 `detail`·경로를 그리지 않는다).
- Produces: `NotFound()` — h1 「찾을 수 없습니다」, 본문 문구(Global Constraints), `<meta name="robots" content="noindex" />`.
- 작업 로그 이름표: `page.create` 페이지 작성, `page.update` 페이지 수정, `page.delete` 페이지 삭제, `page.publish` 페이지 발행, `page.unpublish` 페이지 발행 취소.
- Produces: `tests/html-sinks.test.sh` — 함수 `check_sinks <src 폴더>`(위반을 `파일:줄: 이름` 으로 찍고 있으면 1). 스펙 8.4 그대로: 시험 파일(`*.test.ts`·`*.test.tsx`·`test-*.ts`·`test-*.tsx`) 밖에서 열 이름과 `href={` 를 센다. 예외 표는 스크립트 안 한 곳(`경로|이름|횟수|사유`): `shared/ui/RichHtml.tsx|dangerouslySetInnerHTML|1|서버 정화 두 번 + DOMPurify(1-C1 스펙 8.1)`. 그 파일은 `DOMPurify.sanitize(` 가 있고 `addHook`·`IN_PLACE`·`RETURN_DOM`·`SAFE_FOR_TEMPLATES`·`CUSTOM_ELEMENT_HANDLING`·`ADD_TAGS`·`setConfig` 가 없고 사유 주석 문장이 있어야 한다. `node_modules` 는 보지 않는다. `check-public.test.sh` 처럼 `통과 N / 실패 M` 을 찍는다.

- [ ] **Step 1: 실패하는 시험**
  - **첫 Red** `PageView.test`: `200_이면_h1_제목_본문_문서_제목`(가짜 `/api/pages/about` 200, `/api/site` → h1 「소개」에 초점, `.rich` 안에 `<strong>`, `document.title` 이 「소개 – 사이트」 — jsdom 에서 React `<title>` 이 `head` 에 오르는지는 구현 때 확인, 안 오르면 이 단언은 E2E(Task 9)로 옮기고 이유를 적는다), `404_면_찾을_수_없습니다와_noindex`(`document.head` 의 `meta[name=robots][content=noindex]`), `500_이면_고정_문구이고_서버_detail_을_그리지_않는다`(`detail` 「비밀-detail-x」 없음), `형식이_틀린_슬러그는_요청이_0`(`/Foo`·`/a.b` — 가짜 fetch 호출 0, `NotFound`), `NotFound_에서_유효한_페이지로_이동하면_noindex_가_남지_않는다`(Review Focus 3: `/Foo` → 링크로 `/about` → `meta[name=robots]` 0개).
  - `App.test`: `/없는/주소` 가 「찾을 수 없습니다」(옛 「없는 화면입니다」 단언을 바꾼다).
  - `reservedSlugs.test.ts`: `App.tsx` 소스에서 `<Route path="/([a-z][a-z0-9-]*)"` 로 뽑은 최상위 정적 라우트(`login`·`admin`)와 `vite.config.ts` 의 `assetsDir`(없으면 Vite 기본 `assets`)가 `reserved-slugs.txt` 에 모두 있다.
  - `OplogPage.test`: 다섯 키가 이름표로 보인다.
  - `tests/html-sinks.test.sh`: 임시 폴더 고정 입력 — 깨끗한 트리 0, 다른 파일의 `dangerouslySetInnerHTML` 1, `RichHtml.tsx` 의 두 번째 사용 1, `RichHtml.tsx` 에 `DOMPurify.sanitize(` 없음 1, `addHook` 있음 1, `localStorage` 1, `href={` 1, 시험 파일의 `innerHTML` 0. 그리고 실제 `src/front/src` 에 `check_sinks` → 0.
- [ ] **Step 2: Red** — `npx vitest run src/features src/app` → 모듈 없음·문구 다름. `bash tests/html-sinks.test.sh` → 파일 없음. 싱크 검사는 구현 뒤 **일부러 깨서**(다른 파일에 `dangerouslySetInnerHTML` 한 줄, `RichHtml.tsx` 에서 `DOMPurify.sanitize(` 빼기) 실제 트리 검사가 실패하는 것을 보고 되돌린다(완료 기준 6).
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** — `npx vitest run` 전체 + `npm run typecheck` + `for t in tests/*.test.sh; do bash "$t" || exit 1; done`.
- [ ] **Step 5: 커밋** `페이지 8: 공개 화면 /:slug 와 404(noindex), 작업 로그 이름표, HTML 싱크 셸 검사`

### Task 9: 빌드 CSP 와 빌드 산출물로 도는 E2E

**Files:**
- Modify: `src/front/vite.config.ts`, `src/front/playwright.config.ts`, `src/front/e2e/stack.ts`, `.github/workflows/ci.yml`(필요할 때만)
- Create: `src/front/e2e/pages.spec.ts`

**Interfaces:**
- Produces: `vite.config.ts` 의 플러그인 하나 — `{ name: "csp", apply: "build", transformIndexHtml: () => [{ tag: "meta", attrs: { "http-equiv": "Content-Security-Policy", content: CSP }, injectTo: "head-prepend" }] }`, `CSP` 는 스펙 8.3 문자열 그대로. 반환 꼴(태그 배열)은 Vite 문서로 확인한다.
- Produces: `playwright.config.ts` — `use.baseURL` `http://127.0.0.1:4173`, `webServer.command` `npm run build && npx vite preview --host 127.0.0.1 --port 4173 --strictPort`, `url` 같은 주소, `timeout: 180_000`(기본값은 빌드에 짧다 — 기본값은 구현 때 확인해 주석에), `bypassCSP` 없음. 머리 주석을 「실제 jar + PostgreSQL + 빌드 산출물(`vite preview`, `/api` 는 `server.proxy` 를 물려받아 8080)」으로(스펙 12절).
- Produces(`stack.ts`): `export type CspViolation = { directive: string; blocked: string; url: string }`, `export async function watch(target: BrowserContext): Promise<Watch>`, `export type Watch = { violations: CspViolation[]; dialogs: string[]; allow(match: (v: CspViolation) => boolean): void; check(): void }`.
  - `watch` 는 `target.exposeFunction("__cspViolation", …)` 과 `target.addInitScript` 로 `document.addEventListener("securitypolicyviolation", e => __cspViolation({ directive: e.effectiveDirective, blocked: e.blockedURI, url: location.href }))` 를 붙이고, 문맥의 모든 페이지에 `page.on("dialog", d => { dialogs.push(d.message()); d.dismiss() })` 를 건다 — 이미 열린 `target.pages()` 를 한 번 돌고, 새 페이지는 `target.on("page")`. 문맥 단위라 `page.goto` 를 여러 번 해도, 새 문맥을 만들어도 모인다. 콘솔 오류는 쓰지 않는다.
  - `check()` — `allow` 로 받지 않은 위반이 0건이고 `dialogs` 가 0건임을 단언한다.
  - 자동 fixture `guard`(`{ auto: true }`): 기본 `context` 에 `watch` → `use` → `check()`. 시험은 `guard.allow(…)` 로 자기 기대를 적는다. 새 문맥을 만드는 시험은 그 문맥에 `watch` 를 직접 부르고 끝에 `check()` 한다.
- 시험용 변환 `xss(input: string) = input.replaceAll("alert(1)", "window.__xss=1")`(「다르게 정한 것」 5), `pages.spec.ts` 안.

- [ ] **Step 1: 빌드·미리보기 확인** — `npm run build` 뒤 `dist/index.html` 의 `<head>` 첫 자식이 CSP `<meta>` 이고 내용이 8.3 문자열과 같은지, 인라인 `<script>`·`<style>`·`data:` 폰트가 산출물에 없는지(`grep`) 본다. `npx vite preview --host 127.0.0.1 --port 4173 --strictPort` 가 뜨고 `/login`·`/admin/users` 같은 깊은 주소가 `index.html` 을 내며 `/api/health` 가 8080 으로 넘어가는지(jar 를 띄워) 본다. 미리보기는 확인 뒤 끈다.
- [ ] **Step 2: 실패하는 시험** — 먼저 `stack.ts` 의 `watch`·`Watch`·자동 fixture `guard`(위 Interfaces)를 만든다(시험 도구라 Red 앞이다). 그다음 `pages.spec.ts`:
  - **첫 Red** `CSP 가 실제로 걸려 있다`: `page.route` 로 `/api/health`(running)·`/api/site`·`/api/me`(401)를 주고 `/` 를 연다 → `meta[http-equiv="Content-Security-Policy"]` 의 `content` 가 8.3 문자열과 같다. `page.evaluate` 로 `<script>window.__inline = 1</script>` 요소를 `head` 에 붙인다 → `window.__inline` 이 `undefined`, `guard.violations` 에 `script-src-elem`(또는 `script-src`) 1건 — 그 1건만 `allow`. (개발 서버라 meta 가 없어 실패.)
  - `API 로 만든 페이지가 비로그인 방문자에게 정화된 채로 보인다`(스택): 설치 → 관리자 로그인 → `page.request` 로 작성(`<h2>소제목</h2><p><strong>굵게</strong> <a href="/about">안</a> <a href="https://example.com/">밖</a></p><ul><li>하나</li></ul>` + `xss()` 한 X1·X3·X14·X16 입력)·발행 → `browser.newContext()` + `watch` → `/{슬러그}`: h1·`strong`·두 링크가 보이고, `page.title()` 이 「제목 – E2E 사이트」(「다르게 정한 것」 7 을 가른다), `.rich` 안에 `script` 요소·`on*` 속성이 없고, `waitForLoadState("networkidle")` 뒤 `window.__xss` 가 `undefined`, `dialogs` 0. 초안 슬러그·없는 슬러그 → 「찾을 수 없습니다」 + `meta[name=robots][content=noindex]`. 발행 취소 → 같은 주소를 다시 열면 「찾을 수 없습니다」. 끝에 `check()`.
  - `DOMPurify 겹: 서버를 거치지 않은 오염 HTML 도 스크립트가 돌지 않는다`(스택 없음): `page.route` 로 health·site·me(401), `/api/media/**` 는 빈 200, `/api/pages/*` 는 그 차례 줄의 `xss(input)` 을 `body_html` 로. `test.setTimeout(300_000)`(줄이 90개 안팎이고 줄마다 `goto`·`networkidle`). `vectors.json`(node `fs`) 줄마다 `/v-<id 소문자>` 를 열고: `.rich` 에 `frontInvariant` 와 같은 판정(`page.evaluate` 안에 같은 규칙)이 빈 목록, `networkidle` 뒤 `window.__xss` `undefined`. CSP 기대: `img-src` 위반은 I1·I7 줄에서만(그 둘만 `allow`, 다른 줄에서 나면 실패), `script-src` 계열 0건, `dialogs` 0.
  - 기존 E2E 12개(`admin`·`install`·`login`·`users`)는 고치지 않고 같은 `guard` 아래에서 돈다.
- [ ] **Step 3: Red** — `mise run e2e`(개발 서버 설정 그대로) → `CSP 가 실제로 걸려 있다` 가 `meta[http-equiv=…]` 없음으로 실패한다. 기존 12개는 `guard` 아래에서 통과해야 한다(개발 서버에는 CSP 가 없다).
- [ ] **Step 4: 구현** — 위 Interfaces(플러그인·`playwright.config.ts`).
- [ ] **Step 5: Green** — `mise run e2e` → 15개(기존 12 + 새 3). 기존 시험에서 위반이 나면:
  - Radix 대화상자 등이 `<style>` 을 넣어 `style-src-elem` 위반이면(증거: 위반의 `directive`·`blocked`·시험 이름), CSP 의 `style-src` 에만 `'unsafe-inline'` 을 더하고(스펙 8.3) 증거를 결정 문서 메모에. `script-src` 는 어떤 경우에도 넓히지 않는다 — 스크립트 위반이면 멈추고 보고한다.
  - `font-src`·`img-src` 위반(작은 자산이 `data:` 로 인라인된 경우 등)은 원인을 적고 빌드 설정(`build.assetsInlineLimit`)으로 없앤다. CSP 를 넓히지 않는다.
  - 새 시험이 처음부터 통과하면 단언 하나(예: `window.__xss` 기대를 `1` 로)를 일부러 틀려 실패를 보고 되돌린다.
- [ ] **Step 6: CI** — `ci.yml` 의 e2e 작업은 `npx playwright test` 가 빌드까지 하므로 단계는 그대로 둔다. `timeout-minutes: 20` 은 스펙 10절 기준선(Playwright 단계 8분 25초) + 빌드 시간이라 바꾸지 않는다. 로컬 `mise run e2e` 전체 시간과 `npm run build` 시간을 재서 결정 문서 메모에 적는다(PR CI 의 단계 시간은 PR 뒤에 주 세션이 본다).
- [ ] **Step 7: 커밋** `페이지 9: 빌드 산출물 CSP 와 vite preview E2E, CSP 위반·대화상자 수집, 공개 페이지·DOMPurify 겹 E2E`

### Task 10: 문서, 공개 점검, 전체 시험

**Files:**
- Modify: `docs/decisions.md`, `docs/appendix-a.md`

- [ ] **Step 1: 결정 문서** — 「Phase 1-C 스펙」 아래 「1-C1 구현 결과」 절:
  - 구현 중 정한 것: 위 「스펙과 다르게 정한 것」 여덟의 실제 결과(Task 1 폼 탐침, chunked 상태 코드, `<title>` 승패), 「구현 때 확인」의 결과(jsoup 상대 `href`·`&nbsp;`·`&#0;`·`&#8;`·X11~X13 출력, 깊은 중첩 시간, `<meta>` CSP 위치, `style-src` 결정과 증거, `addInitScript` 리스너가 CSP 아래에서 붙었는지, 공개 조회·상세 질의 수 실측값, DOMPurify 권고 대조 한 줄씩, Playwright `webServer` 기본 시한, 빌드·E2E 시간).
  - D86 결과 표: 스펙 14절 행마다 ✓·시험 이름·한계(1.12 미해결 그대로, 3.7 `frame-ancestors` 는 Phase 4).
  - 「1-B2 최종 검토」 미룬 Minor 표의 (e)·(f)·M-11·M-12·M-13·M-14 에 「1-C1 Task 6 에서 고침」.
- [ ] **Step 2: 부록 A** — FR-401(정적 페이지 CRUD: 1-C1 API, 화면은 1-C2), FR-402(슬러그 URL: 1-C1, 한글 슬러그 없음), FR-403(초안·발행: 1-C1, 발행본 수정에 `page.publish`), FR-513(리치 텍스트: 서버 정화·공개 표시 1-C1, 편집기 1-C2) 메모.
- [ ] **Step 3: 전체 시험** — `nice -n 19 mise run test` 와 `mise run e2e`. 셸·백엔드·프런트·E2E 의 통과·실패 숫자를 결정 문서에 적는다. 못 돌린 것은 「미실행」과 이유.
- [ ] **Step 4: 공개 점검** — `bash scripts/check-public.sh $(git diff --name-only main...HEAD)` → 출력 없음, exit 0. `git config user.email` 이 noreply 인지 본다. 스크래치 파일(jsoup jar·jshell 기록)이 남지 않았는지 본다.
- [ ] **Step 5: 커밋** `페이지 10: 1-C1 구현 결과와 D86 대조, 부록 A`

---

**메모리 측정은 1-C 끝(1-C4 뒤)에** 주 세션이 1-B 와 같은 방법으로 따로 한다(스펙 11절). 이 계획의 Task 에는 측정이 없다. 돌리지 않은 측정은 결과로 적지 않는다.
