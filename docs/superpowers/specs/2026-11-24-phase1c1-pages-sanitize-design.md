# Phase 1-C1 설계 — 정화·페이지 API·공개 화면

작성 2026-11-24. 사용자 답(`docs/decisions.md` 「Phase 1-C 사용자 결정」)과 역할 에이전트 결정(제품·보안, 2026-11-24 — **사용자 확인 전**: 1-C 결정 Q1~Q22 와 그것을 사용자 답에 맞춰 고친 리치 텍스트 후속 결정)을 옮겼다. 두 결정이 다르면 리치 텍스트 후속 결정을 따른다. 결정에 답이 없던 것은 17절에 추천 없이 남겼다.
근거: 1-B2 스펙 `2026-11-22-phase1b2-users-roles-design.md`, ondolith 원문 D10(FR-401~403·513)·D12(P-202·903·904)·D13·D15(SC-1·SC-4·2.5·5.3-1)·D19(A-301~303)·D30(`pages`)·D60(리치 텍스트 정화)·D86, 원문 코드(문서가 침묵하는 곳만: 슬러그 정규식, 404 문구). 요청서 `request-core.md` 4.2·4.3(`:237`·`:264`·`:265`).
기능 상세의 단일 출처는 ondolith 원문 문서다. 문서와 원문 코드가 다르면 문서를 따르고 다른 곳을 적는다.

## 0. 범위

1-C 는 넷으로 나눈다(리치 텍스트 후속 결정 1절). 위험 종류마다 한 단계다 — 1-C1 서버가 받는 HTML·공개 출력, 1-C2 편집기·붙여넣기, 1-C3 이미지 디코더·디스크·메모리, 1-C4 메뉴 링크.

| 넣는다 | 뺀다(해당 하위 Phase 로) |
|---|---|
| 서버 HTML 정화기(jsoup): 허용 목록, 값 검사, **저장 때·내보낼 때 두 번**(3절) | 편집기 Tiptap, IME 관문, 편집기 ↔ 서버 짝 시험 — 1-C2 |
| 링크 검사기 하나(서버). 본문 `a[href]` 가 1-C1 에서 쓰고 메뉴(1-C4)가 같은 함수를 쓴다(4절) | 관리 화면 A-301·302·303 — 1-C2. 1-C1 의 시험은 API 로 HTML 을 넣는다 |
| V5: `pages` 표, 권한 `page.*` 다섯(enum 과 같은 커밋) | 프런트 링크 판정 `linkHref`·링크 벡터의 프런트 열 — 1-C2(첫 소비자 `isAllowedUri`). 1-C1 에는 부르는 곳이 없다 |
| 관리 API: 목록·상세·작성·수정·삭제·발행·발행 취소(5절), 공개 페이지 API(발행된 것만, 초안은 누구에게나 404) | 공개·관리자 코드 분할, 넘어온 (g) chunk 500kB — 1-C2(편집기가 가장 큰 관리자 전용 코드) |
| 공개 화면 P-202(`/:slug`), SPA 404(noindex)·불러오기 실패 화면, `RichHtml` 한 곳 + DOMPurify(8절) | 넘어온 (h) `["me"]` 무효화 — 1-C2(페이지 단추를 `can` 으로 그리는 단계) |
| HTML 싱크 검사(`tests/html-sinks.test.sh`, 8.4) | 페이지 사진 A-310(`media` 표·올리기·서빙)·180m 측정 — 1-C3. 1-C1 정화기는 `img src` 꼴만 정한다 |
| 빌드 산출물의 `<meta>` CSP, 빌드 산출물로 도는 E2E, CSP 위반 수집(8.5) | 메뉴 표·`menu.manage`·A-204·공개 메뉴·머리글·P-201 홈·링크 DB CHECK — 1-C4 |
| 작업 로그 동작 다섯(7절) | 서버 메타 주입·HTML 404 상태·sitemap·robots·기본 설명(D-2 ①) — Phase 4(사용자 답 Q2) |
| 넘어온 (e) A-402 상세 질의 수, (f) 권한 빼기 E2E, M-11~M-14(9절) | 페이지별 템플릿 FR-404 — 1-D(사용자 답 Q15) |
| **첫 Task 후보**: CI E2E 시간 조사(10절) | 공개 HTML 의 `frame-ancestors`(클릭재킹) — Phase 4 CloudFront 헤더 |

1-C1 이 끝나면: 관리 화면은 아직 없다. 편집자 권한을 가진 사람이 API 로 페이지를 만들고 발행하면, 비로그인 방문자가 `/{슬러그}` 에서 굵게·제목·목록·링크가 든 본문을 본다. 초안·없는 주소·발행 취소한 페이지는 「찾을 수 없습니다」다. 서버가 저장 때와 내보낼 때 HTML 을 정화하고, 브라우저가 DOMPurify 로 한 번 더 거르며, 빌드 산출물의 CSP 가 인라인 스크립트를 막는다.

완료 기준(리치 텍스트 후속 결정의 1-C1 기준):
1. 공격 표(13.2)의 모든 입력을 A-302 API 로 저장하면 저장값과 공개 API 응답에 스크립트가 실릴 자리가 없고, 저장값이 고정 파일의 `server` 와 바이트까지 같다.
2. `clean(clean(x)) == clean(x)`. 허용 고정 입력(A1)은 바뀌지 않는다.
3. 정화 정책을 좁히면 이미 저장된 행도 공개·관리 응답에서 좁힌 정책으로 나간다(DB 에 직접 넣은 옛 HTML 로 본다).
4. 빌드 산출물 + CSP 위의 E2E: 발행한 페이지가 비로그인 방문자에게 서식과 함께 보인다. DOMPurify 겹을 따로 본다(공개 API 응답을 가로채 오염 HTML 을 준다). CSP 위반 보고가 0건이고(8.5-2 의 `img-src` 기대 밖), CSP 가 실제로 걸려 있다.
5. 초안·없는 슬러그 404, 발행 취소 뒤 404, `page.update` 만 가진 역할의 발행·삭제 403·발행본 수정 403(초안 수정은 204, 17절 1), 슬러그 중복 409·예약어 422(A-302)·409(A-303).
7. 모든 요청 본문이 1 MiB 로 묶인다 — `Content-Length` 초과는 처리기 전 413, chunked 초과도 500 이 아니다, multipart 해석기가 없다, 한글 200,000자 본문은 상한 안이다(17절 2).
6. HTML 싱크 검사(8.4)가 허용 한 곳 밖의 사용을 잡는다 — 일부러 깨 red 를 확인한다.

## 1. 기술

| 항목 | 선택 | 근거 |
|---|---|---|
| 서버 정화 | **jsoup 1.24.1**(`org.jsoup:jsoup`, `pom.xml` 에 판 명시). `Jsoup.parseBodyFragment` → `Cleaner(Safelist)` → 정화된 DOM 을 한 번 더 걷는 값 검사(3절) | 한 라이브러리로 정화·평문 추출(빈 본문 판정)을 다 한다. 판은 `repo1.maven.org` 의 `maven-metadata.xml`(`release` 1.24.1, 2026-11-03)로 확인했다 — `search.maven.org` 색인은 1.21.1 로 낡아 있다. 라이선스 MIT. 필수 런타임 의존 없음(pom: `re2j` 는 optional, 나머지는 test·provided) |
| 브라우저 정화 | **DOMPurify 3.5.0**(`dompurify`, `package-lock.json`) | 서버 파서(jsoup)와 다른 **실제 브라우저 파서**로 다시 본다(mXSS). npm `latest` 3.5.0(2026-11-22 게시, 이틀째). 라이선스 MPL-2.0 OR Apache-2.0. 의존 `@types/trusted-types` |
| 빌드 CSP | Vite `transformIndexHtml` 플러그인(`apply: 'build'`) 몇 줄, `vite.config.ts` 안 | 새 의존 없음. `<meta>` 가 빌드 산출물에 실려 어디에 올리든 따라간다 |
| E2E 서버 | `npm run build && vite preview --host 127.0.0.1 --port 4173 --strictPort` | `preview.proxy` 가 `server.proxy` 를 물려받는다(vite.dev, 역할 에이전트 확인). 실제로 나갈 HTML 을 CSP 아래에서 본다 |
| 문서 제목·noindex | React 19 `<title>`·`<meta>` 를 부품에서 그린다 | react.dev 확인: 「React will always place the DOM element … within the document's `<head>`, regardless of where in the React tree it is rendered」. `index.html` 의 정적 `<title>` 과 함께 있을 때 어느 쪽이 보이는지는 구현 때 확인(E2E 가 `page.title()` 로 단언) |
| SPA 404 | `<meta name="robots" content="noindex">` 를 404 화면에서 넣는다 | Google Search Central 「JavaScript SEO basics」 「Avoid soft 404 errors in single-page apps」(2026-03-04 갱신본, 이 작업에서 확인): JS 로 404 주소로 보내거나, 「Add a `<meta name="robots" content="noindex">` to error pages using JavaScript」 |

- 새 의존성은 둘이다: jsoup 1.24.1(백엔드), DOMPurify 3.5.0(프런트).
- **OSV**(`api.osv.dev/v1/query`, 2026-11-24): `org.jsoup:jsoup@1.24.1` 0건, `dompurify@3.5.0` 0건.
  - jsoup 과거 이력 중 관련: GHSA-gp7f(2022, `preserveRelativeLinks` + 제어 문자 `javascript:`, 1.15.3 수정) — 그 옵션과 jsoup 프로토콜 검사를 **쓰지 않는다**(링크는 4절 검사기). GHSA-pmhh(2026-08, 사용자 Safelist 가 raw-text 요소를 허용할 때, 1.23.1 수정) — 우리 허용 목록에 raw-text 요소가 없다.
  - DOMPurify 2026 년 권고 25건은 역할 에이전트가 제목만 읽었다. 기본이 아닌 모드가 많지만 모드 이름이 없는 XSS·mXSS 제목도 있다 — **구현 때 권고 본문으로 확인**하고, 그때의 최신 GA·OSV 를 다시 본다.
- context7 은 월 한도 초과로 쓰지 못했다(역할 에이전트 기록). 판은 레지스트리, API 는 공식 문서·배포 소스로 확인했다. 구현 때 context7 이 되면 jsoup `Cleaner`·`Safelist`·`OutputSettings` 와 DOMPurify 설정 이름을 다시 본다.

## 2. 데이터 (V5 마이그레이션, 앞으로만)

