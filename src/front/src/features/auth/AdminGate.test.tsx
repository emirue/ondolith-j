import type { Editor } from "@tiptap/react";
import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, LocationProbe, mockApi, renderWithQuery } from "@/test-api";
import AdminTree, { AdminGate } from "./AdminGate";

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

  it("하위 주소에서 401 이면 next 가 그 주소(쿼리까지)를 담는다", async () => {
    mockApi(() => json(401, { status: 401, detail: "로그인이 필요합니다" }));
    renderWithQuery(
      <Routes>
        <Route path="/admin/*" element={<AdminGate />} />
        <Route path="*" element={<LocationProbe />} />
      </Routes>,
      "/admin/oplog?page=2",
    );
    expect(await screen.findByTestId("location")).toHaveTextContent(/^\/login\?next=%2Fadmin%2Foplog%3Fpage%3D2$/);
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

describe("관문 다시 확인 (1-C2 최종 검토 I-1)", () => {
  const U = "00000000-0000-4000-8000-000000000001";
  const PAGE = { id: U, slug: "about", title: "소개", status: "draft", body_html: "<p>본문</p>", created_at: "2026-11-24T01:02:03.123456Z", updated_at: "2026-11-24T01:02:03.123456Z" };
  const area = () => document.querySelector<HTMLElement & { editor: Editor }>(".ProseMirror");
  const settle = () => act(() => new Promise((r) => setTimeout(r, 50)));

  it.each([401, 503])("편집_중_창에_돌아오거나_다시_이어져도_관문이_%i_로_바뀌었다고_편집기를_내리지_않는다", async (broken) => {
    let status = 200;
    const api = mockApi((req) => {
      const path = new URL(req.url).pathname;
      if (path === "/api/admin") return json(status, {});
      if (path === "/api/me") return status === 200 ? json(200, { can: { "page.view": true, "page.update": true } }) : json(status, {});
      if (path === `/api/admin/pages/${U}`) return json(200, PAGE);
      return json(404, {});
    });
    renderWithQuery(
      <Routes>
        <Route path="/admin/*" element={<AdminTree />} />
        <Route path="*" element={<LocationProbe />} />
      </Routes>,
      `/admin/pages/${U}`,
      { notice: true },
    );
    await waitFor(() => expect(area()?.editor.getHTML()).toBe("<p>본문</p>"));
    await userEvent.setup().type(screen.getByLabelText("제목"), " 고침");
    act(() => {
      area()!.editor.commands.insertContent("쓰던 글");
    });
    const written = area()!.editor.getHTML();

    // 세션이 끝났거나(401) 서버가 잠시 내려갔다(503) — 관문과 me 가 함께 바뀐다
    status = broken;
    act(() => {
      window.dispatchEvent(new Event("focus"));
      document.dispatchEvent(new Event("visibilitychange", { bubbles: true }));
      window.dispatchEvent(new Event("offline"));
      window.dispatchEvent(new Event("online"));
    });
    await settle();

    expect(screen.queryByTestId("location")).toBeNull();
    expect(area()?.editor.getHTML()).toBe(written);
    expect(written).toContain("쓰던 글");
    expect(screen.getByLabelText("제목")).toHaveValue("소개 고침");
    expect(api.mock.calls.filter(([r]) => new URL(r.url).pathname === "/api/admin")).toHaveLength(1);
  });
});
