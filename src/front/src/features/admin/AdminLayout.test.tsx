import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, mockApi, renderWithQuery } from "@/test-api";
import { AdminLayout, adminRoutes } from "./AdminLayout";

afterEach(() => vi.unstubAllGlobals());

function renderLayout(can: Record<string, boolean>, route = "/admin") {
  mockApi(() => json(200, { user: { id: "u1", email: "op@ondolith.test", role: "operator" }, can }));
  return renderWithQuery(
    <Routes>
      <Route path="/admin" element={<AdminLayout />}>{adminRoutes}</Route>
    </Routes>,
    route,
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
});
