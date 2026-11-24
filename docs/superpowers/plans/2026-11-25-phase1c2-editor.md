# Phase 1-C2 편집기·관리 화면 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 편집자가 `/admin/pages` 에서 페이지 목록을 보고, Tiptap 편집기로 굵게·기울임·h2·h3·두 목록·인용·링크를 쓴다. 한글 조합 입력은 깨지지 않는다. 저장하고, 확인 대화상자의 미리보기를 거쳐 발행·발행 취소·삭제한다. 승인자가 읽은 뒤 본문이 바뀌었으면 발행은 409 로 멈춘다. 방문자가 내려받는 공개 묶음에는 편집기 코드가 없다.

**Architecture:**
- 편집기는 `features/admin/RichEditor.tsx` 한 파일이다. 확장 목록 `EXTENSIONS` 는 서버 허용 목록과 같은 노드·마크·속성만 둔다. 프런트 링크 판정은 `shared/lib/linkHref.ts` 가 한다.
- 화면은 `PagesPage`(A-301)와 `PageEditPage`(A-302 작성·수정, A-303 단추)다. 저장할 수 없는 사람에게는 편집기 대신 `RichHtml` 로 본문을 그린다.
- 서버는 `PagesController` 의 발행·상세·삭제 셋을 고치고, `RequestSizeLimit` 의 413 응답에 보안 헤더를 단다.
- 관리자 트리는 `/admin/*` 의 lazy 경계 하나 뒤로 옮긴다.

**Tech Stack:** Spring Boot 4.1.2, jsoup 1.24.1, React 19.3.1, React Router 8.5.1, TanStack Query 5, DOMPurify 3.5.0, Tiptap 3.35.0(새, 다섯 패키지), Vite 8.3.8, Vitest 5.1.0(jsdom), Playwright 1.65.0(CDP).

**Spec:** `docs/superpowers/specs/2026-11-25-phase1c2-editor-design.md` — **16절이 다른 절보다 우선한다.** 표(2.3 장면, 3.1 대조표, 5.1 E 줄, 13절 D86)는 베끼지 않고 절 번호와 줄 id(I-1·E3-2 …)로 가리킨다.

## Global Constraints

이어받는 것(1-C1 계획 그대로):
- 저장소는 PUBLIC 이다. 실제 메일·홈 경로·비밀을 쓰지 않는다. 시험 값은 누가 봐도 가짜로 쓴다(`*@ondolith.test`, `…-pass-…`, `evil.example`, `U` = `00000000-0000-4000-8000-000000000001`).
- TDD. 실패를 눈으로 본 뒤 구현한다. 단언 완화·skip 은 금지다. 이미 통과하는 시험은 구현을 잠시 되돌리거나 망가뜨려 Red 를 한 번 본다.
- 무거운 작업은 한 번에 하나만, `nice -n 19` 로 돌린다. Java 명령은 `mise x --` 로 부른다.
- 시험 명령
  - 백엔드 한 클래스: `cd src/back && nice -n 19 mise x -- ./mvnw -q -B test -Dtest=<클래스>`
  - 프런트: `cd src/front && npx vitest run <경로>`
  - E2E 한 파일(스택 없는 시험): `cd src/front && nice -n 19 npx playwright test e2e/<파일> -g "<이름>"`
  - 전체: `nice -n 19 mise run test`, `mise run e2e`
  - 셸: `for t in tests/*.test.sh; do bash "$t" || exit 1; done`
- 시험 HTTP 는 `127.0.0.1` 로 보낸다. 마이그레이션은 앞으로만 간다 — 1-C2 에는 새 마이그레이션이 없다.
- 오류는 Problem Details, JSON 은 snake_case 다. 422 는 최상위 `errors{칸: 메시지}` 이고, 403·404·409 의 `detail` 은 코드 상수다.
- `operation_logs` 는 시험도 비울 수 없다. 행 단언은 시험마다 새 계정으로, 증분으로 한다. 기록기를 부르는 처리기에 `@Transactional` 을 달지 않는다.
- 정규식은 전체 일치다. Java 는 `matches()` 를 쓰고, TS 는 `^…$` 에 `m` 플래그를 쓰지 않는다.
- **API 모양을 바꾸는 커밋(Task 4)은 같은 커밋에서** `OpenApiFileTest` 를 고치고 `src/front/openapi.json`·`src/front/src/shared/api/schema.d.ts` 를 다시 만든다(`OpenApiFileTest` 실행 → `cd src/front && npm run gen:api`).
- `/api/admin` 의 GET 이 아닌 새 처리기는 없다. `ReauthTargetsTest.NOT_TARGETS` 는 그대로 두고, `call` 만 Task 4 에서 본다.
- 메모리 측정은 1-C 끝(1-C4 뒤)에 따로 한다. 이 계획의 Task 가 아니다.

1-C2 에서 더하는 것:
- 새 의존성은 프런트 다섯뿐이다: `@tiptap/react`·`@tiptap/pm`·`@tiptap/starter-kit`·`@tiptap/extension-link`·`@tiptap/extension-list` 모두 `3.35.0`, `npm install --save-exact`, `package-lock.json` 커밋. 서버 의존성은 늘지 않는다.
- 「`prosemirror-view`·`@tiptap/*`는 넣기 직전에 판과 OSV를 다시 본다(붙여넣기 XSS 이력)」
  - 2026-11-24 계획 담당이 `npm view` 로 확인한 `latest`: 다섯 모두 3.35.0, `prosemirror-view` 1.43.1(2026-11-23 게시), `lexical`·`@lexical/react` 0.53.0.
  - OSV 는 계획 담당이 다시 돌리지 않았다 — 근거는 스펙 1절의 조회(2026-11-24, 새 51개 0건)다.
  - 규칙: OSV 가 1건이라도 나오면 멈추고 보고한다. 더 새 GA 가 있으면 그 판으로 넣고, 스펙 1절 「배포 소스에서 확인한 것」(Link 속성 다섯·OrderedList `type`·TrailingNode·`injectCSS`·`dom.editor`)을 새 판의 소스로 다시 본다.
- context7 은 계획 때 월 한도 초과였다. 레지스트리 tarball 의 배포 소스로 확인한 것:
  - StarterKit 옵션 이름 `link`·`orderedList`·`code`·`codeBlock`·`strike`·`underline`·`horizontalRule`·`trailingNode`(각각 `false` 로 뺀다)
  - `@tiptap/react` 가 `useEditor`·`useEditorState`·`@tiptap/core` 를 다시 내보낸다. `OrderedList` 는 `@tiptap/extension-list` 에 있다.
  - 그 밖의 API(`extend` 꼴, `setContent` 옵션, `editorProps` 갱신)는 구현 때 context7, 안 되면 `node_modules/@tiptap/*/src` 로 확인한다.
- 「프런트에서 HTML을 넣는 곳은 `RichHtml` 한 곳(편집기 내부는 `node_modules` 몫)」
- 「관리 쿼리 키는 `["admin", …]`」 — 목록 `["admin", "pages", page]`, 상세 `["admin", "page", id]`. 둘 다 `gcTime: 0` 이고, 상세는 `refetchOnWindowFocus: false` 다(스펙 7.3).
- 서버 허용 목록을 편집기 출력에 맞춰 넓히지 않는다. 편집기 쪽을 고치거나 멈추고 보고한다(스펙 5.1).
- 앱에 시험용 라우트·시험 고리를 넣지 않는다. E2E 는 편집기 DOM 의 `.editor`(스펙 1절 `dom.editor = this`)로 `getHTML()` 을 읽는다.
- **IME 관문 E2E 는 묶음에 남는다.** `EXTENSIONS`·도구 막대·링크 입력을 바꾸는 Task 는 끝에 `npx playwright test e2e/editor.spec.ts -g "IME"` 를 다시 통과시킨다.
- CSP 는 1-C1 정책 그대로 시작한다(스펙 9절). `script-src` 는 어떤 경우에도 넓히지 않는다 — 스크립트 위반이 나면 멈추고 보고한다.
- 고정 문구(스펙 그대로)
  - API: 발행 409 「그사이 페이지가 바뀌었습니다. 바뀐 내용을 확인한 뒤 발행하세요」, 발행본 삭제 403 「발행된 페이지는 발행 권한이 있어야 지울 수 있습니다」, `errors.updated_at` 「필수입니다」
  - 링크 입력: 「링크로 만들 글자를 먼저 고르세요」, 「사용할 수 없는 링크입니다」
  - 알림: 「페이지를 만들었습니다」·「저장했습니다」·「페이지를 지웠습니다」
  - 대화상자
    - 「발행할까요?」 / 「방문자가 /{slug} 에서 이 내용을 봅니다」
    - 「발행을 취소할까요?」 / 「방문자가 이 주소에서 「찾을 수 없습니다」를 봅니다」
    - 「페이지를 지울까요?」, 발행본이면 설명 「공개 중인 페이지입니다. 지우면 주소가 바로 「찾을 수 없습니다」가 됩니다」
  - 화면 안내: 「발행을 취소한 뒤 지울 수 있습니다」, 「저장하지 않은 수정이 있습니다」, 「관리자 화면을 불러오지 못했습니다. 새로 고침하세요」, 상태 「초안」·「발행」

## 스펙과 다르게(또는 스펙이 침묵해서) 정한 것

1. **발행의 칸 검사(422)는 슬러그 조회(404)·예약어(409)보다 앞이다.**
   - 스펙 6.1 의 순서에는 422 가 없다. 수정 API 의 관례(칸 검사가 대상 조회보다 앞)를 따른다.
   - 그래서 없는 id 로 발행하는 시험도 유효한 `{updated_at}` 을 싣는다.
2. **발행 본문은 `@RequestBody(required = false)` 다.** 본문이 없거나 `updated_at` 이 `null` 이면 처리기가 422 를 낸다.
   - `@AdminPermission` 은 `@PreAuthorize`(메서드 보안)라 인자 해석보다 뒤에 돈다(확인: `AdminPermission.java`).
   - 필수 본문이면 권한 없는 사람의 빈 요청이 403 이 아니라 400 이 된다(추정 — Task 4 Step 1 이 확인). 빈 본문을 보내는 기존 401·403 시험은 고치지 않고 그대로 돈다.
