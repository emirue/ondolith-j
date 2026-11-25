import type { BrowserContext, CDPSession, Locator, Page } from "@playwright/test";
import { EDITOR_TAGS, editorInvariant, vectors } from "../src/test-vectors";
import { ADMIN_EMAIL, ADMIN_PASSWORD, expect, installSite, loginAdmin, offline, test, watch, type CspViolation } from "./stack";

/**
 * 한글 IME 관문(1-C2 스펙 2절). 실제 입력기 대신 CDP 조합 입력으로 흉내 낸다 — 스택 없이 빌드 산출물 + CSP 위에서.
 * 대조 단언: 조합 이벤트를 세어 흉내가 실제로 조합 경로를 탔는지 본다(insertText 만으로 통과하지 못하게).
 */

type Comp = { start: number; update: number; end: number };

const EDITOR_CAN = { "admin.access": true, "page.create": true };
const U = "00000000-0000-4000-8000-000000000001";

/** 한 글자를 낱자 단계마다 조합하고, 끝 단계 글자로 확정한다. */
async function compose(cdp: CDPSession, steps: string[]) {
  for (const text of steps) await cdp.send("Input.imeSetComposition", { text, selectionStart: text.length, selectionEnd: text.length });
  await cdp.send("Input.insertText", { text: steps.at(-1)! });
}

/** 「한글 입력」 — 조합 확정 4번(공백은 조합 없이). */
async function typeHangul(cdp: CDPSession) {
  await compose(cdp, ["ㅎ", "하", "한"]);
  await compose(cdp, ["ㄱ", "그", "글"]);
  await cdp.send("Input.insertText", { text: " " });
  await compose(cdp, ["ㅇ", "이", "입"]);
  await compose(cdp, ["ㄹ", "려", "력"]);
}

/** 새 작성 화면을 연다. 조합 이벤트 셈·pageerror 수집·저장 가로채기를 건다. */
async function open(page: Page) {
  await page.addInitScript(() => {
    const comp = { start: 0, update: 0, end: 0 };
    Reflect.set(window, "__comp", comp);
    document.addEventListener("compositionstart", () => comp.start++, true);
    document.addEventListener("compositionupdate", () => comp.update++, true);
    document.addEventListener("compositionend", () => comp.end++, true);
  });
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await offline(page, EDITOR_CAN);
  const saved: string[] = [];
  await page.route("**/api/admin/pages", (r) => {
    if (r.request().method() !== "POST") return r.fallback();
    saved.push(r.request().postDataJSON().body_html);
    return r.fulfill({ status: 201, json: { id: U } });
  });
  await page.goto("/admin/pages/new");
  await expect(page.getByRole("heading", { name: "새 페이지" })).toBeVisible();
  const cdp = await page.context().newCDPSession(page);
  const comp = () => page.evaluate(() => Reflect.get(window, "__comp") as Comp);
  return { cdp, comp, errors, saved };
}

test("IME 흉내 대조군: 제목 칸에 한글 입력", async ({ page }) => {
  const { cdp, comp, errors } = await open(page);
  await page.getByLabel("제목").click();
  await typeHangul(cdp);
  await expect(page.getByLabel("제목")).toHaveValue("한글 입력");
  const c = await comp();
  console.log("대조군 조합 이벤트", JSON.stringify(c));
  expect(c.start).toBeGreaterThanOrEqual(1);
  expect(c.end).toBe(4);
  expect(errors).toEqual([]);
});

/** 편집기 DOM 의 editor(Tiptap 이 단다, 스펙 1절)로 지금 선택을 읽는다 — 앱에 시험 고리를 넣지 않는다. */
const selection = (page: Page) =>
  page.locator(".ProseMirror").evaluate((el) => {
    const { from, to } = (Reflect.get(el, "editor") as { state: { selection: { from: number; to: number } } }).state.selection;
    return { from, to };
  });

/** 보낸 본문을 브라우저의 DOMParser 로 푼 판정용 값. */
const parse = (page: Page, html: string) =>
  page.evaluate((h) => {
    const body = new DOMParser().parseFromString(h, "text/html").body;
    const one = (sel: string) => [...body.querySelectorAll(sel)].map((e) => ({ text: e.textContent, href: e.getAttribute("href") }));
    return { text: body.textContent, p: one("p"), strong: one("strong"), li: one("ul > li"), a: one("a") };
  }, html);

type Parsed = Awaited<ReturnType<typeof parse>>;