```sql
CREATE TABLE pages (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    slug       text        NOT NULL UNIQUE CHECK (length(slug) <= 100 AND slug ~ '^[a-z0-9][a-z0-9-]*$'),
    title      text        NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    body_html  text        NOT NULL CHECK (length(body_html) <= 200000),
    status     text        NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
```

- 원문 D30 `pages` 에서 `body`(평문)·`template` 을 뺐다. 평문은 쓸 곳(Phase 4 설명)이 생기면 `text()` 로 뽑는다. `template` 은 1-D(Q15)에서 `ADD COLUMN`.
- `slug` 형식·길이와 `title` 길이 CHECK 는 원문에 없다(원문은 `body_html` 길이만). 슬러그가 공개 주소와 API 경로에 들어가므로 앱 검사를 못 지나간 값이 저장되지 않게 하는 마지막 둑이다. 예약어는 DB 에 두지 않는다(목록이 릴리스마다 는다).
- 목록 인덱스는 두지 않는다. `ponytail:` 페이지는 수가 적다. 목록이 느려지면 `pages (updated_at DESC, id)`.
- 권한 씨앗(D15 2.5, V2 「쓰는 권한만 심는다」). enum `Permission` 에 같은 커밋으로 더한다 — 아니면 기동 점검이 멈춘다.

  | 키 | 설명 | 쓰는 곳 | 시드 |
  |---|---|---|---|
  | `page.view` | 페이지 목록을 본다 | A-301 | `editor`·`operator` |
  | `page.create` | 페이지를 만든다 | A-302 작성 | `editor`·`operator` |
  | `page.update` | 페이지를 열어 고친다 | A-302 상세·수정 | `editor`·`operator` |
  | `page.delete` | 페이지를 지운다 | A-302 삭제 | `editor`·`operator` |
  | `page.publish` | 페이지를 발행하고 내린다 | A-303 | `editor`·`operator` |

  `anonymous`·`member` 에는 주지 않는다(방문자 권한이 아니다, R9). `admin` 은 `is_superuser` 라 행이 없다. `menu.manage` 는 1-C4 의 V 가 심는다.

## 3. 정화 규칙

### 3.1 함수 둘 (`shared/RichText.java`)

- **`clean(html)`** — 내보낼 때와 저장 때 모두 쓰는 순수 함수. 예외를 던지지 않는다(DB 에 직접 들어간 어떤 옛 행에도).
  1. `Jsoup.parseBodyFragment(html)` → `new Cleaner(safelist).clean(doc)` 으로 허용 목록 밖의 요소·속성을 뺀다. 빠진 요소 안의 글자는 남고, `script`·`style` 의 내용은 남지 않는다(jsoup 동작으로 추정 — 시험 표 X1·X7·X8 이 고정한다).
  2. 정화된 DOM 을 한 번 더 걷는다(값 검사, jsoup 은 속성 값을 정규식으로 보지 못한다).
     - `a[href]`: 4절 검사기를 통과하지 못하면 **`href` 를 지우고 글자는 남긴다.** 검사기는 jsoup 이 엔티티를 푼 속성 값을 본다(`&#x6A;avascript:` 는 `javascript:` 로 보인다).
     - `img`: `src` 가 정확히 `^/api/media/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` 일 때만 남긴다. 아니거나 `src` 가 없으면 **요소째** 지운다.
     - `ol[start]`: `^[0-9]{1,6}$` 이 아니면 속성을 지운다.
  3. `OutputSettings` 를 `prettyPrint(false)`·UTF-8 로 두고 `body().html()` 을 낸다. escapeMode 는 기본값으로 두되, 바이트 비교 시험(13.2)이 실제 출력을 고정한다(구현 때 확인).
- **`forSave(raw)`** — A-302 작성·수정만 쓴다. 칸 오류는 `errors.body_html` 422 다.
  1. 없음(null) → 「필수입니다」. 200,000자 초과 → 「200,000자 이하」. `\n`·`\r`·`\t` 밖의 제어 문자(`\p{Cc}`) → 「쓸 수 없는 문자가 있습니다」(U+0000 은 PostgreSQL 이 받지 않아 500 이 된다 — 1-B1 교훈).
  2. `clean(raw)`.
  3. 결과가 200,000자를 넘으면 「200,000자 이하」(엔티티·닫는 태그로 입력보다 길어질 수 있다 — 검사하지 않으면 DB CHECK 위반이 500 이 된다).
  4. 결과에도 1번의 제어 문자 검사를 한 번 더 한다. 엔티티로 쓴 제어 문자(`&#0;`·`&#8;`)는 입력에서는 글자가 아니고 jsoup 이 푼 뒤에야 제어 문자가 된다. jsoup 이 `&#0;` 을 U+FFFD 로 바꾸는지는 구현 때 확인 — 그대로 두면 U+0000 이 DB 에 가 500 이 된다.
  5. 결과의 `text()` 가 보이지 않는 문자뿐(`SiteName` 과 같은 `[\p{Z}\p{C}]*`, NBSP·U+200B 포함)이고 `img` 도 없으면 「본문을 입력하세요」(Q20 개정 — 사진만 있는 본문은 받는다).
- 내보낼 때(`clean`)는 길이·제어 문자·빈 본문을 보지 않는다. GET 이 422 를 낼 자리가 없다.
- **이 스펙의 정규식(슬러그·`img src`·`ol start`·링크 검사기)은 모두 전체 일치(`Matcher.matches()`)로 판정한다.** Java 의 `$` 는 `find()` 에서 마지막 개행 앞에서도 맞는다(구현 때 시험으로 확인) — 그러면 슬러그 `about\n` 이 앱 검사를 지나 DB CHECK 에서 500 이 되고, `img src` 끝에 개행이 남는다. 끝 개행 줄을 벡터에 둔다(13.2 I11, 13.3, 13.4).
- `clean` 은 멱등이어야 한다: `clean(clean(x)) == clean(x)`.

### 3.2 허용 목록

| 요소 | 속성 | 비고 |
|---|---|---|
| `p` `br` `strong` `em` `h2` `h3` `ul` `ol` `li` `blockquote` | 없음(`ol` 만 `start`) | `h1` 은 페이지 제목 자리라 받지 않는다 |
| `a` | `href` | **`rel`·`target` 은 받지 않고 붙이지도 않는다** — 새 창으로 열지 않으니 `noopener` 가 필요 없다. Referrer 는 이미 `strict-origin-when-cross-origin` |
| `img` | `src` `alt` | `width`·`height`·`title` 은 받지 않는다. 이미지는 우리 업로드 경로만(외부 이미지는 방문자 IP 를 남의 서버로 보내는 추적 픽셀이고, CSP `img-src 'self'` 와도 맞다) |

- 빠지는 것: 위에 없는 모든 요소·속성 — `class`·`style`·`id`·`on*`·`data-*`·`aria-*`·`iframe`·`table`·`svg`·`math`·`form`·`base`·`meta`·`template`·`noscript`·주석.
- 원문 D60 의 표·밑줄·취소선·색·정렬·글자 크기·유튜브·지도 블록은 요청받지 않았다(사용자 답 「굵게·링크·사진」). 필요해지면 허용 목록과 시험 표를 함께 넓힌다.
- jsoup 의 `addProtocols`·`preserveRelativeLinks` 는 쓰지 않는다. 프로토콜을 지정하지 않은 속성의 값을 jsoup 이 그대로 두는지(상대 경로 `href="/about"` 가 남는지)는 **구현 때 시험으로 확인**한다 — 시험 표의 허용 줄이 잡는다.

### 3.3 저장 때·내보낼 때 (원문 D60:194, D86 3.6)

- 저장 때: A-302 작성·수정은 `forSave` 를 지난 값만 `body_html` 에 쓴다(요청서 `:237`).
- 내보낼 때: 공개 페이지 API 와 관리 상세 API 가 `body_html` 을 내기 전에 `clean` 을 다시 부른다. 정책을 좁힌 뒤에도 옛 행이 옛 정책으로 나가지 않게, 그리고 API 가 정화되지 않은 HTML 을 내는 일이 클라이언트와 무관하게 없게 한다(Phase 4 메타 같은 비브라우저 소비자도).
- 비용: 요청마다 파싱 한 번. 크기 상한이 200,000자다. 시간은 재지 않았다(메모리 측정 때 함께 본다, 11절).
- 관리 상세가 내보낸 값은 저장값과 다를 수 있다(정책을 좁힌 뒤). 그 값을 그대로 다시 저장하면 좁힌 값으로 저장된다 — 멱등이라 두 번째부터는 바뀌지 않는다.

## 4. 링크 검사기 (`shared/LinkCheck.java`, 함수 하나 `ok(String)`)

나쁜 꼴을 찾아 막지 않는다. 정해 둔 두 꼴과 정확히 맞을 때만 받는다(허용 목록). 1-C1 의 소비자는 정화기의 `a[href]` 하나다. 1-C4 메뉴가 같은 함수를 쓰고(거부는 422 `errors.url` 「사용할 수 없는 링크입니다」), 같은 규칙을 DB CHECK 와 프런트 판정에도 둔다(그 단계 몫).

1. 길이 1~500자.
2. **모든 글자가 `A-Z a-z 0-9 . _ ~ ! $ & ( ) * + , ; = : @ / ? # % -` 중 하나.** RFC 3986 주소 글자에서 `[`·`]`·`'` 를 뺀 것이다. 브라우저 URL 파서가 퍼센트 인코딩으로 바꾸지 않는 글자만 남겨 저장값과 그려지는 값이 같게 한다. 공백·탭·개행·제어 문자·NBSP·전각·보이지 않는 문자·`"`·`<`·`{`·`` ` ``·`^`·`\`·세로 막대가 여기서 모두 떨어진다. 한글 경로는 퍼센트 인코딩된 꼴로만 받는다.
3. 아래 두 꼴 중 정확히 하나.
   - **내부 경로**: `^/(?!/).*$`. 그리고 `?`·`#` 앞의 경로를 `/` 로 나눈 마디 중 `.`·`..` 가 없어야 한다. 이때 `%2e`(대소문자 무관)를 `.` 으로 본다 — WHATWG URL 파서가 `%2e` 를 점 마디로 다룬다. `/.//evil`·`/a/..//evil`·`/%2e//evil` 은 풀면 `//evil` 이다(1-B1 M-6 의 `safeNext` 와 같은 꼴).
   - **외부 주소**: `^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?([/?#].*)?$`. 스킴은 소문자 `http`·`https` 만. 포트는 1~65535 를 코드로 본다. 호스트는 영숫자·점·하이픈만이라 `@`(사용자 정보로 남의 호스트를 가림)와 IPv6 리터럴은 받지 않는다. 한글 도메인은 `xn--` 꼴로.