3. **`page.update` 만 가진 역할(`page.view` 없음)의 상세 GET 은 200 에서 403 으로 바뀐다.** 스펙 6.1-2 의 귀결인데 스펙은 이 줄을 적지 않았다. 기존 시험의 그 단언이 함께 뒤집힌다(Task 4).
4. **발행·발행 취소의 성공 알림은 「발행했습니다」·「발행을 취소했습니다」다.** 스펙에 문구가 없다.
5. **dirty 이거나 다시 읽는 중일 때의 「발행」 단추.** `ConfirmButton` 에 셋째 속성을 더하지 않는다.
   - dirty 면 `PageEditPage` 가 `ConfirmButton` 대신 `aria-disabled="true"` 인 평범한 `Button` 을 그린다. 누르면 아무것도 하지 않고, 안내를 `aria-describedby` 로 단다. 고치는 중에는 대화상자가 열려 있을 수 없어 바꿔 그려도 잃는 것이 없다.
   - 「다시 읽는 중」은 바꿔 그리지 않는다. 내 동작(저장·발행·발행 취소)의 `send` 가 상세 무효화를 `await` 한 뒤에 끝나게 해, 다시 읽는 동안은 그 단추의 `save.pending` 이 `aria-disabled` 를 건다.
     - 발행 409 뒤에 단추가 빠지면 대화상자 안의 `SaveAlert` 와 초점이 돌아갈 Trigger 가 함께 사라진다. 그래서 바꿔 그리지 않는다.
     - 저장 뒤의 dirty 는 `onSuccess`(이미 새 응답이 왔다)에서 지운다 — 다시 읽는 동안 발행은 dirty 로 막힌다.
   - 스펙 7.3 의 「`ConfirmButton` 에 둘을 더한다」(`ask`·`children`)는 그대로 지킨다.
10. **`PageEditPage` 는 `id` 마다 안쪽 부품을 새로 마운트한다**(`key={id ?? "new"}`). `pages/new` 와 `pages/:id` 가 같은 부품이라, 작성 201 뒤 이동해도 작성 때의 dirty·편집기가 남아 다시 채우기가 막힐 수 있다(React Router 8.5.1 동작은 구현 때 확인 — 시험이 가른다).
6. **`offline(page)` 도우미를 `pages.spec.ts` 에서 `e2e/stack.ts` 로 옮긴다.** 로그인한 관리자 응답을 주는 인자를 더한다 — 편집기 E2E 가 함께 쓴다.
7. **붙여넣기 공격 E2E(12.5-3)와 링크 거부 E2E(12.5-5)는 Task 3 에 둔다.**
   - CSP Task 8 의 m-6 실험이 붙여넣기를 보아야 하므로(스펙 9절 ③) 그보다 앞이다.
   - 스펙 0절 ⑦ 의 흐름 E2E 는 12.5-2·12.5-4 만 Task 9 에 남는다.
8. **IME 관문 Task 는 대조군을 의존성 설치보다 먼저 본다.** 흉내가 안 되는 브라우저라면 51개를 넣기 전에 멈춘다.
9. **A-301 의 제목은 늘 링크다.** 스펙 7.2(「늘」)와 12.4(「제목 링크(`can`)」)가 다르다. 목록 자체가 `page.view` 라 목록을 보는 사람은 상세도 연다 — 7.2 를 따른다.

## Review Focus

1. **빈 편집기로 저장** — `getHTML()` 이 `<p></p>` 다. 서버 422 `errors.body_html` 「본문을 입력하세요」가 오면 초점이 편집기로 가고, 그 문구가 `aria-describedby` 로 읽힌다. Task 6 시험.
2. **저장이 401 이면** — 로그인 링크가 보이고 편집기의 본문은 그대로 남는다(지워지지도, 다시 채워지지도 않는다). Task 6 시험.
3. **편집기 꼴이 아닌 본문을 열고 두 번 저장** — 첫 저장에서 편집기 꼴로 바뀌고 둘째 저장의 `body_html` 은 첫째와 같다. 편집기 출력을 다시 실은 출력이 같아야 한다. Task 2 시험.
4. **표·색·밑줄이 든 HTML 붙여넣기** — `<table>`·`style`·`<u>` 는 빠지고 그 안의 글자 「칸」·「색」은 남는다. Task 3 시험.
5. **링크 주소의 앞뒤 공백**(`" /about"`) — 「사용할 수 없는 링크입니다」로 받지 않는다. 서버 `LinkCheck` 도 공백을 받지 않는다. Task 1 시험.

---

### Task 1: 한글 IME 관문 — 편집기·`linkHref`·최소 작성 화면 (스펙 2·3·4절)

**Files:**
- Modify: `src/front/package.json`·`package-lock.json`, `src/front/src/features/admin/AdminLayout.tsx`, `src/front/e2e/stack.ts`, `src/front/e2e/pages.spec.ts`(`offline` import 만), `src/front/src/test-vectors.ts`
- Modify(대조군이 headless shell 에서 실패할 때만): `src/front/playwright.config.ts`, `.github/workflows/ci.yml`
- Create: `src/front/src/shared/lib/linkHref.ts`·`linkHref.test.ts`, `src/front/src/features/admin/RichEditor.tsx`·`RichEditor.test.tsx`, `src/front/src/features/admin/PageEditPage.tsx`, `src/front/e2e/editor.spec.ts`

**Interfaces:**
- Produces: `linkHref(url: string): string | null` — 스펙 4절 판정 1·2 그대로. 서버 `LinkCheck` 규칙을 옮기고 `safeNext`·`new URL` 로 한 번 더 본다. `new URL` 의 예외는 `null` 이다.
- Produces(`RichEditor.tsx`)
  - `export const EXTENSIONS: Extensions` — 스펙 3.1 대조표와 3.2 Link 표 그대로.
    - `StarterKit.configure({ link: false, orderedList: false, code: false, codeBlock: false, strike: false, underline: false, horizontalRule: false, trailingNode: false, heading: { levels: [2, 3] } })`
    - `Link` 를 `extend` 해 속성을 `href` 하나로 줄이고 `.configure({ isAllowedUri: (url) => linkHref(url) !== null, autolink: false, linkOnPaste: false, openOnClick: false, HTMLAttributes: { target: null, rel: null } })` 한다.
    - `OrderedList` 를 `extend` 해 속성을 `start` 하나로 줄인다. 읽을 때 `^[0-9]{1,6}$` 가 아니면 1 이다.
  - `export function useRichEditor(content: string, onChange?: () => void): Editor | null` — `useEditor({ extensions: EXTENSIONS, content, onUpdate: onChange })`. `injectCSS` 는 기본값이다(스펙 3.4).
  - `export function RichEditor({ editor, labelId, error, errorId }: { editor: Editor | null; labelId: string; error?: string; errorId?: string })`
    - 도구 막대: `role="toolbar"`·`aria-label="서식"`. 단추 여덟 개 「굵게」·「기울임」·「제목 2」·「제목 3」·「글머리 목록」·「번호 목록」·「인용」·「링크」는 `aria-pressed` 를 가지고, 상태는 `useEditorState` 로 읽는다.
    - 「링크」 단추는 링크 입력 칸에 초점을 준다.
    - 링크 입력: 라벨 「링크 주소」인 `Input` 하나와 「적용」·「링크 빼기」. 스펙 3.3 규칙 그대로다.
      - 「적용」이 성공하면 `editor.chain().focus().extendMarkRange("link").setLink({ href: <입력 문자열 그대로> }).run()` — 초점이 편집기로 돌아간다(I-4 가 이어서 `End` 를 누른다).
    - 편집 영역 속성: `role="textbox"`·`aria-multiline="true"`·`aria-labelledby={labelId}`, `error` 가 있으면 `aria-invalid`·`aria-describedby={errorId}`. 속성을 바꾸는 방법(`editorProps.attributes` 를 다시 다는 법)은 구현 때 확인한다.
- Produces: `PageEditPage()` — 이 Task 에서는 작성 모드만 만든다(`useParams().id` 가 없을 때).
  - h2 「새 페이지」(초점).
  - `react-hook-form` + zod 칸 둘: 「제목」(`nameSchema(200)`, `shared/lib/siteName.ts`), 「주소」(필수·100자·`^[a-z0-9][a-z0-9-]*$`, 서버 `Slug.error` 문구 그대로).
  - 「본문」 라벨(`id` → `RichEditor` 의 `labelId`), 제출 단추 「만들기」.
  - 저장은 `useSave` 다: `POST /api/admin/pages` 에 `{ slug, title, body_html: editor.getHTML() }` → 201 → `["admin", "pages"]` 무효화 → `/admin/pages/{id}` 로 이동, 알림 「페이지를 만들었습니다」(알림 주소 = 상세).
  - 수정·발행·렌더러는 Task 6 이 더한다.
- `ADMIN_SCREENS` 에 `{ path: "pages/new", name: "새 페이지", perm: "page.create", menu: false }` 를 더하고, `path`·`perm` 유니언 타입을 넓힌다.
- Produces(`stack.ts`): `offline(page: Page, can?: Record<string, boolean>)` — 1-C1 `pages.spec.ts` 의 것을 옮긴다.
  - `can` 이 없으면 지금 동작 그대로다(`/api/me` 401).
  - `can` 이 있으면 `/api/me` 는 200 `{ user: { email: "editor@ondolith.test", display_name: "편집자" }, can }`, `/api/admin` 은 200 이다.
