import type { Page } from "@playwright/test";
import { ADMIN_PASSWORD, expect, installSite, loginAdmin, test } from "./stack";

/** 1-B2: 사용자·역할·재인증(실제 jar, 스펙 12절 E2E). 시험마다 새 jar 라 로그인·관리자 횟수 제한은 시험마다 새로 센다. */

const USER_PASSWORD = "e2e-user-pass-not-real";
const NO_REAUTH = "DELETE FROM spring_session_attributes WHERE attribute_name = 'ondolithj.reauth_at'";

/** 메뉴 「사용자」 → 「새 사용자」로 계정을 만들고, 옮겨 간 상세에서 생성 알림까지 기다린다. */
async function createUser(page: Page, email: string) {
  await page.getByRole("navigation", { name: "관리자 메뉴" }).getByRole("link", { name: "사용자", exact: true }).click();
  await page.getByRole("link", { name: "새 사용자" }).click();
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("표시 이름").fill("시험 사용자");
  await page.getByLabel("비밀번호", { exact: true }).fill(USER_PASSWORD);
  await page.getByLabel("비밀번호 확인").fill(USER_PASSWORD);
  await page.getByRole("button", { name: "만들기" }).click();
  await expect(page.getByRole("status")).toHaveText("사용자를 만들었습니다");
  await expect(page.getByRole("heading", { name: "사용자 상세" })).toBeVisible();
}

/** 상세에서 「운영자」를 준다(창이 열려 있으면 대화상자 없이 바로 보낸다). */
async function grantOperator(page: Page) {
  await page.getByLabel("줄 역할").selectOption({ label: "운영자" });
  await page.getByRole("button", { name: "부여" }).click();
  await expect(page.getByRole("status")).toHaveText("역할을 부여했습니다");
  await expect(page.getByRole("list", { name: "가진 역할" }).getByRole("listitem").filter({ hasText: "운영자" })).toBeVisible();
}

test("사용자 생성 → 운영자 부여 → 그 사용자는 메뉴 다섯·바꾸는 단추 없음 → 역할 빼기 → 작업 로그", async ({ page, stack }) => {
  await installSite(page, stack);
  await loginAdmin(page);
  await createUser(page, "op@ondolith.test");
  await grantOperator(page);

  await page.getByRole("main").getByRole("button", { name: "로그아웃" }).click(); // 머리글에도 하나 있다
  await loginAdmin(page, "op@ondolith.test", USER_PASSWORD);
  const menu = page.getByRole("navigation", { name: "관리자 메뉴" });
  await expect(menu.getByRole("link")).toHaveText(["사이트 설정", "작업 로그", "시스템 정보", "사용자", "역할"]);

  // 「없다」는 행이 그려진 것을 본 뒤에 센다 — 응답 전이면 그냥 통과한다
  await menu.getByRole("link", { name: "사용자", exact: true }).click();
  const users = page.getByRole("table", { name: "사용자 목록" });
  await expect(users.getByRole("cell", { name: "op@ondolith.test", exact: true })).toBeVisible();
  await expect(users.getByRole("link")).toHaveCount(0);
  await expect(page.getByRole("link", { name: "새 사용자" })).toHaveCount(0);

  await menu.getByRole("link", { name: "역할", exact: true }).click();
  await expect(page.getByRole("region", { name: "편집자" })).toBeVisible();
  await expect(page.getByRole("button", { name: "부여" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "빼기" })).toHaveCount(0);

  await page.getByRole("main").getByRole("button", { name: "로그아웃" }).click(); // 머리글에도 하나 있다
  await loginAdmin(page);
  await menu.getByRole("link", { name: "사용자", exact: true }).click();
  await page.getByRole("link", { name: "op@ondolith.test" }).click();
  const held = page.getByRole("list", { name: "가진 역할" });
  await held.getByRole("listitem").filter({ hasText: "운영자" }).getByRole("button", { name: "빼기" }).click();
  const dialog = page.getByRole("alertdialog", { name: "역할을 뺄까요?" });
  await expect(dialog).toContainText("이 사용자에게서 운영자 역할을 뺍니다");
  await dialog.getByRole("button", { name: "확인" }).click();
  await expect(page.getByRole("status")).toHaveText("역할을 뺐습니다");
  await expect(held.getByRole("listitem")).toHaveCount(0);

  await page.goto("/admin/oplog");
  const oplog = page.getByRole("table", { name: "작업 로그" });
  await expect(oplog.getByRole("cell", { name: "사용자 생성", exact: true })).toHaveCount(1);
  await expect(oplog.getByRole("cell", { name: "역할 부여", exact: true })).toHaveCount(1);
  await expect(oplog.getByRole("cell", { name: "역할 빼기", exact: true })).toHaveCount(1);
});