4. 그 밖은 모두 거부. `javascript:`·`data:`·`vbscript:`·`mailto:`·`tel:`·`//x`·`http:evil`·`https:/x`·`https:///x` 는 따로 적지 않아도 떨어진다.

- 서버는 URL 파서(`java.net.URI`)에 기대지 않는다. Java 와 브라우저(WHATWG) 파서의 해석이 다르다. 브라우저 파서로는 그릴 때(프런트 판정, 1-C2·1-C4) 한 번 더 본다.
- 원문 리치 텍스트의 `mailto`·`tel` 은 받지 않는다(메뉴 Q9 와 검사기를 하나로). 본문에 「메일 보내기」가 필요해지면 검사기에 한 갈래를 더해 메뉴와 본문이 함께 넓어진다.

## 5. 페이지 API

- 파일: 관리 `admin/PagesController.java`(1-B 관례대로 권한 표시는 메서드마다), 공개 `ops/PageController.java`(`SiteController` 옆), 슬러그 규칙 `shared/Slug.java`, 예약어 목록 `src/back/src/main/resources/reserved-slugs.txt`. 기록기를 부르므로 `@Transactional` 을 달지 않는다(1-B1 규칙).
- 재인증 대상이 아니다(D15:545 「그 밖의 저장·삭제(페이지·메뉴 …)는 일상 편집」). `ReauthTargetsTest` 의 「대상 아님」 표에 상태 변경 다섯을 더한다 — 분류하지 않으면 그 시험이 실패한다.
- 메서드는 1-B 관례대로 상태 변경을 모두 POST 로 둔다(`…/delete`·`…/publish`). CSRF·관리자 IP 60회/분은 `/api/admin/**` 그대로.

### 5.1 슬러그 (`Slug`)

