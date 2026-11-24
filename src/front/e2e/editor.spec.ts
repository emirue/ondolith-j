import type { CDPSession, Page } from "@playwright/test";
import { expect, offline, test } from "./stack";

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
