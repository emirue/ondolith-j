import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, LocationProbe, mockApi, renderWithQuery } from "@/test-api";
import { AdminGate } from "./AdminGate";

afterEach(() => vi.unstubAllGlobals());

function renderAdmin() {
  return renderWithQuery(
    <Routes>
      <Route path="/admin" element={<AdminGate />} />
      <Route path="*" element={<LocationProbe />} />
    </Routes>,
    "/admin",
  );
}

describe("관리자 문", () => {
  it("로그인하지 않았으면(401) 로그인 화면으로 보낸다", async () => {
    mockApi(() => json(401, { status: 401, detail: "로그인이 필요합니다" }));
    renderAdmin();
    expect(await screen.findByTestId("location")).toHaveTextContent("/login?next=%2Fadmin");
  });

  it("권한이 없으면(403) 권한이 없다고 보인다", async () => {
    mockApi(() => json(403, { status: 403, detail: "권한이 없습니다" }));
    renderAdmin();
    expect(await screen.findByRole("heading", { name: "권한이 없습니다" })).toBeInTheDocument();
  });

  it("통과하면 관리자 셸과 로그아웃 단추, 로그아웃하면 홈으로", async () => {
    const api = mockApi((req) => (req.url.endsWith("/api/auth/logout") ? new Response(null, { status: 204 }) : json(200, {})));
    renderAdmin();
    expect(await screen.findByRole("heading", { name: "관리자" })).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "로그아웃" }));
    expect(await screen.findByTestId("location")).toHaveTextContent(/^\/$/);
    expect(api.mock.calls.some(([r]) => r.url.endsWith("/api/auth/logout") && r.method === "POST")).toBe(true);
  });
});
