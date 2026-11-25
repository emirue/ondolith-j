import type { Page } from "@playwright/test";
import { ALLOWED_TAGS, frontInvariant, vectors } from "../src/test-vectors";
import { expect, installSite, loginAdmin, offline, test, watch } from "./stack";

// 1-C1 스펙 8.3 문자열 그대로 적는다 — vite.config.ts 의 상수를 읽으면 스스로와 비교하게 된다
const CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; font-src 'self'; connect-src 'self'; "
  + "object-src 'none'; base-uri 'none'; form-action 'self'; frame-src 'none'";

// 계획 「다르게 정한 것」 5: 대화상자 대신 표시를 남긴다. vectors.json 은 스펙 표 그대로 둔다
const xss = (input: string) => input.replaceAll("alert(1)", "window.__xss=1");
const marker = (page: Page) => page.evaluate(() => Reflect.get(window, "__xss"));

test("CSP 가 실제로 걸려 있다", async ({ page, guard }) => {
  await offline(page);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "E2E 사이트" })).toBeVisible();
  await expect(page.locator('meta[http-equiv="Content-Security-Policy"]')).toHaveAttribute("content", CSP);
  // <meta> CSP 는 그 뒤부터 걸린다 — head 맨 앞이어야 한다(스펙 8.3)
  expect(await page.evaluate(() => document.head.firstElementChild?.getAttribute("http-equiv"))).toBe("Content-Security-Policy");

  // 이 시험만 인라인 스크립트 위반 하나를 기대한다
  guard.allow((v) => /^script-src(-elem)?$/.test(v.directive) && v.blocked === "inline");
  await page.evaluate(() => {
    const s = document.createElement("script");
    s.textContent = "window.__inline = 1";
    document.head.append(s);
  });
  await expect.poll(() => guard.violations.length).toBe(1);
  expect(guard.violations[0].directive).toMatch(/^script-src(-elem)?$/);
  expect(await page.evaluate(() => Reflect.get(window, "__inline"))).toBeUndefined();
});

test("API 로 만든 페이지가 비로그인 방문자에게 정화된 채로 보인다", async ({ page, stack, browser }) => {
  await installSite(page, stack);
  await loginAdmin(page);
  // page.request 는 문맥의 쿠키를 쓴다. CSRF 는 앱처럼 XSRF-TOKEN 쿠키를 헤더로(부를 때마다 다시 읽는다)
  const post = async (url: string, data?: object) => {
    const token = (await page.context().cookies()).find((c) => c.name === "XSRF-TOKEN")?.value;
    expect(token, "XSRF-TOKEN 쿠키").toBeTruthy();
    const res = await page.request.post(url, { headers: { "X-XSRF-TOKEN": token! }, data });
    expect(res.status(), url).toBeLessThan(300);
    return res;
  };
  const attacks = ["X1", "X3", "X14", "X16"].map((id) => xss(vectors.find((v) => v.id === id)!.input)).join("");
  const body = '<h2>소제목</h2><p><strong>굵게</strong> <a href="/about">안</a> <a href="https://example.com/">밖</a></p>'
    + "<ul><li>하나</li></ul>" + attacks;
  const { id } = await (await post("/api/admin/pages", { slug: "about", title: "소개", body_html: body })).json();
  await post("/api/admin/pages", { slug: "draft-page", title: "초안", body_html: "<p>초안</p>" });
  // 발행은 읽은 상세의 updated_at 을 문자열 그대로 싣는다(1-C2 스펙 6.1)
  const detail = await page.request.get(`/api/admin/pages/${id}`);
  expect(detail.status()).toBe(200);
  await post(`/api/admin/pages/${id}/publish`, { updated_at: (await detail.json()).updated_at });

  const visitor = await browser.newContext();
  const seen = await watch(visitor);
  const guest = await visitor.newPage();
  await guest.goto("/about");
  await expect(guest.getByRole("heading", { level: 1, name: "소개" })).toBeVisible();
  const rich = guest.locator(".rich");
  await expect(rich.locator("strong")).toHaveText("굵게");
  await expect(rich.getByRole("link", { name: "안" })).toHaveAttribute("href", "/about");
  await expect(rich.getByRole("link", { name: "밖" })).toHaveAttribute("href", "https://example.com/");
  // React <title> 이 정적 index.html 의 것을 이기는지(「다르게 정한 것」 7). 사이트 이름은 따로 오므로 기다린다
  await expect(guest).toHaveTitle("소개 – E2E 사이트");
  await expect(rich.locator("script")).toHaveCount(0);
  expect(await rich.evaluate((root) =>
    [...root.querySelectorAll("*")].flatMap((el) => el.getAttributeNames().filter((n) => n.toLowerCase().startsWith("on"))))).toEqual([]);
  await guest.waitForLoadState("networkidle");
  expect(await marker(guest)).toBeUndefined();

  for (const path of ["/draft-page", "/no-such-page"]) {
    await guest.goto(path);
    await expect(guest.getByRole("heading", { name: "찾을 수 없습니다" })).toBeVisible();
    await expect(guest.locator('meta[name="robots"][content="noindex"]')).toHaveCount(1);
  }

  await post(`/api/admin/pages/${id}/unpublish`);
  await guest.goto("/about");
  await expect(guest.getByRole("heading", { name: "찾을 수 없습니다" })).toBeVisible();
  seen.check();
  await visitor.close();
});

