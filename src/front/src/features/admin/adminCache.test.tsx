import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import { json, mockApi } from "@/test-api";
import { OplogPage } from "./OplogPage";
import { SystemPage } from "./SystemPage";
import { UsersPage } from "./UsersPage";

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
    if (path === "/api/me") return json(200, { can: {} });
    return json(200, { items: [], page: 1, page_size: 100, total: 0 });
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/x"]}>
        <Routes>
          <Route path="/x" element={<><OplogPage /><SystemPage /><UsersPage /></>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  await screen.findByRole("table", { name: "작업 로그" });
  await screen.findByRole("table", { name: "마이그레이션" });
  await screen.findByRole("table", { name: "사용자 목록" });
  const keys = client.getQueryCache().findAll().map((q) => q.queryKey);
  expect(keys.filter((k) => k[0] === "admin").length).toBeGreaterThanOrEqual(3);
  // 사용자 목록은 can 을 읽으려고 /api/me 를 쓴다 — ["me"] 는 관리자 데이터가 아니고, 로그인·로그아웃이 ["admin"] 과 함께 지운다
  expect(keys.filter((k) => k[0] !== "admin")).toEqual([["me"]]);
  client.removeQueries({ queryKey: ["admin"] });
  client.removeQueries({ queryKey: ["me"] });
  expect(client.getQueryCache().findAll()).toEqual([]);
});