const SCENES: { name: string; ends: number; act: (page: Page, cdp: CDPSession) => Promise<void>; dom: (d: Parsed) => void; bytes: string }[] = [
  {
    name: "I-1 빈 문단",
    ends: 4,
    act: async (page, cdp) => {
      await typeHangul(cdp);
      await page.keyboard.type("x");
    },
    dom: (d) => expect(d.p[0].text).toBe("한글 입력x"),
    bytes: "<p>한글 입력x</p>",
  },
  {
    name: "I-2 굵게",
    ends: 4,
    act: async (page, cdp) => {
      await page.keyboard.press("ControlOrMeta+b");
      await typeHangul(cdp);
    },
    dom: (d) => {
      expect(d.strong.map((s) => s.text)).toEqual(["한글 입력"]);
      expect(d.text).toBe("한글 입력"); // strong 밖 글자 없음
    },
    bytes: "<p><strong>한글 입력</strong></p>",
  },
  {
    name: "I-3 목록 안",
    ends: 4,
    act: async (page, cdp) => {
      await page.getByRole("button", { name: "글머리 목록" }).click();
      await expect(page.locator(".ProseMirror")).toBeFocused();
      await typeHangul(cdp);
    },
    dom: (d) => expect(d.li.map((l) => l.text)).toEqual(["한글 입력"]),
    bytes: "<ul><li><p>한글 입력</p></li></ul>",
  },
  {
    name: "I-4 링크 바로 뒤",
    ends: 4,
    act: async (page, cdp) => {
      await cdp.send("Input.insertText", { text: "안" });
      await page.keyboard.press("Shift+ArrowLeft");
      // 편집기가 선택을 읽은 뒤에 칸으로 간다 — 곧바로 초점을 옮기면 selectionchange 를 읽을 때 초점이 없어 선택을 버린다(관문 실행에서 확인)
      await expect.poll(() => selection(page)).toEqual({ from: 1, to: 2 });
      await page.getByLabel("링크 주소").fill("/about");
      await page.getByRole("button", { name: "적용" }).click();
      await expect(page.locator(".ProseMirror")).toBeFocused();
      // 계획의 End 대신 ArrowRight(선택을 끝으로 접는다): Playwright 는 macOS 에서 End 를 scrollToEndOfDocument: 로 보내 캐럿이
      // 그대로다(playwright-core macEditingCommands) — 고른 「안」 위에서 조합이 시작돼 덮는다. Linux(CI)의 End 는 줄 끝으로 간다
      await page.keyboard.press("ArrowRight");
      await expect.poll(() => selection(page)).toEqual({ from: 2, to: 2 });
      await typeHangul(cdp);
    },
    dom: (d) => {
      expect(d.a).toEqual([{ text: "안", href: "/about" }]);
      expect(d.text).toBe("안한글 입력"); // 「한글 입력」은 a 밖
    },
    bytes: '<p><a href="/about">안</a>한글 입력</p>',
  },
  {
    name: "I-5 받침 넘김",
    ends: 2,
    act: async (_page, cdp) => {
      await compose(cdp, ["ㅎ", "하", "한", "하"]);
      await compose(cdp, ["ㄴ", "나"]);
    },
    dom: (d) => expect(d.text).toBe("하나"),
    bytes: "<p>하나</p>",
  },
];

for (const scene of SCENES) {
  test(`IME ${scene.name}`, async ({ page }) => {
    const { cdp, comp, errors, saved } = await open(page);
    await page.locator(".ProseMirror").click();
    await expect(page.locator(".ProseMirror")).toBeFocused();
    await scene.act(page, cdp);
    // 제목·주소는 fill — 조합 이벤트를 더하지 않는다(끝의 셈이 본다)
    await page.getByLabel("제목").fill("관문");
    await page.getByLabel("주소", { exact: true }).fill("gate");
    await page.getByRole("button", { name: "만들기" }).click();
    await expect.poll(() => saved.length).toBe(1);
    const c = await comp();
    console.log(`${scene.name} 조합 이벤트`, JSON.stringify(c), "보낸 본문", JSON.stringify(saved[0]));
    scene.dom(await parse(page, saved[0]));
    expect(saved[0]).toBe(scene.bytes);
    expect(c.start).toBeGreaterThanOrEqual(1);
    expect(c.end).toBe(scene.ends);
    expect(errors).toEqual([]);
  });
}

// 붙여넣기 공격·링크 입력 거부(1-C2 스펙 12.5-3·12.5-5). 편집기는 방어가 아니다 — 여기서 보는 것은 관리자 출처에서
// 붙여넣은 HTML 이 스크립트를 돌리지 못하고 편집기 출력이 서버 허용 목록 안에 머무는 것이다

/** 1-C1 pages.spec.ts 와 같은 한 줄. 돌면 window.__xss 가 생긴다 */
const xss = (input: string) => input.replaceAll("alert(1)", "window.__xss=1");

const editorHtml = (page: Page) =>
  page.locator(".ProseMirror").evaluate((el) => (Reflect.get(el, "editor") as { getHTML(): string }).getHTML());

/** 편집 영역에 text/html 붙여넣기를 합성해 보낸다. 편집기가 처리했으면(preventDefault) true */
const paste = (page: Page, html: string) =>
  page.locator(".ProseMirror").evaluate((el, h) => {
    const dt = new DataTransfer();
    dt.setData("text/html", h);
    return !el.dispatchEvent(new ClipboardEvent("paste", { clipboardData: dt, bubbles: true, cancelable: true }));
  }, html);