test("DOMPurify 겹: 서버를 거치지 않은 오염 HTML 도 스크립트가 돌지 않는다", async ({ page, guard }) => {
  test.setTimeout(300_000); // 92줄, 줄마다 goto·networkidle
  const rows = vectors.map((v) => ({ id: v.id, slug: `v-${v.id.toLowerCase()}`, html: xss(v.input) }));
  expect(rows.filter((r) => r.html.includes("alert")).map((r) => r.id), "바꾸지 못한 alert").toEqual([]);
  await offline(page);
  await page.route("**/api/media/**", (r) => r.fulfill({ status: 200, body: "" }));
  await page.route("**/api/pages/*", (r) => {
    const slug = new URL(r.request().url()).pathname.split("/").pop();
    const row = rows.find((x) => x.slug === slug);
    return row ? r.fulfill({ json: { slug, title: row.id, body_html: row.html } }) : r.fulfill({ status: 404 });
  });
  // 셋째 겹의 증거: DOMPurify 가 통과시킨 외부·data: 이미지(스펙 8.1)를 CSP img-src 가 막는다. 그 두 줄에서만
  const imgRows = ["/v-i1", "/v-i7"];
  const imgBlocked = (path: string) => guard.violations.filter((v) => v.directive === "img-src" && new URL(v.url).pathname === path);
  guard.allow((v) => v.directive === "img-src" && imgRows.includes(new URL(v.url).pathname));
  // Chrome 은 DOMPurify 가 쓰는 붙이지 않은 DOMParser 문서에서도 파싱 때 처리기 속성·<base> 를 CSP 로 보고한다(2026-11-24 탐침).
  // 그래서 그 입력이 든 줄에서만 받는다. 처리기가 실제 DOM 에 닿았는지는 불변식과 __xss 표시가 본다
  const attrRows = ["/v-x3", "/v-x4", "/v-x5", "/v-x11", "/v-x14", "/v-x15"];
  guard.allow((v) => v.directive === "script-src-attr" && v.blocked === "inline" && attrRows.includes(new URL(v.url).pathname));
  guard.allow((v) => v.directive === "base-uri" && v.blocked === "https://evil.example/" && new URL(v.url).pathname === "/v-x9");

  for (const row of rows) {
    await page.goto(`/${row.slug}`);
    await expect(page.getByRole("heading", { level: 1, name: row.id, exact: true })).toBeVisible();
    expect(await page.locator(".rich").evaluate(frontInvariant, ALLOWED_TAGS), row.id).toEqual([]);
    await page.waitForLoadState("networkidle");
    expect(await marker(page), row.id).toBeUndefined();
  }
  for (const path of imgRows) await expect.poll(() => imgBlocked(path).length, path).toBeGreaterThan(0);
  expect(guard.violations.filter((v) => v.directive === "script-src" || v.directive === "script-src-elem")).toEqual([]);
});