- Produces(`test-vectors.ts`): `export const links: { reject: string[]; allow: string[] }` — `links.json` 을 `vectors` 와 같은 방식으로 읽는다.
- `editor.spec.ts` 안(파일 밖으로 내보내지 않는다)
  - `compose(cdp: CDPSession, steps: string[])` — 단계마다 `Input.imeSetComposition({ text, selectionStart: text.length, selectionEnd: text.length })` 를 부르고, 끝에 `Input.insertText({ text: steps.at(-1) })` 로 확정한다.
  - `typeHangul(cdp)` — 「한글 입력」을 친다: `compose(["ㅎ","하","한"])`, `compose(["ㄱ","그","글"])`, `insertText(" ")`, `compose(["ㅇ","이","입"])`, `compose(["ㄹ","려","력"])`. `compositionend` 기대는 4 다.
  - I-5 는 `compose(["ㅎ","하","한","하"])` 와 `compose(["ㄴ","나"])` 다.
  - 조합 이벤트 셈: `addInitScript` 가 `document` 의 `compositionstart`·`compositionupdate`·`compositionend` 를 capture 로 세어 `window.__comp` 에 둔다.
  - 저장 가로채기: `POST /api/admin/pages` 를 `route` 로 받아 `postDataJSON().body_html` 을 모으고 201 `{ id: U }` 로 답한다.
  - `pageerror` 수집.

- [ ] **Step 1: 대조군부터(의존성 전)**
  - `offline` 을 옮기고, 제목·주소 칸만 있는 `PageEditPage` 와 `ADMIN_SCREENS` 한 줄을 넣는다.
  - E2E `IME 흉내 대조군: 제목 칸에 한글 입력` 을 쓴다: `offline(page, { "admin.access": true, "page.create": true })` → `/admin/pages/new` → 제목 칸에 `typeHangul` → 값이 정확히 「한글 입력」, `compositionstart` ≥ 1, `compositionend` = 4.
  - Red 는 화면을 넣기 전(라벨 없음)에 본다.
  - **대조 단언 Red**: `compose` 에서 `imeSetComposition` 을 빼고 `insertText` 만 보내면 「`compositionstart` ≥ 1」에서 실패하는 것을 보고 되돌린다(스펙 12.1).
- [ ] **Step 2: 대조군이 실패하면 — 순서와 멈춤**(스펙 2.2)
  1. 기본 `chromium` 프로젝트(headless shell)에서 실패하면 `playwright.config.ts` 의 그 프로젝트에 `channel: "chromium"` 을 주고 같은 시험을 돌린다.
     - 통과하면 채널을 남긴다. `ci.yml` 의 `npx playwright install --with-deps chromium` 줄을 그 채널에 맞는 꼴로 고친다(정확한 꼴은 그때 Playwright 문서로 확인).
     - 바꾼 이유를 결정 메모에 적는다.
  2. 둘 다 실패하면 **멈추고 보고한다.** 의존성을 넣지 않는다. 수동 확인만으로 고를지는 사용자가 정할 일이다(`request-core.md:290`).
- [ ] **Step 3: 판·OSV 다시 보기**(Global Constraints 규칙)
  - `npm view <패키지> dist-tags.latest` 로 다섯과 `prosemirror-view` 를 본다.
  - 스크래치 폴더에서 `npm install --package-lock-only --ignore-scripts` 로 lock 을 풀어 새로 드는 패키지(스펙 1절 기준 51개)를 뽑고, `api.osv.dev/v1/querybatch` 로 조회한다.
  - 0건이 아니면 멈추고 보고한다. 스크래치는 지운다.
- [ ] **Step 4: 설치** — `cd src/front && npm install --save-exact @tiptap/react@3.35.0 @tiptap/pm@3.35.0 @tiptap/starter-kit@3.35.0 @tiptap/extension-link@3.35.0 @tiptap/extension-list@3.35.0`. `package.json` 에 판이 정확히 적혔는지, lock 에 새로 든 수가 Step 3 과 같은지 본다.
- [ ] **Step 5: 실패하는 단위 시험**
  - `linkHref.test.ts`
    - **첫 Red** `links_json_의_reject_는_모두_null`(입력을 그대로 내는 껍데기면 `javascript:alert(1)` 줄이 실패한다)
    - `links_json_의_allow_는_모두_null_이_아니다`
    - `끝_개행은_null`(`"/about\n"`)
    - `앞뒤_공백은_null`(`" /about"`·`"/about "`, Review Focus 5)
    - `new_URL_이_던지면_null`
  - 서버와 갈리는 꼴(`https://a.1/` 등, 스펙 4절)은 Vitest(jsdom 의 `URL`)에서 결과를 보고 한 줄로 고정한다. `links.json` 은 고치지 않는다. Chromium 의 결과는 Task 3 이 같은 값으로 본다.
  - `RichEditor.test.tsx`
    - **탐침** `jsdom_에서_편집기가_뜬다`: `new Editor({ extensions: EXTENSIONS, content: "<p>a</p>" }).getHTML()` 이 `"<p>a</p>"`.
      - jsdom 에서 돌지 않으면 결정 메모에 적는다. 이 파일의 시험(과 Task 2 의 프런트 짝)을 `editor.spec.ts` 의 `page.evaluate` 로 옮긴다(스펙 5.2·17절 3).
    - `도구_막대_단추마다_aria_pressed_가_상태를_따른다`(여덟 단추 각각 누르기 전 false, 누른 뒤 true)
    - `링크_입력이_javascript_와_protocol_relative_를_거부한다`: `javascript:alert(1)`·`//evil.example` → 「사용할 수 없는 링크입니다」, 칸이 `aria-invalid="true"` 이고 초점을 가진다, `getHTML()` 에 `<a` 가 없다.
    - `고른_글자가_없으면_안내`: 「링크로 만들 글자를 먼저 고르세요」
    - `저장값은_입력_문자열_그대로`: `https://EXAMPLE.com/` → `href="https://EXAMPLE.com/"`. `URL.href` 의 소문자 꼴이 아니다.
    - `링크_빼기`
    - `라벨을_누르면_편집기에_초점`
- [ ] **Step 6: Red** — `npx vitest run src/shared/lib/linkHref.test.ts src/features/admin/RichEditor.test.tsx` → 모듈 없음, 그다음 껍데기가 실패한다.
- [ ] **Step 7: 구현** — 위 Interfaces 대로 만든다. `PageEditPage` 에 본문을 더한다.
- [ ] **Step 8: 다섯 장면 E2E**(스펙 2.3 표)
  - 시험 이름은 `IME I-1 빈 문단` … `IME I-5 받침 넘김` 이다. 장면마다 페이지를 새로 연다.
  - 편집기를 누르고 장면 동작을 한다. 굵게는 `ControlOrMeta+b` 다. I-4 는 `insertText("안")` → `Shift+ArrowLeft` → 링크 칸 `/about` → 「적용」 → `End` 순서다.
  - 「만들기」 전에 제목·주소를 채우고, 가로챈 `body_html` 로 판정한다.
  - 단언: ① DOM 판정(`DOMParser` 로 푼 문서) ② Tiptap 바이트 기대 ③ 장면별 `compositionstart` ≥ 1·`compositionend` = 확정 글자 수 ④ `pageerror` 0·받지 않은 CSP 위반 0(`guard`)·대화상자 0
  - 바이트 기대는 처음 Red 의 실제 출력을 보고 고정한다. 글자가 맞고 바이트만 다르면 3절 설정 문제다 — 설정을 고치고 적는다(스펙 2.3).
- [ ] **Step 9: 판정과 대안 경로**(스펙 2.5) — 대조군이 통과한 상태에서
  1. 다섯 장면이 모두 통과하면 관문 통과다.
  2. 하나라도 실패하면 3절 설정(끈 확장·입력 규칙)으로 고쳐지는지 한 번 본다. ProseMirror 를 고치거나 덧대지 않는다.
  3. 그래도 실패하면 `lexical`·`@lexical/react` 0.53.0 을 넣는다(넣기 전 Step 3 과 같은 판·전이 의존 OSV 확인). 같은 화면에 Lexical 부품을 붙여 **같은 시험**(같은 장면·DOM 판정·대조 단언, 바이트 기대만 뺀다)을 건다.
  4. **Lexical 이 통과해도 멈추고 보고한다** — Task 2 이후(3절 대조표·5절 짝 시험)가 모두 Tiptap 전제다.
  5. Lexical 도 실패하면 멈추고 보고한다. 편집기를 직접 만들지 않는다.
- [ ] **Step 10: 관문 보고**(결정 메모에 적고, 보고에 그대로 넣는다)
  - 「통과」 또는 「실패」
  - 브라우저: headless shell 인지 `channel: "chromium"` 인지
  - 대조군 값과 그 장면의 조합 이벤트 수
  - 장면별 ①~④ 결과와 `compositionstart`·`compositionupdate`·`compositionend` 수(대조 단언 수)
  - 대조 단언 Red 를 본 사실
  - `Input.insertText` 가 조합을 끝냈는지(스펙 17절 2)
  - 고정한 바이트 기대 다섯
  - 사용자 수동 확인(맥 한글 입력기, Chrome·Safari)은 「미실행 — 사용자 확인 요청」으로 적는다.
- [ ] **Step 11: Green + 회귀** — `npx vitest run` 전체, `npm run typecheck`, 셸 시험(`html-sinks` 가 새 파일을 통과한다), `mise run e2e`(기존 15 + 새 6). 통과 숫자를 적는다.
- [ ] **Step 12: 커밋** `편집기 1: 한글 IME 관문 — Tiptap 편집기·링크 판정·작성 화면과 CDP 조합 입력 E2E`

### Task 2: 편집기 ↔ 서버 짝 시험, W1~W4 를 벡터 표로 (스펙 5절, m-10)

**Files:**
- Create: `src/back/src/test/resources/richtext/editor.json`
- Modify: `src/back/src/test/resources/richtext/vectors.json`, `src/back/src/test/java/dev/ondolithj/shared/RichTextTest.java`, `src/front/src/test-vectors.ts`, `src/front/src/features/admin/RichEditor.test.tsx`, `src/front/src/shared/ui/RichHtml.test.tsx`
- Modify(설정을 고쳐야 할 때만): `src/front/src/features/admin/RichEditor.tsx`