/** getHTML() 을 브라우저 DOMParser 로 푼 body 에 5.2 불변식을 건다(live 편집 영역이 아니다) */
async function invariant(page: Page) {
  const body = await page.locator(".ProseMirror").evaluateHandle((el) =>
    new DOMParser().parseFromString((Reflect.get(el, "editor") as { getHTML(): string }).getHTML(), "text/html").body);
  return body.evaluate(editorInvariant, EDITOR_TAGS);
}

type Report = { directive: string; count: number };

/**
 * 붙인 HTML 을 prosemirror-view 가 붙이지 않은 문서(createHTMLDocument)에 innerHTML 로 풀 때 Chrome 이 내는 CSP 보고.
 * 줄마다 붙이기 전후로 새로 생긴 위반이 이 표와 정확히 같을 때만 받는다 — 처음 Red 에서 본 값이다(Chromium headless shell,
 * prosemirror-view 1.43.1). 처리기 속성·<base> 가 실제 DOM 에 닿았는지는 불변식과 __xss 표시가 본다
 */
const ATTR = [{ directive: "script-src-attr", count: 1 }];
const PASTE_REPORTS: Record<string, Report[]> = {
  X3: ATTR, X4: ATTR, X5: ATTR, X11: ATTR, X15: ATTR,
  // style-src-attr 'none'(m-6) 뒤의 style="color:red" 파싱 보고. 붙은 DOM 의 보고가 아니다 — 대상이 요소가 아니라 문서이고
  // 위치가 readHTML 의 innerHTML 이다(2026-11-25 탐침, docs/decisions.md m-6)
  X14: [...ATTR, { directive: "style-src-attr", count: 1 }],
  X9: [{ directive: "base-uri", count: 1 }],
};

const summary = (vs: CspViolation[]): Report[] =>
  [...new Set(vs.map((v) => v.directive))].sort().map((directive) => ({ directive, count: vs.filter((v) => v.directive === directive).length }));

test("붙여넣기: 오염 HTML 을 붙여도 스크립트가 돌지 않는다", async ({ page, guard }) => {
  test.setTimeout(300_000);
  const rows = vectors.map((v) => ({ id: v.id, html: xss(v.input) }));
  expect(rows, "vectors 줄 수").toHaveLength(96);
  expect(rows.filter((r) => r.html.includes("alert")).map((r) => r.id), "바꾸지 못한 alert").toEqual([]);
  // 페이지 안 위반 셈 — guard 는 exposeFunction 을 거쳐 늦게 받는다. 이 수에 닿을 때까지 기다려 줄마다 가른다
  await page.addInitScript(() => {
    Reflect.set(window, "__csp", 0);
    document.addEventListener("securitypolicyviolation", () => Reflect.set(window, "__csp", Reflect.get(window, "__csp") + 1));
  });
  const { errors } = await open(page);
  const box = page.locator(".ProseMirror");
  const accepted = new Set<CspViolation>();
  guard.allow((v) => accepted.has(v));

  // 합성 붙여넣기가 prosemirror-view 에 닿는지 먼저(스펙 17절 4)
  await box.focus();
  expect(await paste(page, "<p><strong>붙임</strong></p>"), "합성 붙여넣기 처리").toBe(true);
  expect(await editorHtml(page)).toBe("<p><strong>붙임</strong></p>");

  for (const row of rows) {
    await box.evaluate((el) => (Reflect.get(el, "editor") as { commands: { clearContent(): void } }).commands.clearContent());
    await box.focus();
    const before = guard.violations.length;
    expect.soft(await paste(page, row.html), `${row.id} 붙여넣기 처리`).toBe(true);
    expect.soft(await invariant(page), row.id).toEqual([]);
    await page.waitForLoadState("networkidle");
    // 위반 보고는 비동기다 — 잠깐 양보한 뒤의 페이지 안 셈까지 Node 쪽 수집을 기다린다(상한은 poll 기본 5초)
    const seen = await page.evaluate(() => new Promise<number>((r) => setTimeout(() => r(Reflect.get(window, "__csp")), 50)));
    await expect.poll(() => guard.violations.length, row.id).toBe(seen);
    const fresh = guard.violations.slice(before);
    if (fresh.length) console.log(row.id, JSON.stringify(summary(fresh)), JSON.stringify(fresh.map((v) => v.blocked)));
    const want = JSON.stringify(PASTE_REPORTS[row.id] ?? []);
    expect.soft(JSON.stringify(summary(fresh)), `${row.id} CSP 보고`).toBe(want);
    if (JSON.stringify(summary(fresh)) === want) fresh.forEach((v) => accepted.add(v));
    expect.soft(await page.evaluate(() => Reflect.get(window, "__xss")), row.id).toBeUndefined();
  }
  expect(await page.evaluate(() => Reflect.get(window, "__xss"))).toBeUndefined();
  expect(guard.dialogs).toEqual([]);
  expect(guard.violations.filter((v) => v.directive === "script-src" || v.directive === "script-src-elem")).toEqual([]);
  expect(guard.violations.length, "CSP 보고 합계").toBe(Object.values(PASTE_REPORTS).flat().reduce((n, r) => n + r.count, 0));
  expect(errors).toEqual([]);
});

