# Phase 1-C2 설계 — 편집기·관리 화면

작성 2026-11-24(파일 이름의 날짜는 계획 순서다. 이 문서의 「확인」은 모두 2026-11-24 에 했다). 사용자 답(`docs/decisions.md` 「Phase 1-C 사용자 결정」)과 역할 에이전트 결정(1-C 결정 Q1~Q22, 리치 텍스트 후속 결정 — **사용자 확인 전**)을 옮겼다. 두 결정이 다르면 리치 텍스트 후속 결정을 따른다. 1-C1 스펙과 「1-C1 최종 검토」 표의 1-C2 몫을 이어받는다. 결정에 답이 없던 것은 16절에 역할 에이전트 결정(2026-11-25, 사용자 확인 전)으로 적었고, 다른 절의 문장도 그 결정대로 고쳤다.
근거: 1-C1 스펙 `2026-11-24-phase1c1-pages-sanitize-design.md`, 리치 텍스트 후속 결정 1절(1-C2 행·완료 기준)·3절(편집기)·5절(CSP), 1-C 결정 Q16·Q19·Q21, 요청서 `request-core.md` `:162`·`:264`·`:290`, ondolith 원문 D11(A-301~303)·D13:266(발행 분리)·D19:2632~2698(A-302·A-303 입출력)·FR-513.
표기는 1-C1 스펙과 같다 — 「확인」은 이 작업에서 파일·레지스트리·배포 소스를 직접 본 사실, 「추정」은 보지 않은 판단, 「구현 때 확인」은 시험·공식 문서로 확정할 것. 빌드·시험은 돌리지 않았다. 구현(2026-11-24~25) 뒤 「구현 때 확인」의 결과를 `→ 구현:` 으로 덧붙였다(근거는 `docs/decisions.md` 「Phase 1-C2 구현 중 결정과 보안 점검」).

## 0. 범위

| 넣는다 | 뺀다(해당 하위 Phase 로) |
|---|---|
| **첫 Task: 한글 IME 관문**(2절). 실패하면 같은 시험을 Lexical 에 건다 | 사진 단추·`Image` 확장·`media` 표·올리기·서빙·180m 측정 — 1-C3 |
| 편집기 Tiptap 3.35.0 — 확장은 서버 허용 목록과 같은 노드·마크·속성만(3절) | 넘어온 m-2(`alt` 입력 규칙) — 1-C3(`alt` 칸이 사진 단추와 함께 생긴다, 10절) |
| 프런트 링크 판정 `linkHref`(4절) — 첫 소비자는 편집기 링크 입력 | 메뉴 링크 부품·`href={` 예외·링크 DB CHECK·공개 메뉴·머리글·P-201 — 1-C4 |
| 편집기 ↔ 서버 짝 시험(5절) | 서버 메타 주입·HTML 404 상태·sitemap·robots — Phase 4(사용자 답 Q2) |
| 관리 화면 A-301 목록·A-302 작성·수정·A-303 발행·발행 취소·삭제(7절), 메뉴 항목을 `can` 으로. 발행 확인 대화상자의 본문 미리보기(`RichHtml`) | 따로 주소를 가진 미리보기 화면 — 만들지 않는다(Q16) |
| API: 발행 요청에 읽은 `updated_at`, 다르면 409(I-2), 상세 GET 을 `page.view` 로, 발행본 삭제에 `page.publish`(6절, 16절). 413 응답의 보안 헤더(m-7) | 자동 저장 — 요청받지 않았다(6.3) |
| 공개·관리자 코드 분할, 넘어온 (g)(8절) | 1-C 끝 메모리 측정 — 1-C4 뒤(1-C1 스펙 11절) |
| 편집기 화면의 CSP 다시 보기, `style-src-attr 'none'` 실험(m-6), 예외 건수 고정(m-4)(9절) | 공개 HTML 의 `frame-ancestors` — Phase 4 CloudFront |
| 1-C1 최종 검토의 1-C2 몫 m-3·m-4·m-5·m-6·m-7·m-8·m-10·m-11, 1-B2 의 (h)(10절) | |

1-C2 가 끝나면: 편집자가 `/admin` 메뉴의 「페이지」에서 목록을 보고, 「새 페이지」에서 제목·주소·본문을 쓴다. 본문은 굵게·기울임·제목 둘·목록 둘·인용·링크를 도구 막대와 단축키로 넣고, 한글 조합 입력이 깨지지 않는다. 저장하면 상세로 가고, 발행 권한이 있으면 확인 대화상자를 거쳐 발행·발행 취소·삭제한다. 발행 승인자(`page.view` + `page.publish`)는 고치지 못하고 본문을 공개 화면과 같은 렌더러로 읽으며, 발행 대화상자에서 발행될 본문을 한 번 더 본다. 승인자가 연 뒤에 다른 사람이 본문을 바꿨으면 발행이 409 로 멈추고 바뀐 내용을 다시 보여 준다. 발행된 페이지는 발행 권한이 있어야 지운다. 방문자가 내려받는 공개 묶음에는 편집기 코드가 없다.

완료 기준(리치 텍스트 후속 결정의 1-C2 기준 넷에 I-2·CSP·분할 반대 단언을 더했다):
1. IME 관문(2절)을 통과한다 — 자동 시험 다섯 장면 모두. 사용자 수동 확인(맥 한글 입력기, Chrome·Safari)은 완료 보고에 결과 또는 「미실행」으로 적는다.
2. A-302 에서 굵게·기울임·h2·h3·글머리 목록·번호 목록·인용·링크를 쓰고 저장·발행하면, 비로그인 방문자가 `/{슬러그}` 에서 같은 요소로 본다(E2E, 빌드 산출물 + CSP).
3. 짝 시험(5절): 고정 파일의 모든 줄에서 편집기 출력 = 서버 `clean` 출력 = DOMPurify 출력(바이트). Chromium 에서 입력해 저장한 본문이 저장 요청과 상세 응답에서 바이트까지 같다.
4. 편집기의 링크 입력이 `javascript:alert(1)`·`//evil.example` 을 받지 않는다(편의). 받더라도 서버가 `href` 를 지운다(방어, 1-C1 X16·X20 그대로).
5. 방문자가 공개 화면에서 내려받는 JS 에 `ProseMirror`·`tiptap` 표지가 없고, 관리 편집 화면에서 내려받는 JS 에는 있다(반대 단언). 빌드에 chunk 500kB 경고가 없다(g).
6. I-2: 승인자가 읽은 `updated_at` 과 다르면 발행이 409 이고 상태가 그대로·기록이 없다. 상세 응답의 값을 그대로 실으면 204 다.
7. 편집기 화면을 지나는 모든 E2E 가 받지 않은 CSP 위반 0건이다.
8. 승인자 역할(`page.view` + `page.publish`)이 상세를 읽고 그 `updated_at` 으로 발행하면 204 다. `page.delete` 만으로는 발행본을 지우지 못한다(403·행과 기록 그대로), 초안은 지운다(16절 2·3).

작업 순서(계획의 뼈대, 순서만 정한다): ① IME 관문 — 편집기 부품(3절 설정 그대로)·`linkHref`·링크 입력·최소 작성 화면·E2E(I-4 장면에 링크 입력이 필요하다) → ② 짝 시험 고정 파일 → ③ 발행 `updated_at`(I-2)·상세 GET `page.view`·발행본 삭제 권한·m-7 → ④ A-301·A-302 수정·A-303 화면(승인자 렌더러·발행 미리보기), (h)·m-11 → ⑤ 코드 분할 → ⑥ CSP 다시 보기(m-6·m-4)·m-3·m-5·m-10 → ⑦ 흐름 E2E·D86 대조.

## 1. 기술

| 항목 | 선택 | 근거 |
|---|---|---|
| 편집기 | **Tiptap 3.35.0**: `@tiptap/react`·`@tiptap/pm`·`@tiptap/starter-kit`·`@tiptap/extension-link`·`@tiptap/extension-list` 다섯(모두 `package-lock.json`, 판 고정 `3.35.0`) | 리치 텍스트 후속 결정 3.1(출력이 의미 HTML 이라 서버 허용 목록과 짝 맞추기가 가장 싸다). npm `latest` 3.35.0(2026-11-20 게시), MIT. `@tiptap/core` 는 `@tiptap/react` 가 다시 내보내고(확인: `src/index.ts` `export * from '@tiptap/core'`) peer 로 lock 에 고정된다. 링크·번호 목록은 속성을 줄이려 `extend` 해야 해 직접 가져온다(3.2) — 둘은 StarterKit 이 같은 판으로 고정해 둔 의존이라 판이 갈리지 않는다 |
| 대안 편집기 | Lexical 0.53.0(`lexical`·`@lexical/react`, MIT, 2026-11-24 갱신) | IME 관문이 실패할 때만(2.5). 지금은 넣지 않는다 |
| IME 흉내 | Playwright 1.65.0 의 CDP 세션(`page.context().newCDPSession(page)`) → `Input.imeSetComposition`·`Input.insertText` | 확인: 설치된 `playwright-core/types/protocol.d.ts` 에 두 메서드와 매개변수(`text`·`selectionStart`·`selectionEnd`·`replacementStart?`·`replacementEnd?`)가 있다. 설명은 「Use imeCommitComposition to commit」이라 적지만 그 메서드는 목록에 없다 — 확정 방법은 구현 때 확인(2.2) |
| 새 서버 의존 | 없음 | |

- **새 의존성은 프런트 다섯이다.** 전이 의존을 합쳐 lock 에 새로 드는 패키지는 51개(이미 있는 `react`·`react-dom`·`@types/react*`·`scheduler`·`csstype` 빼고)다. 스크래치 폴더에서 `npm install --package-lock-only --ignore-scripts` 로 풀어 셌다(설치는 하지 않았다).
  - prosemirror 계열 13개(`prosemirror-view` 1.43.1·`prosemirror-model` 1.25.13 등), `orderedmap`·`rope-sequence`·`w3c-keyname`, `linkifyjs` 4.4.0(Link 가 가져온다. autolink 를 꺼도 모듈은 든다), `fast-equals`·`use-sync-external-store`·`@types/use-sync-external-store`, `@tiptap/extension-*` 스물셋(아래 optional 둘 포함)과 `@tiptap/extensions`·`core`·`pm`·`react`·`starter-kit`.
  - `@tiptap/react` 의 optional 의존 `@tiptap/extension-bubble-menu`·`floating-menu` → `@floating-ui/core`·`dom`·`utils` 도 lock 에 든다. 가져오지 않으므로 묶음에는 들지 않는다(추정 — 8절 표지 검사가 본다).
- **OSV**(`api.osv.dev/v1/querybatch`, 2026-11-24): 새 51개 모두 0건. → 구현: 넣기 직전에 다시 봤다 — 판은 그대로 최신이고, 실제로 lock 에 새로 든 것은 48개(`@floating-ui/core`·`dom`·`utils` 는 radix-ui 경유로 이미 같은 판이 있었다), OSV 0건. `lexical@0.53.0`·`@lexical/react@0.53.0` 도 0건(그 둘만, 전이 의존은 관문이 실패할 때 조회).
  - 과거 이력 중 관련: prosemirror-view GHSA-c8x8(2026-10, 붙여넣기 XSS, 1.42.3 수정). `@tiptap/pm@3.35.0` 이 `prosemirror-view ^1.42.6` 을 요구해 1.43.1 로 풀린다.
  - **`prosemirror-view` 1.43.1 은 2026-11-23 게시, 하루째다.** 붙여넣기 XSS 이력이 있는 패키지라 **넣기 직전에** 그때의 최신 GA 와 OSV 를 다시 본다 — 계획의 Global Constraints 로 넘긴다(Tiptap 다섯도 같은 때 다시 본다).
- **context7 은 이 세션에 도구가 없어 쓰지 못했다.** 판은 npm 레지스트리, API·기본값은 레지스트리 tarball 을 스크래치에 풀어 배포 소스(`package/src`)로 확인했다. 구현 때 context7 이 되면 Tiptap `useEditor`·`useEditorState`·Link 설정 이름을 다시 본다.

