import { ADMIN_EMAIL, ADMIN_PASSWORD, expect, installSite, test } from "./stack";

/** 1-A: 설치 → 관리자 문 → 로그인 → 관리자 셸 → 로그아웃(실제 jar, 1-A 스펙 8절). */
test("로그인 흐름: 막힌 관리자 → 로그인 → 셸 → 로그아웃 → 다시 막힘", async ({ page, stack }) => {
  await installSite(page, stack);
  await page.goto("/admin");
  await expect(page).toHaveURL(/\/login\?next=%2Fadmin$/);
  await expect(page.getByRole("heading", { name: "로그인" })).toBeVisible();

  await page.getByLabel("이메일").fill(ADMIN_EMAIL);
  await page.getByLabel("비밀번호").fill("wrong-pass-0000");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page.getByRole("alert")).toHaveText("이메일 또는 비밀번호가 맞지 않습니다");

  await page.getByLabel("비밀번호").fill(ADMIN_PASSWORD);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page.getByRole("heading", { name: "관리자" })).toBeVisible();
  await expect(page).toHaveURL(/\/admin$/);
  await expect(page.getByRole("banner")).toContainText(ADMIN_EMAIL);

  await page.getByRole("main").getByRole("button", { name: "로그아웃" }).click();
  await expect(page).toHaveURL(/127\.0\.0\.1:5173\/$/);
  await expect(page.getByRole("banner").getByRole("link", { name: "로그인" })).toBeVisible();
  await page.goto("/admin");
  await expect(page).toHaveURL(/\/login\?next=%2Fadmin$/);
});

test("키보드만으로 로그인", async ({ page, stack }) => {
  await installSite(page, stack);
  await page.goto("/login?next=/admin");
  await expect(page.getByRole("heading", { name: "로그인" })).toBeFocused();
  const k = page.keyboard;
  await k.press("Tab");
  await k.type(ADMIN_EMAIL);
  await k.press("Tab");
  await k.type(ADMIN_PASSWORD);
  await k.press("Enter");
  await expect(page.getByRole("heading", { name: "관리자" })).toBeFocused();
});
