import type { CDPSession, Page } from "@playwright/test";
import { EDITOR_TAGS, editorInvariant, vectors } from "../src/test-vectors";
import { expect, offline, test, type CspViolation } from "./stack";

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
  X3: ATTR, X4: ATTR, X5: ATTR, X11: ATTR, X14: ATTR, X15: ATTR,
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

test("붙여넣기: 표·색·밑줄은 빠지고 글자는 남는다", async ({ page }) => {
  const { errors } = await open(page);
  await page.locator(".ProseMirror").focus();
  expect(await paste(page, '<table><tr><td>칸</td></tr></table><p style="color:red"><u>색</u></p>')).toBe(true);
  const html = await editorHtml(page);
  expect(html).not.toMatch(/table|style|<u/);
  expect(html).toContain("칸");
  expect(html).toContain("색");
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