**Interfaces:**
- Consumes: `EXTENSIONS`·`linkHref`·`links`(Task 1), `RichHtml`, `vectors`, `frontInvariant`.
- Produces(`editor.json`): 배열, 줄마다 `{ "id", "input", "editor" }`, 스펙 5.1 표의 열세 줄(E1, E2, E3-1~E3-5, E4~E9).
  - E1 의 `input` 은 `vectors.json` A1 의 `input` 에서 끝의 `<p><img …></p>` 를 뺀 것이다.
- Produces(`test-vectors.ts`)
  - `export type EditorRow = { id: string; input: string; editor: string }`, `export const editorRows: EditorRow[]`
  - `export const EDITOR_TAGS = ALLOWED_TAGS.filter((t) => t !== "img")`
  - `export function editorInvariant(root: Element, tags: string[]): string[]` — `frontInvariant` 판정에 「속성은 `a[href]`·`ol[start]` 뿐, `start` 는 `^[0-9]{1,6}$`」를 더한다.
    - E2E 가 `evaluate` 로 넘기므로 바깥 변수를 읽지 않는다(1-C1 `frontInvariant` 와 같은 제약).
    - `linkHref` 판정은 넣지 않는다 — Vitest 쪽 시험이 따로 본다.
- `vectors.json`: `RichHtml.test.tsx` 의 `UNWRAPPED` 넷을 id `W1`~`W4` 로 옮긴다(92 → 96줄). `server` 는 그 파일의 값 그대로 쓴다.

- [ ] **Step 1: 실패하는 시험**
  - `RichEditor.test.tsx`(Task 1 탐침이 jsdom 불가였으면 같은 이름으로 `editor.spec.ts` 의 `page.evaluate` 에)
    - **첫 Red** `editor_json_의_모든_줄에서_편집기_출력이_editor_와_같다`
      - `editor` 열은 스펙 표에서 바로 나오는 값만 먼저 적는다. 「실제 출력으로 고정」 줄(E6·E7·E8)은 Red 로 본 값을 넣는다.
      - 규칙과 다르게 나오면 3절 설정을 고치거나 멈추고 보고한다.
    - `편집기_출력은_DOMPurify_가_바꾸지_않는다`: 줄마다 렌더한 `RichHtml` 의 `.rich` `innerHTML === editor`(1-C1 처럼 `CONFIG` 를 내보내지 않는다)
    - `편집기_출력을_다시_실어도_같다`(Review Focus 3): 줄마다 `editor` 를 실은 `getHTML() === editor`
    - `links_allow_값은_편집기에서_링크로_남는다`: 값마다 `<p><a href="<속성 이스케이프>">x</a></p>` → 남은 `a` 의 `href` 가 그 값이다.
    - `vectors_입력을_실은_편집기_출력의_불변식`: 96줄 모두 `editorInvariant(<getHTML 을 DOMParser 로 푼 body>, EDITOR_TAGS)` 가 빈 목록이고, 모든 `href` 가 `linkHref` 를 지난다.
    - `끝이_목록인_문서에_트랜잭션을_내도_빈_문단이_붙지_않는다`: `<ul><li><p>a</p></li></ul>` 를 싣고 `setTextSelection(1)` 한 뒤 `getHTML()` 끝에 `<p></p>` 가 없다.
  - `RichTextTest`
    - `편집기_고정_파일의_editor_는_clean_해도_같다`: 줄마다 `clean(editor) == editor`
    - `VECTORS` 크기 단언 `hasSize(92)` → 96
  - `RichHtml.test.tsx`: `UNWRAPPED` 를 지우고 `ALL` 을 `vectors` 로, `toBe(92)` → 96. 줄을 옮겼을 뿐 단언은 그대로다.
- [ ] **Step 2: Red** — `npx vitest run src/features/admin/RichEditor.test.tsx` → `editor.json` 없음으로 실패한다. 파일을 넣은 뒤 줄 단위 불일치를 본다.
  - 변이로 Red 를 한 번씩 본다:
    - `extend` 를 빼면 E4·E5 가 실패한다.
    - `trailingNode` 를 켜면 빈 문단 시험이 실패한다.
    - 백엔드는 `editor.json` 한 줄에 일부러 `<p class="x">` 를 넣으면 `RichTextTest` 가 실패한다.
  - 모두 되돌린다.
- [ ] **Step 3: 구현** — 고정 파일과 도우미를 넣는다. 설정을 고쳤으면 무엇을 왜 고쳤는지 결정 메모에 적는다.
- [ ] **Step 4: 파급 확인**
  - `-Dtest='RichTextTest,PagesApiTest'` — `PagesApiTest.벡터_입력을_저장하면_저장값과_공개_응답이_server_와_같다` 가 W1~W4 를 돈다(`<p>z</p>` 를 앞에 붙인다). 성립하지 않으면 단언을 고치지 않고 멈추고 보고한다.
  - E2E `DOMPurify 겹` 은 96줄을 돈다: `npx playwright test e2e/pages.spec.ts -g "DOMPurify"`.
    - W1~W4 줄에서 붙이지 않은 문서 파싱 보고가 새로 나면 1-C1 처럼 경로 완전 일치로 받고 Task 8 m-4 에서 건수와 함께 고정한다.
    - `script-src`·`script-src-elem` 이면 멈추고 보고한다.
- [ ] **Step 5: Green** — 백엔드 전체, `npx vitest run`, `npm run typecheck`, IME 관문.
- [ ] **Step 6: 커밋** `편집기 2: 편집기 ↔ 서버 짝 시험(editor.json), W1~W4 를 벡터 표로`

### Task 3: 붙여넣기 공격·링크 입력 거부 E2E (스펙 12.5-3·12.5-5)

**Files:**
- Modify: `src/front/e2e/editor.spec.ts`

**Interfaces:**
- Consumes: `offline(page, can)`(Task 1), `vectors`·`editorInvariant`·`EDITOR_TAGS`(Task 2), 1-C1 `pages.spec.ts` 의 `xss()` — 같은 한 줄을 이 파일에도 둔다. 두 파일이 같은 변환인지는 `alert` 가 남지 않는다는 단언이 본다.

- [ ] **Step 1: 합성 붙여넣기가 닿는지 먼저**(스펙 17절 4)
  - `/admin/pages/new` 에서 편집 영역(`.ProseMirror`)에 `ClipboardEvent("paste", { clipboardData: dt, bubbles: true, cancelable: true })` 를 보낸다. `dt` 는 `new DataTransfer()` 에 `setData("text/html", "<p><strong>붙임</strong></p>")` 한 것이다.
  - 그 뒤 `getHTML()` 에 `<strong>붙임</strong>` 이 있는지 본다.
  - 없으면 CDP 로 바꾼다: `context.grantPermissions(["clipboard-read", "clipboard-write"])`, `navigator.clipboard.write` 로 `text/html` 을 넣고 `Input.dispatchKeyEvent` 로 붙여넣기 키를 보낸다.
  - 둘 다 안 되면 이 시험은 「미실행」과 이유를 적고 Step 4 로 간다(방어는 서버 정화다, 스펙 14절).
- [ ] **Step 2: 시험**
  - `붙여넣기: 오염 HTML 을 붙여도 스크립트가 돌지 않는다`(`test.setTimeout(300_000)`)
    - `vectors` 96줄마다 `editor.commands.clearContent()` → 초점 → `xss(input)` 을 붙여넣는다.
    - 줄 단언: `editorInvariant(<getHTML 을 DOMParser 로 푼 body>, EDITOR_TAGS)` 가 빈 목록이고, `networkidle` 뒤 `window.__xss` 가 `undefined` 다.
    - 끝 단언(1-C1 8.5-2 와 같은 셋): **`__xss` 0·대화상자 0·`script-src`·`script-src-elem` 위반 0**
    - 붙이지 않은 문서 파싱 보고(`script-src-attr`·`style-src-attr`·`base-uri`)가 나면 받는다.
      - 모든 줄이 같은 주소라 경로로는 가를 수 없다. 줄마다 붙이기 전후의 `guard.violations` 길이로 그 줄의 새 위반을 뽑는다.
      - 처음 Red 에서 본 값을 표 `PASTE_REPORTS: Record<줄 id, { directive; count }[]>` 로 고정한다. 그 줄의 새 위반이 표와 정확히 같을 때만 그 객체들을 `guard.allow(v => accepted.has(v))` 로 받는다.
  - `붙여넣기: 표·색·밑줄은 빠지고 글자는 남는다`(Review Focus 4): `<table><tr><td>칸</td></tr></table><p style="color:red"><u>색</u></p>` → `getHTML()` 에 `table`·`style`·`<u` 가 없고 글자 「칸」·「색」이 있다.
  - `링크 입력이 javascript 와 //evil.example 을 받지 않는다`(완료 기준 4)
    - 글자를 치고 고른 뒤 링크 칸에 `javascript:alert(1)` → 「적용」 → 칸 오류(문구·`aria-invalid`). `//evil.example` 도 같다.
    - 제목·주소를 채워 「만들기」 → 가로챈 `body_html` 에 `href` 가 없다.
  - `linkHref 와 LinkCheck 가 갈리는 꼴은 Chromium 에서도 같다`(스펙 17절 8): Task 1 에서 고정한 값마다 `page.evaluate` 로 `new URL(값)` 이 던지는지를 보고, jsdom 결과와 같은지 단언한다. 다르면 Chromium 쪽 값을 결정 메모에 적는다.
- [ ] **Step 3: Red(변이)** — 처음부터 통과할 시험이다. 변이를 걸어 실패를 보고 되돌린다.
  - `isAllowedUri` 를 `() => true` 로 잠시 바꾸면 붙여넣기 시험이 `javascript:` 줄(X16 등)의 불변식에서 실패한다.
  - `linkHref` 가 입력을 그대로 내게 바꾸면 링크 입력 시험이 실패한다.
