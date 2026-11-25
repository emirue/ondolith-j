import { expect, installSite, loginAdmin, test } from "./stack";

/** 1-B1: 관리자 셸 흐름(실제 jar, 스펙 8절 E2E). 시험마다 새 jar 라 로그인 횟수 제한은 시험마다 새로 센다. */
test("관리자 셸 흐름: 메뉴 여섯 → 이름 저장 → 알림 → 홈 → 작업 로그 → 시스템 정보", async ({ page, stack }) => {
  await installSite(page, stack);
  await loginAdmin(page);
  const menu = page.getByRole("navigation", { name: "관리자 메뉴" });
  await expect(menu.getByRole("link")).toHaveText(["사이트 설정", "작업 로그", "시스템 정보", "사용자", "역할", "페이지"]);

  await menu.getByRole("link", { name: "사이트 설정" }).click();
  await page.getByLabel("사이트 이름").fill("바뀐 이름");
  await page.getByRole("button", { name: "저장" }).click();
  // 알림은 화면을 옮기면 지워진다 — 옮기기 전에 본다
  await expect(page.getByRole("status")).toHaveText("사이트 설정을 저장했습니다");

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "바뀐 이름" })).toBeVisible();

  await page.goto("/admin/oplog");
  const oplog = page.getByRole("table", { name: "작업 로그" });
  await expect(oplog.getByRole("cell", { name: "로그인", exact: true })).toHaveCount(1);
  await expect(oplog.getByRole("cell", { name: "사이트 설정 변경", exact: true })).toHaveCount(1);

  await page.goto("/admin/system");
  const v3 = page.getByRole("table", { name: "마이그레이션" }).getByRole("row")
    .filter({ has: page.getByRole("cell", { name: "3", exact: true }) });
  await expect(v3.getByRole("cell", { name: "성공", exact: true })).toBeVisible();
});

test("저장 단추를 두 번 눌러도 POST 는 하나", async ({ page, stack }) => {
  await installSite(page, stack);
  await loginAdmin(page);
  // 로컬 jar 는 둘째 클릭보다 먼저 204 를 낼 수 있다 — 첫 POST 를 1초 붙잡아 둘째 클릭이 보내는 중에 떨어지게 한다
  await page.route("**/api/admin/settings", async (route) => {
    await new Promise((r) => setTimeout(r, 1000));
    await route.continue();
  });
  let posts = 0;
  page.on("request", (r) => {
    if (r.method() === "POST" && new URL(r.url()).pathname === "/api/admin/settings") posts++;
  });
  await page.goto("/admin/settings");
  await page.getByLabel("사이트 이름").fill("두 번 누른 이름");
  await page.getByRole("button", { name: "저장" }).dblclick();
  await expect(page.getByRole("status")).toHaveText("사이트 설정을 저장했습니다");
  // 늦게 나간 둘째 요청을 놓치지 않게 조금 더 기다린 뒤 센다
  await page.waitForTimeout(1500);
  expect(posts).toBe(1);
});

test("키보드만으로 메뉴 이동과 저장", async ({ page, stack }) => {
  await installSite(page, stack);
  await loginAdmin(page);
  const k = page.keyboard;
  const link = page.getByRole("navigation", { name: "관리자 메뉴" }).getByRole("link", { name: "사이트 설정" });
  await expect(page.getByRole("heading", { name: "관리자", exact: true })).toBeFocused();
  // 메뉴는 /api/me 가 온 뒤 그려진다 — 그 전에 Tab 을 누르면 로그아웃 다음이 비어 있다
  await expect(link).toBeVisible();
  await k.press("Tab"); // 로그아웃
  await k.press("Tab");
  await expect(link).toBeFocused();
  await k.press("Enter");

  const name = page.getByLabel("사이트 이름");
  await expect(page.getByRole("heading", { name: "사이트 설정" })).toBeFocused();
  // 폼은 /api/site 가 온 뒤 그려진다 — 칸이 보이기 전에 Tab 을 누르면 초점이 body 로 빠진다
  await expect(name).toBeVisible();
  await k.press("Tab");
  await expect(name).toBeFocused();
  await k.press("ControlOrMeta+A");
  await k.type("키보드 이름");
  await k.press("Enter");
  await expect(page.getByRole("status")).toHaveText("사이트 설정을 저장했습니다");
  await expect(name).toHaveValue("키보드 이름");
});