배포 소스에서 확인한 것(Tiptap 3.35.0, prosemirror-view 1.43.1) — 3·5·9절이 이것에 기댄다:
- StarterKit 은 Blockquote·Bold·BulletList·Code·CodeBlock·Document·Dropcursor·Gapcursor·HardBreak·Heading·HorizontalRule·Italic·ListItem·ListKeymap·Link·OrderedList·Paragraph·Strike·Text·Underline·UndoRedo·TrailingNode 를 넣고, 각각 `false` 로 뺄 수 있다.
- Link 기본값: `openOnClick: true`, `linkOnPaste: true`, `autolink: true`, `HTMLAttributes: { target: '_blank', rel: 'noopener noreferrer nofollow' }`. 속성은 `href`·`target`·`rel`·`class`·`title` 다섯이고, `parseHTML` 이 없는 넷은 붙여넣은 HTML 의 같은 이름 속성을 그대로 읽는다(`injectExtensionAttributesToParseRule` 의 `getAttribute`). `isAllowedUri` 는 `parseHTML`(거부면 링크가 아니다)·`renderHTML`(거부면 `href=""`)·`setLink`·`toggleLink`(거부면 `false`)·autolink·붙여넣기 여섯 곳에서 불린다. `inclusive()` 는 `autolink` 값이다.
- OrderedList 속성: `start`(`parseInt`, 범위 검사 없음)와 `type`(`type` 속성, `style` 의 `list-style-type`, 첫 `li` 의 `style` 순으로 읽는다). `renderHTML` 은 `start === 1` 이면 빼고, `type` 이 비었거나 `'1'` 이면 뺀다.
- Heading `level` 은 `rendered: false`, ListItem 내용은 `paragraph block*`, Blockquote 는 `block+`.
- TrailingNode 는 플러그인 상태(`init`·`apply`)에 「마지막 노드가 문단이 아니다」를 기억하고, 그 값이 참이면 `appendTransaction` 에서 끝에 빈 문단을 붙인다. 읽은 부분에서 `docChanged` 를 보는 곳은 `apply` 뿐이고 `appendTransaction` 에는 그 조건이 없다 — 싣기 뒤 첫 트랜잭션(누르기만 해도)에서 붙는 것으로 읽힌다(구현 때 확인). 트랜잭션이 없으면 붙지 않는다. → 구현: 끈다(`trailingNode: false`). 켜면 끝이 목록인 문서에 `<p></p>` 가 붙는 것을 변이로 봤다(IME I-3 바이트·`RichEditor.test` 빈 문단 시험이 실패).
- `Editor` 의 `injectCSS` 기본 `true` 가 `<style>` 요소를 넣는다(`createStyleTag`). 끄는 옵션 `injectCSS: false` 가 있다.
- 내용 싣기(`elementFromString`)는 `new window.DOMParser().parseFromString` — 비활성 문서다. `getHTML()` 은 `DOMSerializer` 결과를 `document.implementation.createHTMLDocument()` 의 `div` 에 붙여 `innerHTML` 로 낸다.
- 붙여넣기(prosemirror-view `readHTML`)는 `createHTMLDocument("title")` 의 요소에 `innerHTML`(Trusted Types 감싸기 포함)로 파싱한다 — 비활성 문서다. 화면 쪽 인라인 스타일은 `style.cssText`(CSSOM)로 쓴다(`input.ts`·`viewdesc.ts`).
- 편집기는 자기 DOM 에 `dom.editor = this` 를 단다(`Editor.ts:646`). E2E 가 앱에 시험 고리를 넣지 않고 `getHTML()` 을 읽을 수 있다.

## 2. IME 관문 (첫 Task)

요청서 `:290` 「리치 텍스트 에디터는 한글 IME 조합 입력을 브라우저 시험으로 먼저 확인한 뒤 고른다」와 `:162`·FR-513 「ㅎ→하→한이 브라우저 시험에서 그대로 남는다」를 이 Task 로 지킨다. 원문에는 시험 방법의 기록이 없다(리치 텍스트 후속 결정 3.3).

### 2.1 어디에 띄우나

- 조건: 빌드 산출물 + CSP 위에서 돈다, 배포 묶음에 시험용 라우트를 넣지 않는다, 최종과 같은 확장 설정이다.
- 그래서 관문 Task 는 **최종 부품 `RichEditor`(3절 설정 그대로)와 그것을 쓰는 최소 A-302 작성 화면 `/admin/pages/new`**(제목·주소·본문·「만들기」)를 만든다. 7절 화면의 첫 조각이고 버릴 코드가 아니다.
- 시험은 1-C1 8.5-2 처럼 스택(jar·DB) 없이 `page.route` 로 `/api/health`(running)·`/api/site`·`/api/me`(로그인, `can` 에 `page.create` 등)·`/api/admin`(200)을 주고, 저장 요청 `POST /api/admin/pages` 를 가로채 **보낸 `body_html` 을 읽는다**(201 `{id}` 로 답한다). 그 밖의 `/api/` 는 404.
- 관문 E2E 는 뒤 Task 에서 편집기 설정(3절)이나 도구 막대가 바뀔 때마다 다시 통과해야 한다 — 관문은 한 번 넘고 끝나는 문이 아니라 E2E 묶음에 남는 시험이다.
- 출력을 저장 요청에서 읽는 이유: 편집기에 묶이지 않는다. Lexical 로 바꿔도(2.5) 같은 시험이 그대로 돈다. Tiptap 일 때는 바이트 기대도 본다(2.3).

### 2.2 흉내 — CDP 조합 입력

- 한 글자 = `Input.imeSetComposition` 을 낱자 단계마다(`ㅎ` → `하` → `한`, `selectionStart`·`selectionEnd` 는 글자 수) 부른 뒤 확정한다. 확정은 `Input.insertText` 로 같은 글자를 넣는다(추정 — 조합 중 `insertText` 가 조합을 끝내는지는 구현 때 확인. 안 되면 CDP 의 다른 방법을 찾고 그 근거를 적는다).
- **대조 단언(흉내가 실제로 조합 경로를 탔는가)**: `addInitScript` 로 `document` 의 `compositionstart`·`compositionupdate`·`compositionend` 를 센다. 장면마다 `compositionstart` ≥ 1 이고 `compositionend` 수가 확정한 글자 수와 같아야 한다. 이 단언이 없으면 `insertText` 만으로도 통과하는, 실패할 수 없는 관문이 된다.
- **흉내 대조군**: 같은 흉내를 같은 화면의 제목 `<input>` 에 걸면 값이 정확히 「한글 입력」이다. 대조군이 실패하면 편집기가 아니라 흉내가 틀린 것이다 — 관문 판정을 보류한다.
- **흉내가 안 될 때의 순서와 멈춤**(계획 brief 용)
  1. 기본 headless shell(지금 E2E 의 `chromium` 프로젝트)에서 대조군이 실패하면, 전체 Chromium(`channel: "chromium"`)으로 같은 시험을 돌린다. 채널을 바꾸면 CI 의 `npx playwright install --with-deps chromium` 줄도 그 채널에 맞게 바꾼다(정확한 꼴은 그때 Playwright 문서로 확인).
  2. 둘 다 대조군이 실패하면 **멈추고 보고한다.** 「브라우저 시험으로 먼저 확인한 뒤 고른다」(`:290`)는 요청서 문장이라, 자동 흉내가 불가능하면 수동 확인만으로 고를지는 사용자에게 물을 일이다 — 에이전트가 정하지 않는다.
  3. 대조군이 통과한 뒤의 실패만 편집기 실패다(2.3·2.5).

### 2.3 장면과 통과 기준

장면마다 페이지를 새로 연다. 「한글 입력」 = `한`(ㅎ→하→한) `글`(ㄱ→그→글) 공백(`insertText(" ")`) `입`(ㅇ→이→입) `력`(ㄹ→려→력).

| 장면 | 하는 일 | 보낸 본문의 DOM 판정 | Tiptap 바이트 기대(구현 때 확인) |
|---|---|---|---|
| I-1 빈 문단 | 편집기를 눌러 「한글 입력」, 이어서 키보드로 `x` | 첫 `p` 의 글자가 「한글 입력x」(캐럿이 끝에 있다) | `<p>한글 입력x</p>` |
| I-2 굵게 | `Control+B`(맥 `Meta+B`) 뒤 「한글 입력」 | `strong` 하나, 글자 「한글 입력」, `strong` 밖 글자 없음 | `<p><strong>한글 입력</strong></p>` |
| I-3 목록 안 | 「글머리 목록」 단추 뒤 「한글 입력」 | `ul > li` 하나, 글자 「한글 입력」 | `<ul><li><p>한글 입력</p></li></ul>` |
| I-4 링크 바로 뒤 | 「안」을 넣어 고르고 링크 `/about`, `End`, 「한글 입력」 | `a` 의 글자 「안」·`href` `/about`, 「한글 입력」은 `a` 밖 | `<p><a href="/about">안</a>한글 입력</p>` |
| I-5 받침 넘김 | ㅎ→하→한 다음 조합을 「하」로 바꿔 확정하고 「나」(ㄴ→나) 확정 | 글자 「하나」 | `<p>하나</p>` |

→ 구현: 다섯 장면의 바이트 기대가 실제 출력과 같았다(3절 설정을 고치지 않았다, `docs/decisions.md` 1-C2 Task 1 표). I-4 의 `End` 는 `ArrowRight` 로 바꿨다 — macOS 의 Playwright 는 `End` 를 문서 끝 스크롤로 보내 캐럿이 움직이지 않는다(Linux 도 `ArrowRight` 로 같을 것은 추정).

- I-5 는 결정 3.3 에 없던 장면이다. 앞 글자의 받침이 다음 글자로 넘어가는 꼴이 조합 처리에서 가장 잘 깨지는 자리라(추정) 더했다 — 기준을 좁히는 쪽이다.
- **통과**: 다섯 장면 모두에서 ① DOM 판정 ② Tiptap 바이트 기대 ③ 대조 단언 ④ `pageerror` 0·받지 않은 CSP 위반 0·대화상자 0 이 성립하고, 대조군이 「한글 입력」이다.
- **실패**: 대조군이 통과한 상태에서 ①~④ 중 하나라도 어긋난다(글자가 겹치거나 빠짐, 마크가 새어 나감, 캐럿 위치).
- 바이트 기대(②)는 Tiptap 의 실제 출력을 처음 Red 로 보고 고정한다. 글자가 맞고 바이트만 다르면(예: 빈 문단이 더 붙음) 3절 설정 문제이지 IME 실패가 아니다 — 설정을 고치고 기록한다.

### 2.4 한계와 수동 확인

- CDP 흉내는 실제 OS 입력기가 아니다. Chromium 하나만 본다(E2E 프로젝트가 Chromium 하나).
- **사용자 수동 확인 한 번**: 맥 한글 입력기(2벌식)로 Chrome·Safari 에서 I-1~I-5 를 손으로 친다. 완료 보고에 브라우저별 결과를 적고, 못 했으면 「미실행」이다.

### 2.5 실패하면 — 대안 경로

1. 원인이 3절 설정(끈 확장·입력 규칙 등)으로 고쳐지는지 한 번 본다. ProseMirror 를 고치거나 덧대지 않는다.
2. 고쳐지지 않으면 같은 화면에 Lexical 0.53.0 부품을 붙여 **같은 시험**(같은 장면·DOM 판정·대조 단언, 바이트 기대만 뺀다)을 건다. 그때 Lexical 의 전이 의존 OSV 를 조회한다.
3. Lexical 이 통과하면 멈추고 보고한다 — 3절(확장 대조표)·5절(짝 시험)은 Tiptap 전제라 다시 쓴다. Lexical 은 `class`·`style` 이 붙은 `span` 을 낸다고 기억해(리치 텍스트 후속 결정 3.1, 추정) 내보내기를 덮어써야 할 수 있다.
4. 둘 다 실패하면 멈추고 보고한다. 편집기를 직접 만들지 않는다.

## 3. 편집기 구성 (`features/admin/RichEditor.tsx`)

관리 화면만 쓰므로 `features/admin` 에 둔다(공개 묶음에 들 길을 줄인다). 파일 하나가 확장 목록 `EXTENSIONS`(짝 시험이 같은 값을 가져다 쓴다), 도구 막대, 링크 입력을 맡는다.

### 3.1 확장 = 서버 허용 목록 대조표

