import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, mockApi, renderWithQuery } from "@/test-api";
import { AdminLayout, adminRoutes } from "./AdminLayout";

afterEach(() => vi.unstubAllGlobals());

function renderLayout(can: Record<string, boolean>, route = "/admin") {
  mockApi((req) => {
    const path = new URL(req.url).pathname;
    if (path === "/api/admin/oplog") return json(200, { items: [], page: 1, page_size: 100, total: 0 });
    if (path === "/api/admin/system") return json(200, { version: "t", database: { server_version: "18" }, pending_count: 0, migrations: [] });
    if (path === "/api/admin/users") return json(200, { items: [], page: 1, page_size: 50, has_next: false });
    return json(200, { user: { id: "u1", email: "op@ondolith.test", role: "operator" }, can });
  });
  return renderWithQuery(
    <Routes>
      <Route path="/admin" element={<AdminLayout />}>{adminRoutes}</Route>
    </Routes>,
    route,
    // 새 사용자 화면이 useSave(알림)를 쓴다
    { notice: true },
  );
}

describe("관리자 셸 메뉴", () => {
  it("can 이 true 인 항목만 메뉴에 그린다", async () => {
    renderLayout({ "admin.access": true, "settings.update": true, "log.view": false, "settings.view": true });
    const nav = screen.getByRole("navigation", { name: "관리자 메뉴" });
    expect(await within(nav).findByRole("link", { name: "사이트 설정" })).toHaveAttribute("href", "/admin/settings");
    expect(within(nav).getByRole("link", { name: "시스템 정보" })).toHaveAttribute("href", "/admin/system");
    expect(screen.queryByRole("link", { name: "작업 로그" })).toBeNull();
  });

  it("admin.access 만 있으면(editor) 메뉴 링크가 없다", async () => {
    renderLayout({ "admin.access": true, "settings.update": false, "log.view": false, "settings.view": false });
    // /api/me 응답이 반영된 뒤에 본다(오기 전에도 링크 0 이라 헛통과한다)
    await vi.waitFor(() => expect(vi.mocked(fetch)).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 0));
    expect(within(screen.getByRole("navigation", { name: "관리자 메뉴" })).queryAllByRole("link")).toHaveLength(0);
  });

  it("/admin/oplog 로 시작하면 표에서 만든 라우트의 화면 제목이 보인다", async () => {
    renderLayout({ "admin.access": true, "log.view": true }, "/admin/oplog");
    expect(await screen.findByRole("heading", { name: "작업 로그" })).toBeInTheDocument();
  });

  it("메뉴를 누르면 현재 항목에 aria-current, 초점은 새 화면 제목", async () => {
    renderLayout({ "admin.access": true, "settings.update": true, "log.view": true, "settings.view": true });
    const link = await screen.findByRole("link", { name: "작업 로그" });
    await userEvent.setup().click(link);
    expect(await screen.findByRole("heading", { name: "작업 로그" })).toHaveFocus();
    expect(link).toHaveAttribute("aria-current", "page");
  });

  it("can['user.view']·can['role.view'] 가 true 면 메뉴에 「사용자」·「역할」, 순서는 표 순서", async () => {
    renderLayout({ "settings.update": true, "log.view": true, "settings.view": true, "user.view": true, "role.view": true });
    const nav = screen.getByRole("navigation", { name: "관리자 메뉴" });
    expect(await within(nav).findByRole("link", { name: "사용자" })).toHaveAttribute("href", "/admin/users");
    expect(within(nav).getByRole("link", { name: "역할" })).toHaveAttribute("href", "/admin/roles");
    expect(within(nav).getAllByRole("link").map((a) => a.textContent)).toEqual(["사이트 설정", "작업 로그", "시스템 정보", "사용자", "역할"]);
  });

  it("can['user.view']·can['role.view'] 가 false 면 「사용자」·「역할」이 없다", async () => {
    renderLayout({ "settings.update": true, "user.view": false, "role.view": false });
    const nav = screen.getByRole("navigation", { name: "관리자 메뉴" });
    await within(nav).findByRole("link", { name: "사이트 설정" });
    expect(within(nav).queryByRole("link", { name: "사용자" })).toBeNull();
    expect(within(nav).queryByRole("link", { name: "역할" })).toBeNull();
  });

  it("메뉴 아닌 항목(새 사용자·사용자 상세)은 can 이 모두 true 여도 메뉴에 없다", async () => {
    renderLayout({ "settings.update": true, "log.view": true, "settings.view": true, "user.view": true, "role.view": true, "user.create": true, "user.update": true });
    const nav = screen.getByRole("navigation", { name: "관리자 메뉴" });
    await within(nav).findByRole("link", { name: "역할" });
    expect(within(nav).getAllByRole("link").map((a) => a.textContent)).toEqual(["사이트 설정", "작업 로그", "시스템 정보", "사용자", "역할"]);
  });

  it("/admin/users/new 는 사용자 상세가 아니라 「새 사용자」 화면", async () => {
    renderLayout({ "user.create": true }, "/admin/users/new");
    expect(await screen.findByRole("heading", { name: "새 사용자" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "사용자 상세" })).toBeNull();
  });

  it("/admin 은 경고 없이 빈 대시보드(M-4)", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    try {
      renderLayout({ "admin.access": true });
      await vi.waitFor(() => expect(vi.mocked(fetch)).toHaveBeenCalled());
      await new Promise((r) => setTimeout(r, 0));
      expect(screen.queryByRole("heading", { level: 2 })).toBeNull();
      expect(warn).not.toHaveBeenCalled();
    } finally {
      warn.mockRestore();
    }
  });
});