- [ ] **Step 4: Green** — `npx playwright test e2e/editor.spec.ts`.
- [ ] **Step 5: 커밋** `편집기 3: 붙여넣기 공격·링크 입력 거부 E2E(실제 Chromium, 빌드 CSP)`

### Task 4: 발행에 읽은 `updated_at`(I-2), 상세는 `page.view`, 발행본 삭제에 `page.publish`, 413 보안 헤더(m-7) (스펙 6절)

**Files:**
- Modify: `src/back/src/main/java/dev/ondolithj/admin/PagesController.java`, `shared/RequestSizeLimit.java`, `shared/SecurityConfig.java`, `src/front/openapi.json`·`src/front/src/shared/api/schema.d.ts`(생성), `src/front/e2e/pages.spec.ts`
- Modify(시험): `admin/PagesApiTest.java`, `shared/RequestSizeLimitTest.java`, `OpenApiFileTest.java`, `auth/ReauthTargetsTest.java`(Step 1 결과에 따라)

**Interfaces:**
- Produces: `record PublishInput(Instant updatedAt)`. `publish(@PathVariable UUID id, @RequestBody(required = false) PublishInput in, …)` 의 순서:
  1. `in == null || in.updatedAt() == null` → `FieldErrors(updated_at: "필수입니다")` (「다르게 정한 것」 1·2)
  2. 슬러그 읽기 → 없으면 404 → 예약어면 409 「이 주소는 시스템 경로와 충돌합니다」
  3. `UPDATE pages SET status = 'published', updated_at = now() WHERE id = ? AND status = 'draft' AND updated_at = ?` → 1행이면 204·기록 `page.publish`
  4. 0행이면 `SELECT status FROM pages WHERE id = ?` 를 다시 읽는다.
     - 없음 → 404
     - `published` → 204·기록 없음
     - `draft` → `Refused(409, "그사이 페이지가 바뀌었습니다. 바뀐 내용을 확인한 뒤 발행하세요")`
  - `updated_at` 바인딩(`Instant` 그대로인지 `OffsetDateTime.ofInstant(…, ZoneOffset.UTC)` 인지)은 구현 때 확인한다. 판별은 시험 `상세의_updated_at_을_그대로_실으면_204` 가 한다.
  - 발행 취소는 그대로 `change()` 를 쓴다.
- Produces: `detail` 의 표시를 `@AdminPermission(Permission.PAGE_VIEW)` 로 바꾼다.
- Produces: `delete`
  - `boolean publisher = actor.permissions().contains(Permission.PAGE_PUBLISH)`
  - `DELETE FROM pages WHERE id = ?` 에, `publisher` 가 아니면 ` AND status = 'draft'` 를 붙인 한 문장이다.
  - 0행이면 `EXISTS` 로 가른다: 있으면 `Refused(403, "발행된 페이지는 발행 권한이 있어야 지울 수 있습니다")`, 없으면 404.
- `update` 의 `ponytail:` 주석: 「동시 편집은 나중 저장이 이긴다. 1-C2 는 자동 저장 없음(1-C2 스펙 6.3) — 자동 저장이나 편집이 사라졌다는 보고가 생기면 읽은 updated_at 으로 409」
- `@ApiResponse` 는 스펙 6.5 표대로 고친다.
  - 발행: 409 「예약어·낡은 updated_at」, 422 `errors.updated_at`, 400 「본문이 JSON 이 아님·형식 불량」
  - 상세: 403 「page.view 권한 없음」
  - 삭제: 403 「page.delete 권한 없음·CSRF 토큰 없음·발행본인데 page.publish 없음」
- m-7
  - `SecurityConfig` 에 `public static final String API_CSP = "default-src 'none'; frame-ancestors 'none'"` 를 두고 체인과 필터가 함께 쓴다.
  - `RequestSizeLimit` 이 413 을 쓰는 한 함수(두 갈래 — 길이 초과와 `chain.doFilter` 를 감싼 쪽 — 가 모두 지난다)에서 `X-Content-Type-Options: nosniff`·`Content-Security-Policy: API_CSP`·`X-Frame-Options: DENY`·`Cache-Control` 을 단다.
  - `Cache-Control` 값은 Spring Security 가 보통 응답에 다는 값과 같게 한다(시험 안에서 일반 응답의 값을 읽어 비교한다).
- Produces(시험 도우미, `PagesApiTest`)
  - `static String updatedAt(TestHttp http, UUID id)` — 상세 GET 의 `updated_at` 문자열을 그대로 낸다.
  - `static HttpResponse<String> publishAt(TestHttp http, UUID id, String updatedAt)` — 본문 `{"updated_at": <값>}`, `null` 이면 JSON `null`.
  - `publish(http, id)` 는 `publishAt(http, id, updatedAt(http, id))` 가 204 임을 단언한다.
  - `static final String ANY_TIME = "2026-11-24T00:00:00Z"` — 없는 id·예약어 시험용이다.

- [ ] **Step 1: 순서 탐침** — `@RequestBody(required = false)` 처리기에서 권한 없는 역할이 빈 본문(`""`)으로 보내면 403, 익명이면 401 인지 본다. 기존 `page_update_만_가진_역할은_작성_발행_발행취소가_403이고_기록이_없다`·`익명은_401`·`CSRF_없으면_403`·`거절은_기록하지_않는다` 가 고치지 않고 통과해야 한다. 아니면 멈추고 보고한다.
- [ ] **Step 2: 실패하는 시험**(스펙 12.1·12.3 이름 그대로)
  - 승인자 역할은 `role("admin.access", "page.view", "page.publish")`(`page.update` 없음), 고치는 사람은 `role("admin.access", "page.update")` 다.
  - I-2 발행
    - **Red(I-2)** `읽은_뒤_본문이_바뀌면_발행이_409_이고_상태와_기록이_그대로`: 승인자가 상세를 읽는다 → 고치는 사람이 수정 204 → 승인자가 읽은 값으로 발행 → 409 문구, 상태 `draft`, 승인자 기록 0.
    - **Red(16절 1)** `page_view_와_page_publish_만_가진_승인자가_상세를_읽고_그_updated_at_으로_발행하면_204`: `page.publish` 기록이 한 줄이다.
    - `상세의_updated_at_을_그대로_실으면_204`(편집자): 응답 문자열의 소수 자리 수를 찍어 결정 메모에 적는다.
    - `같은_값으로_두_번_발행하면_둘째도_204_이고_기록이_늘지_않는다`(Q7)
    - `updated_at_이_없거나_null_이면_422_형식이_틀리면_400_다른_칸은_무시`: 본문 없음·`{}`·`{"updated_at": null}` → 422 `errors.updated_at` 「필수입니다」, `"abc"` → 400, 맞는 값 + `"status": "x"` → 204.
    - `예약어_슬러그는_옛_updated_at_이어도_409_시스템_경로`(`f.sql` 로 `slug = 'admin'`, `ANY_TIME`)
    - `없는_id_와_지워진_뒤는_404`
  - 상세 — 뒤집히는 기존 시험: `상세는_편집자_200_page_view_만이면_403` → **`상세는_page_view_만이면_200_권한이_없으면_403`**
    - 응답 칸 단언은 그대로 둔다.
    - `page.view` 만이면 200 이다.
    - `admin.access` 만, 그리고 `page.update` 만(「다르게 정한 것」 3)이면 403 이다.
    - 단언 완화가 아니라 결정으로 동작이 바뀐 것이다. 커밋 메시지 본문에 적는다.
  - 삭제
    - **Red(16절 3)** `page_delete_만_가진_역할은_발행된_페이지를_지울_수_없다_403_이고_행과_기록이_그대로`(`role("admin.access", "page.delete")`, `row(id)` 그대로, `logs` 증분 0)
    - `page_delete_만으로_초안은_지운다_204`(`page.delete` 한 줄). 처음부터 통과하면 판정 조건을 잠시 `status = 'published'` 로 뒤집어 실패를 본다.
    - `편집자는_발행본도_지운다_204`
    - `없는_id_는_두_갈래_모두_404`(편집자와 `page.delete` 만)
  - m-8: 코드를 바꾸지 않는다(스펙 6.2). 기존 `발행_권한_없는_역할은_발행된_페이지를_고칠_수_없다_403` 의 「기록 없음」 단언이 그대로 지킨다. 새 409·403(낡은 `updated_at`·발행본 삭제)도 위 시험이 기록 0 을 단언한다.
  - m-7 `RequestSizeLimitTest.413_에도_API_보안_헤더가_붙는다`: Content-Length 1,048,577바이트 요청과, `chain` 을 감싼 갈래(1-C1 의 `POST_가_아닌_chunked_폼_초과도_500_이_아니라_413` 과 같은 요청) 둘 다 네 헤더를 단언한다. 처음부터 통과하면 고치지 않고 그 사실을 적는다(스펙 6.4).
  - 바뀌는 기존 시험
    - `/publish` 를 부르는 줄 가운데 401·403(Step 1 통과)이 아닌 것은 `publish`·`publishAt` 로 바꾼다 — 633·636·647·650줄 등. 기대 상태는 그대로다.
    - `ReauthTargetsTest` 의 발행 `call` 이 2xx 를 기대하면 그 페이지의 상세를 읽어 `{updated_at}` 을 싣는다(구현 때 확인).
    - `OpenApiFileTest`: 발행 `requestBody` 스키마 `PublishInput` 의 칸 `updated_at`, 위 응답 설명.
    - `e2e/pages.spec.ts` 의 발행 호출: `page.request.get` 으로 상세를 읽어 `updated_at` 을 싣는다.
- [ ] **Step 3: Red** — `-Dtest='PagesApiTest,RequestSizeLimitTest,OpenApiFileTest,ReauthTargetsTest'` → 204(409 기대)·403(200 기대)·204(403 기대)·헤더 없음.
- [ ] **Step 4: 구현** — 위 Interfaces.
- [ ] **Step 5: Green** — 백엔드 전체 → `npm run gen:api && npm run typecheck` → `mise run e2e`(`pages.spec.ts` 의 바뀐 발행 호출).
- [ ] **Step 6: 커밋** `편집기 4: 발행에 읽은 updated_at(다르면 409), 상세는 page.view, 발행본 삭제에 page.publish, 413 보안 헤더`