| 서버 허용(1-C1 3.2) | Tiptap | 설정 |
|---|---|---|
| `p` | Paragraph | 그대로 |
| `br` | HardBreak | 그대로(`Shift+Enter`) |
| `strong` | Bold | 그대로. 붙여넣은 `b` 도 `strong` 으로 낸다(확인: `parseHTML` 규칙). `font-weight` 스타일도 받는다고 추정 |
| `em` | Italic | 그대로 |
| `h2` `h3` | Heading | `levels: [2, 3]`. 붙여넣은 `h1`·`h4` 는 제목이 아니게 된다(규칙이 `h2`·`h3` 만 만든다) |
| `ul` `li` | BulletList, ListItem | 그대로. `li` 안은 늘 `p` 로 시작한다(`paragraph block*`) |
| `ol` + `start` | OrderedList | `extend` 로 속성을 `start` 하나로 줄이고, 읽을 때 `^[0-9]{1,6}$` 가 아니면 1(서버 규칙과 같다). `type` 은 받지 않는다 |
| `blockquote` | Blockquote | 그대로 |
| `a` + `href` | Link | `extend` 로 속성을 `href` 하나로 줄인다(아래 3.2) |
| `img` + `src` `alt` | **없음** | 1-C3. 편집기에 싣거나 붙여넣은 `img` 는 사라진다(14절 위험) |
| — | Document·Text·UndoRedo·ListKeymap·Gapcursor·Dropcursor | 그대로(출력에 요소를 더하지 않는다) |
| — | Code·CodeBlock·Strike·Underline·HorizontalRule | **끈다** — 서버가 받지 않는 `code`·`pre`·`s`·`u`·`hr` |
| — | TrailingNode | **끈다** — 끝이 문단이 아닌 본문을 열고 아무 트랜잭션이나 나면 빈 `<p></p>` 가 붙어(1절), 열고 저장만 해도 본문이 바뀐다. 출력이 싣기 뒤의 조작에 따라 달라지면 짝 시험이 흔들린다 |

- StarterKit 은 `link: false`·`orderedList: false`·위 「끈다」를 주고, `Link`·`OrderedList` 는 각 패키지에서 가져와 `extend` 한 것을 따로 넣는다.
- `extend` 의 꼴(`addAttributes() { return { href: this.parent?.().href } }` 등)은 구현 때 Tiptap 3 API 로 확인한다. 줄이지 않으면 붙여넣은 `<a title class target rel>`·`<ol type style>` 이 편집기 출력에 남고 서버가 지워, 저장할 때마다 본문이 바뀐다(스크립트 자리는 아니다).
- 편집기는 **방어가 아니다.** 방어는 서버 정화 두 번과 공개 화면의 DOMPurify·CSP 다(1-C1). 편집기를 맞추는 이유는 「저장하면 내가 쓴 그대로」(짝)다.

### 3.2 Link 설정

| 옵션 | 값 | 이유 |
|---|---|---|
| `isAllowedUri` | `(url) => linkHref(url) !== null`(4절) | 프런트 링크 판정(브라우저 URL 파서 포함)을 쓴다. 여섯 곳에서 불리므로 붙여넣기·내용 싣기도 같은 판정을 지난다 |
| `autolink` | `false` | 링크는 사람이 명시해서 만든다(1-C 결정 4.5-1). 그래서 `inclusive()` 도 `false` — 링크 바로 뒤에 친 글자가 링크에 들어가지 않는다(I-4) |
| `linkOnPaste` | `false` | 결정 3.2 에 없던 줄. 기본 `true` 는 글자를 고르고 주소를 붙여 넣으면 링크로 만든다 — autolink 를 끈 이유와 같다 |
| `openOnClick` | `false` | 편집 중 눌러도 떠나지 않는다 |
| `HTMLAttributes` | `{ target: null, rel: null }` | 기본값 `_blank`·`noopener …` 를 지운다. 서버가 받지 않는다(1-C1 3.2) |
| `protocols`·`defaultProtocol` | 기본값 그대로 | `isAllowedUri` 를 덮어써 쓰이지 않는다(`defaultValidate` 를 부르지 않는다) |

### 3.3 도구 막대와 링크 입력

- `role="toolbar"`·`aria-label="서식"`, 단추는 `button` + `aria-pressed`: 굵게·기울임·제목 2·제목 3·글머리 목록·번호 목록·인용·링크. 단축키는 확장 기본(`Mod-b`·`Mod-i` 등). 단추 상태는 `useEditorState` 로 읽는다(Tiptap 3 은 트랜잭션마다 다시 그리지 않는다 — 구현 때 확인).
- **링크 입력은 같은 화면의 칸 하나**(주소 `Input` + 「적용」·「링크 빼기」)다. `window.prompt` 를 쓰지 않는다 — E2E 의 대화상자 0건 단언에 걸리고, 칸 오류를 `aria-describedby` 로 읽힐 수 없다.
  - 「적용」: 고른 글자가 없으면 「링크로 만들 글자를 먼저 고르세요」. `linkHref(url)` 이 `null` 이면 「사용할 수 없는 링크입니다」(1-C4 메뉴의 422 `errors.url` 과 같은 문구) — 칸에 초점·`aria-invalid`. 통과하면 `extendMarkRange('link').setLink({ href: url })`. 저장되는 값은 **입력한 문자열 그대로**다(`linkHref` 가 정규화한 값이 아니다 — 서버 `LinkCheck` 가 같은 문자열을 보게).
  - 「링크 빼기」: `unsetLink`.
- 편집 영역(`editorProps.attributes`): `role="textbox"`·`aria-multiline="true"`·`aria-labelledby`(「본문」 라벨), 칸 오류가 있으면 `aria-invalid`·`aria-describedby`. 라벨을 누르면 편집기에 초점.

### 3.4 내용 싣기·붙여넣기·꺼내기

- 싣기: 편집기는 저장 단추가 있을 때만 띄운다(7.3, 16절 2). 그때 상세 응답의 `body_html`(서버가 내보낼 때 `clean` 한 값)을 `content` 로 준다. 비활성 문서(`DOMParser`)로 파싱하고 스키마 밖은 버린다(확인, 1절). 결정 3.2 의 「구현 때 확인」이 닫힌다.
- 붙여넣기: prosemirror-view 가 비활성 문서에서 파싱해 스키마로 거른다(확인). 표·색·정렬·밑줄·사진은 말없이 빠진다(15절 1). 붙여넣기 공격 표는 12.5.
- 꺼내기: 저장 때 `editor.getHTML()` 을 그대로 `body_html` 로 보낸다. 폼 상태에 본문 사본을 따로 두지 않는다(처음 실은 값과 편집기가 정규화한 값이 달라 엇갈린다).
- 정규화: 서버 값이 편집기 꼴과 다르면(예: API 로 넣은 `<li>x</li>` → `<li><p>x</p></li>`) 열고 저장만 해도 본문이 편집기 꼴로 바뀐다. 한 번 바뀐 뒤에는 그대로다(짝).
- `injectCSS` 는 기본 `true` 로 둔다 — `style-src 'unsafe-inline'`(Radix, 1-C1 8.3) 아래라 위반이 없다(9절이 E2E 로 본다). `ponytail:` `style-src` 에서 `'unsafe-inline'` 을 뺄 때 `injectCSS: false` 로 하고 기본 CSS(`@tiptap/core` `style.ts`, 71줄)를 `index.css` 로 옮긴다.

## 4. 프런트 링크 판정 (`shared/lib/linkHref.ts`)

- `linkHref(url: string): string | null` — 그릴 수 있으면 그릴 값, 아니면 `null`. 이름과 규칙은 1-C 결정 4.3(`menuHref` 를 `linkHref` 로 넓힘). 1-C2 의 소비자는 편집기(3.2·3.3), 1-C4 메뉴 부품이 같은 함수를 쓴다. 공개 화면 `RichHtml` 은 쓰지 않는다.
- 판정
  1. 서버 `LinkCheck.ok` 규칙을 그대로 옮긴다(1-C1 4절: 길이 1~500, 글자 허용 목록, 내부 경로·외부 주소 두 꼴, `%2e` 를 점으로 보는 점 마디 검사, 포트 1~65535). 정규식은 전체 일치(`^…$`, 플래그 없음 — JS 의 `$` 는 `m` 플래그 없이는 끝 개행 앞에서 맞지 않는다. 확인은 시험의 끝 개행 줄이 한다).
  2. 그리고 브라우저 URL 파서로 한 번 더 본다. 내부 경로는 `safeNext(url)` 이 `/` 로 떨어뜨리지 않아야 하고(`t !== "/" || url === "/"`), 그때 `t` 를 낸다. 외부 주소는 `new URL(url)` 이 던지지 않고 `protocol` 이 `http:`·`https:` 이며 그때 `u.href` 를 낸다. **`new URL` 예외는 잡아서 `null`** 로 본다.
- 짝 시험(1-C1 13.3 이 1-C2 로 넘긴 열): `links.json` 의 `reject` 40값은 모두 `null`, `allow` 9값은 모두 `null` 이 아니다. 같은 파일을 읽기만 한다 — 새 열을 더하지 않는다.
- 서버보다 엄격한 곳이 생긴다: 서버가 받는 꼴 가운데 WHATWG 파서가 거부하는 호스트(예: 마지막 마디가 숫자인 `https://a.1/` — IPv4 로 읽혀 실패, 잘못된 `xn--` 마디)는 편집기가 링크로 받지 않는다(추정 — 구현 때 Chromium 에서 확인해 시험 한 줄로 고정한다). → 구현: `https://a.1/` 은 jsdom·Chromium 모두 `new URL` 이 던져 `null` 이다(Vitest 한 줄, E2E 「linkHref 와 LinkCheck 가 갈리는 꼴은 Chromium 에서도 같다」). `xn--` 은 보지 않았다. API 로 그런 링크를 넣은 본문을 편집기로 열면 링크가 글자로 남고, 저장하면 `href` 가 빠진다(14절 위험).

## 5. 편집기 ↔ 서버 짝 시험 (결정 3.4)

1-C1 의 A1 은 그대로 쓸 수 없다 — `img` 가 들어 있고(1-C2 에는 Image 확장이 없다) `li` 안에 `p` 가 없다(편집기 꼴이 아니다).

### 5.1 고정 파일 `src/back/src/test/resources/richtext/editor.json`

- 줄마다 `{id, input, editor}`. `input` 은 편집기에 싣는 HTML, `editor` 는 그것을 실은 편집기의 `getHTML()` 기대값이다. 프런트 시험과 백엔드 시험이 이 파일을 읽는다(프런트는 읽기만, `vectors.json` 과 같은 방식).
- `editor` 열은 **Tiptap 의 실제 출력을 첫 Red 로 보고 고정한다.** 아래 표의 기대는 3절 규칙에서 바로 나오는 것만 적었다. 규칙과 다르게 나오면 3절 설정을 고치거나 멈추고 보고한다 — 출력에 맞춰 서버 허용 목록을 넓히지 않는다.

| id | 입력 | `editor` 기대 |
|---|---|---|
| E1 | 1-C2 허용 HTML: A1 에서 사진 문단을 뺀 것 | A1 과 같되 `<li><p>하나</p></li>`·`<li><p>셋</p></li>` |
| E2 | 빈 문자열 | `<p></p>` |
| E3-1~E3-5 | `<ol start="1"><li>a</li></ol>` 과 `start` 만 `"0"`·`"1234567"`·`"-1"`·`"x"` 로 바꾼 넷(줄마다 id 하나) | E3-1 `<ol><li><p>a</p></li></ol>`, E3-2 `start="0"` 유지, E3-3~E3-5 `start` 없음 |
| E4 | `<ol type="a" style="list-style-type: upper-roman"><li style="list-style-type: lower-alpha">a</li></ol>` | `<ol><li><p>a</p></li></ol>` |
| E5 | `<p><a href="/x" title="t" class="c" target="_blank" rel="opener">x</a></p>` | `<p><a href="/x">x</a></p>` |
| E6 | `<p>a &amp; b &lt; c &gt; d&nbsp;e "f"</p>` | 엔티티 꼴은 실제 출력으로 고정(jsoup·Chromium·jsdom 의 직렬화가 같은지 이 줄이 본다) |
| E7 | `<p><strong><em><a href="https://example.com/?a=1&amp;b=2">x</a></em></strong></p>` | 마크 순서는 실제 출력으로 고정 |
| E8 | `<h1>a</h1><h4>b</h4><p><u>c</u><s>d</s><code>e</code></p><pre>f</pre><hr><p><img src="/api/media/U" alt="g"></p>` | 제목·서식·사진이 빠지고 글자는 남는다. 꼴은 실제 출력으로 고정 |
| E9 | `<p>a<br>b</p><blockquote><p>c</p></blockquote><ul><li><p>d</p><ul><li><p>e</p></li></ul></li></ul>` | 입력 그대로(이미 편집기 꼴) |

