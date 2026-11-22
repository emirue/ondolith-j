import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Link, Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { LocationProbe, json, mockApi, renderWithQuery } from "@/test-api";
import { UsersPage } from "./UsersPage";

afterEach(() => vi.unstubAllGlobals());

const AT = "2026-11-21T03:04:05Z";
const ID = "00000000-0000-0000-0000-00000000000a";

const user = (over: Record<string, unknown> = {}) => ({
  id: ID,
  email: "kim@ondolith.test",
  display_name: "김",
  is_active: true,
  roles: ["admin", "editor"],
  created_at: AT,
  ...over,
});

const page = (over: Record<string, unknown> = {}) => ({ items: [user()], page: 1, page_size: 50, has_next: false, ...over });

/** /api/me 는 can 으로 고정, 목록 요청에는 respond 가 답한다. */
function renderUsers(respond: (url: URL) => Response, route = "/admin/users", can: Record<string, boolean> = {}) {
  const f = mockApi((req) => {
    const url = new URL(req.url);
    if (url.pathname === "/api/me") return json(200, { user: { id: "u1", email: "op@ondolith.test" }, can });
    return respond(url);
  });
  renderWithQuery(
    <Routes>
      <Route path="/admin/users" element={<><UsersPage /><LocationProbe /></>} />
    </Routes>,
    route,
  );
  return f;
}

/** 목록 요청의 [page, q]. */
const lists = (f: ReturnType<typeof mockApi>) =>
  f.mock.calls
    .map(([req]) => new URL(req.url))
    .filter((u) => u.pathname === "/api/admin/users")
    .map((u) => [u.searchParams.get("page"), u.searchParams.get("q")]);