test("재인증 창이 닫히면 같은 대화상자가 비밀번호를 묻고 비활성화된다", async ({ page, stack }) => {
  await installSite(page, stack);
  await loginAdmin(page);
  await createUser(page, "target@ondolith.test");
  // 로그인이 연 창(15분)을 닫는다 — 세션은 그대로다
  expect(stack.sql(NO_REAUTH)).toBe("DELETE 1");

  await page.getByRole("button", { name: "비활성화" }).click();
  const dialog = page.getByRole("alertdialog", { name: "사용자를 비활성화할까요?" });
  await dialog.getByRole("button", { name: "확인" }).click();
  await dialog.getByLabel("관리자 비밀번호 재확인").fill(ADMIN_PASSWORD);
  await dialog.getByRole("button", { name: "확인" }).click();
  await expect(page.getByRole("status")).toHaveText("사용자를 비활성화했습니다");
  await expect(page.getByText("비활성", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "활성화" })).toBeVisible();
});

test("역할 화면에서 편집자에게 권한을 준다", async ({ page, stack }) => {
  await installSite(page, stack);
  await loginAdmin(page);
  await page.goto("/admin/roles");
  const editor = page.getByRole("region", { name: "편집자" });
  await editor.getByLabel("줄 권한").selectOption("log.view");
  await editor.getByRole("button", { name: "부여" }).click();
  await expect(page.getByRole("status")).toHaveText("권한을 부여했습니다");
  const granted = editor.getByRole("list", { name: "권한" }).getByText("log.view", { exact: true });
  await expect(granted).toBeVisible();
  // 화면 상태가 아니라 서버에 들어갔다
  await page.reload();
  await expect(granted).toBeVisible();
});

test("확인 단추를 두 번 눌러도 POST 는 하나", async ({ page, stack }) => {
  await installSite(page, stack);
  await loginAdmin(page);
  await createUser(page, "twice@ondolith.test");
  await grantOperator(page);
  // 로컬 jar 는 둘째 클릭보다 먼저 204 를 낼 수 있다 — 첫 POST 를 1초 붙잡아 둘째 클릭이 보내는 중에 떨어지게 한다
  await page.route("**/api/admin/users/*/roles/revoke", async (route) => {
    await new Promise((r) => setTimeout(r, 1000));
    await route.continue();
  });
  let posts = 0;
  page.on("request", (r) => {
    if (r.method() === "POST" && new URL(r.url()).pathname.endsWith("/roles/revoke")) posts++;
  });
  await page.getByRole("list", { name: "가진 역할" }).getByRole("button", { name: "빼기" }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "확인" }).dblclick();
  await expect(page.getByRole("status")).toHaveText("역할을 뺐습니다");
  // 늦게 나간 둘째 요청을 놓치지 않게 조금 더 기다린 뒤 센다
  await page.waitForTimeout(1500);
  expect(posts).toBe(1);
});

test("키보드만으로 대화상자를 열고 비밀번호를 넣고 확인한다", async ({ page, stack }) => {
  await installSite(page, stack);
  await loginAdmin(page);
  await createUser(page, "keys@ondolith.test");
  expect(stack.sql(NO_REAUTH)).toBe("DELETE 1");
  const posts: string[] = [];
  page.on("response", (r) => {
    if (r.request().method() === "POST") posts.push(`${new URL(r.url()).pathname.split("/").pop()} ${r.status()}`);
  });

  const k = page.keyboard;
  const open = page.getByRole("button", { name: "비활성화" });
  await expect(page.getByRole("heading", { name: "사용자 상세" })).toBeFocused();
  await expect(open).toBeVisible();
  await k.press("Tab");
  await expect(open).toBeFocused();
  await k.press("Enter");

  const dialog = page.getByRole("alertdialog", { name: "사용자를 비활성화할까요?" });
  const confirm = dialog.getByRole("button", { name: "확인" });
  // Radix 는 열 때 「취소」에 초점을 준다
  await expect(dialog.getByRole("button", { name: "취소" })).toBeFocused();
  await k.press("Tab");
  await expect(confirm).toBeFocused();
  await k.press("Enter");

  const password = dialog.getByLabel("관리자 비밀번호 재확인");
  await expect(password).toBeFocused();
  await k.type(ADMIN_PASSWORD);
  await k.press("Tab"); // 취소
  await k.press("Tab");
  await expect(confirm).toBeFocused();
  await k.press("Enter");
  await expect(page.getByRole("status")).toHaveText("사용자를 비활성화했습니다");
  await page.waitForTimeout(500);
  // 확인은 한 번 — 창이 닫혀 첫 POST 는 재인증 403, 재인증 뒤 원 요청을 한 번 다시 보낸다
  expect(posts).toEqual(["deactivate 403", "reauth 204", "deactivate 204"]);
});
