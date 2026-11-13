import { expect, test } from "./stack";

const SITE = "E2E 사이트";

test("설치 흐름: 설치 폼 → 대기 화면 → 홈에 사이트 이름", async ({ page, stack }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "사이트 설치" })).toBeVisible();
  await page.getByLabel("DB 호스트").fill(stack.dbHost);
  await page.getByLabel("DB 포트").fill(String(stack.dbPort));
  await page.getByLabel("DB 사용자").fill(stack.dbUser);
  await page.getByLabel("DB 비밀번호").fill(stack.dbPassword);
  await page.getByLabel("DB 이름").fill(stack.dbName);
  await page.getByLabel("SSL 모드").selectOption("disable");
  await page.getByLabel("사이트 이름").fill(SITE);
  await page.getByLabel("관리자 이메일").fill("admin@ondolith.test");
  await page.getByLabel("관리자 비밀번호").fill("e2e-admin-pass");
  await page.getByLabel("비밀번호 확인").fill("e2e-admin-pass");
  await page.getByRole("button", { name: "설치" }).click();
  await expect(page.getByRole("heading", { name: "사이트를 준비하고 있습니다" })).toBeVisible();
  await expect(page.getByRole("heading", { name: SITE })).toBeVisible({ timeout: 60_000 });
});

test("키보드만으로 설치", async ({ page, stack }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "사이트 설치" })).toBeFocused();
  const k = page.keyboard;
  const next = async (text?: string, replace = false) => {
    await k.press("Tab");
    if (replace) await k.press("ControlOrMeta+a");
    if (text !== undefined) await k.type(text);
  };
  await next(stack.dbHost);
  await next(String(stack.dbPort), true);
  await next(stack.dbUser);
  await next(stack.dbPassword);
  await next(stack.dbName);
  await next();
  await k.type("d"); // SSL 모드: 첫 글자로 고른다(macOS Chromium 은 화살표가 목록을 연다)
  await expect(page.getByLabel("SSL 모드")).toHaveValue("disable");
  await next(SITE);
  await next(); // 사이트 유형: cms 그대로
  await next(); // 사이트 주소: 미리 채워진 값 그대로
  await next("admin@ondolith.test");
  await next("e2e-admin-pass");
  await next("e2e-admin-pass");
  await k.press("Enter");
  await expect(page.getByRole("heading", { name: "사이트를 준비하고 있습니다" })).toBeVisible();
  await expect(page.getByRole("heading", { name: SITE })).toBeVisible({ timeout: 60_000 });
});