describe("사용자 목록", () => {
  it("표: 이메일·표시 이름·상태(활성/비활성)·역할·만든 날", async () => {
    renderUsers(() => json(200, page({ items: [user(), user({ id: "b", email: "lee@ondolith.test", display_name: "이", is_active: false, roles: [] })] })));
    const table = await screen.findByRole("table", { name: "사용자 목록" });
    expect(within(table).getAllByRole("columnheader").map((th) => th.textContent)).toEqual(["이메일", "표시 이름", "상태", "역할", "만든 날"]);
    const [, kim, lee] = within(table).getAllByRole("row").map((tr) => within(tr).queryAllByRole("cell").map((td) => td.textContent));
    expect(kim).toEqual(["kim@ondolith.test", "김", "활성", "admin, editor", new Date(AT).toLocaleDateString()]);
    expect(lee).toEqual(["lee@ondolith.test", "이", "비활성", "", new Date(AT).toLocaleDateString()]);
  });

  it("검색 중 has_next 면 「다음」은 q 를 담는다 — ?page=2&q=a_b (Review Focus 2)", async () => {
    const f = renderUsers((u) => json(200, page({ page: Number(u.searchParams.get("page")), has_next: u.searchParams.get("page") === "1" })), "/admin/users?q=a_b");
    const next = await screen.findByRole("link", { name: "다음" });
    expect(next).toHaveAttribute("href", "/admin/users?page=2&q=a_b");
    expect(lists(f)).toEqual([["1", "a_b"]]);
    await userEvent.setup().click(next);
    expect(await screen.findByRole("link", { name: "이전" })).toHaveAttribute("href", "/admin/users?page=1&q=a_b");
    expect(lists(f)).toEqual([["1", "a_b"], ["2", "a_b"]]);
  });

  it("검색을 제출하면 q 가 실린 1쪽 요청, 주소는 ?q=", async () => {
    const f = renderUsers(() => json(200, page()), "/admin/users?page=3");
    const u = userEvent.setup();
    const search = await screen.findByRole("search");
    await u.type(within(search).getByLabelText("검색"), "kim");
    await u.click(within(search).getByRole("button", { name: "검색" }));
    await waitFor(() => expect(lists(f)).toEqual([["3", null], ["1", "kim"]]));
    expect(screen.getByTestId("location")).toHaveTextContent("/admin/users?q=kim");
  });

  it("검색 칸에서 Enter 로 제출해도 초점은 h2 로 — 칸이 다시 그려져 초점이 body 로 떨어지지 않는다", async () => {
    renderUsers(() => json(200, page()));
    const u = userEvent.setup();
    await u.type(await screen.findByLabelText("검색"), "kim{Enter}");
    expect(await screen.findByTestId("location")).toHaveTextContent("/admin/users?q=kim");
    await waitFor(() => expect(screen.getByRole("heading", { name: "사용자" })).toHaveFocus());
  });

  it("can['user.update'] 가 false 면 이메일이 링크가 아니다, true 면 상세 링크", async () => {
    renderUsers(() => json(200, page()), "/admin/users", { "user.update": false });
    await screen.findByRole("table", { name: "사용자 목록" });
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.queryByRole("link", { name: "kim@ondolith.test" })).toBeNull();
    cleanup();
    vi.unstubAllGlobals();
    renderUsers(() => json(200, page()), "/admin/users", { "user.update": true });
    expect(await screen.findByRole("link", { name: "kim@ondolith.test" })).toHaveAttribute("href", `/admin/users/${ID}`);
  });

  it("can['user.create'] 면 「새 사용자」 링크, 아니면 없다", async () => {
    renderUsers(() => json(200, page()), "/admin/users", { "user.create": true });
    expect(await screen.findByRole("link", { name: "새 사용자" })).toHaveAttribute("href", "/admin/users/new");
    cleanup();
    vi.unstubAllGlobals();
    renderUsers(() => json(200, page()), "/admin/users", { "user.create": false });
    await screen.findByRole("table", { name: "사용자 목록" });
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.queryByRole("link", { name: "새 사용자" })).toBeNull();
  });

  it("쪽을_넘기면_h2_로_초점_M_3", async () => {
    renderUsers((u) => json(200, page({ has_next: u.searchParams.get("page") === "1" })));
    await userEvent.setup().click(await screen.findByRole("link", { name: "다음" }));
    await screen.findByRole("link", { name: "이전" });
    expect(screen.getByRole("heading", { name: "사용자" })).toHaveFocus();
  });

  it("page_0_과_2147483648_은_1쪽", async () => {
    for (const query of ["0", "2147483648", "abc"]) {
      const f = renderUsers(() => json(200, page()), `/admin/users?page=${query}`);
      await waitFor(() => expect(lists(f)).toEqual([["1", null]]));
      cleanup();
      vi.unstubAllGlobals();
    }
  });

  it("빈_쪽이면_첫_쪽으로_M_9 — q 를 담는다", async () => {
    renderUsers(() => json(200, page({ items: [], page: 4 })), "/admin/users?page=4&q=x");
    expect(await screen.findByRole("link", { name: "첫 쪽으로" })).toHaveAttribute("href", "/admin/users?page=1&q=x");
    expect(screen.queryByRole("link", { name: "이전" })).toBeNull();
  });

  it("401_이면_로그인_안내와_next_링크_M_9", async () => {
    renderUsers(() => json(401, { status: 401, detail: "로그인이 필요합니다" }), "/admin/users?q=a");
    expect(await screen.findByRole("alert")).toHaveTextContent("로그인이 끝났습니다");
    expect(screen.getByRole("link", { name: "다시 로그인" })).toHaveAttribute("href", "/login?next=%2Fadmin%2Fusers%3Fq%3Da");
  });

  it("403 → 「권한이 없습니다」, 제목은 남는다", async () => {
    renderUsers(() => json(403, { status: 403, detail: "권한이 없습니다" }));
    expect(await screen.findByText("권한이 없습니다")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "사용자" })).toBeInTheDocument();
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("그 밖의 실패 → 「사용자 목록을 불러오지 못했습니다」", async () => {
    renderUsers(() => json(500, {}));
    expect(await screen.findByRole("alert")).toHaveTextContent("사용자 목록을 불러오지 못했습니다");
  });

  it("화면을 떠나면 캐시를 남기지 않는다 — 다시 들어오면 앞 행이 응답 전에 보이지 않는다", async () => {
    let hang = false;
    mockApi((req) =>
      new URL(req.url).pathname === "/api/me"
        ? json(200, { can: {} })
        : hang ? new Promise<Response>(() => {}) : json(200, page()),
    );
    renderWithQuery(
      <Routes>
        <Route path="/admin/users" element={<><UsersPage /><Link to="/away">나가기</Link></>} />
        <Route path="/away" element={<Link to="/admin/users">돌아가기</Link>} />
      </Routes>,
      "/admin/users",
    );
    const u = userEvent.setup();
    await screen.findByRole("table", { name: "사용자 목록" });
    hang = true;
    await u.click(screen.getByRole("link", { name: "나가기" }));
    await new Promise((r) => setTimeout(r, 10));
    await u.click(await screen.findByRole("link", { name: "돌아가기" }));
    await screen.findByRole("heading", { name: "사용자" });
    expect(screen.queryByRole("table")).toBeNull();
  });
});