### Task 5: `ConfirmButton` 의 `ask`·`children`, A-301 목록과 메뉴, (h)·m-11 (스펙 7.1·7.2·7.4)

**Files:**
- Modify: `src/front/src/shared/ui/confirm.tsx`·`confirm.test.tsx`, `features/admin/AdminLayout.tsx`, `adminCache.test.tsx`, `RolesPage.tsx`·`RolesPage.test.tsx`, `UserDetailPage.tsx`·`UserDetailPage.test.tsx`
- Create: `features/admin/PagesPage.tsx`·`PagesPage.test.tsx`

**Interfaces:**
- Produces: `ConfirmButton` 에 `ask?: boolean`(기본값 `destructive`)과 `children?: ReactNode` 를 더한다.
  - `ask` 면 누를 때 대화상자를 연다. 단추 색은 지금처럼 `destructive` 로 고른다.
  - `children` 은 `AlertDialogHeader` 안, 설명 아래에 그린다.
  - 기존 호출은 바뀌지 않는다.
- Produces: `PagesPage()` — `UsersPage` 와 같은 꼴이다(스펙 7.2).
  - 쿼리 `["admin", "pages", page]`·`gcTime: 0`, `LoadFailure`.
  - 표: 제목(늘 `/admin/pages/{id}` 링크, 「다르게 정한 것」 9)·주소(`/{slug}` 글자, 링크 아님)·상태(`draft` 「초안」, `published` 「발행」)·수정 시각(`OplogPage` 와 같은 `toLocaleString`).
  - `can["page.create"]` 이면 「새 페이지」 링크.
- `ADMIN_SCREENS`: `{ path: "pages", name: "페이지", perm: "page.view", menu: true, element: <PagesPage /> }` 를 `roles` 뒤에 더한다.
- (h): `RolesPage` 의 권한 부여·빼기 성공 때 `["admin", "roles"]` 와 함께 `["me"]` 도 무효화한다.
- m-11: `UserDetailPage` 의 「빼기」는 역할이 `is_superuser` 이면 그 역할 id 가 `assignable_roles` 에 있을 때만 그린다.

- [ ] **Step 1: 실패하는 시험**
  - `confirm.test`
    - `ask_면_destructive_가_아니어도_묻는다`: 누르면 대화상자가 열리고 요청이 0 이다. 「확인」을 누르면 1 이다.
    - `children_이_설명_아래에_그려진다`
    - 기존 시험은 그대로 둔다.
  - `PagesPage.test`
    - `쪽_링크와_범위_밖_첫_쪽으로`
    - `can_이면_새_페이지_링크_아니면_없다`
    - `제목은_상세_링크_주소는_글자`
    - `상태_이름표`
    - `LoadFailure_401_403_그_밖`
    - `쿼리의_gcTime_은_0`
  - `adminCache.test`: `PagesPage` 를 더한다(로그아웃 뒤 `["admin"]` 아래가 지워진다).
  - `AdminLayout.test`: `page.view` 가 true 일 때만 메뉴 「페이지」가 보인다.
  - (h) `RolesPage.test.권한을_바꾸면_/api/me_를_다시_읽는다`: 부여 성공 뒤 `/api/me` 두 번째 요청을 기다린다. 빼기도 같다.
  - m-11 `UserDetailPage.test`
    - `superuser_역할은_assignable_roles_에_없으면_빼기가_없다`: 비-superuser 응답(시험 값), `roles` 에 `admin(is_superuser: true)`, `assignable_roles` 에는 `editor` 만 → admin 줄에 「빼기」가 없고 editor 줄에는 있다.
    - 반대로, `assignable_roles` 에 admin 이 있으면 보인다. V1 시드의 admin 은 `is_assignable` true 라(확인) superuser 요청자에게는 단추가 남는다.
- [ ] **Step 2: Red** — `npx vitest run src/shared/ui src/features/admin` → 모듈 없음·요청 수·두 번째 `/api/me` 없음·단추 있음.
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** — `npx vitest run`, `npm run typecheck`.
- [ ] **Step 5: 커밋** `편집기 5: 확인 단추의 ask·children, 페이지 목록 화면과 메뉴, 권한 변경 뒤 /api/me, superuser 역할 빼기 단추`

### Task 6: A-302 수정·A-303 발행·발행 취소·삭제, 렌더러 규칙, 다시 채우기 (스펙 7.3, 16절 1·2)

**Files:**
- Modify: `src/front/src/features/admin/PageEditPage.tsx`, `AdminLayout.tsx`, `adminCache.test.tsx`
- Create: `src/front/src/features/admin/PageEditPage.test.tsx`

**Interfaces:**
- Consumes: `useRichEditor`·`RichEditor`(Task 1), `ConfirmButton` 의 `ask`·`children`(Task 5), `RichHtml`, Task 4 의 생성 타입(`PageDetail`·`PublishInput`).
- `ADMIN_SCREENS`: `{ path: "pages/:id", name: "페이지 편집", perm: "page.view", menu: false, element: <PageEditPage /> }`
- Produces(`PageEditPage`, `id` 가 있을 때)
  - 바깥은 `useParams().id` 만 읽고 안쪽 부품을 `key={id ?? "new"}` 로 그린다(「다르게 정한 것」 10).
  - 상세 쿼리 `["admin", "page", id]`·`gcTime: 0`·`refetchOnWindowFocus: false`.
  - `loaded`: 「지금 폼에 실린 응답」(`useState<PageDetail | null>`).
  - `dirty` = 폼 `isDirty` ‖ 편집기 `onChange` 가 났다.
  - **다시 채우기**: `detail.data.updated_at !== loaded?.updated_at` 이고 `!dirty` 면 `loaded` 를 바꾸고 폼 `reset`, 편집기 `setContent(body_html, …)` 한다.
    - `setContent` 가 `onUpdate` 를 내 dirty 를 켜면, 내지 않는 옵션(Tiptap 3 이름은 구현 때 확인)을 쓴다. 그런 옵션이 없으면 폼을 `key={loaded.updated_at}` 인 자식 부품으로 떼어 다시 마운트한다.
  - `canSave = can["page.update"] && (loaded.status === "draft" || can["page.publish"])`
    - 거짓이면 렌더러로 그린다: 제목·주소 글자 + `<RichHtml html={loaded.body_html} />`, 폼·편집기·「저장」 없음.
    - 발행본이라 거짓이고 `can["page.update"]` 이면 「발행된 페이지는 발행 권한이 있어야 고칠 수 있습니다」를 함께 보인다.
  - 저장: `POST /api/admin/pages/{id}` → 204 → `send` 안에서 상세·목록 무효화를 `await` → `onSuccess` 에서 dirty 를 지운다. 알림 「저장했습니다」.
  - 422 초점: `slug`·`title` 은 `form.setFocus`, `body_html` 은 `editor.commands.focus()`.
  - 「발행」(초안 + `can["page.publish"]`): `ConfirmButton ask`, 본문 `{ updated_at: loaded.updated_at }`(문자열 그대로, `Date` 로 바꾸지 않는다).
    - `children` 은 `<div tabIndex={0} aria-label="발행할 본문" className="max-h-64 overflow-y-auto"><RichHtml html={loaded.body_html} /></div>` 다.
    - `send` 안에서 응답이 2xx 나 409 면 상세(·목록) 무효화를 `await` 한 뒤 돌려준다.
    - dirty 면 「다르게 정한 것」 5 의 막힌 단추를 그린다. 다시 읽는 동안은 `save.pending` 이 막는다.
  - 「발행 취소」(발행본 + `can["page.publish"]`): `ConfirmButton ask`.
  - 「삭제」(`can["page.delete"]` 이고 (초안이거나 `can["page.publish"]`)): `ConfirmButton destructive`. 발행본이면 설명이 바뀐다. 성공하면 `/admin/pages` 로 가고 알림 「페이지를 지웠습니다」.
    - `can["page.delete"]` 인데 발행본이라 숨기면 그 자리에 「발행을 취소한 뒤 지울 수 있습니다」를 보인다.
  - 발행·발행 취소가 성공하면 상세·목록을 무효화한다(알림은 「다르게 정한 것」 4).