test("붙여넣기: 표·색·밑줄은 빠지고 글자는 남는다", async ({ page, guard }) => {
  const { errors } = await open(page);
  await page.locator(".ProseMirror").focus();
  // 붙인 style="color:red" 를 readHTML 이 붙이지 않은 문서에 풀 때의 보고 하나만 받는다(PASTE_REPORTS 의 X14 와 같은 꼴)
  guard.allow((v) => v.directive === "style-src-attr" && v.blocked === "inline" && new URL(v.url).pathname === "/admin/pages/new");
  expect(await paste(page, '<table><tr><td>칸</td></tr></table><p style="color:red"><u>색</u></p>')).toBe(true);
  await expect.poll(() => guard.violations.length, "CSP 보고").toBe(1);
  const html = await editorHtml(page);
  expect(html).not.toMatch(/table|style|<u/);
  expect(html).toContain("칸");
  expect(html).toContain("색");
  expect(guard.violations, "늦게 온 보고").toHaveLength(1);
  expect(errors).toEqual([]);
});

test("링크 입력이 javascript 와 //evil.example 을 받지 않는다", async ({ page }) => {
  const { errors, saved } = await open(page);
  for (const [i, bad] of ["javascript:alert(1)", "//evil.example"].entries()) {
    // 값마다 새 화면 — 앞 값의 칸 오류가 남아 뒤 값의 거부를 가리지 않게
    if (i > 0) {
      await page.goto("/admin/pages/new");
      await expect(page.getByRole("heading", { name: "새 페이지" })).toBeVisible();
    }
    await page.locator(".ProseMirror").click();
    await page.keyboard.type("a");
    await page.keyboard.press("Shift+ArrowLeft");
    // 편집기가 선택을 읽은 뒤에 칸으로 간다(IME I-4 와 같은 경합)
    await expect.poll(() => selection(page)).toEqual({ from: 1, to: 2 });
    const field = page.getByLabel("링크 주소");
    await field.fill(bad);
    await page.getByRole("button", { name: "적용" }).click();
    await expect(field, bad).toHaveAttribute("aria-invalid", "true");
    await expect(field, bad).toHaveAccessibleDescription("사용할 수 없는 링크입니다");
    await expect(field, bad).toBeFocused();
    await page.getByLabel("제목").fill("링크");
    await page.getByLabel("주소", { exact: true }).fill("link");
    await page.getByRole("button", { name: "만들기" }).click();
    await expect.poll(() => saved.length).toBe(i + 1);
    // 201 뒤 상세로 옮긴 것을 본 뒤에 다음 값으로 — 옮기기 전에 goto 하면 아직 dirty 인 작성 화면이 beforeunload 로 묻는다(I-4)
    await expect(page.getByRole("heading", { name: "페이지 편집" })).toBeVisible();
    expect(saved[i], bad).not.toContain("href");
    expect(saved[i], bad).toBe("<p>a</p>");
  }
  expect(errors).toEqual([]);
});

test("linkHref 와 LinkCheck 가 갈리는 꼴은 Chromium 에서도 같다", async ({ page }) => {
  // linkHref.test.ts 「new_URL_이_던지면_null」 이 jsdom 에서 고정한 값: 서버 LinkCheck 는 받고 WHATWG 파서는 던진다(스펙 17절 8)
  const throws = await page.evaluate((u) => {
    try {
      new URL(u);
      return false;
    } catch {
      return true;
    }
  }, "https://a.1/");
  expect(throws).toBe(true);
});

/** 문맥이 내려받은 .js 응답 본문을 모은다. 끝에 부르면 모인 본문을 돌려준다. */
function scripts(ctx: BrowserContext) {
  const bodies: Promise<string>[] = [];
  ctx.on("response", (r) => {
    if (new URL(r.url()).pathname.endsWith(".js")) bodies.push(r.text());
  });
  return () => Promise.all(bodies);
}

// 편집기 표지(스펙 8절): prosemirror-view 가 편집 DOM 에 다는 클래스 이름, Tiptap 의 오류·경고 문구
const hasEditor = (js: string) => js.includes("ProseMirror") || /tiptap/i.test(js);

