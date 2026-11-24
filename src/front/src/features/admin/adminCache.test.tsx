import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import { NoticeProvider } from "@/shared/ui/notice";
import { json, mockApi } from "@/test-api";
import { OplogPage } from "./OplogPage";
import { RolesPage } from "./RolesPage";
import { SystemPage } from "./SystemPage";
import { UserDetailPage } from "./UserDetailPage";
import { UsersPage } from "./UsersPage";

const ID = "00000000-0000-0000-0000-0000000000d1";
const ROLE = { id: "00000000-0000-0000-0000-0000000000a2", key: "editor", name: "편집자", is_superuser: false };

afterEach(() => vi.unstubAllGlobals());

/**
 * 계정에 묶인 관리자 데이터는 모두 ["admin", …] 키 아래에 둔다 — 로그인·로그아웃이 지우는 키다(useLogout·LoginPage).
 * 쿼리마다 gcTime 을 기억해야 하는 방식이면 하나만 잊어도 같은 탭의 다음 계정에게 앞 사람 데이터가 보인다(1-B1 최종 검토 I-1).
 */
it("관리자 화면의 쿼리는 모두 admin 키 아래에 있어 로그아웃 때 함께 지워진다", async () => {
  mockApi((req) => {
    const path = new URL(req.url).pathname;
    if (path === "/api/admin/system") return json(200, { version: "0.1.0", migrations: [], pending_count: 0, database: { server_version: "18" } });
    if (path === "/api/admin/users") return json(200, { items: [], page: 1, page_size: 50, has_next: false });
    if (path === `/api/admin/users/${ID}`) {
      return json(200, { id: ID, email: "target@ondolith.test", display_name: "대상", is_active: true, roles: [ROLE], assignable_roles: [ROLE] });
    }
    if (path === "/api/admin/roles") {
      return json(200, { roles: [{ ...ROLE, description: "", permissions: [{ key: "log.view", effective: true }] }], permissions: [{ key: "log.view", description: "" }] });
    }
    // 바꾸는 단추(useSave)까지 그리게 can 을 모두 준다
    if (path === "/api/me") return json(200, { can: { "user.update": true, "role.assign": true, "role.manage": true } });
    return json(200, { items: [], page: 1, page_size: 100, total: 0 });
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/admin/users/${ID}`]}>
        <NoticeProvider>
          <Routes>
            <Route path="/admin/users/:id" element={<><OplogPage /><SystemPage /><UsersPage /><UserDetailPage /><RolesPage /></>} />
          </Routes>
        </NoticeProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  await screen.findByRole("table", { name: "작업 로그" });
  await screen.findByRole("table", { name: "마이그레이션" });
  await screen.findByRole("table", { name: "사용자 목록" });
  await screen.findByText("target@ondolith.test");
  await screen.findByRole("region", { name: "편집자" });
  await screen.findAllByRole("button", { name: "빼기" });
  const keys = client.getQueryCache().findAll().map((q) => q.queryKey);
  expect(keys.filter((k) => k[0] === "admin").length).toBeGreaterThanOrEqual(5);
  // 사용자 목록은 can 을 읽으려고 /api/me 를 쓴다 — ["me"] 는 관리자 데이터가 아니고, 로그인·로그아웃이 ["admin"] 과 함께 지운다
  expect(keys.filter((k) => k[0] !== "admin")).toEqual([["me"]]);
  client.removeQueries({ queryKey: ["admin"] });
  client.removeQueries({ queryKey: ["me"] });
  expect(client.getQueryCache().findAll()).toEqual([]);
});