- [ ] **Step 1: 실패하는 시험**(스펙 12.1 렌더러 둘, 12.4 `PageEditPage`, 16절 1 프런트 줄, Review Focus 1·2)
  - 렌더러
    - **Red** `저장_단추가_없으면_role_textbox_가_없고_img_가_그려진다`: `can` 은 `page.view`·`page.publish`, `body_html` 에 `<img src="/api/media/U">`.
    - **Red** `발행_확인_대화상자에_img_가_보인다`
    - `page_update_없이_view_publish_면_편집기_없음_저장_없음_발행_단추가_aria_disabled_아님`
    - `발행본인데_발행_권한이_없으면_렌더러와_403_문구`
  - 오류
    - `422_의_첫_칸에_초점이_가고_본문이면_편집기`: 빈 편집기로 저장 → `body_html` 이 `<p></p>` → 모의 422 「본문을 입력하세요」 → `document.activeElement` 가 편집 영역이고 그 문구가 `aria-describedby` 로 이어진다(Review Focus 1).
    - `409_와_403_은_서버_detail_을_그린다`
    - `저장_401_뒤에도_편집기_내용이_남는다`(Review Focus 2)
  - 발행
    - `발행_미리보기는_지금_폼에_실린_응답의_본문이다`
    - `dirty_면_발행이_aria_disabled_이고_안내를_읽는다`(「저장하지 않은 수정이 있습니다」, 눌러도 요청 0)
    - `발행은_지금_폼에_실린_응답의_updated_at_문자열을_그대로_보낸다`(값 `"2026-11-24T01:02:03.123456Z"`, 보낸 JSON 의 그 칸이 바이트까지 같다)
    - `발행_409_뒤_상세를_다시_읽고_dirty_가_아니면_다시_채운다`
      - 두 번째 상세 응답의 본문·`updated_at` 이 다르다.
      - 두 번째 응답을 붙잡아 둔 동안 「발행」이 `aria-disabled` 다.
      - 응답 뒤에는 알림(409 문구)이 보이고, `document.activeElement` 가 `body` 가 아니라 「발행」 단추다.
      - 다시 연 미리보기가 새 본문이다.
    - `작성_201_뒤_상세로_옮기면_상세_응답으로_채워지고_발행이_막히지_않는다`: `pages/new`·`pages/:id` 두 라우트를 함께 렌더한다. 작성 201 → 상세 응답의 제목·본문이 실리고 「발행」이 `aria-disabled` 가 아니다(「다르게 정한 것」 10).
  - 다시 읽기
    - `창_초점으로는_상세를_다시_읽지_않는다`: `window` `focus` 와 `document` `visibilitychange` 를 보내도 상세 요청이 1 이다. 처음부터 통과하면 `refetchOnWindowFocus` 를 잠시 빼 실패하는지 본다. jsdom 에서 실패하지 않으면 그 사실을 적는다.
    - `저장_204_뒤_상세를_다시_읽어_dirty_가_풀린다`
  - 삭제
    - `삭제_단추가_발행본_발행_권한_없음이면_없고_안내가_보인다`
    - `삭제_설명이_발행본에서_바뀐다`
    - `삭제_성공은_목록으로_가고_알림`
  - `adminCache.test` 에 `PageEditPage`(`/admin/pages/:id`)를 더한다.
- [ ] **Step 2: Red** — `npx vitest run src/features/admin/PageEditPage.test.tsx` → 편집기가 그려짐·`children` 없음 등.
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: Green** — `npx vitest run`, `npm run typecheck`, IME 관문(편집기를 감싼 화면이 바뀌었다).
- [ ] **Step 5: 커밋** `편집기 6: 페이지 수정·발행·발행 취소·삭제 화면, 저장할 수 없으면 공개 렌더러, 발행 미리보기`

### Task 7: 공개·관리자 코드 분할 (스펙 8절, (g))

**Files:**
- Modify: `src/front/src/app/App.tsx`·`App.test.tsx`, `src/front/src/features/auth/AdminGate.tsx`, `src/front/src/features/page/reservedSlugs.test.ts`, `src/front/e2e/editor.spec.ts`

**Interfaces:**
- Produces: `AdminGate.tsx` 의 `export default function AdminTree()` — `<Routes><Route element={<AdminGate />}>{adminRoutes}</Route></Routes>`.
- `App.tsx`
  - `const AdminTree = lazy(() => import("@/features/auth/AdminGate").catch(() => ({ default: AdminLoadFailed })))`
  - 라우트는 `<Route path="/admin/*" element={<Suspense fallback={null}><AdminTree /></Suspense>} />` 다.
  - `App.tsx` 는 `features/admin`·`AdminGate` 를 정적으로 가져오지 않는다.
  - `AdminLoadFailed` 는 `App.tsx` 안 몇 줄이다: h1 「관리자 화면을 불러오지 못했습니다. 새로 고침하세요」(초점). 오류 경계는 두지 않는다.
- `reservedSlugs.test.ts` 의 정규식을 `/<Route path="\/([a-z][a-z0-9-]*)(?:\/\*)?"/g` 로 고친다. 단언 꼴은 그대로다.

- [ ] **Step 1: 실패하는 시험**
  - E2E `공개 화면 JS 에는 편집기 표지가 없고 관리 편집 화면에는 있다`
    - 문맥의 `response` 가운데 `.js` 본문을 모은다.
    - 공개: `offline(page)` + `/api/pages/about` 200 → `/`·`/about`·`/login` 을 연다 → 모은 본문 어디에도 `ProseMirror`·`/tiptap/i` 가 없다.
    - **반대 단언**: 새 문맥(`watch` 를 걸고 끝에 `check()`)에서 `offline(page, can)` → `/admin/pages/new` → 표지가 든 본문이 하나 이상 있다.
  - E2E `관리 묶음을 받지 못하면 대체 화면`: 관리 chunk(`assets/AdminGate-*.js` — 이름 꼴은 빌드 출력으로 확인)를 `route` 로 404 하면 h1 「관리자 화면을 불러오지 못했습니다. 새로 고침하세요」가 보인다.
  - Vitest `App.test.관리자_묶음을_불러오지_못하면_대체_화면`: `AdminGate` 모듈 import 가 거절되게 모의한다(`vi.doMock` 등 방법은 구현 때 확인).
  - 기존 `App.test`·`AdminLayout.test`·`adminCache.test` 가 lazy 때문에 깨지면 `findBy` 로 기다리게만 고친다(단언은 그대로).
- [ ] **Step 2: Red** — `npx playwright test e2e/editor.spec.ts -g "표지"` → 공개 화면 JS 에서 표지가 나와 실패한다(분할 전).
- [ ] **Step 3: 구현** — 위 Interfaces.
- [ ] **Step 4: (g) 빌드 확인** — `npm run build` 출력에 「larger than 500 kB」 경고가 없다. chunk 별 크기(공개·관리, gzip 포함)를 결정 메모에 적는다.
  - 경고가 남으면 `PageEditPage` 안에서 `RichEditor` 를 두 번째 lazy 경계로 뗀다(스펙 8절). 다시 빌드하고, 반대 단언이 여전히 통과하는지 본다.
- [ ] **Step 5: Green** — `npx vitest run`, `npm run typecheck`, `npx playwright test e2e/editor.spec.ts`, `reservedSlugs.test`.
- [ ] **Step 6: 커밋** `편집기 7: 관리자 트리를 lazy 경계 뒤로, 공개 묶음에 편집기 없음(반대 단언 포함), 묶음 실패 대체 화면`

### Task 8: CSP 다시 보기 — m-3·m-4·m-6·m-5 (스펙 9절, 10절)

**Files:**
- Modify: `src/front/src/shared/ui/RichHtml.tsx`·`RichHtml.test.tsx`, `src/front/e2e/pages.spec.ts`, `src/front/e2e/editor.spec.ts`(`PASTE_REPORTS` 가 바뀔 때만), `src/front/vite.config.ts`(m-6 판정에 따라), `tests/html-sinks.test.sh`

- [ ] **Step 1: m-3** — Red `RichHtml.test.같은_html_로_다시_그려도_sanitize_는_한_번`(`vi.spyOn(DOMPurify, "sanitize")`, 같은 `html` 로 `rerender` → 호출 1).
  - 구현: `dangerouslySetInnerHTML={{ __html: useMemo(() => DOMPurify.sanitize(html, CONFIG), [html]) }}` 를 **같은 줄에** 둔다(싱크 검사의 「같은 줄」 규칙 그대로).
  - `bash tests/html-sinks.test.sh` 가 통과하는지 본다.
- [ ] **Step 2: m-4** — m-3 과 Task 2(96줄) 뒤에 `pages.spec.ts` `DOMPurify 겹` 을 돌려 일곱 줄 예외의 경로별 건수를 센다. 「있으면 받는다」를 「경로마다 정확히 n건」 단언으로 바꾼다(줄마다 2건 → 1건일 것, 추정). 세어 본 값을 적는다.
- [ ] **Step 3: m-6 실험**(판정은 E2E 다)
  - `vite.config.ts` 의 `CSP` 와 E2E 의 기대 문자열(`pages.spec.ts` 의 `CSP` — 다른 곳에 복사본이 있으면 `grep "unsafe-inline" src/front/e2e` 로 모두)에 `; style-src-attr 'none'` 을 더한다.
  - `mise run e2e` 전체를 돌린다. 볼 곳은 스펙 9절 ①~④ 다.
  - 판정
    - 붙인 DOM 에서 난 `style-src-attr` 위반이 0 이면 남긴다. 붙이지 않은 문서 파싱 보고만 있으면 1-C1 처럼 경로(붙여넣기는 줄 id) 완전 일치·건수 고정으로 받는다.
    - 하나라도 붙인 DOM 에서 나면 두 곳 모두 되돌리고 증거(시험 이름·경로·`directive`·`blocked`)를 결정 메모에 적는다.
- [ ] **Step 4: m-5** — `html-sinks.test.sh`
  - `NAMES` 에 `setHTMLUnsafe`·`parseHTMLUnsafe`·`createContextualFragment` 를 더한다.
  - `RICH_FORBIDDEN` 에 `ADD_ATTR`·`ALLOW_UNKNOWN_PROTOCOLS`·`USE_PROFILES`·`SAFE_FOR_XML`·`ADD_DATA_URI_TAGS`·`ALLOWED_NAMESPACES`·`PARSER_MEDIA_TYPE`·`WHOLE_DOCUMENT` 를 더한다.
  - 고정 입력: 새 이름마다 위반 파일 한 줄이 1 이다.
  - `ADD_URI_SAFE_ATTR` 만 있는 `RichHtml.tsx` 는 0 이다(`ADD_ATTR` 에 걸리지 않는다).
  - Red 는 이름을 넣기 전의 고정 입력 실패로 본다. 실제 트리는 0 이다.
- [ ] **Step 5: Green** — 셸 시험 전체, `npx vitest run`, `mise run e2e`. 편집기 화면을 지나는 모든 E2E 가 받지 않은 위반 0건이다(완료 기준 7).
- [ ] **Step 6: 커밋** `편집기 8: RichHtml 정화 한 번(useMemo), E2E 예외 건수 고정, style-src-attr 실험, 싱크 검사 금지 이름 보강`

### Task 9: 흐름 E2E — 쓰기에서 공개까지, I-2 화면 (스펙 12.5-2·12.5-4)

**Files:**
- Modify: `src/front/e2e/editor.spec.ts`