- 형식 `^[a-z0-9][a-z0-9-]*$`, 1~100자(원문 코드 `validate.go:89`, D30:142). 대소문자를 바꾸거나 공백을 빼지 않는다 — 틀리면 422. 한글은 받지 않는다(Q5: 유니코드는 정규화·대소문자·퍼센트 인코딩의 차이가 새 공격면).
- 형식이 `/`·`.`·`%`·`\` 를 모두 막아, 슬러그를 경로·쿼리 어디에 넣어도 형식만으로 안전하다.
- **예약어**: 목록 파일 하나 — 원문 D19:2615~2616 의 스물하나(`admin` `login` `logout` `signup` `password` `auth` `me` `verify` `board` `comments` `attachments` `search` `shop` `cart` `checkout` `orders` `webhooks` `static` `install` `sitemap.xml` `robots.txt`)에 `api`·`assets`·`error` 를 더한다. `sitemap.xml`·`robots.txt` 는 점이 있어 형식이 이미 막지만 원문대로 둔다. `error` 는 Boot 오류 처리기(`/error`)의 첫 마디다 — 시험에서 예외로 빼는 것보다 목록에 두는 쪽이 갈래가 적다.
- 목록이 라우트와 어긋나지 않게 시험 둘(Q5): 백엔드 — 운영 컨텍스트의 모든 MVC 매핑의 첫 마디가 목록에 있다. 프런트 — `App.tsx` 의 최상위 정적 라우트(`login`·`admin`)와 Vite `assetsDir`(`assets`)가 같은 파일에 있다. 프런트 시험은 그 파일을 읽기만 한다(읽는 방법은 구현 때 정한다).

### 5.2 작성·수정 (A-302)

- 본문 고정 레코드 `{slug, title, body_html, status}`. `status` 는 받기 위한 칸이 아니라 거절하기 위한 칸이다 — null 이 아니면 422 `errors.status` 「발행 상태는 발행 단추로 바꿉니다」(Q8, D19 「무시가 아니라 거부」). 그 밖의 모르는 칸은 1-B 관례대로 읽지 않는다(전역 Jackson 설정은 바꾸지 않는다). 작성자·작성 시각·정화 생략 옵션·id 는 칸이 없다(D86 2.5).
- 칸 검사(한 번에 모아 422):
  - `slug`: 없음 「필수입니다」, 100자 초과 「100자 이하」, 형식 「소문자·숫자·하이픈만 쓸 수 있습니다」, 예약어 「사용할 수 없는 주소입니다」(D19:2656).
  - `title`: `SiteName.error(title, 200)` 를 그대로 쓴다 — 앞뒤 공백(NBSP 포함)을 빼고, 공백·보이지 않는 문자만이면 「필수입니다」, 제어 문자가 있으면 「쓸 수 없는 문자가 있습니다」, 200자(D30:143). 저장은 `SiteName.strip`.
  - `body_html`: 3.1 `forSave`.
- **작성** `POST /api/admin/pages`(`page.create`): 칸 검사 → `INSERT … RETURNING id` → 201 `{id}`, 기록 `page.create`. 상태는 늘 `draft` 로 만든다. 슬러그 중복은 먼저 세지 않고 DB `UNIQUE` 가 막는다 → 409 「이미 사용 중인 주소입니다」(D19:2644·2655).
- **수정** `POST /api/admin/pages/{id}`(`page.update`): 칸 검사 → `UPDATE pages SET slug, title, body_html, updated_at = now() WHERE id = ?` → 0행 404 「페이지를 찾을 수 없습니다」, 중복 409 → 204, 기록 `page.update`. 상태는 바꾸지 않는다. 칸 검사가 대상 조회보다 앞이라, 없는 id 에 틀린 본문을 보내면 422 다(관리자 권한 뒤라 새는 것이 없다).
- 발행된 페이지의 수정과 슬러그 변경은 `page.publish` 도 요구한다(17절 1, 원문과 다르다).

### 5.3 상세·목록·삭제

- **목록** `GET /api/admin/pages?page=1`(A-301, `page.view`): 1-B2 사용자 목록과 같은 방식(Q19) — 쪽당 50, 한 행 더 읽어 `has_next`, 전체 건수 없음, 정렬 `updated_at DESC, id`. `page` 는 A-601 과 같다(1 미만 422 `errors.page`, 정수가 아니면 400, offset 은 long). **본문을 읽지 않는다** — 슬러그·제목·상태·수정 시각만(SC-4, 초안 본문이 목록 캐시에 남지 않는다).
- **상세** `GET /api/admin/pages/{id}`(`page.update` — D19:2632 「편집 폼 조회 GET = `page.update`」): 모든 칸, `body_html` 은 `clean` 을 지난 값. 초안도 여기서만 나온다(Q16).
- **삭제** `POST /api/admin/pages/{id}/delete`(`page.delete`): `DELETE … WHERE id = ?` → 0행 404 → 204, 기록 `page.delete`. 메뉴를 건드리지 않는다(Q14 — 원문 코드는 메뉴를 함께 지워, `page.delete` 만 가진 사람이 `menu.manage` 몫을 바꿨다). 발행 중인 페이지의 삭제 확인(D19:2647)은 화면의 확인 대화상자다(1-C2).
- 경로 `{id}` 는 1-B2 그대로 `@PathVariable UUID` + 형식 불량·공백 404(`ApiErrors`).

### 5.4 발행·발행 취소 (A-303, `page.publish`)

- **발행** `POST /api/admin/pages/{id}/publish`, 본문 없음.
  - 순서(Q7): 슬러그 읽기 → 없으면 404 → **예약어 재검사**(D19:2687, 목록은 릴리스마다 는다) → 걸리면 409 「이 주소는 시스템 경로와 충돌합니다」(D19:2697, Q6) → `UPDATE pages SET status = 'published', updated_at = now() WHERE id = ? AND status = 'draft'`.
  - 1행이면 204·기록 `page.publish`. 0행이면 다시 있는지 보고 있으면 204(이미 발행됨, 기록 없음), 없으면 404(그 사이 지워짐).
  - 이미 발행된 페이지도 예약어와 부딪히면 409 다.
- **발행 취소** `POST /api/admin/pages/{id}/unpublish`: `UPDATE … SET status = 'draft', updated_at = now() WHERE id = ? AND status = 'published'` → 1행 204·기록 `page.unpublish`, 0행이면 있으면 204·기록 없음, 없으면 404. **예약어를 보지 않는다** — 문제 있는 페이지를 언제든 바로 내릴 수 있어야 한다.
- 같은 상태로 다시 누르면 2xx 이고 기록하지 않는다(Q7). 한 문장이 바꾼 경우만 기록하므로, 두 사람이 동시에 눌러도 실제로 바꾼 한 사람만 남는다. 1-B2 의 「이미 비활성이어도 204 이고 기록한다」와 다른 이유: 비활성화는 탈취 대응이라 시도 자체가 감사 대상이고, 발행 재요청은 효과가 없는 조작이다.
- 빈 본문 발행 검사는 없다(Q20 — 작성·수정이 이미 막는다).

### 5.5 공개 조회 (P-202)

- `GET /api/pages/{slug}`, `@PublicApi`. `/api/` 바로 다음 마디가 고정(`pages`)이라 M-7·M-11 점검을 지난다.
- 형식(5.1)이 틀리면 DB 에 가지 않고 404. `SELECT slug, title, body_html FROM pages WHERE slug = ? AND status = 'published'` — 초안은 DB 밖으로 나오지 않는다(원문 `store.sql:1~3`, 조회 뒤 비교 금지). 없으면 404 「페이지를 찾을 수 없습니다」. 403 이 아니다 — 403 은 존재를 알려 준다(D12:130).
- 200 `{slug, title, body_html}`, `body_html` 은 `clean` 을 지난 값. **세션·권한을 읽지 않는다** — 로그인 쿠키가 있든 없든 본문이 바이트까지 같다(Q16·D-2 「세션에 따라 달라지는 형태로 캐시하지 않는다」).
- 캐시 헤더는 지금의 `no-store` 그대로(사용자 답 Q22). 발행 취소·삭제가 다음 요청에 바로 반영된다.
- 질의는 요청당 하나(익명). JSON 만 내고, API 응답 CSP `default-src 'none'`·`nosniff` 가 그대로 붙는다.

## 6. API

| 경로 | 메서드 | 표시 | 요청 | 응답 |
|---|---|---|---|---|
| `/api/admin/pages` (A-301) | GET | `@AdminPermission(PAGE_VIEW)` | `page`(기본 1) | 200 `{items: [{id, slug, title, status, updated_at}], page, page_size: 50, has_next}` / 400 / 401 / 403 / 422 / 429 |
| `/api/admin/pages` (A-302 작성) | POST | `@AdminPermission(PAGE_CREATE)` | `{slug, title, body_html}` | 201 `{id}` / 400 / 401 / 403(권한·CSRF) / 409 / 422 / 429 |
| `/api/admin/pages/{id}` (A-302 상세) | GET | `@AdminPermission(PAGE_UPDATE)` | — | 200 `{id, slug, title, body_html, status, created_at, updated_at}` / 401 / 403 / 404 / 429 |
| `/api/admin/pages/{id}` (A-302 수정) | POST | `@AdminPermission(PAGE_UPDATE)` | `{slug, title, body_html}` | 204 / 400 / 401 / 403(권한·CSRF·발행본인데 `page.publish` 없음) / 404 / 409 / 422 / 429 |
| `/api/admin/pages/{id}/delete` | POST | `@AdminPermission(PAGE_DELETE)` | — | 204 / 401 / 403 / 404 / 429 |
| `/api/admin/pages/{id}/publish` (A-303) | POST | `@AdminPermission(PAGE_PUBLISH)` | — | 204 / 401 / 403 / 404 / 409(예약어) / 429 |
| `/api/admin/pages/{id}/unpublish` (A-303) | POST | `@AdminPermission(PAGE_PUBLISH)` | — | 204 / 401 / 403 / 404 / 429 |
| `/api/pages/{slug}` (P-202) | GET | `@PublicApi` | — | 200 `{slug, title, body_html}` / 404 |
| `/api/me` (키 추가) | GET | 공개 | — | `can` 키가 열다섯이 된다(1-B 열 + `page.*` 다섯) |
| `/api/admin/roles` (A-403, M-12 칸 추가) | GET | 그대로 | — | 역할에 `visitor_only`, 권한 목록에 `visitor` 칸(9절) |

- 새 API 는 **여덟**: 조회 GET 셋(A-301 목록, A-302 상세, 공개 조회), 상태 변경 POST 다섯(작성·수정·삭제·발행·발행 취소).
- JSON 은 snake_case, 오류는 Problem Details, 422 는 최상위 `errors`. 403·404·409 의 `detail` 은 원문 D19 문구를 코드 상수로 쓴다 — 요청 값(슬러그·제목)이 섞이지 않는다. OpenAPI 문서에 실제 응답 코드를 적고 생성 시험이 확인한다. 프런트 타입(`schema.d.ts`)을 다시 만든다.
- 작성·수정의 요청 표에는 `status` 를 적지 않는다(받지 않는 칸이다). 422 설명에 `errors.status` 를 적는다.

## 7. 작업 로그 동작

| `action` | 언제 | `target_type` / `target_id` | `summary` | 화면 이름표 |
|---|---|---|---|---|
| `page.create` | 작성 201 | `page` / 새 id | `''` | 페이지 작성 |
| `page.update` | 수정 204 | `page` / 대상 id | `''` | 페이지 수정 |
| `page.delete` | 삭제 204 | `page` / 대상 id | `''` | 페이지 삭제 |
| `page.publish` | 발행이 실제로 상태를 바꾼 204 | `page` / 대상 id | `''` | 페이지 발행 |
| `page.unpublish` | 발행 취소가 실제로 상태를 바꾼 204 | `page` / 대상 id | `''` | 페이지 발행 취소 |

- 이름은 V3 CHECK 를 통과한다. 새 마이그레이션은 필요 없다. `OperationLog.Action` 에 다섯을 더한다.
- **값은 남기지 않는다**(D15:730) — 슬러그·제목·본문 모두. 칸 출처 규칙(1-B1 4.3): 요청 문자열은 어느 칸에도 넣지 않는다. 지운 페이지는 A-601 에서 id 로만 보인다.
- 남기지 않는 것: 403·404·409·422 거절(D19:2653~2656), 같은 상태 재요청(Q7), 공개 조회.
- 작업 로그 화면(A-601)의 이름표 다섯을 더한다.

## 8. 프런트

### 8.1 `RichHtml` (`shared/ui/RichHtml.tsx`)

- **`dangerouslySetInnerHTML` 을 쓰는 곳은 이 부품 하나**이고, 값은 같은 식 안에서 `DOMPurify.sanitize(html, 설정)` 를 지난 것이다. 사유 주석을 단다(요청서 `:264`): 「서버가 저장·내보낼 때 허용 목록으로 정화한 본문(1-C1 스펙 3절). 브라우저 파서로 한 번 더 거른다」.
- 설정(이것만 쓴다):
  ```ts
  {
    ALLOWED_TAGS: ["p", "br", "strong", "em", "h2", "h3", "ul", "ol", "li", "blockquote", "a", "img"],
    ALLOWED_ATTR: ["href", "src", "alt", "start"],
    ALLOWED_URI_REGEXP: /^(?:https?:\/\/|\/(?![\/\\]))/i,
    ADD_URI_SAFE_ATTR: ["start"],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
  }
  ```
- **결정 파일의 설정(앞 세 줄)에서 세 줄을 더한 이유** — DOMPurify 3.5.0 배포 소스(`dist/purify.es.mjs` 의 `_isValidAttribute`)를 읽어 확인했다.
  - `ALLOW_DATA_ATTR`·`ALLOW_ARIA_ATTR` 는 기본 `true` 이고(1218·1219줄), 그 판정이 `ALLOWED_ATTR` 보다 앞에 있다. 끄지 않으면 `data-*`·`aria-*` 가 남는다. 스크립트 자리는 아니지만 서버 허용 목록과 어긋난다.
  - `URI_SAFE_ATTRIBUTES` 에 없는 속성은 값을 `ALLOWED_URI_REGEXP` 로 본다. 우리 정규식이면 `start="3"` 이 떨어진다(기본 정규식은 URI 가 아닌 값도 통과시키게 짜여 있다). `start` 를 URI 와 무관한 속성으로 둔다.
- **`img src="data:…"` 는 이 설정으로도 남는다**(확인 — 2224줄: `src` 가 `data:` 로 시작하고 태그가 `DATA_URI_TAGS`(기본에 `img`)면 정규식과 무관하게 통과. `ADD_DATA_URI_TAGS` 는 더하기만 된다). 스크립트 자리는 아니다(`img` 안의 SVG 는 스크립트를 돌리지 않는다). 서버가 지우고, CSP `img-src 'self'` 가 불러오기를 막는다. 훅으로 지우지 않는다.
- **쓰지 않는다**: 훅(`addHook`)·`IN_PLACE`·`RETURN_DOM`·`SAFE_FOR_TEMPLATES`·`CUSTOM_ELEMENT_HANDLING`·함수형 `ADD_TAGS`·`setConfig`. 2026 년 권고 대부분이 기본이 아닌 모드다. 엄격한 꼴(img 경로·링크 꼴)은 서버 몫이고, 프런트 겹은 「스크립트가 돌 자리 없음」만 맡는다.
- 본문 안 내부 링크는 일반 `<a>` 라 누르면 문서를 다시 불러온다. `ponytail:` SPA 내비게이션이 필요하면 `RichHtml` 에서 클릭을 가로챈다.
- 서식: `index.css` 에 `RichHtml` 바깥 요소의 클래스 아래로 몇 줄(문단 간격, `h2`·`h3` 크기, 목록 기호, 인용 왼쪽 선, 링크 밑줄, `img` `max-width: 100%`). Tailwind preflight 가 지운 모양을 되살린다. 새 의존(typography 플러그인)은 쓰지 않는다.

### 8.2 공개 화면

- `App.tsx` 의 `Running` 라우트에 `/:slug` → `PageView`(`features/page/PageView.tsx`)를 더하고, `*` 의 「없는 화면입니다」를 `NotFound`(`shared/ui/NotFound.tsx`)로 바꾼다. `/login`·`/admin` 은 정적 라우트라 `/:slug` 보다 먼저 맞는다.
- `PageView`
  - 슬러그를 5.1 형식으로 먼저 본다. 틀리면 요청 없이 `NotFound`. 맞는 값만 경로에 들어가므로 인코딩할 글자가 없다.
  - `GET /api/pages/{slug}`, 쿼리 키 `["page", slug]`(공개 데이터라 `["admin", …]` 밖).
  - 200: `<h1>` 제목(React 텍스트) + `RichHtml`. 문서 제목 `<title>` 은 「페이지 제목 – 사이트 이름」(Q17, React 텍스트라 이스케이프된다. 사이트 이름은 `useSite`). 제목 h1 으로 초점(`useFocusOnMount`, 1-B 관례).
  - 404: `NotFound`.
  - 그 밖(5xx·연결 실패): `role="alert"` 「페이지를 불러오지 못했습니다」. 고정 문구만이다 — 요청 경로·슬러그·서버 오류를 되비추지 않는다(NFR-210, P-904).
- `NotFound`: h1 「찾을 수 없습니다」, 본문 「없는 페이지이거나 주소가 바뀌었습니다. 주소를 확인하세요.」(원문 `handler_public.go:336`), `<meta name="robots" content="noindex">`(1절, Q4). 문서의 HTTP 상태는 200 이다 — HTML 404 상태는 Phase 4(사용자 답 Q2).
- 렌더 오류 경계(error boundary)는 두지 않는다. `ponytail:` 공개 화면의 부품이 늘면(1-C4 머리글) 경계 하나를 `Running` 에 둔다.
- 관리 화면은 바꾸지 않는다(1-C2). `/api/me` 의 `can` 에 `page.*` 가 생기지만 메뉴는 없다.

### 8.3 빌드 CSP

- `vite.config.ts` 에 플러그인 하나: `apply: 'build'`, `transformIndexHtml` 이 `<meta http-equiv="Content-Security-Policy">` 를 `injectTo: 'head-prepend'` 로 넣는다. `<meta>` CSP 는 그 뒤의 요소부터 걸린다(추정 — 그래서 맨 앞에 둔다, 구현 때 확인).
- 정책(1-C1 E2E 의 위반 0건으로 확정하고, 1-C2 에서 편집기 화면으로 다시 본다):
  `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-src 'none'`
  - `style-src 'unsafe-inline'` 없이 시작한다. Radix 대화상자(스크롤 잠금)가 `<style>` 요소를 넣으면(추정) 기존 관리 E2E 에서 위반 보고가 나온다. 그때 증거를 결정 문서에 적고 `style-src` 에만 `'unsafe-inline'` 을 더한다 — 스타일 주입은 스크립트 실행보다 대가가 작다. React 가 `style` 속성을 CSSOM 으로 쓰는 것은 막히지 않는다(추정, E2E 가 본다).
  - `frame-ancestors` 는 `<meta>` 로 걸리지 않는다(추정). Phase 4 의 CloudFront 응답 헤더 정책이 같은 문자열에 `frame-ancestors 'none'` 을 더해 건다.
- 개발 서버(`npm run dev`)에는 CSP 가 없다. 개발 서버는 인라인 스크립트(React Refresh)를 넣는다(추정). 방어 대상이 아니다.

### 8.4 HTML 싱크 검사 (`tests/html-sinks.test.sh`)

- 셸 시험이 `src/front/src` 의 시험이 아닌 파일(`*.test.ts`·`*.test.tsx`·`test-*.ts(x)` 밖)에서 다음 이름을 센다: `dangerouslySetInnerHTML`·`innerHTML`·`outerHTML`·`insertAdjacentHTML`·`document.write`·`srcdoc`·`eval(`·`new Function`·`localStorage`·`sessionStorage`. 시험 파일은 배포 묶음에 들지 않는다(시험은 출력 비교에 `innerHTML` 을 읽는다).
- **예외는 정확히 하나**: `src/front/src/shared/ui/RichHtml.tsx` 의 `dangerouslySetInnerHTML` **1회**. 그 파일에 `DOMPurify.sanitize(` 가 있어야 하고, 8.1 의 「쓰지 않는다」 이름이 없어야 한다. 다른 파일의 사용, 같은 파일의 두 번째 사용, 목록에 없는 새 예외는 실패다.
- `href={` 는 시험이 아닌 파일에서 0건이어야 한다(지금 0건, 2026-11-24 grep). 1-C4 의 메뉴 링크 부품이 예외 한 줄을 더한다.
- 예외를 더하려면 시험 안의 목록에 경로·횟수·사유를 함께 적는다(PR 에서 보인다). `node_modules` 는 보지 않는다.
- 시험은 검사 함수를 고정 입력(임시 폴더의 위반 파일)으로도 돌려 실패를 확인한다(`check-public.test.sh` 와 같은 방식, Docker 없음). CI 의 셸 작업이 `tests/*.test.sh` 로 함께 돌린다.

### 8.5 빌드 산출물로 도는 E2E

- `playwright.config.ts`: `webServer` 를 1절 명령으로, `baseURL` 을 `http://127.0.0.1:4173` 으로. `bypassCSP` 는 켜지 않는다.
- `e2e/stack.ts` 에 모든 시험이 쓰는 자동 fixture: 페이지마다 `securitypolicyviolation` **이벤트**를 모으고(`addInitScript` 의 리스너. 콘솔 오류는 쓰지 않는다 — 요청 실패가 섞인다. 리스너가 CSP 아래에서도 붙는지는 구현 때 확인), 시험 끝에 0건을 단언한다. 예외는 아래 「CSP 적용 확인」과 8.5-2 두 시험이며, 각자 기대를 적는다.
- **CSP 가 실제로 걸렸는지** 따로 본다: 위반 0건은 CSP 가 없어도 통과하기 때문이다. 시험 하나가 `meta[http-equiv="Content-Security-Policy"]` 의 내용이 8.3 문자열과 같은지, 시험이 끼워 넣은 인라인 `<script>` 가 돌지 않고 위반 1건이 생기는지(그 시험만 위반을 기대) 본다.
- 새 시험 `e2e/pages.spec.ts`
  1. **API 로 만든 페이지가 공개 주소에 정화된 채로 나온다**: 설치 → 관리자 로그인 → `page.request` 로 작성(굵게·`h2`·목록·내부 링크·외부 링크 + 공격 표 몇 줄)·발행 → 새 브라우저 문맥(비로그인)으로 `/{슬러그}` → 제목·굵게·링크가 보이고, 문서 제목이 「제목 – 사이트 이름」이다. 그 DOM 에 `script`·`on*` 이 없다. 초안 슬러그·없는 슬러그 → 「찾을 수 없습니다」 + noindex. 발행 취소 → 같은 주소를 다시 열면 「찾을 수 없습니다」.
  2. **DOMPurify 겹**: 스택(DB·jar) 없이 `page.route` 로 `/api/health`(running)·`/api/site`·`/api/me`(401)·`/api/pages/*` 를 준다. `/api/media/*` 도 `page.route` 로 빈 응답을 준다(jar 가 없어 생기는 요청 실패를 없앤다). 공개 페이지 응답을 13.2 표의 **입력**(서버를 거치지 않은 오염 HTML)으로 바꿔 차례로 열고, 그려진 DOM 에 13.2 의 프런트 불변식이 성립하는지 단언한다. CSP 기대: `script-src` 계열 위반 0건, `img-src` 위반은 I1·I7 줄에서만 난다 — DOMPurify 가 통과시킨 외부·`data:` 이미지(8.1)를 CSP 가 막는다는 셋째 겹의 증거다. DB 오염으로는 이 겹을 보지 못한다 — 서버가 내보낼 때 다시 정화하고, CSP 도 인라인 실행을 막아 DOMPurify 가 없어도 통과하기 때문이다.
- 기존 시험 12개는 그대로 같은 CSP 아래에서 돈다.

## 9. 1-B2 에서 넘어온 것

| 항목 | 1-C1 에서 | 시험 |
|---|---|---|
| (e) A-402 상세의 질의 수 | 1-B1 의 `pg_stat_statements` 방법으로, 역할 1개·5개 사용자의 상세 질의 수가 같다(인증 1 + 상세 셋) | `UsersApiTest` |
| (f) 권한 빼기 E2E | 기존 「역할 화면에서 편집자에게 권한을 준다」 시험에 이어서 같은 권한을 빼기(확인 대화상자) → 다시 읽어 없음 | `users.spec.ts` |
| M-11 첫 마디 와일드카드 | 기동 점검이 **첫 마디**에 `{`·`*`·`?` 가 든 매핑(`/{x}/admin/y`·`/**`·`/*`·`/{*rest}`)도 거부한다. 지금 `checkApiRoot` 의 정규식 `^/api/[^/]*[{*?]` 을 `^/(api/)?[^/]*[{*?]` 로 넓히는 꼴이다. 그런 매핑은 `/api/admin/x` 를 받으면서 관리자 경로로 세지 않는다. `ponytail:` Phase 4 의 껍데기 처리기(`/{slug}`)가 생기면 「`/api` 아래를 받을 수 없는 꼴」로 좁혀 다시 정한다 | `GuardCheckTest` 에 `/{area}/admin/probe` 고정 매핑 → 기동 멈춤 |
| M-12 늘 실패하는 선택지 | ① A-402 상세의 `assignable_roles` 에서 요청자가 superuser 가 아니면 superuser 역할을 뺀다(같은 술어 `ActorQuery.SUPERUSER_ROLE`, R3 이 늘 거절한다). `/api/me` 에 superuser 칸을 더하지 않는다. ② A-403 이 역할마다 `visitor_only`(모두가 갖는 역할, 서버의 `EVERYONE`)와 권한마다 `visitor` 를 낸다. 역할 화면은 `visitor_only` 역할의 부여 고르기에 `visitor` 권한만 보이고, 없으면 고르기를 그리지 않는다(지금은 방문자 권한이 없어 그리지 않는다, R9 가 늘 422) | `UsersApiTest`·`RolesApiTest`·`RolesPage.test` |
| M-13 주석 | `LoginController`·`ReauthController` 의 「인코더가 `IllegalArgumentException` 을 던진다」를 「spring-security-crypto 7.1.2 의 `BCrypt.checkpw` 는 73바이트 비밀번호에 false 를 낸다. catch 는 판이 바뀌어 다시 던질 때를 위해 둔다」로 고친다(1-B2 구현에서 확인한 사실) | 없음(주석) |
| M-14 A-404 빼기의 R4 문구 | 빼기의 R4 거절을 「보유하지 않은 권한은 뺄 수 없습니다」로. 부여는 그대로 「보유하지 않은 권한은 부여할 수 없습니다」. 원문 D19:3184 는 한 문구다 — 원문과 다른 곳 | `RolesApiTest` 의 R4 빼기 단언 |
| (g) chunk 500kB, (h) `["me"]` 무효화 | 1-C2 | — |

## 10. 첫 Task 후보 — CI E2E 시간 조사

- 사실: PR #6 에서 CI 의 「Playwright」 단계가 8분 25초였고, 로컬의 같은 12개는 약 2분이었다. **그 단계는 명령 셋이다**(`ci.yml`): `npm ci`, `npx playwright install --with-deps chromium`, `npx playwright test`. 그래서 8분 25초는 시험 시간이 아니라 세 명령의 합이다. 원인은 추정하지 않는다.
- 1-C1 의 E2E 가 빌드(`tsc` + `vite build`)를 먼저 하므로, **`webServer` 를 바꾸기 전에** 기준선을 잰다. 조사 방법:
  1. PR #6 의 실행 로그를 읽는다(`gh run view <run-id> --log`, 읽기만). 줄마다 붙은 시각으로 세 명령의 시간을 가른다.
  2. 같은 로그의 list 리포터가 찍은 시험별 시간을 로컬의 같은 시험과 나란히 둔다. 첫 시험만 긴지, 모든 시험이 고르게 긴지 본다.
  3. 첫 시험의 `postgres:18-alpine` 내려받기를 따로 잰다(`docker pull` 을 별도 단계로 먼저 돌려 시간을 본다).
  4. 시험 하나 안의 단계(DB 컨테이너 시작 → `pg_isready` → jar 의 health 200 → 시험 본문 → 정리)에 시각을 남긴다. 조사 동안만 `test-results/` 의 파일로 남기고 경로·사용자 이름은 찍지 않는다.
  5. runner 와 로컬의 CPU 수·메모리를 함께 적는다.
- 결과는 측정한 숫자로만 `docs/decisions.md` 에 적는다. 고칠 방법(브라우저 캐시, 단계 분리, 컨테이너 재사용 등)은 결과를 본 뒤 정한다 — 이 스펙은 고치는 방법을 정하지 않는다.
- E2E 작업의 `timeout-minutes: 20` 이 빌드가 더해진 뒤에도 충분한지 같은 기준선으로 본다.

## 11. 메모리 측정 — 언제

- **1-C1 에서는 재지 않는다.** jsoup·DOMPurify·페이지 처리기가 늘린 유휴·기본 경로 비용은 1-C 를 다 마친 뒤(1-C4 뒤) 코어 jar 를 1-B 와 같은 방법(같은 세션에서 측정 앱 ↔ 코어 번갈아, S5 3쌍·S6 3쌍, 이미지 다이제스트 고정)으로 다시 잰다(CLAUDE.md 「코어에 기능을 붙이면 다시 잰다」).
- 1-C3 의 180m 사진 측정(4000×3000 JPEG, 최악 구간 긴 변 3839)은 **1-C3 의 완료 기준**이다. 1-C 끝 측정과 따로다.
- 부하 경로에 공개 페이지 API 를 넣을지는 1-C 끝 측정 스펙에서 정한다. 돌리지 않은 측정은 결과로 적지 않는다.

## 12. 다른 문서에서 고친 것

- **이 커밋에서 고친 것**: `docs/decisions.md` — 새 절 「Phase 1-C 스펙」(정한 것, 원문·결정 문서와 다른 곳, 결정 필요 셋), 「1-B2 최종 검토」 Minor 표의 「갈 곳」을 1-C1·1-C2 로 적었다.
- **구현 때 고칠 것**(이 커밋에서는 고치지 않는다)
  - `src/front/playwright.config.ts` 의 머리 주석(「실제 jar + PostgreSQL + Vite」)을 빌드 산출물 + `vite preview` 로.
  - `CLAUDE.md` 는 고치지 않는다. `mise run e2e` 는 그대로 Playwright 를 부르고, 빌드는 `webServer` 가 한다.
- 고치지 않는 것
  - Phase 0 스펙의 「공개 HTML 메타 주입(D-2, 공개 화면이 생기는 Phase)」 — `docs/decisions.md` 「Phase 1-C 사용자 결정」 Q2(Phase 4)가 대신한다. 「`dangerouslySetInnerHTML`·`localStorage` 금지 검사(그 위험이 처음 생기는 Phase)」는 1-C1 이 그 Phase 다(8.4).
  - 1-B2 스펙. M-12·M-14 의 바뀐 동작은 이 스펙 9절이 대신한다.

## 13. 검증

통합 시험(Testcontainers PostgreSQL). 작업 로그는 계정별·증분으로 단언한다(1-B 와 같다). **첫 Red** 는 작업 묶음마다 하나다.

### 13.1 첫 Red

- **정화(첫 Red)**: `RichTextTest` 가 13.2 표를 읽는다. `clean` 을 입력을 그대로 내는 껍데기로 두면 X1(`<script>`)부터 실패한다.
- **링크 검사기(첫 Red)**: `LinkCheckTest` 가 13.3 표를 읽는다. 늘 `true` 인 껍데기면 `javascript:alert(1)` 줄이 실패한다.
- **페이지 API(첫 Red)**: API 로 작성·발행한 페이지를 익명 `GET /api/pages/{slug}` 가 200 으로 받고 `body_html` 이 정화된 값이다(처리기가 없어 404 로 실패). 이어서 **공개 질의의 `status = 'published'` 조건을 빼고 돌려 「초안은 익명·로그인 모두 404」 시험이 실패하는 것을 확인한다.**
- **내보낼 때 정화(Red)**: DB 에 직접 넣은 옛 HTML(`<p onclick="x">a</p><script>alert(1)</script>`)이 공개 조회·관리 상세 응답에서 `<p>a</p>` 다. 내보낼 때의 `clean` 을 빼면 실패한다(0절 완료 기준 3).
- **공개 화면(첫 Red, Vitest)**: `RichHtml` 에 `<img src=x onerror=alert(1)>` 와 `<a href="javascript:alert(1)">x</a>` 를 주면 DOM 에 `onerror`·`javascript:` 가 없다(부품이 없어 실패).
- **싱크 검사(Red)**: 다른 파일에 `dangerouslySetInnerHTML` 을 한 줄 넣거나 `RichHtml.tsx` 에서 `DOMPurify.sanitize(` 를 빼면 실패한다 — 일부러 깨서 red 를 확인한 뒤 되돌린다.
- **M-11(Red)**: `/{area}/admin/probe` 고정 매핑(`--spring.main.sources` 방식, 1-B1)이 지금은 기동을 통과한다 → 고친 뒤 기동이 멈춘다.

### 13.2 XSS 벡터 표 — 서버 정화와 프런트 DOMPurify 가 같은 파일을 읽는다

- 위치: 고정 파일 하나 `src/back/src/test/resources/richtext/vectors.json`(줄마다 `{id, input, server}`). 백엔드 시험과 프런트 시험(Vitest)이 그 파일을 읽고, E2E(8.5-2)가 입력을 쓴다. 프런트는 읽기만 한다.
- **한 열을 둘이 같이 쓰지 않는다.** DOMPurify 는 외부 이미지·`/api/media/../x`·`data:` 이미지를 통과시키므로(8.1) 기대 출력이 서버와 다르다.
  - **서버**: `clean(input)` 이 `server` 와 바이트까지 같다. 멱등 — `clean(server) == server`.
  - **프런트 불변식**(모든 줄의 `input` 에): 결과 DOM 에 `on` 으로 시작하는 속성이 없고, 8.1 `ALLOWED_TAGS` 밖의 요소가 없고, `href`·`src` 는 `^(https?://|/(?![/\\]))` 이거나 `img` 의 `data:` 뿐이다.
  - **서버 ↔ 프런트 짝**: `DOMPurify.sanitize(server) == server`(모든 줄). 서버가 낸 HTML 을 DOMPurify 가 바꾸면 화면이 서버와 다르다 — `start` 문제(8.1)를 이 시험이 잡는다.
  - Vitest 는 jsdom(parse5)이라 실제 브라우저 파서가 아니다. 실제 브라우저 겹은 E2E(8.5-2, Chromium)가 본다.
- `server` 열은 **규칙에서 바로 나오는 것만** 아래에 적었다. 「요지」 줄은 jsoup 의 실제 출력을 시험으로 보고 고정한다 — 요지와 다르면(스크립트 자리가 남으면) 멈추고 보고한다. 빌드·시험을 돌리지 않았으므로 그 바이트를 이 스펙에 지어 쓰지 않는다. `U` 는 `00000000-0000-4000-8000-000000000001`.

| id | 입력 | `server` 기대 |
|---|---|---|
| X1 | `<p>a</p><script>alert(1)</script>` | `<p>a</p>` |
| X2 | `<SCRIPT>alert(1)</SCRIPT><p>a</p>` | `<p>a</p>` |
| X3 | `<img src=x onerror=alert(1)>` | 빈 문자열(꼴이 아닌 `src` → 요소째) |
| X4 | `<img src="/api/media/U" onerror="alert(1)" alt="a">` | `<img src="/api/media/U" alt="a">` |
| X5 | `<svg onload=alert(1)><circle/></svg>` | 빈 문자열 |
| X6 | `<iframe src="https://evil.example/"></iframe>` | 빈 문자열 |
| X7 | `<style>p{color:red}</style><p>a</p>` | `<p>a</p>` |
| X8 | `<form action="/api/admin/users"><input name=x><button>b</button></form>` | `b`(빠진 요소의 글자는 남는다) |
| X9 | `<base href="https://evil.example/"><p>a</p>` | `<p>a</p>` |
| X10 | `<meta http-equiv="refresh" content="0;url=https://evil.example/"><p>a</p>` | `<p>a</p>` |
| X11 | `<template><img src=x onerror=alert(1)></template>` | 요지: `img`·`onerror` 없음 |
| X12 | `<noscript><p title="</noscript><img src=x onerror=alert(1)>"></noscript>` | 요지: `img`·`onerror` 없음(파서 차이 mXSS 고전) |
| X13 | `<math><mtext><table><mglyph><style><img src=x onerror=alert(1)>` | 요지: `img`·`onerror`·`math` 없음 |
| X14 | `<p onclick="alert(1)" class="x" style="color:red" id="i" data-x="1" aria-label="l">a</p>` | `<p>a</p>` |
| X15 | `<a href="/about" onclick="alert(1)" target="_blank" rel="opener">x</a>` | `<a href="/about">x</a>` |
| X16 | `<a href="javascript:alert(1)">x</a>` | `<a>x</a>` |
| X17 | `<a href="&#x6A;avascript:alert(1)">x</a>` | `<a>x</a>`(엔티티를 푼 값을 본다) |
| X18 | `<a href="java&#9;script:alert(1)">x</a>` | `<a>x</a>` |
| X19 | `<a href=" javascript:alert(1)">x</a>` | `<a>x</a>` |
| X20 | `<a href="//evil.example/">x</a>` | `<a>x</a>` |
| X21 | `<a href="data:text/html,x">x</a>` | `<a>x</a>` |
| X22 | `<a href="https://example.com/?a=1&b=2">x</a>` | `<a href="https://example.com/?a=1&amp;b=2">x</a>` |
| X23 | 13.3 거부 표의 나머지 값을 각각 `<a href="…">x</a>` 에 넣은 줄 | 모두 `<a>x</a>` |
| X24 | 13.3 허용 표의 값을 각각 `<a href="…">x</a>` 에 넣은 줄 | 입력 그대로(`&` 는 `&amp;`) |
| I1 | `<img src="https://evil.example/x.png">` | 빈 문자열 |
| I2 | `<img src="//evil.example/x">` | 빈 문자열 |
| I3 | `<img src="/api/media/../admin">` | 빈 문자열 |
| I4 | `<img src="/api/media/U?x">` | 빈 문자열 |
| I5 | `<img src="/api/media/U/thumb">` | 빈 문자열 |
| I6 | `<img src="/api/media/00000000-0000-4000-8000-00000000000A">`(대문자) | 빈 문자열 |
| I7 | `<img src="data:image/png;base64,iVBORw0KGgo=">` | 빈 문자열 |
| I8 | `<img alt="a">`(`src` 없음) | 빈 문자열 |
| I9 | `<img src="/api/media/U" width="1" height="1" title="t">` | `<img src="/api/media/U">` |
| I10 | `<img src="/api/media/U" alt="&quot; onerror=&quot;x">` | `alt` 안의 따옴표가 `&quot;` 로 남고 `onerror` 속성이 없다 |
| I11 | `<img src="/api/media/U&#10;">`(끝 개행) | 빈 문자열(전체 일치) |
| S1 | `<strong>a` | `<strong>a</strong>`(본문 안에서 닫힌다) |
| S2 | `<h2>a` | `<h2>a</h2>` |
| S3 | `<p><strong>a</p><p>b</p>` | 요지: 짝이 맞고 `</strong>` 가 본문 밖으로 새지 않는다 |
| S4 | `<p>a<!-- c --></p>` | `<p>a</p>` |
| S5 | `<h1>t</h1>` | `t` |
| S6 | `<p>&lt;script&gt;</p>` | `<p>&lt;script&gt;</p>`(이스케이프된 글자는 글자로 남는다) |
| S7 | `<ol start="3"><li>a</li></ol>` | 입력 그대로 |
| S8 | `<ol start="x"><li>a</li></ol>`, `<ol start="1234567"><li>a</li></ol>` | `<ol><li>a</li></ol>` |
| A1 | 허용 HTML: 3.2 의 모든 요소를 한 번씩 쓴 문서(내부·외부 링크, `/api/media/U` 이미지, `ol start`) | 입력 그대로(1-C2 의 편집기 짝 시험이 같은 줄을 쓴다) |

- API 수준(표 밖, `PagesApiTest`): 본문 200,000자 통과·200,001자 422, `<` 만 200,000자 → 정화 뒤 `&lt;` 로 늘어 422(500 이 아니다), U+0000·U+0008 422, `\n`·`\r`·`\t` 통과, 엔티티 `<p>&#0;</p>`·`<p>&#8;</p>`·`<img src="/api/media/U" alt="&#0;">` → 422 또는 무해한 치환(U+FFFD)이고 500 이 아니다, `<p> </p>`·`<p>&nbsp;</p>`·`<p>​</p>`·`<script>x</script>` 만 → 422 「본문을 입력하세요」, `<img src="/api/media/U">` 만 → 201, 깊게 중첩한 200,000자(`<em>` 반복)가 500·`StackOverflowError` 없이 끝난다(시간은 구현 때 확인).
- 저장값 시험: 공격 표의 입력을 A-302 API 로 저장하면 DB 의 `body_html` 이 `server` 와 같고(본문이 비지 않게 `<p>z</p>` 를 붙인다), 공개 API 응답에도 같다.

### 13.3 링크 벡터 표 (`src/back/src/test/resources/richtext/links.json`)

- 1-C1 은 서버 검사기 열만 쓴다. DB CHECK 열(점 마디 줄만 빼고)과 프런트 판정 열은 1-C4·1-C2 가 같은 파일로 더한다. 파일에는 탭·개행·NBSP 를 JSON 이스케이프(`\t`·`\n`·` `)로 적는다.
- **거부**
  - 링크 꼴: `javascript:alert(1)`, `JaVaScRiPt:alert(1)`, ` javascript:alert(1)`(앞 공백), `java\tscript:alert(1)`, `data:text/html,x`, `vbscript:x`, `mailto:a@b.test`, `tel:010`
  - 슬래시 두 개·역슬래시: `//evil.example`, ` //evil.example`, `/\evil.example`, `\/evil.example`
  - 탭·개행: `/\t/evil.example`, `/\n/evil.example`, `/\r/evil.example`
  - 점 마디: `/.//evil.example`, `/a/..//evil.example`, `/%2e//evil.example`, `/%2E%2E//evil.example`, `/a/./b`
  - 스킴 뒤 꼴: `http:evil.example`, `https:/evil.example`, `https:///evil.example`, `https://a.test@evil.test`, `https://a.test\@evil.test`, `http://`, `HTTPS://example.com`
  - 포트: `http://a.example:99999/`, `http://a.example:0/`
  - 글자: 전각 `／／evil.example`, NBSP 가 든 `/a b`, `/a"b`, `/a<b`, 세로 막대(U+007C)가 든 `/a` + `|` + `b`, `/a'b`, `/a[b]`
  - 끝 개행(전체 일치): `/about\n`, `https://example.com\n`
  - 길이: 501자, 빈 값
- **허용**: `/`, `/about`, `/about?x=1#top`, `/%ED%95%9C`, `/a..b`, `https://example.com`, `http://example.com:8080/a?b#c`, `https://example.com:65535/`, `https://xn--3e0b707e.example/`
- 프런트 짝 시험(허용 표의 모든 값이 프런트 판정도 통과해 링크로 그려진다)은 1-C2·1-C4 에서 같은 파일로 더한다.

### 13.4 페이지 API

- 권한: 편집자는 다섯 동작 모두 된다. 시험용 역할에 `admin.access`·`page.update` 만 준 사용자는 발행·발행 취소·삭제가 403 이고(0절 완료 기준 5), 작성도 403 이다. 그 사용자의 초안 수정은 204, **발행본 수정은 403 이고 내용이 그대로·기록 없음**(17절 1, `발행_권한_없는_역할은_발행된_페이지를_고칠_수_없다_403`). `page.view` 만이면 목록 200·상세 403(`page.update` 가 필요하다). 익명 401. 403·404·409·422 거절은 작업 로그가 늘지 않는다.
- 슬러그: 중복(작성·수정) 409, 동시에 같은 슬러그 작성 둘 → 201 하나·409 하나. 예약어 `admin`·`api`·`assets`·`error`·`login` 422 `errors.slug`. 형식 `Foo`·`-a`·`a/b`·`a.b`·`a_b`·`한글`·`about\n`(끝 개행, 500 이 아니다)·101자·빈 값 422. 100자 통과.
- 발행: 첫 발행 204·`page.publish` 행 하나, 다시 발행 204·행이 늘지 않는다(Q7). DB 에 직접 넣은 예약어 슬러그(`admin`) 초안의 발행 409·이미 발행된 그 행의 재발행도 409, 발행 취소는 204(예약어를 보지 않는다). 없는 id 404.
- Q8: 작성·수정 본문에 `"status": "published"` → 422 `errors.status` 이고 상태가 바뀌지 않는다. 본문의 다른 모르는 칸은 무시된다.
- 제목: 0자·공백만·NBSP 만·U+0000·201자 422, 200자 통과.
- 공개 조회: 초안은 익명·로그인(편집자) 모두 404(Q16). 형식이 틀린 슬러그(`Foo`·`a.b`)는 404 이고 질의가 0 이다. 발행 → 200, 발행 취소 → 404, 삭제 → 404. 같은 요청을 로그인 쿠키가 있을 때와 없을 때 보내 본문이 바이트까지 같다. 응답 `Cache-Control` 에 `no-store`. 익명 요청의 질의 수가 하나다(`pg_stat_statements`, 실제 값은 구현 때 적는다).
- 목록: 51개면 1쪽 50·`has_next` true, 2쪽 1·false. 응답에 `body_html` 이 없다. 질의 수가 행 수와 무관하다.
- 작업 로그: 작성·수정·삭제·발행·발행 취소 뒤 그 계정의 행 전체(`row_to_json`)에 제목·본문·슬러그 문자열이 없다.
- 바뀌는 기존 시험: `ReauthTargetsTest`(대상 아님 표에 다섯), `MeTest`(키 열다섯), `MigrationTest`(V5 — 표·CHECK·키 다섯·`editor`·`operator` 에 다섯, `anonymous`·`member` 에 없음), 설치 실패 원복의 기대 이력 1~5, `SystemApiTest` 적용 목록에 5, `OpenApiFileTest`(새 API 여덟·A-403 칸), `GuardCheckTest`(M-11), 시드 역할 질의를 단언하는 곳.
- 예약어 대조: 운영 컨텍스트의 모든 MVC 매핑 첫 마디가 목록에 있다(`ReservedSlugsTest`).

### 13.5 프런트 시험(Vitest)

- `RichHtml`: 13.2 의 프런트 불변식(모든 입력)과 서버 ↔ 프런트 짝(모든 `server`). 사유 주석이 있다(싱크 검사가 본다).
- `PageView`: 200 이면 h1 제목·본문·문서 제목, 404 면 「찾을 수 없습니다」와 `meta[name=robots][content=noindex]`, 500 이면 고정 문구(서버 `detail` 을 그리지 않는다), 형식이 틀린 슬러그는 요청이 0 이다.
- 예약어 대조: `App.tsx` 최상위 정적 라우트와 `assetsDir` 가 목록 파일에 있다.
- M-12: `visitor_only` 역할에 부여 고르기가 없다. superuser 가 아닌 요청자의 상세 응답(시험 값)에 superuser 역할이 없으면 고르기에도 없다.
- 작업 로그 이름표 다섯.

### 13.6 E2E

- 8.5 의 둘, 9절 (f), 모든 시험의 CSP 위반 0건(8.5-2 는 그 시험의 기대)과 CSP 적용 확인.
- 셸: `tests/html-sinks.test.sh`(8.4).

## 14. D86 대조 — 1-C1 행

| D86 | 1-C1 에서 |
|---|---|
| 1.12 프록시 뒤 IP | 미해결 — Phase 4 배포 전 필수. 새 작업 로그 행의 `ip` 도 같은 한계. 공개 조회는 쓰기가 없어 새 영향이 없다 |
| 2.1 처리기 권한·기동 점검 | 새 처리기 모두 `@AdminPermission`·`@PublicApi`. M-11 로 첫 마디 와일드카드를 거부한다 |
| 2.5 대량 할당 | 고정 레코드. 상태는 받지 않고 실리면 422(Q8). 작성자·시각 칸 없음 |
| 2.7 `{id}` 는 uuid 만, 형식 불량 404 | 1-B2 `ApiErrors` 그대로. 공개 슬러그는 형식 불량이면 DB 에 가지 않고 404 |
| 2.8 관리자 60/분 | 새 관리 API 일곱 모두 `/api/admin/**` |
| 3.1 CSRF | 새 POST 다섯 — 1-A 그대로 |
| 3.3~3.5 이스케이프 우회 지점의 수와 출처 | SPA 판: `dangerouslySetInnerHTML` 은 `RichHtml` 한 곳 1회, 값은 서버 정화 두 번 + DOMPurify. 셸 시험이 횟수까지 센다(8.4) |
| 3.6 리치 텍스트 정화 두 번, 열린 태그를 본문 안에서 닫는다 | 저장 `forSave`·내보냄 `clean`(3.3). jsoup 은 DOM 으로 만든 뒤 직렬화해 짝이 맞는다 — S1~S3 이 확인 |
| 3.7 보안 헤더 | API 는 그대로. 공개 HTML 은 빌드 `<meta>` CSP(8.3). `X-Frame-Options`·`frame-ancestors` 는 Phase 4 CloudFront |
| 3.8 CSP 에 `script-src` 없음 | **바뀐다** — 원문의 이유(테마 인라인)가 없어 `script-src 'self'` 를 건다 |
| 3.10 오류가 내부를 말하지 않는다 | Problem Details, `detail` 은 코드 상수. SPA 404·불러오기 실패는 고정 문구 |
| 4.1 SQL 바인딩 | 슬러그 조회·목록 모두 바인딩. 결합 없음 |
| 7.1 요청당 고정 질의 수 | 공개 조회 익명 1(시험) |
| 7.3 목록 질의 한 번 | A-301 하나, A-402 상세 셋(넘어온 (e)) |

## 15. 위험과 확인할 것

| 위험 | 대응 |
|---|---|
| 본문 200,000자 상한은 본문을 다 읽은 뒤에 걸린다. `@RequestBody` 해석은 권한 검사보다 앞이라 익명도 큰 JSON 으로 힙을 쓰게 할 수 있었다 | 17절 2 로 닫았다 — 모든 요청 본문 1 MiB(맨 앞 필터). 남은 위험: 요청당 상한이라 동시 요청의 합은 묶지 않는다(Phase 4 앞단 제한) |
| jsoup 이 프로토콜을 지정하지 않은 `href`·`src` 값을 지우거나 바꾼다 | 허용 줄(X15·X24·A1)이 잡는다 |
| jsoup 출력(escapeMode·`&nbsp;`·속성 순서)과 DOMPurify 출력이 달라 짝 시험이 깨진다 | 13.2 짝 시험이 먼저 알린다. 서버 `OutputSettings` 를 맞추고, 안 되면 결정 문서에 적는다 |
| DOMPurify 가 허용 HTML 을 다르게 바꾼다(`start` 처럼) | 짝 시험. 설정은 8.1 의 여섯 줄만 |
| DOMPurify 권고가 잦다 | 기본 모드만 쓰는 규칙(8.1)과 싱크 검사의 이름 검사(8.4), Dependabot |
| Radix 가 `<style>` 을 넣어 CSP 가 관리 화면을 깨뜨린다 | 기존 관리 E2E 의 위반 수집이 알린다. 증거를 적고 `style-src` 에만 `'unsafe-inline'` |
| CSP 가 아예 안 걸렸는데 위반 0건으로 통과한다 | 8.5 의 CSP 적용 확인 시험(`<meta>` 내용 + 인라인 스크립트 차단) |
| E2E 가 빌드를 먼저 해 CI 가 더 느려진다 | 10절 기준선, `timeout-minutes` 확인 |
| 형식이 틀린 경로·`@Valid` 오류가 익명에게도 404·422(1-B2 한계 (a)(b)) | 그대로 둔다. 새는 것은 공개된 상수 문구뿐 |
| 1-C1 에는 관리 화면이 없어 사람이 페이지를 만들 수 없다 | 의도다(API 로 시험). 1-C2 가 화면을 더한다 |

## 16. 검토 초점 — 시험이 다루지 않지만 사람이 쓸 때 문제가 될 것

1. **같은 출처의 무게**: 공개 페이지의 XSS 하나가 그 페이지를 연 관리자 권한으로 관리 API 를 부른다(CSRF 토큰은 JS 가 읽는 쿠키다). 네 겹 중 서버 둘(저장·내보냄)은 **같은 구현**(jsoup)이라 같은 우회에 함께 진다. 독립된 겹은 DOMPurify(다른 파서)와 CSP 다. 재인증 15분 창도 가짜 비밀번호 대화상자에는 무력하다.
2. **링크가 조용히 사라진다**: 검사기를 못 지난 `href` 는 저장 응답이 아무 말 없이 지운다. 편집자는 공개 화면에서야 링크가 글자로 바뀐 것을 안다. 1-C2 편집기의 `isAllowedUri` 가 입력 때 막는 것이 편의 겹이다.
3. **발행 분리** — 17절 1 로 닫았다(발행본 수정에 `page.publish`). 남는 것: 「편집자는 고치기만」 역할을 둔 설치처는 발행본의 오탈자도 승인자가 고쳐야 한다.
4. **동시 편집 덮어쓰기**: 두 사람이 같은 페이지를 고치면 나중 저장이 앞 저장을 말없이 덮는다(원문과 같다). 1-C2 에서 화면이 생기면 실제로 부딪힌다 — 17절 3, 1-C2 에서 다시 본다.
5. **슬러그 변경**: 발행된 페이지의 슬러그를 바꾸면 옛 주소가 바로 404 다. 리다이렉트는 없고, 그 주소를 가리키는 본문 링크·메뉴(1-C4)는 깨진다(Q14 — 메뉴를 건드리지 않는다).

## 17. 결정 — 결정 문서에 답이 없던 것 (2026-11-24, 역할 에이전트 결정 — 사용자 확인 전)

**이 절이 다른 절의 옛 문장보다 우선한다.** 5.2·6·13·15·16절에 남은 「원문대로 그대로 받는다」 꼴의 문장은 아래로 읽는다.

1. **발행된 페이지의 수정·슬러그 변경에는 `page.publish` 도 요구한다**(원문과 다르다, 발행 승인 분리 D13:266). 초안 수정은 `page.update` 만으로 된다.
   - 판정은 조회 뒤 비교가 아니라 한 문장이다: 요청자가 `page.publish` 를 갖지 않으면 `UPDATE … WHERE id = ? AND status = 'draft'`. 0행이면 다시 보고, 있으면 403 「발행된 페이지는 발행 권한이 있어야 고칠 수 있습니다」(기록 없음), 없으면 404(그 사이에 발행되는 경쟁을 막는다).
   - 6절 수정 행의 403 에 「발행본·발행 권한 없음」. 시드 역할 editor·operator 는 두 권한이 다 있어 동작이 바뀌지 않는다.
   - Red: `PagesApiTest.발행_권한_없는_역할은_발행된_페이지를_고칠_수_없다_403`(판정을 빼면 204). `page.update` 만 가진 역할: 초안 수정 204, 발행본 수정 403·내용 그대로·기록 없음.
2. **모든 요청 본문을 1 MiB 로 묶는다**(맨 앞 `HIGHEST_PRECEDENCE` 필터, 설치·운영 두 컨텍스트, 모든 경로·메서드). URL 층이 `permitAll` 이라 `@RequestBody` 해석이 권한 검사보다 앞이고 CSRF 토큰은 누구나 받으므로, 익명이 큰 JSON 으로 180m 힙을 쓰게 하는 길을 막는다.
   - 값 1 MiB 근거: 200,000자 × 한글 3바이트 = 600,000바이트 + 제목·JSON 이스케이프.
   - 두 겹: `Content-Length` 초과는 읽기 전 413. 헤더가 없으면(chunked) 읽는 바이트를 세어 상한 + 1 에서 끊는다(상태 413 또는 400 — 구현 때 확인, 500 이 아니다).
   - `spring.servlet.multipart.enabled=false` — 쓰는 곳이 없다. 익명 요청이 해석기를 통해 임시 파일을 쓰는 길을 닫는다.
   - Tomcat `max-http-form-post-size`·`max-swallow-size` 는 JSON 에 걸리지 않는다고 본다(추정, 구현 때 확인) — 기본값 그대로.
   - 1-C3 업로드: 필터 표에 한 행만 더한다 — 메서드 POST 이고 원본 `getRequestURI()` 가 정확히 `/api/admin/pages/media` 일 때만 10 MiB, 그 밖의 변형(`;x`, `//` 등)은 1 MiB(fail-closed).
   - 15절 첫 줄은 이것으로 닫는다. 남은 위험: 요청당 상한이라 동시 요청의 합은 묶지 않는다(Phase 4 앞단 제한).
   - Red(`RequestSizeLimitTest`): `익명_1MiB_넘는_Content_Length_는_처리기_전에_413`, `chunked_1MiB_초과도_끊기고_500_이_아니다`, `multipart_해석기가_없다`, `한글_200000자_본문은_상한_안에서_201`(상한이 정상 최대 입력을 깨지 않는다).
3. **동시 편집은 나중 저장이 이긴다**(원문). 덮어쓰기는 내용 손실이지 보안 구멍이 아니고, 작업 로그에 두 수정이 다 남는다. `ponytail:` 편집이 사라졌다는 보고가 나오거나 1-C2 화면에 자동 저장이 생기면 읽은 `updated_at` 으로 409(열은 이미 있다). 16절 4는 1-C2 에서 다시 본다.

## 18. 열린 문제

1. **CI E2E 시간의 원인** — 10절 조사 전이다. 측정한 숫자만 적는다.
2. **DOMPurify 2026 권고 본문** — 기본 설정에도 닿는 것이 있는지 구현 때 읽는다. jsoup·DOMPurify 는 그때의 최신 GA·OSV 를 다시 본다.
3. **jsoup 의 미확인 동작** — 상대 경로 `href` 유지, escapeMode·NBSP 출력, `noscript`·`math`·`template` 출력(X11~X13), 깊은 중첩의 시간. 모두 13.2 시험으로 확정한다.
4. **`<meta>` CSP 의 위치와 `index.html` 의 정적 `<title>`** — 8.3·8.2 의 추정은 E2E 로 확인한다.
5. **HTML 404 상태·메타·sitemap** — Phase 4(사용자 답). 그동안 없는 주소·초안은 200 + noindex 이고 초안 내용은 새지 않는다.
6. **클릭재킹** — 공개 HTML 의 `frame-ancestors` 는 Phase 4 CloudFront 헤더 전까지 없다.
7. **렌더 오류 경계 없음** — 8.2 `ponytail:`.
8. **1-C2~1-C4 의 결정 세부**(편집기 설정, 업로드 형식·크기·메모리 검사, 메뉴 트리 잠금 등)는 역할 에이전트의 작업 파일에만 있고 저장소에는 요지만 있다(`docs/decisions.md`). 각 단계 스펙을 쓸 때 옮긴다.
9. **1-B2 열린 문제는 그대로** — Q12 버전 노출(사용자 확인 필요), 시험 이미지 고정, 작업 로그를 일과 다른 트랜잭션에 씀.