→ 구현(jsdom, Tiptap 3.35.0): E6 은 입력 그대로 `<p>a &amp; b &lt; c &gt; d&nbsp;e "f"</p>`(jsoup 도 같다, Chromium 은 미확인). E7 은 링크가 바깥 `<p><a href="https://example.com/?a=1&amp;b=2"><strong><em>x</em></strong></a></p>`. E8 은 `<p>a</p><p>b</p><p>cde</p><p>f</p><p></p>`. 셋 다 규칙과 맞아 3절 설정을 고치지 않았다. jsdom 에서 `new Editor` 가 돌아 프런트 쪽은 Vitest 에 남았다.

### 5.2 세 쪽

- **프런트(Vitest)**: 모든 줄에서 `new Editor({ extensions: EXTENSIONS, content: input }).getHTML() === editor`, 그리고 `DOMPurify.sanitize(editor, RichHtml 설정) === editor`. `links.json` 의 `allow` 값마다 `<p><a href="값">x</a></p>`(속성 이스케이프)를 실어 링크가 남는다 — 서버가 받은 링크가 편집기로 열 때 사라지지 않는지 표의 값으로 본다.
- **백엔드(JUnit, `RichTextTest`)**: 모든 줄에서 `clean(editor) == editor`.
- 둘을 이으면 「편집기 출력 = 서버 정화 출력 = 화면 정화 출력」이다. 편집기 판을 올려 출력이 바뀌면 프런트 시험이 먼저 깨진다.
- **불변식(Vitest)**: `vectors.json` 의 모든 `input` 을 편집기에 실은 `getHTML()` 결과 DOM 에 허용 요소(`img` 를 뺀 열하나) 밖의 요소가 없고, 속성은 `a[href]`·`ol[start]` 뿐이며, `href` 는 `linkHref` 를 지나고 `start` 는 `^[0-9]{1,6}$` 다. 1-C1 `frontInvariant` 에 태그 인자를 넘겨 쓴다(속성 검사는 이 시험에서 한 줄 더한다).
- Vitest 는 jsdom 이라 실제 브라우저가 아니다. jsdom 에서 `new Editor` 가 도는지는 구현 때 확인한다(Tiptap 배포본에 자체 시험 `Editor.test.ts` 가 있다 — 환경은 미확인). 안 되면 같은 표를 E2E 의 `page.evaluate` 로 옮긴다.
- **Chromium 쪽(E2E, 12.5-2)**: 실제 편집기에서 서식을 넣고 저장 → 가로챈 저장 요청의 `body_html` 과 상세 응답의 `body_html`(서버 `clean`)이 바이트까지 같다.

### 5.3 함께 고치는 것

- m-10: 1-C1 W1~W4(멱등을 깨던 입력 넷, 기대는 `RichText.clean` 실제 출력)를 `RichHtml.test.tsx` 에서 `vectors.json` 으로 옮긴다(92 → 96줄). 백엔드 `벡터_표의_모든_줄이_server_와_바이트까지_같다` 가 그 기대를 고정하고, 프런트 불변식·짝·E2E DOMPurify 겹도 그 넷을 돈다.

## 6. API 변경

### 6.1 발행에 읽은 `updated_at` (I-2)

- `POST /api/admin/pages/{id}/publish` 본문 `{updated_at}` — 고정 레코드 하나(`PublishInput(Instant updatedAt)`). 없거나 `null` 이면 422 `errors.updated_at` 「필수입니다」, 형식이 틀리면 400(Jackson, 1-B 관례). 다른 칸은 읽지 않는다.
- `updated_at` 은 **불투명 값**이다. 화면은 상세 응답의 문자열을 그대로 되돌려 보낸다 — `Date` 로 바꾸면 마이크로초가 잘려 늘 409 다. 서버 직렬화는 1-C1 그대로(`Instant`, Jackson 기본 ISO-8601). PostgreSQL `timestamptz` 마이크로초가 문자열 왕복에서 그대로인지는 시험 `상세의_updated_at_을_그대로_실으면_204` 가 확인한다. → 구현: 그대로다(응답 예 `…T00:50:09.332977Z`). 바인딩은 `Instant` 그대로면 pgjdbc 가 형을 추론하지 못해 500 이라 `OffsetDateTime`(UTC)으로 바꿔 싣는다. 범위 밖 `Instant.MAX` 는 500 이 남았다(최종 검토 몫).
- 순서
  1. 슬러그 읽기 → 없으면 404 → 예약어면 409 「이 주소는 시스템 경로와 충돌합니다」(1-C1 그대로, 내용 비교보다 앞 — 예약어 충돌은 다시 읽어도 풀리지 않는다).
  2. `UPDATE pages SET status = 'published', updated_at = now() WHERE id = ? AND status = 'draft' AND updated_at = ?` → 1행이면 204·기록 `page.publish`.
  3. 0행이면 `status, updated_at` 을 다시 읽는다(→ 구현: `status` 만 읽는다 — `updated_at` 은 쓰는 곳이 없다).
     - 없음 → 404(그 사이 지워짐).
     - `status = 'published'` → 204·기록 없음(Q7 그대로 — 이미 발행됨. 요청의 `updated_at` 을 보지 않는다).
     - 초안인데 `updated_at` 이 다름 → **409 「그사이 페이지가 바뀌었습니다. 바뀐 내용을 확인한 뒤 발행하세요」**·기록 없음·상태 그대로.
- 「이미 발행됨」을 409 보다 먼저 보는 이유: 발행이 `updated_at` 을 `now()` 로 바꾸므로(1-C1·원문 `store.sql:33` 그대로), 응답을 잃고 다시 누른 같은 승인자의 요청이 409 가 되지 않게 한다(Q7 「같은 상태로 다시 누르면 2xx」). 남는 틈은 14절 — 다른 승인자가 바뀐 본문을 먼저 발행했으면 이 요청도 204 다.
- 발행 취소는 바꾸지 않는다 — `updated_at` 을 받지 않는다. 문제 있는 페이지를 언제든 바로 내릴 수 있어야 한다(1-C1 5.4).
- 409 뒤 화면은 상세를 다시 읽기만 하고 자동으로 다시 보내지 않는다(7.3).

### 6.1-2 상세 GET 은 `page.view` (16절 1)

- `GET /api/admin/pages/{id}` 의 표시를 `@AdminPermission(PAGE_UPDATE)` → `@AdminPermission(PAGE_VIEW)` 로 바꾼다. 응답·질의는 그대로(내보낼 때 `clean`). 원문 D19:2632 「편집 폼 조회 GET = `page.update`」와 다르다.
- 이유: 발행 승인자 역할(`page.view` + `page.publish`)이 고칠 권한 없이 본문을 읽어야 2인 승인이 선다(16절 1). 넓어지는 노출은 `admin.access` + `page.view` 보유자에게 초안 본문이 보이는 것이다.

### 6.1-3 발행본 삭제는 `page.publish` 도 요구 (16절 3)

- `POST /api/admin/pages/{id}/delete`(`@AdminPermission(PAGE_DELETE)` 그대로). 한 문장으로 판정한다(조회 뒤 비교가 아니다 — 1-C1 17절 1 의 수정과 같은 꼴): 요청자가 `page.publish` 를 가지면 `DELETE FROM pages WHERE id = ?`, 아니면 `DELETE FROM pages WHERE id = ? AND status = 'draft'`.
- 1행 → 204·기록 `page.delete`. 0행 → `EXISTS` 로 가른다: 있으면 **403 「발행된 페이지는 발행 권한이 있어야 지울 수 있습니다」**(기록 없음, 6.2), 없으면 404.
- 이유: 지우기는 공개를 끝내는 동작이라 발행 취소(`page.publish`)와 같은 무게다. `page.delete` 만으로 발행본을 지울 수 있으면 승인 없이 공개 페이지를 내리는 길이 된다. 원문과 다르다(원문은 `page.delete` 하나, D19:2635).
- superuser 는 `ActorQuery` 가 권한 전부로 채우므로 그대로 지운다. 시드 역할 editor·operator 는 두 권한을 다 가져 동작이 바뀌지 않는다.

### 6.2 m-8 — 발행본 수정 403 은 기록하지 않는다

- 정한 것: 그대로 둔다. 원문 D19:2653 「해당 동작 권한 없음 403 — 작업 로그 남기지 않음」, 1-C1 7절 「남기지 않는 것: 403·404·409·422 거절」. 6.1 의 낡은 `updated_at` 409, 6.1-3 의 발행본 삭제 403 도 같다.
- 1-B2 의 `user.denied` 와 다른 이유: 그쪽은 계정·역할(권한 상승) 거절이라 시도 자체가 감사 대상이었다. 페이지 수정은 일상 편집이고, 1-C2 화면은 발행 권한이 없는 사람에게 발행본의 저장 단추를 그리지 않는다(7.3) — 이 403 은 API 를 직접 쓰거나 그 사이 발행된 경쟁에서만 난다.

### 6.3 수정의 동시성 — 나중 저장이 이긴다(그대로)

- 정한 것: 1-C1 17절 3(역할 에이전트 결정)이 답이다 — 「나중 저장이 이긴다. 편집이 사라졌다는 보고가 나오거나 1-C2 화면에 **자동 저장**이 생기면 읽은 `updated_at` 으로 409」. 1-C2 는 자동 저장을 넣지 않는다(요청서·원문 A-302 에 없다 — `request-core.md`·D13·D19 에서 「자동 저장」·「임시 저장」 0건, 확인). 그래서 조건이 서지 않았고 수정 API 는 바뀌지 않는다.
- 화면이 생겨 실제로 부딪힌다(15절 3). 올릴 때의 비용은 작다 — 6.1 과 같은 칼럼·같은 꼴(`AND updated_at = ?`, 0행이면 다시 읽어 404·403·409)이고, 화면은 이미 그 값을 쥐고 있다(7.3). `PagesController.update` 의 `ponytail:` 주석을 「1-C2 는 자동 저장 없음 — 6.3」으로 고친다.

### 6.4 m-7 — 413 응답의 보안 헤더