- [ ] **Step 1: 시험**(스택)
  - `쓰기에서 공개까지: 도구 막대로 쓴 본문을 방문자가 같은 요소로 본다`(완료 기준 2·3)
    - 설치 → 관리자 로그인 → 메뉴 「페이지」 → 「새 페이지」.
    - 굵게(`ControlOrMeta+b`)·기울임(`ControlOrMeta+i`)·제목 2·제목 3·글머리 목록·번호 목록·인용은 도구 막대로 넣는다. 링크 `/about`·`https://example.com/` 은 링크 입력으로 넣는다.
    - 「만들기」 → `waitForRequest` 로 잡은 저장 요청의 `body_html` 과, 이동한 상세 화면이 받은 `GET /api/admin/pages/{id}` 응답의 `body_html` 이 **바이트까지 같다**.
    - 「발행」 → 대화상자 미리보기(「발행할 본문」)에 `h2`·`h3`·`strong`·`em`·`ul`·`ol`·`blockquote`·두 링크가 있다 → 「확인」.
    - `browser.newContext()` + `watch` 로 `/{슬러그}` 를 연다 → `.rich` 에 같은 요소와 `href` 가 있다.
    - 「발행 취소」 → 방문자가 다시 열면 「찾을 수 없습니다」.
    - 「삭제」 → 목록에 그 제목이 없다.
  - `I-2: 승인자가 연 뒤 본문이 바뀌면 발행이 멈추고 바뀐 본문을 다시 보인다`
    - 역할은 `stack.sql` 로 만든다: 승인자 `admin.access`·`page.view`·`page.publish`, 고치는 사람 `admin.access`·`page.update`.
    - 계정은 관리자 문맥에서 `POST /api/admin/users`, 역할은 `POST /api/admin/users/{id}/roles` 로 만든다(요청 꼴은 `schema.d.ts`, 재인증이 묻히면 `/api/admin/reauth`). 관리자가 초안을 만든다.
    - 승인자 문맥으로 상세를 연다 → 편집기(`role=textbox`)가 없고 `RichHtml` 로 보인다.
    - 고치는 사람 문맥이 API 로 본문을 바꾼다 → 승인자가 「발행」(미리보기는 옛 본문) → 「확인」 → 알림 「그사이 페이지가 바뀌었습니다…」, 본문 영역에 바뀐 본문.
    - 다시 「발행」(미리보기는 바뀐 본문) → 「확인」 → 발행 알림 → 방문자 문맥의 공개 주소에 바뀐 본문.
    - 문맥마다 `watch`·`check()` 한다.
  - Task 8 에서 `style-src-attr` 를 남겼고 이 흐름에서 붙인 DOM 위반이 나면 Task 8 의 판정 규칙대로 되돌리고 적는다.
- [ ] **Step 2: Red** — 처음 돌릴 때 실패 지점을 본다. 처음부터 통과하면 변이를 걸어 실패를 보고 되돌린다.
  - 발행 `send` 의 409 무효화를 빼면 I-2 시험이 「바뀐 본문」에서 실패한다.
  - 저장값을 `getHTML()` 대신 처음 실은 값으로 바꾸면 쓰기 시험이 바이트에서 실패한다.
- [ ] **Step 3: Green** — `mise run e2e` 전체. 시험 수와 Playwright 시간을 적는다(1-C1 기준선 15개 2.5m).
- [ ] **Step 4: 커밋** `편집기 9: 흐름 E2E — 도구 막대로 쓴 본문의 저장·발행·공개, 발행 승인 경쟁의 409 화면`

### Task 10: 문서, 공개 점검, 전체 시험

**Files:**
- Modify: `docs/decisions.md`, `docs/appendix-a.md`, `docs/superpowers/specs/2026-11-25-phase1c2-editor-design.md`(「구현 때 확인」 결과 반영)

- [ ] **Step 1: 결정 문서** — `## Phase 1-C2 구현 중 결정과 보안 점검 (<날짜>)` 절을 쓴다(1-C1 절과 같은 꼴).
  - 구현하며 찾아 고친 것(시험이 잡은 것)
  - 「스펙과 다르게 정한 것」 열의 실제 결과
  - **IME 관문 보고**(Task 1 Step 10 그대로 — 통과/실패와 근거)
  - 「구현 때 확인」의 결과
    - 스펙 17절 2~9: CDP 확정 방법, jsdom 에서 Tiptap, 합성 붙여넣기, 붙이지 않은 문서 파싱 보고, `style-src-attr` 판정과 증거, 묶음 크기
    - `linkHref`·`LinkCheck` 가 갈린 꼴
    - 넣을 때의 판·OSV
  - 그 밖: `updated_at` 소수 자리, `Instant` 바인딩, 413 헤더가 처음부터 있었는지, m-4 건수, E2E·빌드 시간
  - **D86 결과 표**: 스펙 13절 행마다 ✓·시험 이름·한계(1.12 미해결 그대로 — Phase 4 배포 전 필수, 3.7 `frame-ancestors` 는 Phase 4).
  - 「1-C1 최종 검토」 미룬 Minor 표의 m-3·m-4·m-5·m-6·m-7·m-10·m-11 에 「1-C2 에서 고침(Task n)」(m-6 은 판정 결과), m-8 에 「그대로 둔다 — 1-C2 스펙 6.2」, 1-B2 의 (g)·(h) 도 「1-C2 에서 고침」.
  - 최종 검토에 넘길 것(고치지 않은 것, Red 를 따로 보지 못한 시험).
- [ ] **Step 2: 부록 A** — 메모를 단다.
  - FR-401: 화면 1-C2
  - FR-403: 발행에 읽은 `updated_at`, 발행본 삭제에 `page.publish`
  - FR-513: 편집기 1-C2(굵게·기울임·h2·h3·목록·인용·링크, 한글 IME 관문), 사진 1-C3
- [ ] **Step 3: 전체 시험** — `nice -n 19 mise run test` 와 `mise run e2e`. 셸·백엔드·프런트·E2E 의 통과·실패 숫자를 결정 문서에 적는다. 못 돌린 것은 「미실행」과 이유를 적는다.
- [ ] **Step 4: 공개 점검**
  - `bash scripts/check-public.sh $(git diff --name-only main...HEAD)` → 출력 없음, exit 0.
  - `git config user.email` 이 noreply 인지 본다.
  - 스크래치(npm 풀이·OSV 결과·Lexical 시도 흔적)가 남지 않았는지, `package.json` 에 Lexical 이 남지 않았는지(관문 통과 시) 본다.
- [ ] **Step 5: 커밋** `편집기 10: 1-C2 구현 결과와 D86 대조, 부록 A`
- [ ] **Step 6: PR 메모(주 세션에 넘긴다)** — push·PR 은 사용자 승인 뒤 주 세션이 한다. PR 본문에 다음 한 줄을 반드시 넣는다: **「IME 사용자 수동 확인: 미실행 — 사용자 확인 요청」**(맥 한글 입력기 2벌식, Chrome·Safari 에서 I-1~I-5). 사용자가 확인하면 결과로 바꾼다.

---

## 스펙 12절 대조표

| 스펙 | 시험 | Task |
|---|---|---|
| 12.1 IME 첫 Red·대조 단언 Red | `IME 흉내 대조군…`, `IME I-1`~`I-5`, `imeSetComposition` 빼기 변이 | 1 |
| 12.1 링크 판정 | `linkHref.test` 의 `links_json_의_reject_는_모두_null` 외 | 1 |
| 12.1 짝 시험·TrailingNode·백엔드 `<p class="x">` | `editor_json_의_모든_줄에서…`, `끝이_목록인_문서에…`, `편집기_고정_파일의_editor_는_clean_해도_같다` | 2 |
| 12.1 I-2·승인자·뒤집히는 상세 시험 | `읽은_뒤_본문이_바뀌면_발행이_409_…`, `page_view_와_page_publish_만_가진_승인자가…`, `상세는_page_view_만이면_200_권한이_없으면_403` | 4 |
| 12.1 발행본 삭제 | `page_delete_만_가진_역할은_발행된_페이지를_지울_수_없다_403_…`, `page_delete_만으로_초안은_지운다_204`(변이) | 4 |
| 12.1 렌더러 | `저장_단추가_없으면_role_textbox_가_없고_img_가_그려진다`, `발행_확인_대화상자에_img_가_보인다` | 6 |
| 12.1 코드 분할 | E2E 표지 검사(분할 전 Red) | 7 |
| 12.1 m-7·(h)·m-11 | `413_에도_API_보안_헤더가_붙는다`(4), `권한을_바꾸면_/api/me_를_다시_읽는다`(5), `superuser_역할은_assignable_roles_에_없으면_빼기가_없다`(5) | 4·5 |
| 12.2 짝 시험 전부(DOMPurify 짝·`allow` 링크·불변식·W1~W4) | Task 2 Step 1 | 2 |
| 12.3 발행·마이크로초·422·400·예약어·404·상세·삭제 | Task 4 Step 2 | 4 |
| 12.4 `RichEditor` | Task 1 Step 5 | 1 |
| 12.4 `PagesPage`·`ConfirmButton`·쿼리 키 | Task 5 Step 1 | 5 |
| 12.4 `PageEditPage` 전부·쿼리 키 | Task 6 Step 1 | 6 |
| 12.4 `linkHref`·`App` lazy 실패 | Task 1, Task 7 | 1·7 |
| 12.5-1 IME 관문 | Task 1 | 1 |
| 12.5-2 쓰기에서 공개까지 | Task 9 | 9 |
| 12.5-3 붙여넣기 | Task 3 | 3 |
| 12.5-4 I-2 화면 | Task 9 | 9 |
| 12.5-5 링크 입력 거부 | Task 3 | 3 |
| 12.5-6 코드 분할·chunk 404 | Task 7 | 7 |
| 12.5-7 `pages.spec.ts` 발행 호출·m-4·m-6·CSP 0건 | Task 4(발행 호출), Task 8 | 4·8 |
| 12.6 셸 m-5·새 파일 | Task 8 Step 4, Task 1 Step 11 | 1·8 |