test("공개 화면 JS 에는 편집기 표지가 없고 관리 편집 화면에는 있다", async ({ page, browser }) => {
  const publicJs = scripts(page.context());
  await offline(page);
  await page.route("**/api/pages/about", (r) => r.fulfill({ json: { slug: "about", title: "소개", body_html: "<p>a</p>" } }));
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "E2E 사이트" })).toBeVisible();
  await page.goto("/about");
  await expect(page.getByRole("heading", { level: 1, name: "소개" })).toBeVisible();
  await page.goto("/login");
  await expect(page.getByRole("heading", { name: "로그인" })).toBeVisible();
  await page.waitForLoadState("networkidle");
  const visitor = await publicJs();
  expect(visitor.length, "내려받은 JS").toBeGreaterThan(0);
  expect(visitor.filter(hasEditor).length, "표지가 든 공개 JS").toBe(0);

  // 반대 단언: 표지가 압축 뒤에도 남아 있어야 위의 0 이 뜻이 있다
  const admin = await browser.newContext();
  const seen = await watch(admin);
  const adminJs = scripts(admin);
  const editor = await admin.newPage();
  await offline(editor, EDITOR_CAN);
  await editor.goto("/admin/pages/new");
  await expect(editor.getByRole("heading", { name: "새 페이지" })).toBeVisible();
  await editor.waitForLoadState("networkidle");
  expect((await adminJs()).filter(hasEditor).length, "표지가 든 관리 JS").toBeGreaterThan(0);
  seen.check();
  await admin.close();
});

test("관리 묶음을 받지 못하면 대체 화면", async ({ page }) => {
  let refused = 0;
  // 배포 뒤 옛 chunk 가 사라진 꼴(스펙 8절). 이름 꼴은 빌드 출력(dist/assets)으로 확인했다
  await page.route("**/assets/AdminGate-*.js", (r) => {
    refused++;
    return r.fulfill({ status: 404 });
  });
  await offline(page, EDITOR_CAN);
  await page.goto("/admin/pages/new");
  const heading = page.getByRole("heading", { level: 1, name: "관리자 화면을 불러오지 못했습니다. 새로 고침하세요" });
  await expect(heading).toBeVisible();
  await expect(heading).toBeFocused();
  expect(refused).toBeGreaterThan(0);
});

// 흐름(1-C2 스펙 12.5-2·12.5-4): 실제 jar 위에서 쓰기 → 저장 → 발행 → 공개, 발행 승인 경쟁(I-2)의 409 화면.
// 두 시험 모두 CSP 예외를 받지 않는다 — 수정 화면·발행 대화상자·렌더러·미리보기·공개 화면의 위반 0(m-6 포함)을 문맥마다 본다

const USER_PASSWORD = "e2e-user-pass-not-real";

/** 문맥의 쿠키로 보내는 POST. CSRF 는 앱처럼 XSRF-TOKEN 쿠키를 헤더로(부를 때마다 다시 읽는다). 상태 코드를 단언한다 */
async function post(page: Page, url: string, status: number, data?: object) {
  const token = (await page.context().cookies()).find((c) => c.name === "XSRF-TOKEN")?.value;
  expect(token, "XSRF-TOKEN 쿠키").toBeTruthy();
  const res = await page.request.post(url, { headers: { "X-XSRF-TOKEN": token! }, data });
  expect(res.status(), url).toBe(status);
  return res;
}

/** 도구 막대 단추를 누르고 편집기로 초점이 돌아올 때까지 기다린다. */
async function tool(page: Page, name: string) {
  await page.getByRole("toolbar", { name: "서식" }).getByRole("button", { name, exact: true }).click();
  await expect(page.locator(".ProseMirror")).toBeFocused();
}

/** 캐럿 앞 한 글자를 골라 링크로 만들고, 선택을 끝으로 접는다(IME I-4 와 같은 경합을 기다린다). */
async function link(page: Page, href: string) {
  await page.keyboard.press("Shift+ArrowLeft");
  await expect.poll(() => selection(page).then((s) => s.to - s.from)).toBe(1);
  await page.getByLabel("링크 주소").fill(href);
  await page.getByRole("button", { name: "적용" }).click();
  await expect(page.locator(".ProseMirror")).toBeFocused();
  await page.keyboard.press("ArrowRight"); // End 는 macOS 에서 캐럿을 옮기지 않는다(I-4)
  await expect.poll(() => selection(page).then((s) => s.to - s.from)).toBe(0);
}

// 도구 막대·단축키로 쓴 본문의 편집기 출력. 마크 경계와 문단 끝에 공백을 두지 않는다(Chromium 의 &nbsp; 직렬화는 미확인)
const WRITTEN = '<h2>소제목</h2><h3>작은 제목</h3><p><strong>굵게</strong><em>기울임</em></p>'
  + '<p><a href="/about">안</a></p><p><a href="https://example.com/">밖</a></p>'
  + "<ul><li><p>하나</p></li></ul><ol><li><p>첫째</p></li></ol><blockquote><p>인용문</p></blockquote>";