- 1-C1 의 크기 필터(`RequestSizeLimit`, 맨 앞)가 413 을 직접 쓰면 Spring Security 의 헤더 필터를 지나지 않아 `nosniff`·CSP·`X-Frame-Options`·`Cache-Control` 이 없을 것이다(추정).
- Red 먼저: `RequestSizeLimitTest` 에 「413 에도 API 보안 헤더가 붙는다」 — `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `X-Frame-Options: DENY`, `Cache-Control` 에 `no-store`. 추정이 틀려 처음부터 통과하면 고치지 않고 그 사실을 적는다.
- 고침: 필터가 413 을 쓸 때 그 넷을 직접 단다. CSP 문자열은 `SecurityConfig` 의 상수 하나를 둘이 쓴다. 필터 안의 다른 413 갈래(`chain.doFilter` 를 감싼 쪽)도 같은 함수를 지난다.

### 6.5 API 표(바뀐 행만)

| 경로 | 메서드 | 표시 | 요청 | 응답 |
|---|---|---|---|---|
| `/api/admin/pages/{id}/publish` (A-303) | POST | `@AdminPermission(PAGE_PUBLISH)` 그대로 | `{updated_at}` | 204 / 400 / 401 / 403 / 404 / **409(예약어·낡은 `updated_at`)** / **422(`errors.updated_at`)** / 429 |
| `/api/admin/pages/{id}` (A-302 상세) | GET | **`@AdminPermission(PAGE_VIEW)`**(1-C1 은 `PAGE_UPDATE`) | — | 200 / 401 / 403(`page.view` 없음) / 404 / 429 |
| `/api/admin/pages/{id}/delete` | POST | `@AdminPermission(PAGE_DELETE)` 그대로 | — | 204 / 401 / **403(`page.delete` 없음·CSRF·발행본인데 `page.publish` 없음)** / 404 / 429 |
| 모든 경로의 413 | — | — | — | 보안 헤더 넷이 붙는다 |

- 새 API 는 없다. 처리기의 `@ApiResponse` 설명(발행 요청 본문·409·422, 상세 403 「page.view 권한 없음」, 삭제 403 「발행본인데 page.publish 없음」)을 고치고, `OpenApiFileTest` 가 확인한다. `src/front/openapi.json` 을 다시 내고 `npm run gen:api` 로 `schema.d.ts` 를 다시 만든다.
- 바뀌는 기존 시험: `PagesApiTest` 의 본문 없는 발행 호출(`/publish` 가 든 줄 10개, 2026-11-24 grep), `e2e/pages.spec.ts:58` 의 발행 호출(상세를 읽어 `updated_at` 을 싣는다), `OpenApiFileTest`, `ReauthTargetsTest`(경로·메서드는 그대로라 바뀌지 않을 것 — 구현 때 확인). 뒤집히는 시험 「상세는_편집자_200_page_view_만이면_403」→「상세는_page_view_만이면_200_권한이_없으면_403」(12.1).

## 7. 프런트 화면

### 7.1 라우트·메뉴 (`AdminLayout.tsx` 의 `ADMIN_SCREENS`)

| path | 이름 | perm | menu | 화면 |
|---|---|---|---|---|
| `pages` | 페이지 | `page.view` | ✓ | `PagesPage`(A-301) |
| `pages/new` | 새 페이지 | `page.create` | — | `PageEditPage`(A-302 작성) |
| `pages/:id` | 페이지 편집 | `page.view`(16절 1) | — | `PageEditPage`(A-302 수정 + A-303 단추) |

- 메뉴 항목은 지금처럼 `can[perm] === true` 일 때만 그린다(1-B 관례). 숨김은 편의, 막는 것은 서버다. `pages/:id` 의 `perm` 은 상세 GET 권한(`page.view`, 6.1-2)과 같다 — 이 화면은 고칠 수 없는 사람에게도 열린다(7.3 렌더러 규칙).
- 원문 A-303 은 따로 주소(`/admin/pages/{id}/publish`)를 가진 화면이다. SPA 에서는 발행이 본문 없는 동작이라 A-302 화면의 단추로 둔다(1-B 사용자 상세의 활성·비활성과 같은 꼴, 원문과 다른 곳).
- 쿼리 키: 목록 `["admin", "pages", page]`, 상세 `["admin", "page", id]`. 둘 다 `gcTime: 0`(1-B2 관례 — 로그아웃 뒤 같은 탭의 다른 계정에게 앞 사람의 초안이 응답 전에 보이지 않게). 로그아웃은 `["admin"]` 을 지운다(1-B1).
- 새 파일: `features/admin/PagesPage.tsx`, `features/admin/PageEditPage.tsx`, `features/admin/RichEditor.tsx`, `shared/lib/linkHref.ts`(과 각 시험).

### 7.2 A-301 목록 (`PagesPage`)

- `UsersPage` 와 같은 꼴: h2 「페이지」 초점, 주소의 `?page=`(`parsePage`), 표(제목·주소·상태·수정 시각), 「이전」·「다음」·범위 밖이면 「첫 쪽으로」, `LoadFailure`(401 로그인 링크·403·그 밖). 검색은 없다(원문에 없다).
- 제목은 늘 `/admin/pages/{id}` 링크다(상세가 `page.view` — 16절 1). 주소 칸은 `/{slug}` 글자(링크 아님 — 초안은 공개 주소가 404 다). 상태 「초안」·「발행」.
- `can["page.create"]` 이면 「새 페이지」 링크.

### 7.3 A-302 작성·수정 + A-303 (`PageEditPage`)

- **저장할 수 있나** = `can["page.update"]` 이고 (초안이거나 `can["page.publish"]`). 작성 화면은 `can["page.create"]`.
- **렌더러 규칙(16절 2)**: 저장할 수 있을 때만 폼과 편집기를 띄운다. 그 밖(`page.update` 없음, 또는 발행본인데 `page.publish` 없음)에는 제목·주소를 글자로, 본문을 같은 응답의 `body_html` 을 `RichHtml`(공개 화면과 같은 렌더러, DOMPurify 포함)로 그린다. 편집기는 `img`(Image 확장 없음)와 `linkHref` 가 서버보다 엄격해 떨어지는 링크를 버리므로, 편집기로 보여 주면 승인자가 보지 못한 것이 공개된다(I-2 의 다른 경로).
  - 그래서 관리 chunk 에도 `RichHtml`(DOMPurify)이 든다. 싱크 예외는 그대로 `RichHtml.tsx` 1회다.
  - 발행본인데 발행 권한이 없어 렌더러로 그릴 때는 「발행된 페이지는 발행 권한이 있어야 고칠 수 있습니다」(서버 403 문구)를 함께 보인다.
- 폼(저장할 수 있을 때): 제목(`nameSchema(200)`, 서버 `SiteName.error` 와 같은 문구), 주소(zod — 필수·100자·`^[a-z0-9][a-z0-9-]*$`, 서버 문구 그대로. 예약어는 서버 422 가 알린다 — 목록을 프런트에 두지 않는다), 본문(`RichEditor`). 앞의 둘은 `react-hook-form`, 본문은 편집기가 쥔다(3.4).
- 저장(`useSave`)
  - 작성: `POST /api/admin/pages` → 201 → `["admin", "pages"]` 무효화, `/admin/pages/{id}` 로 이동, 알림 「페이지를 만들었습니다」(알림 주소는 상세).
  - 수정: `POST /api/admin/pages/{id}` → 204 → 상세·목록 무효화, 알림 「저장했습니다」.
  - 422 는 칸 오류이고 첫 칸에 초점이 간다(M-1): `slug`·`title` 은 `form.setFocus`, `body_html` 은 `editor.commands.focus()`. 409(슬러그 중복)·403·404 는 `SaveAlert` 가 서버 `detail` 을 그린다(1-B 관례).
- **폼을 다시 채우는 규칙(I-2 의 화면 쪽)** — 승인자가 보는 본문과 발행이 싣는 `updated_at` 이 늘 짝이어야 한다.
  - 폼과 편집기(또는 렌더러)는 상세 응답의 `updated_at` 이 바뀔 때마다 그 응답으로 다시 채운다. 단 **고치는 중(dirty — 폼 칸이 바뀌었거나 편집기 `onUpdate` 가 났다)이면 채우지 않는다.** 렌더러로 그릴 때는 dirty 가 없다.
  - 발행이 싣는 값은 「지금 폼에 실린 응답」의 `updated_at` 이다(가장 최근 응답이 아니다).
  - 저장이 성공하면 dirty 를 지우고 상세를 다시 읽는다 → 새 `updated_at` 으로 다시 채운다(내용은 보낸 값과 같다 — 짝).
  - **상세 쿼리는 배경 다시 읽기를 끈다**(`refetchOnWindowFocus: false`). 앱의 `QueryClient` 는 `retry` 만 끈다(확인: `main.tsx`). 창 초점 다시 읽기의 기본값이 켜짐인 것은 TanStack Query 의 기억이다(구현 때 확인 — 아래 Vitest 가 본다). → 구현: 켜짐이 맞다(jsdom 에서도 `visibilitychange` 로 돈다 — 끄는 줄을 빼면 Red). 재연결 다시 읽기(`refetchOnReconnect`)도 기본이 켜짐이라 함께 껐다. 켜 두면 승인자가 다른 탭에 다녀온 사이 남이 고친 본문이 알림 없이 폼에 실리고 새 `updated_at` 으로 발행이 204 가 된다 — 409 와 그 알림을 비켜 간다. 다시 채우기는 내 동작(저장 성공·발행 409·발행 취소·발행 성공) 뒤의 다시 읽기에서만 일어난다.
- 단추(「저장」은 폼 제출 단추, 나머지는 `ConfirmButton` — 단추마다 `useSave` 하나, 1-B2 규칙)
  - 「저장」: 「저장할 수 있나」일 때만(위 렌더러 규칙과 같은 조건).
  - 「발행」(초안, `can["page.publish"]`): 확인 대화상자 「발행할까요?」·「방문자가 /{slug} 에서 이 내용을 봅니다」. **설명 아래에 같은 응답의 `body_html` 을 `RichHtml` 로 미리보기**(높이 제한·세로 스크롤, 스크롤 영역은 `tabIndex={0}`·`aria-label="발행할 본문"` 로 키보드로도 내린다). 미리보기의 응답과 발행이 싣는 `updated_at` 의 응답은 같은 것(「지금 폼에 실린 응답」)이다. 본문 `{ updated_at }`. **dirty 이거나 상세를 다시 읽는 중이면 `aria-disabled` 이고 「저장하지 않은 수정이 있습니다」를 `aria-describedby` 로** — 고치는 중에 누르면 화면과 다른 저장본이 나간다. 409 면 `send` 안에서 상세를 무효화한다 → dirty 가 아니므로 바뀐 내용으로 다시 채워지고 알림이 「그사이 페이지가 바뀌었습니다…」다.
  - 「발행 취소」(발행본, `can["page.publish"]`): 확인 「발행을 취소할까요?」·「방문자가 이 주소에서 「찾을 수 없습니다」를 봅니다」.
  - 「삭제」(destructive): `can["page.delete"]` 이고 (초안이거나 `can["page.publish"]`)(6.1-3). 발행본이라 숨기면 그 자리에 「발행을 취소한 뒤 지울 수 있습니다」. 확인 「페이지를 지울까요?」. 발행본이면 설명에 「공개 중인 페이지입니다. 지우면 주소가 바로 「찾을 수 없습니다」가 됩니다」(D19:2647). 성공하면 목록으로 이동, 알림 「페이지를 지웠습니다」. 403 이면 `SaveAlert` 가 서버 `detail` 을 그린다.
  - 재인증 대상이 아니다(D15:545) — 서버가 묻지 않으니 비밀번호 칸이 생기지 않는다.
- `ConfirmButton` 에 둘을 더한다. `ask`(기본값 `destructive`) — 지금은 destructive 만 묻는데, 발행은 묻되 빨간 단추가 아니어야 한다. `children` — 설명(`description`, 문자열) 아래에 그릴 내용(발행 미리보기). 기존 호출은 바뀌지 않는다.
- 401: `useSave` 가 로그인 링크를 보이고 자동으로 옮기지 않는다(입력이 남는다, 1-B 관례). 링크를 누르면 본문을 잃는다(15절 4).

### 7.4 넘어온 화면 고침

- **(h)**: `RolesPage` 의 권한 부여·빼기 성공 때 `["admin", "roles"]` 와 함께 `["me"]` 도 무효화한다 — 자기 역할의 권한을 바꾸면 메뉴·단추(이제 페이지 단추까지)가 바로 바뀐다.
- **m-11**: `UserDetailPage` 의 「빼기」 단추를, 역할이 superuser 역할(`is_superuser`)이면 그 역할이 `assignable_roles` 에 있을 때만 그린다. 서버는 비-superuser 에게 superuser 역할을 `assignable_roles` 에서 이미 뺀다(1-C1 M-12) — R3 이 늘 거절하는 단추를 그리지 않는다. superuser 역할이 `is_assignable` 이 아니면 superuser 요청자에게도 단추가 사라진다 — 구현 때 V2 시드로 확인한다. → 구현: 시드는 V1 이다(`V1__init.sql` 의 admin 은 `is_superuser=true, is_assignable=true`, V2~V5 에 바꾸는 줄 없음) — superuser 요청자에게는 「빼기」가 남는다.

## 8. 코드 분할 (Q21, 넘어온 (g))

- 경계 하나: 관리자 트리. `App.tsx` 의 `/admin` 라우트를 `path="/admin/*"` 로 바꾸고 `element` 를 `<Suspense fallback={null}>` 안의 lazy 부품 하나로 둔다. lazy 모듈은 `features/auth/AdminGate.tsx` 의 기본 내보내기 `AdminTree` — `<Routes><Route element={<AdminGate />}>{adminRoutes}</Route></Routes>`. `AdminGate`·`AdminLayout`·관리 화면·Radix 대화상자·Tiptap 이 모두 그 아래다. `/login` 은 작아서 공개 쪽에 둔다(Q21).
- **불러오기 실패**: 배포 뒤 옛 chunk 가 404 면 lazy 가 렌더 중에 던지고, 오류 경계가 없어 앱 전체가 빈 화면이 된다(1-C1 8.2 의 `ponytail:` 경계 없음). `lazy(() => import(…).catch(() => ({ default: AdminLoadFailed })))` 로 받는다 — `AdminLoadFailed` 는 `App.tsx` 의 몇 줄, h1 「관리자 화면을 불러오지 못했습니다. 새로 고침하세요」. 오류 경계는 여전히 두지 않는다.
- 바뀌는 기존 시험: `reservedSlugs.test.ts` 의 라우트 정규식이 `path="/admin"` 꼴만 읽는다 — `(?:\/\*)?` 를 허용하게 한 줄 고친다(단언 꼴은 그대로). `AdminLayout.test`·`adminCache.test` 는 `adminRoutes`·화면을 직접 렌더해 바뀌지 않을 것(구현 때 확인). → 구현: 코드 분할 커밋(793a100·9ab15c9)에서 두 파일은 바뀌지 않았다(메뉴·쿼리 키 때문에 Task 5·6 이 고쳤다).
- **검사 — 방문자가 실제로 내려받는 JS**(E2E, 빌드 산출물 위. 셸 시험 자리가 아니다 — `tests/*.test.sh` 는 고정 입력·Docker 없음이고 이 검사는 `dist` 가 필요하다)
  - 공개 화면(`/`, `/about`, `/login`, 스택 없이 `page.route`)을 열며 응답된 모든 `.js` 본문에 `ProseMirror`(prosemirror-view 가 편집 DOM 에 다는 클래스 이름 문자열)·`tiptap`(대소문자 무관, Tiptap 의 오류·경고 문구)이 없다.
  - **반대 단언**: 같은 시험에서 `/admin/pages/new` 를 열면 내려받은 JS 가운데 그 표지가 든 것이 있다. 이것이 없으면 표지가 압축에서 사라져도 공허하게 통과한다.
  - 표지 문자열이 압축 뒤에도 남는지는 구현 때 확인(반대 단언이 확인한다).
- (g) 500kB 경고: 빌드 출력에서 본다(완료 보고에 chunk 크기를 적는다). 관리자 chunk 가 편집기 때문에 500kB 를 넘어 경고가 남으면 `RichEditor` 를 두 번째 lazy 경계로 뗀다(Q21 「틀렸을 때 비용: 더 쪼갠다」). 크기는 재지 않았다.
- → 구현
  - 관리 chunk 는 473.76 kB 라 `RichEditor` 경계는 두지 않았다.
  - 관리자 트리만 떼면 **공개** chunk 가 502.58 kB 로 경고가 남아, `InstallPage` 도 lazy 로 뗐다(경계 둘 — 이 절의 「경계 하나」와 다르다). 공개 `index-*.js` 497.74 kB, 경고 없음, 여유 약 2.3 kB. 설치 chunk 를 받지 못하면 빈 화면이다(`ponytail:`).
  - 관리 chunk 이름은 `assets/AdminGate-<hash>.js` 이고, 표지 문자열은 압축 뒤에도 남는다(반대 단언 통과).
  - `/admin/*` 아래 없는 주소는 `AdminTree` 의 문 밖 `path="*"` 가 「찾을 수 없습니다」로 받는다.

## 9. CSP 다시 보기 (편집기 화면)

- 정책은 1-C1 8.3 그대로 시작한다: `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-src 'none'`.
- → 구현(m-6 판정 뒤 최종 정책, `vite.config.ts`·`e2e/pages.spec.ts`): `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-src 'none'; style-src-attr 'none'`.
- 편집기 화면을 지나는 E2E(2절 관문, 12.5)가 모두 1-C1 의 `guard`(받지 않은 위반 0·대화상자 0) 아래에서 돈다. 기대: 위반 0 — `contenteditable` 은 CSP 대상이 아니고, Tiptap 의 `<style>`(`injectCSS`)은 `style-src 'unsafe-inline'` 이 받으며, ProseMirror 의 인라인 스타일은 CSSOM(`style.cssText`)이라 `style-src-attr` 대상이 아니다(확인: 소스, 브라우저 동작은 추정).
- **m-6 실험 — `style-src-attr 'none'`**
  - 판정은 소스 grep 이 아니라 E2E 다. 걸리는 것은 마크업의 `style=` 속성(파싱·`setAttribute`)이지 CSSOM 이 아니다.
  - 순서: 정책에 `style-src-attr 'none'` 을 더한 빌드로 전체 E2E 를 돌린다. 볼 곳은 ① 기존 관리 화면(Radix 는 React `style` 속성 — React 는 CSSOM 으로 쓴다, 추정) ② 편집기 ③ `style=` 이 든 HTML 을 편집기에 붙여넣기(12.5-3) ④ DOMPurify 겹(X14 의 `style="color:red"` — 1-C1 에서 Chrome 은 붙이지 않은 문서를 파싱만 해도 `script-src-attr` 을 보고했다. `style-src-attr` 도 그럴 수 있다, 구현 때 확인).
  - 판정: 실제 문서(붙인 DOM)에서 난 `style-src-attr` 위반이 0이면 더한다(플러그인과 E2E 기대 문자열 두 곳, 붙이지 않은 문서 파싱 보고만 있으면 1-C1 8.5-2 처럼 경로 완전 일치·건수 고정으로 받는다). 실제 문서에서 하나라도 나면 더하지 않고 증거(경로·지시어·시험)를 결정 문서에 적는다.
- 관리 화면도 `RichHtml` 을 쓴다(7.3 렌더러·발행 미리보기). 싣는 값은 서버가 내보낼 때 정화한 본문이라 처리기 속성이 없어, 1-C1 8.5-2 의 「붙이지 않은 문서 파싱 보고」가 관리 화면에서는 나지 않을 것이다(추정 — 모든 E2E 의 `guard` 가 본다).
- → 구현(m-6): 붙인 DOM 의 `style-src-attr` 위반 0 이라 더했다. 흐름 E2E(수정 화면·발행 미리보기·렌더러·공개 화면)에서도 0 이라 유지했다. 붙이지 않은 문서 파싱 보고는 붙여넣기(`createHTMLDocument`)에서만 2건(X14, 「표·색·밑줄」) 났고, 경로·지시어·건수로 받는다. `DOMParser`(DOMPurify 겹)는 `style-src-attr` 를 보고하지 않았다. 관리 화면 `RichHtml` 의 파싱 보고는 0 이었다(추정이 맞았다).
- **m-4 — 예외 건수 고정**: 1-C1 8.5-2 의 일곱 줄 예외(`script-src-attr` 여섯, `base-uri` 하나)를 「있으면 받는다」에서 「경로마다 정확히 n건」으로 바꾼다. 지금의 줄마다 2건은 `RichHtml` 의 재렌더 때문이다(검토자 확인). m-3(`useMemo`)을 먼저 넣으면 1건이 될 것이다(추정) — m-3 뒤에 세어 고정한다. → 구현: 경로마다 정확히 1건으로 고정했다. 다만 `useMemo` 를 빼도 1건이라 「줄마다 2건」은 지금 트리에서 재현되지 않았다.
- Trusted Types(`require-trusted-types-for 'script'`)는 넣지 않는다. 편집기는 관리 화면에만 있고, prosemirror-view 는 Trusted Types 감싸기를 갖고 있다(확인) — 공개 화면에 걸지는 리치 텍스트 후속 결정 5절의 `ponytail:` 그대로 남긴다.

## 10. 넘어온 것 — 처분

| 항목 | 1-C2 에서 | 시험 |
|---|---|---|
| I-2 발행 승인 분리의 틈 | 6.1·7.3 | `PagesApiTest`(12.3), E2E 12.5-4 |
| m-2 `alt` 의 닫는 꼴을 DOMPurify 가 지운다 | **1-C3 으로 다시 넘긴다** — 1-C2 에는 `alt` 를 만드는 길(사진 단추)이 없다. 1-C3 의 `alt` 칸 규칙에서 정한다 | — |
| m-3 `RichHtml` 이 렌더마다 `sanitize` | `useMemo` 를 **같은 식 안에**: `dangerouslySetInnerHTML={{ __html: useMemo(() => DOMPurify.sanitize(html, CONFIG), [html]) }}`. 따로 변수로 빼면 싱크 검사의 「같은 줄」 규칙(1-C1 8.4)을 바꿔야 한다 | `RichHtml.test` 「같은 html 로 다시 그려도 sanitize 는 한 번」(`vi.spyOn`) |
| m-4 E2E 예외 건수 | 9절 | `pages.spec.ts` |
| m-5 싱크 검사 금지 이름 보강 | 전역 `NAMES` 에 `setHTMLUnsafe`·`parseHTMLUnsafe`·`createContextualFragment`. `RichHtml.tsx` 금지(`RICH_FORBIDDEN`)에 `ADD_ATTR`·`ALLOW_UNKNOWN_PROTOCOLS`·`USE_PROFILES`·`SAFE_FOR_XML`·`ADD_DATA_URI_TAGS`·`ALLOWED_NAMESPACES`·`PARSER_MEDIA_TYPE`·`WHOLE_DOCUMENT`. 지금 쓰는 `ADD_URI_SAFE_ATTR` 는 `grep -F ADD_ATTR` 에 걸리지 않는다(문자열이 다르다) | `tests/html-sinks.test.sh` 고정 입력에 새 이름마다 위반 파일 한 줄 |
| m-6 `style-src-attr 'none'` | 9절 실험 | E2E 전체 |
| m-7 413 보안 헤더 | 6.4 | `RequestSizeLimitTest` |
| m-8 발행본 수정 403 기록 | 기록하지 않는다(6.2) | 기존 `발행_권한_없는_역할은_발행된_페이지를_고칠_수_없다_403` 의 「기록 없음」 그대로 |
| m-10 W1~W4 기대가 프런트에만 | `vectors.json` 으로 옮긴다(5.3) | `RichTextTest`·`RichHtml.test` |
| m-11 비-superuser 에게 admin 「빼기」 | 7.4 | `UserDetailPage.test` |
| (g) chunk 500kB | 8절 | E2E 표지 검사, 빌드 출력 |
| (h) `["me"]` 무효화 | 7.4 | `RolesPage.test` 「권한을 바꾸면 /api/me 를 다시 읽는다」 |
| 1-C1 16절 4·17절 3 동시 편집 | 6.3(그대로 — 자동 저장 없음) | — |
| m-1·m-9·m-13·m-14 | 1-C1 최종 검토 표의 갈 곳 그대로(측정·한계·Phase 1 끝·멈출 때) | — |

## 11. 다른 문서에서 고친 것

- **이 문서의 커밋들**: `docs/decisions.md` — 새 절 「Phase 1-C2 스펙」(정한 것, 다른 곳, 결정 셋), 「1-C1 최종 검토」 표의 m-2 갈 곳을 1-C3 으로. 1-C1 스펙 5.3·6절 표의 상세 GET·삭제 줄에 「1-C2 고침」 표시.
- **구현 때 고칠 것**: `PagesController.update` 의 동시 편집 `ponytail:` 주석(6.3), `playwright.config.ts` 머리 주석(편집기·CDP 를 쓴다는 한 줄은 넣지 않는다 — 바뀌는 것이 없다), `CLAUDE.md` 는 고치지 않는다.
- 1-C1 스펙은 위 표시만 고친다. 이 스펙이 1-C1 의 「1-C2 에서」 문장(16절 2·4, 17절 3, 8.2 관리 화면, 13.3 프런트 열)을 대신한다.

## 12. 검증

### 12.1 첫 Red

- **IME 관문(첫 Task)**: 2.3 의 E2E 가 편집기 부품이 없어 실패한다 → 부품·최소 화면을 넣는다. 대조 단언이 옳은 이유로 실패하는지 한 번 본다 — `imeSetComposition` 을 빼고 `insertText` 만 보내면 「`compositionstart` ≥ 1」 에서 실패해야 한다.
- **링크 판정**: `linkHref.test.ts` 가 `links.json` 을 읽는다. 입력을 그대로 내는 껍데기면 `javascript:alert(1)` 줄이 실패한다.
- **짝 시험**: `RichEditor.test.tsx` 가 `editor.json` 을 읽는다. 3.1 의 `extend` 를 빼면 E4·E5 가 실패한다. `editor.json` 은 싣기만 해 TrailingNode 를 걸지 못하므로 따로 하나 — 끝이 목록인 문서를 싣고 트랜잭션 하나(선택 이동)를 낸 뒤 `getHTML()` 끝에 `<p></p>` 가 없다(`trailingNode` 를 켜면 실패). 백엔드는 `RichTextTest` 에 `editor` 열 시험을 더한다 — 고정 파일에 일부러 `<p class="x">` 를 넣으면 실패하는 것을 확인하고 되돌린다.
- **I-2**: `PagesApiTest.읽은_뒤_본문이_바뀌면_발행이_409_이고_상태와_기록이_그대로` — 지금은 본문을 읽지 않아 204 로 실패한다.
- **승인자(16절 1)**: `PagesApiTest.page_view_와_page_publish_만_가진_승인자가_상세를_읽고_그_updated_at_으로_발행하면_204` — 지금은 상세 GET 이 403 이라 실패한다. 뒤집히는 기존 시험: 「상세는_편집자_200_page_view_만이면_403」→「상세는_page_view_만이면_200_권한이_없으면_403」. 단언 완화가 아니라 결정으로 동작이 바뀐 것이고, 권한이 없으면 403 인 단언은 남는다.
- **발행본 삭제(16절 3)**: `PagesApiTest.page_delete_만_가진_역할은_발행된_페이지를_지울_수_없다_403_이고_행과_기록이_그대로`(지금은 204 로 실패), `page_delete_만으로_초안은_지운다_204`(판정이 초안까지 막지 않는지 — 처음부터 통과하면 변이로 Red 를 본다: 조건을 `status = 'published'` 로 뒤집어 실패 확인).
- **렌더러(16절 2, Vitest)**: `PageEditPage.test` 「저장 단추가 없으면 role=textbox 가 없고 img 가 그려진다」(응답 `body_html` 에 `<img src="/api/media/U">`, `page.view`+`page.publish` 의 `can` — 지금은 편집기가 그려져 실패), 「발행 확인 대화상자에 img 가 보인다」(`ConfirmButton` 의 `children` 이 없어 실패).
- **코드 분할**: 8절 E2E 가 지금 공개 화면 JS 에서 표지를 찾아 실패한다(편집기를 넣은 뒤, 분할 전).
- **m-7**: 6.4. **(h)**: `RolesPage.test` 가 `/api/me` 두 번째 요청을 기다리다 실패한다. **m-11**: `UserDetailPage.test` 가 비-superuser 응답(시험 값)에서 admin 「빼기」를 찾아 실패한다.

### 12.2 짝 시험

5절. 프런트 `RichEditor.test.tsx`(줄마다 편집기 출력·DOMPurify 짝·`allow` 링크 유지·`vectors.json` 불변식), 백엔드 `RichTextTest`(`editor` 열 `clean` 짝, W1~W4 이동).

### 12.3 백엔드(Testcontainers)

- 승인자는 시험용 역할 `admin.access` + `page.view` + `page.publish`(`page.update` 없음)로 둔다 — `page.update` 를 가지면 16절 1 이 검증되지 않는다.
- 발행(6.1): 승인자가 상세의 `updated_at` 을 그대로 실으면 204·`page.publish` 한 줄. 다른 초안에서: 승인자가 상세를 읽고, `page.update` 만 가진 사람이 그 초안을 고친 뒤, 승인자가 읽은 값으로 발행 → 409·상태 초안·기록 없음. 같은 값으로 두 번 → 둘째는 204·기록이 늘지 않는다(Q7). `updated_at` 없음·`null` → 422 `errors.updated_at`, 형식 불량 → 400, 다른 칸이 실려도 무시. 예약어 슬러그 + 옛 값 → 409 「시스템 경로」 문구(예약어가 먼저). 없는 id → 404. 지워진 뒤 → 404.
- 마이크로초: `updated_at` 을 `now()` 가 낸 값 그대로 왕복해 204(위 첫 줄이 겸한다). 응답 문자열의 소수 자리는 구현 때 적는다.
- 상세(6.1-2): `page.view` 만이면 200, `admin.access` 만이면 403. 삭제(6.1-3): `page.delete` 만이면 발행본 403·행 그대로·작업 로그가 늘지 않는다, 초안 204·`page.delete` 한 줄. 편집자(두 권한)는 발행본도 204. 없는 id 404(두 갈래 모두).
- m-7: 6.4. 바뀌는 기존 시험: 6.5 끝.

### 12.4 프런트(Vitest)

- `RichEditor`: 도구 막대 단추마다 `aria-pressed` 가 상태를 따른다, 링크 입력이 `javascript:`·`//evil.example` 에 「사용할 수 없는 링크입니다」·`aria-invalid` 이고 링크를 만들지 않는다, 고른 글자가 없으면 안내, 저장값은 입력 문자열 그대로.
- `PagesPage`: 쪽 링크·「새 페이지」(`can`)·제목 링크(`can`)·`LoadFailure` 셋·`gcTime 0`.
- `PageEditPage`: 422 의 첫 칸 초점(`body_html` 이면 편집기), 409·403 `detail`, 저장할 수 없으면(`page.update` 없음·발행본인데 `page.publish` 없음) 편집기(`role=textbox`)가 없고 `RichHtml` 이 `img` 까지 그린다, 발행 확인 대화상자의 미리보기가 같은 응답의 본문이다, 삭제 단추가 발행본 + 발행 권한 없음이면 없고 「발행을 취소한 뒤 지울 수 있습니다」, dirty 면 발행 `aria-disabled`, 발행이 지금 폼에 실린 응답의 `updated_at` 문자열을 그대로 보낸다, 발행 409 뒤 상세를 다시 읽고 dirty 가 아니면 다시 채운다, 창 초점(`focus` 이벤트)으로는 상세를 다시 읽지 않는다, 삭제 설명이 발행본에서 바뀐다.
- `ConfirmButton`: `ask` 면 destructive 가 아니어도 묻는다, `children` 이 설명 아래에 그려진다. 기존 시험 그대로.
- `linkHref`: 4절. `App`: lazy 실패 대체 화면(`import` 를 거절하는 모의).
- 쿼리 키: 새 화면 둘을 `adminCache.test` 에 더한다(로그아웃 뒤 `["admin"]` 아래가 지워진다).

### 12.5 E2E (빌드 산출물 + CSP, 새 파일 `e2e/editor.spec.ts`)

1. **IME 관문**(2절): 스택 없이, 다섯 장면 + 대조군.
2. **쓰기에서 공개까지**(완료 기준 2·3): 설치 → 관리자 로그인 → 메뉴 「페이지」 → 「새 페이지」 → 도구 막대·단축키로 굵게·기울임·h2·h3·두 목록·인용·링크(`/about`, `https://example.com/`) → 만들기. 저장 요청 `body_html` 과 상세 응답 `body_html` 이 바이트까지 같다(Chromium 짝). 발행(확인 대화상자 — 미리보기에 같은 요소가 보인다) → 새 비로그인 문맥에서 `/{슬러그}` 에 같은 요소가 있다. 발행 취소 → 「찾을 수 없습니다」. 삭제 → 목록에 없다.
3. **붙여넣기**(실제 Chromium, 빌드 산출물 + CSP): 스택 없이, 편집기에 `vectors.json` 의 입력(`alert(1)` → `window.__xss=1`)을 `ClipboardEvent('paste')` + `DataTransfer('text/html')` 로 하나씩 붙여넣고, 편집기 `getHTML()` 이 5.2 불변식을 지나며 **`__xss` 0·대화상자 0·`script-src`·`script-src-elem` 위반 0**(1-C1 8.5-2 와 같은 세 단언). 이 공격면은 관리자 출처에서 로그인한 관리자에게 일어난다 — 붙여넣기 XSS 하나가 그 관리자의 권한으로 관리 API 를 부르므로 공개 화면의 XSS 보다 대가가 크다(prosemirror-view GHSA-c8x8 이 이 종류였다). 합성 붙여넣기 이벤트가 prosemirror-view 에 닿는지는 구현 때 확인(안 닿으면 CDP `Input.dispatchKeyEvent` + 클립보드 권한으로 바꾼다). CSP 는 1-C1 8.5-2 처럼 끝 단언이 `script-src`·`script-src-elem` 0건이고, 붙이지 않은 문서 파싱 보고가 나면 경로·건수를 고정해 받는다.
4. **I-2 화면**: 승인자 문맥(`page.view` + `page.publish`, `page.update` 없음 — 편집기가 아니라 `RichHtml` 로 본다)이 상세 화면을 연 뒤, 다른 문맥(`page.update` 만 가진 역할)이 API 로 본문을 바꾼다 → 승인자가 「발행」(미리보기는 옛 본문) → 알림 「그사이 페이지가 바뀌었습니다…」, 본문 영역에 바뀐 본문이 실린다, 다시 「발행」(미리보기는 바뀐 본문) → 204. 공개 주소에 바뀐 본문.
5. **링크 입력 거부**(완료 기준 4): `javascript:alert(1)`·`//evil.example` → 칸 오류, 저장 요청 `body_html` 에 `href` 가 없다.
6. **코드 분할**(8절): 표지 검사와 반대 단언. 관리 chunk 를 `page.route` 로 404 하면 「관리자 화면을 불러오지 못했습니다」(chunk 이름 꼴은 구현 때 확인).
7. 기존 시험: `pages.spec.ts` 발행 호출(6.5), m-4 건수 고정, m-6 실험(9절). 모든 시험의 CSP 위반 0건(받은 예외는 각 시험의 `allow`).

### 12.6 셸

- `tests/html-sinks.test.sh`: m-5 의 새 이름. 새 파일(`RichEditor.tsx` 등)이 금지 이름을 쓰지 않는다 — 편집기 안의 `innerHTML` 은 `node_modules` 몫이라 보지 않는다(1-C1 8.4).

## 13. D86 대조 — 1-C2 행

| D86 | 1-C2 에서 |
|---|---|
| 1.12 프록시 뒤 IP | 미해결 그대로 — Phase 4 배포 전 필수. 새 쓰기 경로가 없다 |
| 2.1 처리기 권한·기동 점검 | 새 처리기 없음. 발행은 `@AdminPermission(PAGE_PUBLISH)` 그대로, 상세는 `PAGE_VIEW` 로 바뀐다(6.1-2), 발행본 삭제는 처리기 안에서 `page.publish` 를 한 문장으로 본다(6.1-3). 화면의 `can` 숨김은 편의 |
| 2.5 대량 할당 | 발행 본문은 고정 레코드 `{updated_at}` 하나 |
| 2.8 관리자 60/분 | 그대로(`/api/admin/**`) |
| 3.1 CSRF | 그대로. 화면은 `api` 클라이언트(1-A)로 보낸다 |
| 3.3~3.5 이스케이프 우회 지점 | 새 싱크 없음 — `dangerouslySetInnerHTML` 은 `RichHtml` 1회 그대로(m-3 도 같은 줄). 편집기의 HTML 파싱은 라이브러리 안의 비활성 문서(`DOMParser`·`createHTMLDocument`)이고 결과는 스키마를 지난다(확인: 소스). 싣는 값은 서버가 내보낼 때 정화한 값 |
| 3.6 리치 텍스트 정화 | 편집기 출력 = 서버 `clean` 출력(5절). 서버 두 겹·DOMPurify 는 1-C1 그대로 |
| 3.7·3.8 보안 헤더·CSP | 편집기 화면 E2E 위반 0(9절), `style-src-attr` 실험(m-6), 413 헤더(m-7) |
| 3.10 오류가 내부를 말하지 않는다 | 새 409 `detail` 은 코드 상수. 화면은 서버 `detail` 만 그린다(403·404·409) |
| 4.1 SQL 바인딩 | 발행 문장의 `updated_at` 도 바인딩 |
| 7.1 요청당 고정 질의 수 | 발행: 슬러그 1 + 갱신 1 (+ 0행이면 다시 읽기 1). 삭제: 1 (+ 0행이면 `EXISTS` 1). 공개 조회 그대로 1 |

## 14. 위험

| 위험 | 대응 |
|---|---|
| CDP 흉내가 실제 입력기와 달라 관문이 실제 사용의 깨짐을 놓친다 | 대조 단언·대조군(2.2), 사용자 수동 확인(2.4). 못 하면 「미실행」 |
| Tiptap 판을 올리면 출력이 바뀐다 | 짝 시험의 프런트 쪽이 먼저 깨진다(5.2). 허용 목록을 넓혀 맞추지 않는다 |
| API 로 넣은 `img`·편집기 꼴이 아닌 HTML 을 편집기로 열어 저장하면 사진이 빠지고 꼴이 바뀐다 | 1-C2 에는 사진을 넣는 화면이 없다(API 만). 1-C3 이 Image 확장을 더할 때 닫힌다. 그 전에 사진이 든 페이지를 화면에서 고치면 사진이 사라진다 — 1-C3 전 배포가 없다는 전제(1-C 결정 0절, 추정) |
| `linkHref` 가 서버 `LinkCheck` 보다 엄격하다(브라우저 파서). 그런 링크가 든 본문을 열어 저장하면 `href` 가 빠진다 | `links.json` 의 `allow` 값이 편집기에서 살아남는 시험(5.2). 어긋나는 꼴은 숫자 끝 호스트·잘못된 `xn--` 같은 드문 호스트(추정). 발견되면 그 값을 `links.json` 에 넣고 서버를 좁힐지 정한다 |
| 다른 승인자가 바뀐 본문을 먼저 발행하면, 옛 값을 든 승인자의 발행도 204 다(6.1 「이미 발행됨」 먼저) | 3자 경쟁이다. 바뀐 본문은 다른 승인자가 승인했다. 화면은 성공 뒤 상세를 다시 읽어 발행된 본문을 보인다 |
| 승인자가 미리보기를 실제로 읽었는지는 보장하지 못한다 | I-2·미리보기는 「보여 준 본문 = 발행되는 본문」까지만 지킨다. 읽기는 사람의 일이다(15절 2) |
| 발행본 삭제에 `page.delete` + `page.publish` 두 권한이 든다 — 「지우기만 하는」 역할은 발행본을 지우지 못한다 | 의도다(6.1-3). 그 역할은 발행 취소를 승인자에게 맡긴다 |
| 두 권한(`page.update` + `page.publish`)을 가진 사람은 편집 화면에서 공개 렌더러가 아니라 편집기 모양을 본다 — 편집기가 버리는 `img`·링크는 편집 화면에 보이지 않는다 | 발행 확인 대화상자의 미리보기가 공개 렌더러(`RichHtml`)다. 저장하면 버려진 것이 실제로 빠지는 것은 위 「API 로 넣은 `img`…」 줄 그대로 |
| 폼 다시 채우기 규칙(7.3)이 틀리면 승인자가 본 본문과 발행이 싣는 `updated_at` 이 갈린다 | 「지금 폼에 실린 응답의 `updated_at`」 한 곳에서만 읽는다. Vitest 와 E2E 12.5-4 |
| 관리자 chunk 가 500kB 를 넘는다 | 8절 — 두 번째 경계 |
| 배포 뒤 옛 chunk 404 로 관리자 화면이 빈다 | 8절의 대체 화면. 공개 화면에는 lazy 가 없다 |
| `style-src-attr 'none'` 이 실제 화면을 깨뜨린다 | 9절 판정 — 깨지면 더하지 않는다 |
| 붙여넣기가 synthetic 이벤트로 시험되지 않는다 | 12.5-3 의 대안. 그래도 안 되면 「미실행」과 이유를 적는다(방어는 서버 정화) |
| E2E 가 늘어 CI 가 느려진다 | 1-C1 10절 기준선(시험 12개 2.4분)과 비교해 완료 보고에 적는다 |

## 15. 검토 초점 — 시험이 다루지 않지만 사람이 쓸 때 문제가 될 것

1. **붙여넣기에서 말없이 빠지는 것**: 워드·구글 문서에서 붙인 표·색·정렬·밑줄·사진이 경고 없이 사라진다(3.4). 편집자는 공개 화면에서야 안다. 허용 목록을 넓히지 않는 대가다.
2. **발행 승인의 남은 틈**: I-2 로 「읽은 것과 다른 본문」을, 렌더러 규칙과 발행 미리보기로 「편집기가 버려 보이지 않은 것」을 막았다(16절 1·2). 그래도 승인자가 미리보기를 실제로 읽었는지는 보장하지 못하고, API 로는 목록의 `updated_at` 만으로 읽지 않고 발행할 수 있다. 두 권한을 다 가진 편집자는 편집 화면에서 편집기 모양을 본다(대신 발행 대화상자 미리보기가 공개 렌더러다). 그리고 6.1 의 3자 경쟁. 발행본 삭제에는 두 권한이 든다(16절 3).
3. **동시 편집 덮어쓰기**: 두 편집자가 같은 초안을 열어 저장하면 나중 저장이 앞 저장을 말없이 덮는다(6.3). 화면이 생겨 실제로 부딪힌다. 작업 로그에는 두 수정이 다 남는다.
4. **긴 글을 쓰다 세션이 끝나면**: 저장이 401 이면 로그인 링크를 보이고 머문다. 링크를 누르면 쓰던 본문을 잃는다(본문은 메모리에만 있다). 다른 탭에서 로그인하고 돌아와 다시 누르면 된다는 것을 화면이 말하지 않는다.
5. **편집기가 본문을 고쳐 쓴다**: 편집기 꼴이 아닌 본문(API·옛 정책)은 열고 저장만 해도 `li` 안에 `p` 가 생기고, 사진(1-C3 전)·편집기가 못 읽는 링크가 빠진다. 수정 기록은 「페이지 수정」 한 줄이라 무엇이 바뀌었는지 남지 않는다(값을 남기지 않는다, 1-C1 7절).

## 16. 결정 — 결정 문서에 답이 없던 것 (2026-11-25, 역할 에이전트 결정 — 사용자 확인 전)

1. **발행 권한만 가진 승인자가 초안 본문을 읽는 곳 → (다) 상세 GET 을 `page.view` 로.** 발행 승인자 역할은 `page.view` + `page.publish` 만 갖는다.
   - 이유: (가)는 승인자도 `page.update` 를 가져야 해 승인자 혼자 고치고 바로 발행한다 — 1-C1 결정 ①(발행본 수정에 `page.publish`)이 막을 사람이 없고 2인 승인을 설정할 길이 없다. (다)면 작성자는 혼자 공개하지 못하고 승인자는 본문을 고치지 못한다. 넓어지는 노출은 초안 본문이 `admin.access` + `page.view` 보유자에게 보이는 것뿐이다(이들은 이미 목록에서 초안 제목·주소를 본다, 본문은 내보낼 때 다시 정화).
   - 원문과 다른 곳: D19:2632 「편집 폼 조회 GET = `page.update`」. D15:112 는 `page.view` 를 「초안 열람」으로 적는다.
   - 화면: `page.update` 가 없으면 편집기가 아니라 `RichHtml` 로 본문을 그리고 저장 단추를 그리지 않는다(16절 2). 승인자가 실제로 읽었는지는 보장하지 않는다(15절 2) — I-2 는 읽은 시점 뒤에 본문이 바뀐 경우만 막는다.
   - Red(백엔드): `PagesApiTest.page_view_와_page_publish_만_가진_승인자가_상세를_읽고_그_updated_at_으로_발행하면_204`(지금은 상세 GET 403). 뒤집히는 기존 시험: 「상세는_편집자_200_page_view_만이면_403」→「상세는_page_view_만이면_200_권한이_없으면_403」 — 단언 완화가 아니라 이 결정으로 동작이 바뀐 것이다.
   - 프런트: `PageEditPage.test` 에 「page.update 없이 view·publish 면 편집기 없음(`RichHtml`)·저장 없음·발행 단추가 `aria-disabled` 아님」.
   - 12.3 발행 시험과 12.5-4 E2E 의 승인자 역할은 `page.view` + `page.publish` 로 좁힌다 — 승인자가 `page.update` 를 가지면 이 결정이 검증되지 않는다.
2. **렌더러 — 편집기는 저장 단추가 있을 때만 띄운다.** 그 밖(`page.update` 없음, 또는 발행본인데 `page.publish` 없음)에는 응답의 `body_html` 을 `RichHtml` 로 그린다. 「발행」 확인 대화상자의 설명 아래에 같은 응답의 `body_html` 을 `RichHtml` 로 미리보기(높이 제한·스크롤) — 발행이 싣는 `updated_at` 과 같은 응답이다. `ConfirmButton` 에 `children` 하나를 더한다(7.3).
   - 이유: 편집기는 `img`(Image 확장 없음)와 `linkHref` 가 서버보다 엄격해 떨어지는 링크를 버린다. 편집기로 보여 주면 승인자가 보지 못한 것이 공개된다(I-2 의 다른 경로). 시드가 editor·operator 에 `page.*` 를 다 줘 권한 토글로는 막지 못한다. 대가: 관리 chunk 에 DOMPurify 가 든다.
   - 앞의 「`editable: false` 로 연다」 갈래와 그 「구현 때 확인」(읽기 전용 싣기의 `onUpdate`)은 없앴다.
3. **발행본 삭제는 `page.publish` 도 요구한다**(원문 D19:2635 와 다르다). 요청자가 `page.publish` 를 갖지 않으면 `DELETE … WHERE id = ? AND status = 'draft'` 한 문장, 0행이면 `EXISTS` 로 가른다 — 있으면 403 「발행된 페이지는 발행 권한이 있어야 지울 수 있습니다」(기록 없음), 없으면 404(6.1-3). 화면의 「삭제」는 `can["page.delete"]` 이고 (초안이거나 `can["page.publish"]`), 발행본이라 숨기면 「발행을 취소한 뒤 지울 수 있습니다」(7.3).

## 17. 열린 문제

1. **IME 수동 확인** — 사용자가 맥 한글 입력기로 Chrome·Safari 를 볼 때까지 「미실행」.
2. **CDP 확정 방법** — `Input.insertText` 가 조합을 끝내는지(2.2). 대조 단언이 알려 준다. → 확인했다: 끝낸다(확정마다 `compositionend` 1). 관문 통과.
3. **jsdom 에서 Tiptap** — `new Editor` 가 도는지(5.2). 안 되면 짝 시험 프런트 쪽을 E2E 로 옮긴다. → 확인했다: 돈다(`focus` 때문에 시험에서 `Range` 레이아웃 함수를 채운다).
4. **합성 붙여넣기** — prosemirror-view 가 `ClipboardEvent` 합성 이벤트를 받는지(12.5-3). → 확인했다: 받는다(headless shell, 96줄 모두 `dispatchEvent` 가 `false`).
5. **붙이지 않은 문서 파싱의 CSP 보고** — `createHTMLDocument`(붙여넣기)·`DOMParser`(싣기)에서 Chrome 이 `script-src-attr`·`style-src-attr` 을 보고하는지(9절, 12.5-3). 1-C1 에서는 `DOMParser` 가 보고했다. → 확인했다: `createHTMLDocument` 는 `script-src-attr` 6·`base-uri` 1·`style-src-attr` 2, `DOMParser` 는 `style-src-attr` 를 보고하지 않는다(9절).
6. **`style-src-attr 'none'`** — 9절 실험 결과. → 더했다(9절).
7. **묶음 크기** — 관리자 chunk 크기와 (g) 경고. 재지 않았다. → 쟀다: 공개 497.74 kB·관리 473.76 kB, 경고 없음(8절).
8. **`linkHref` 와 `LinkCheck` 가 갈리는 꼴** — 4절의 예가 실제로 갈리는지 Chromium 에서 본다. → 확인했다: `https://a.1/` 이 갈린다(4절).
9. **판** — `prosemirror-view` 1.43.1 게시 하루째. 구현 때 Tiptap·prosemirror 의 최신 GA·OSV 를 다시 본다. context7 은 이 세션에 없었다. → 다시 봤다: 판 그대로, 새 48개 OSV 0건(1절).
10. **1-C1·1-B2 의 열린 문제는 그대로** — Q12 버전 노출(사용자 확인 필요), 시험 이미지 고정, 작업 로그를 일과 다른 트랜잭션에 씀, m-14 시험 DB 접속 멈춤.
