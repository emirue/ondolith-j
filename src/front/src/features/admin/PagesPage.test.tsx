import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, mockApi, renderWithQuery } from "@/test-api";
import { PagesPage } from "./PagesPage";

afterEach(() => vi.unstubAllGlobals());

const AT = "2026-11-25T03:04:05Z";
const ID = "00000000-0000-4000-8000-000000000001";

const item = (over: Record<string, unknown> = {}) => ({ id: ID, title: "소개", slug: "about", status: "draft", updated_at: AT, ...over });
const list = (over: Record<string, unknown> = {}) => ({ items: [item()], page: 1, page_size: 50, has_next: false, ...over });

let client: QueryClient;
function Grab() {
  client = useQueryClient();
  return null;
}

/** /api/me 는 can 으로 고정, 목록 요청에는 respond 가 답한다. */
function renderPages(respond: (url: URL) => Response, route = "/admin/pages", can: Record<string, boolean> = {}) {
  const f = mockApi((req) => {
    const url = new URL(req.url);
    if (url.pathname === "/api/me") return json(200, { user: { id: "u1", email: "op@ondolith.test" }, can });
    return respond(url);
  });
  renderWithQuery(
    <>
      <Grab />
      <Routes>
        <Route path="/admin/pages" element={<PagesPage />} />
      </Routes>
    </>,
    route,
  );
  return f;
}

const lists = (f: ReturnType<typeof mockApi>) =>
  f.mock.calls.map(([req]) => new URL(req.url)).filter((u) => u.pathname === "/api/admin/pages").map((u) => u.searchParams.get("page"));
const table = () => screen.findByRole("table", { name: "페이지 목록" });

describe("페이지 목록", () => {
  it("쪽_링크와_범위_밖_첫_쪽으로", async () => {
    const f = renderPages((u) => json(200, list({ has_next: u.searchParams.get("page") === "1" })));
    const u = userEvent.setup();
    const next = await screen.findByRole("link", { name: "다음" });
    expect(next).toHaveAttribute("href", "/admin/pages?page=2");
    await u.click(next);
    expect(await screen.findByRole("link", { name: "이전" })).toHaveAttribute("href", "/admin/pages?page=1");
    expect(screen.getByRole("heading", { name: "페이지" })).toHaveFocus();
    expect(lists(f)).toEqual(["1", "2"]);
    cleanup();
    vi.unstubAllGlobals();
    renderPages(() => json(200, list({ items: [], page: 4 })), "/admin/pages?page=4");
    expect(await screen.findByRole("link", { name: "첫 쪽으로" })).toHaveAttribute("href", "/admin/pages?page=1");
    expect(screen.queryByRole("link", { name: "이전" })).toBeNull();
  });

  it("can_이면_새_페이지_링크_아니면_없다", async () => {
    renderPages(() => json(200, list()), "/admin/pages", { "page.create": true });
    expect(await screen.findByRole("link", { name: "새 페이지" })).toHaveAttribute("href", "/admin/pages/new");
    cleanup();
    vi.unstubAllGlobals();
    renderPages(() => json(200, list()), "/admin/pages", { "page.create": false });
    await table();
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.queryByRole("link", { name: "새 페이지" })).toBeNull();
  });

  it("제목은_상세_링크_주소는_글자 — can 이 비어도 링크(다르게 정한 것 9), 수정 시각은 toLocaleString", async () => {
    renderPages(() => json(200, list()));
    const t = await table();
    expect(within(t).getAllByRole("columnheader").map((th) => th.textContent)).toEqual(["제목", "주소", "상태", "수정 시각"]);
    expect(within(t).getByRole("link", { name: "소개" })).toHaveAttribute("href", `/admin/pages/${ID}`);
    expect(within(t).queryByRole("link", { name: "/about" })).toBeNull();
    const [, row] = within(t).getAllByRole("row").map((tr) => within(tr).queryAllByRole("cell").map((td) => td.textContent));
    expect(row).toEqual(["소개", "/about", "초안", new Date(AT).toLocaleString()]);
  });

  it("상태_이름표", async () => {
    renderPages(() => json(200, list({ items: [item(), item({ id: "b", title: "공지", slug: "notice", status: "published" })] })));
    const t = await table();
    const statuses = within(t).getAllByRole("row").slice(1).map((tr) => within(tr).getAllByRole("cell")[2].textContent);
    expect(statuses).toEqual(["초안", "발행"]);
  });

  it("LoadFailure_401_403_그_밖", async () => {
    renderPages(() => json(401, { status: 401, detail: "로그인이 필요합니다" }), "/admin/pages?page=2");
    expect(await screen.findByRole("alert")).toHaveTextContent("로그인이 끝났습니다");
    expect(screen.getByRole("link", { name: "다시 로그인" })).toHaveAttribute("href", "/login?next=%2Fadmin%2Fpages%3Fpage%3D2");
    cleanup();
    vi.unstubAllGlobals();
    renderPages(() => json(403, { status: 403, detail: "권한이 없습니다" }));
    expect(await screen.findByText("권한이 없습니다")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "페이지" })).toBeInTheDocument();
    expect(screen.queryByRole("table")).toBeNull();
    cleanup();
    vi.unstubAllGlobals();
    renderPages(() => json(500, {}));
    expect(await screen.findByRole("alert")).toHaveTextContent("페이지 목록을 불러오지 못했습니다");
  });

  it("쿼리의_gcTime_은_0", async () => {
    renderPages(() => json(200, list()));
    await table();
    await waitFor(() => expect(client.getQueryCache().find({ queryKey: ["admin", "pages", 1], exact: true })?.options.gcTime).toBe(0));
  });
});