/** RichHtml 로 그린 WRITTEN — 미리보기와 공개 화면이 같은 요소를 보인다. */
async function expectWritten(root: Locator) {
  await expect(root.locator("h2")).toHaveText("소제목");
  await expect(root.locator("h3")).toHaveText("작은 제목");
  await expect(root.locator("strong")).toHaveText("굵게");
  await expect(root.locator("em")).toHaveText("기울임");
  await expect(root.locator("ul > li")).toHaveText("하나");
  await expect(root.locator("ol > li")).toHaveText("첫째");
  await expect(root.locator("blockquote")).toHaveText("인용문");
  await expect(root.getByRole("link", { name: "안" })).toHaveAttribute("href", "/about");
  await expect(root.getByRole("link", { name: "밖" })).toHaveAttribute("href", "https://example.com/");
}

test("쓰기에서 공개까지: 도구 막대로 쓴 본문을 방문자가 같은 요소로 본다", async ({ page, stack, browser, guard }) => {
  await installSite(page, stack);
  await loginAdmin(page);
  await page.getByRole("navigation", { name: "관리자 메뉴" }).getByRole("link", { name: "페이지", exact: true }).click();
  await page.getByRole("link", { name: "새 페이지" }).click();
  await expect(page.getByRole("heading", { name: "새 페이지" })).toBeVisible();
  await page.getByLabel("제목").fill("흐름");
  await page.getByLabel("주소", { exact: true }).fill("flow");

  const k = page.keyboard;
  await page.locator(".ProseMirror").click();
  await expect(page.locator(".ProseMirror")).toBeFocused();
  await tool(page, "제목 2");
  await k.type("소제목");
  await k.press("Enter");
  await tool(page, "제목 3");
  await k.type("작은 제목");
  await k.press("Enter");
  await k.press("ControlOrMeta+b");
  await k.type("굵게");
  await k.press("ControlOrMeta+b");
  await k.press("ControlOrMeta+i");
  await k.type("기울임");
  await k.press("ControlOrMeta+i");
  await k.press("Enter");
  await k.type("안");
  await link(page, "/about");
  await k.press("Enter");
  await k.type("밖");
  await link(page, "https://example.com/");
  await k.press("Enter");
  await tool(page, "글머리 목록");
  await k.type("하나");
  await k.press("Enter");
  await k.press("Enter"); // 빈 항목에서 Enter → 목록 밖 문단
  await tool(page, "번호 목록");
  await k.type("첫째");
  await k.press("Enter");
  await k.press("Enter");
  await tool(page, "인용");
  await k.type("인용문");

  const sent = page.waitForRequest((r) => r.method() === "POST" && new URL(r.url()).pathname === "/api/admin/pages", { timeout: 10_000 });
  const read = page.waitForResponse((r) => r.request().method() === "GET" && /^\/api\/admin\/pages\/[0-9a-f-]{36}$/.test(new URL(r.url()).pathname),
    { timeout: 10_000 });
  await page.getByRole("button", { name: "만들기" }).click();
  const saved = (await sent).postDataJSON().body_html;
  expect(saved, "저장 요청 body_html").toBe(WRITTEN);
  const detail = await read;
  expect(detail.status()).toBe(200);
  expect((await detail.json()).body_html, "상세 응답 body_html — 저장 요청과 바이트까지 같다").toBe(saved);
  await expect(page.getByRole("status")).toHaveText("페이지를 만들었습니다");
  await expect(page.getByRole("heading", { name: "페이지 편집" })).toBeVisible();

  await page.getByRole("button", { name: "발행", exact: true }).click();
  const publish = page.getByRole("alertdialog", { name: "발행할까요?" });
  await expect(publish).toContainText("방문자가 /flow 에서 이 내용을 봅니다");
  await expectWritten(publish.getByLabel("발행할 본문"));
  await publish.getByRole("button", { name: "확인" }).click();
  await expect(page.getByRole("status")).toHaveText("발행했습니다");
  await expect(page.getByText("발행", { exact: true })).toBeVisible(); // 상태

  const visitor = await browser.newContext();
  const seen = await watch(visitor);
  const guest = await visitor.newPage();
  await guest.goto("/flow");
  await expect(guest.getByRole("heading", { level: 1, name: "흐름" })).toBeVisible();
  await expectWritten(guest.locator(".rich"));

  await page.getByRole("button", { name: "발행 취소" }).click();
  await page.getByRole("alertdialog", { name: "발행을 취소할까요?" }).getByRole("button", { name: "확인" }).click();
  await expect(page.getByRole("status")).toHaveText("발행을 취소했습니다");
  await guest.goto("/flow");
  await expect(guest.getByRole("heading", { name: "찾을 수 없습니다" })).toBeVisible();

  await page.getByRole("button", { name: "삭제" }).click();
  await page.getByRole("alertdialog", { name: "페이지를 지울까요?" }).getByRole("button", { name: "확인" }).click();
  await expect(page.getByRole("status")).toHaveText("페이지를 지웠습니다");
  // 「없다」는 표가 그려진 뒤에 센다 — 머리 줄 하나만 남는다
  const list = page.getByRole("table", { name: "페이지 목록" });
  await expect(list.getByRole("columnheader", { name: "제목" })).toBeVisible();
  await expect(list.getByRole("row")).toHaveCount(1);
  await expect(list.getByRole("link", { name: "흐름" })).toHaveCount(0);

  await guest.waitForLoadState("networkidle");
  console.log("쓰기 흐름 CSP 위반", JSON.stringify({ admin: guard.violations, visitor: seen.violations }));
  seen.check();
  await visitor.close();
});

test("I-2: 승인자가 연 뒤 본문이 바뀌면 발행이 멈추고 바뀐 본문을 다시 보인다", async ({ page, stack, browser, guard }) => {
  await installSite(page, stack);
  const role = (key: string, perms: string[]) => {
    const keys = perms.map((p) => `'${p}'`).join(", ");
    expect(stack.sql(`WITH r AS (INSERT INTO roles (key, name) VALUES ('${key}', '${key}') RETURNING id) `
      + `INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM r, permissions p WHERE p.key IN (${keys})`), key)
      .toBe(`INSERT 0 ${perms.length}`);
    return stack.sql(`SELECT id FROM roles WHERE key = '${key}'`);
  };
  const approverRole = role("approver", ["admin.access", "page.view", "page.publish"]);
  const updaterRole = role("updater", ["admin.access", "page.update"]);

  await loginAdmin(page);
  for (const [email, roleId] of [["approver@ondolith.test", approverRole], ["updater@ondolith.test", updaterRole]]) {
    const { id } = await (await post(page, "/api/admin/users", 201, { email, display_name: email, password: USER_PASSWORD })).json();
    await post(page, `/api/admin/users/${id}/roles`, 204, { role_id: roleId });
  }
  const { id } = await (await post(page, "/api/admin/pages", 201, { slug: "race", title: "경쟁", body_html: "<p>처음 본문</p>" })).json();

  // 승인자: page.update 가 없어 편집기가 아니라 RichHtml 로 본다
  const approverCtx = await browser.newContext();
  const approverSeen = await watch(approverCtx);
  const approver = await approverCtx.newPage();
  const publishes: number[] = [];
  approver.on("response", (r) => {
    if (r.request().method() === "POST" && new URL(r.url()).pathname.endsWith("/publish")) publishes.push(r.status());
  });
  await loginAdmin(approver, "approver@ondolith.test", USER_PASSWORD);
  await approver.goto(`/admin/pages/${id}`);
  await expect(approver.getByRole("heading", { name: "페이지 편집" })).toBeVisible();
  const body = approver.getByRole("main").locator(".rich");
  await expect(body).toHaveText("처음 본문");
  await expect(approver.getByRole("textbox")).toHaveCount(0);

  // 고치는 사람: page.update 만 — 상세를 읽지 못해 slug·title 을 그대로 싣는다
  const updaterCtx = await browser.newContext();
  const updaterSeen = await watch(updaterCtx);
  const updater = await updaterCtx.newPage();
  await loginAdmin(updater, "updater@ondolith.test", USER_PASSWORD);
  await post(updater, `/api/admin/pages/${id}`, 204, { slug: "race", title: "경쟁", body_html: "<p>바뀐 본문</p>" });

  const publishButton = approver.getByRole("button", { name: "발행", exact: true });
  const dialog = approver.getByRole("alertdialog", { name: "발행할까요?" });
  await publishButton.click();
  await expect(dialog.getByLabel("발행할 본문")).toHaveText("처음 본문");
  await dialog.getByRole("button", { name: "확인" }).click();
  await expect(approver.getByRole("alert")).toHaveText("그사이 페이지가 바뀌었습니다. 바뀐 내용을 확인한 뒤 발행하세요");
  // 다시 읽은 본문이 실린 것을 본 뒤에 다시 누른다 — 아니면 옛 updated_at 으로 다시 409
  await expect(body).toHaveText("바뀐 본문");

  await publishButton.click();
  await expect(dialog.getByLabel("발행할 본문")).toHaveText("바뀐 본문");
  await dialog.getByRole("button", { name: "확인" }).click();
  await expect(approver.getByRole("status")).toHaveText("발행했습니다");
  expect(publishes).toEqual([409, 204]);

  const visitorCtx = await browser.newContext();
  const visitorSeen = await watch(visitorCtx);
  const visitor = await visitorCtx.newPage();
  await visitor.goto("/race");
  await expect(visitor.getByRole("heading", { level: 1, name: "경쟁" })).toBeVisible();
  await expect(visitor.locator(".rich")).toHaveText("바뀐 본문");

  // 위반 보고는 늦게 온다 — 문맥마다 마지막 동작 뒤 조용해질 때까지 기다린 뒤 끝에 모아 본다
  for (const p of [approver, updater, visitor]) await p.waitForLoadState("networkidle");
  console.log("I-2 CSP 위반", JSON.stringify({ admin: guard.violations, approver: approverSeen.violations, updater: updaterSeen.violations, visitor: visitorSeen.violations }));
  approverSeen.check();
  updaterSeen.check();
  visitorSeen.check();
  await Promise.all([approverCtx, updaterCtx, visitorCtx].map((c) => c.close()));
});

test("I-4: 세션이 끊긴 편집 탭에서 새 탭으로 다시 로그인하면 원래 탭의 저장이 본문 그대로 204", async ({ page, stack }) => {
  await installSite(page, stack);
  await loginAdmin(page);
  const { id } = await (await post(page, "/api/admin/pages", 201, { slug: "relogin", title: "다시 로그인", body_html: "<p>처음 본문</p>" })).json();
  await page.goto(`/admin/pages/${id}`);
  await expect(page.getByRole("heading", { name: "페이지 편집" })).toBeVisible();
  const area = page.locator(".ProseMirror");
  await expect(area).toHaveText("처음 본문");
  await area.click();
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.type("새 탭 로그인 뒤 본문");
  const xsrf = async () => (await page.context().cookies()).find((c) => c.name === "XSRF-TOKEN")?.value;
  const before = await xsrf();
  expect(before, "XSRF-TOKEN 쿠키").toBeTruthy();

  // 세션 만료: 서버의 세션 행을 지운다(쿠키는 그대로)
  expect(stack.sql("DELETE FROM spring_session")).toMatch(/^DELETE [1-9]/);
  const saves: { status: number; body: string }[] = [];
  page.on("response", (r) => {
    if (r.request().method() === "POST" && new URL(r.url()).pathname === `/api/admin/pages/${id}`) {
      saves.push({ status: r.status(), body: r.request().postDataJSON().body_html });
    }
  });
  await page.getByRole("button", { name: "저장" }).click();
  const alert = page.getByRole("alert");
  await expect(alert).toContainText("로그인이 끝났습니다");
  await expect(alert).toContainText("로그인한 뒤 다시 저장하세요");
  expect(saves.map((s) => s.status), "첫 저장은 CSRF 403 이 아니라 401").toEqual([401]);

  const [tab] = await Promise.all([page.context().waitForEvent("page"), alert.getByRole("link", { name: "다시 로그인" }).click()]);
  await tab.getByLabel("이메일").fill(ADMIN_EMAIL);
  await tab.getByLabel("비밀번호").fill(ADMIN_PASSWORD);
  await tab.getByRole("button", { name: "로그인" }).click();
  await expect(tab.getByRole("heading", { name: "페이지 편집" })).toBeVisible();
  const after = await xsrf();
  expect(after, "다시 로그인하면 CSRF 토큰이 바뀐다").not.toBe(before);
  await tab.close();

  // 원래 탭: 본문이 그대로이고 같은 저장 단추가 새 세션·새 토큰으로 간다
  await expect(area).toHaveText("새 탭 로그인 뒤 본문");
  await page.bringToFront();
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page.getByRole("status")).toHaveText("저장했습니다");
  expect(saves).toEqual([{ status: 401, body: "<p>새 탭 로그인 뒤 본문</p>" }, { status: 204, body: "<p>새 탭 로그인 뒤 본문</p>" }]);
  expect(stack.sql(`SELECT body_html FROM pages WHERE id = '${id}'`)).toBe("<p>새 탭 로그인 뒤 본문</p>");
});

test("I-3: 편집 영역에서도 제목·목록·인용이 서식대로 보인다", async ({ page }) => {
  const { errors } = await open(page);
  const area = page.locator(".ProseMirror");
  await area.evaluate((el) => (Reflect.get(el, "editor") as { commands: { setContent(html: string): void } }).commands
    .setContent("<h2>제목</h2><p>문단</p><ul><li><p>항목</p></li></ul><blockquote><p>인용</p></blockquote>"));
  await expect(area.locator("blockquote")).toHaveText("인용");
  const style = (sel: string) => area.locator(sel).first().evaluate((el) => {
    const s = getComputedStyle(el);
    return { fontSize: parseFloat(s.fontSize), listStyleType: s.listStyleType, borderLeftStyle: s.borderLeftStyle, borderLeftWidth: parseFloat(s.borderLeftWidth) };
  });
  const [h2, p, ul, quote] = [await style("h2"), await style("p"), await style("ul"), await style("blockquote")];
  console.log("편집 영역 서식", JSON.stringify({ h2, p, ul, quote }));
  expect(h2.fontSize, "h2 글자가 문단보다 크다").toBeGreaterThan(p.fontSize);
  expect(ul.listStyleType, "ul 글머리").not.toBe("none");
  expect(quote.borderLeftStyle, "blockquote 왼쪽 테두리").not.toBe("none");
  expect(quote.borderLeftWidth, "blockquote 왼쪽 테두리 두께").toBeGreaterThan(0);
  expect(errors).toEqual([]);
});
