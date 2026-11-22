import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Link, Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, mockApi, renderWithQuery } from "@/test-api";
import { OplogPage } from "./OplogPage";

afterEach(() => vi.unstubAllGlobals());

const AT = "2026-11-21T03:04:05Z";

const row = (over: Record<string, unknown>) => ({
  id: crypto.randomUUID(),
  created_at: AT,
  actor_user_id: "00000000-0000-0000-0000-000000000001",
  actor_email: "op@ondolith.test",
  action: "auth.login",
  target_type: "user",
  target_id: "00000000-0000-0000-0000-000000000001",
  summary: "",
  ip: "127.0.0.1",
  ...over,
});

/** page 별 응답. 요청의 page 를 기록한다. */
function renderOplog(respond: (page: string | null) => Response, route = "/admin/oplog") {
  const f = mockApi((req) => respond(new URL(req.url).searchParams.get("page")));
  renderWithQuery(
    <Routes>
      <Route path="/admin/oplog" element={<OplogPage />} />
    </Routes>,
    route,
  );
  return f;
}

const pages = (f: ReturnType<typeof mockApi>) => f.mock.calls.map(([req]) => new URL(req.url).searchParams.get("page"));

describe("작업 로그", () => {
  it("표: 시각(브라우저 시간대)·주체·동작 이름표·대상·요약·IP", async () => {
    renderOplog(() =>
      json(200, {
        items: [
          row({ actor_email: "", actor_user_id: null, action: "x.unknown", target_type: "site", target_id: null, ip: null }),
          row({ actor_user_id: null, actor_email: "gone@ondolith.test", action: "settings.update", target_type: "settings", target_id: null, summary: "site.name" }),
          row({}),
        ],
        page: 1,
        page_size: 100,
        total: 3,
      }),
    );
    const table = await screen.findByRole("table", { name: "작업 로그" });
    expect(within(table).getAllByRole("columnheader").map((th) => th.textContent)).toEqual(["시각", "주체", "동작", "대상", "요약", "IP"]);
    const [, system, deleted, login] = within(table).getAllByRole("row").map((tr) => within(tr).queryAllByRole("cell").map((td) => td.textContent));
    expect(system).toEqual([new Date(AT).toLocaleString(), "시스템", "x.unknown", "site", "", ""]);
    expect(deleted).toEqual([new Date(AT).toLocaleString(), "(삭제된 계정) gone@ondolith.test", "사이트 설정 변경", "settings", "site.name", "127.0.0.1"]);
    expect(login).toEqual([
      new Date(AT).toLocaleString(),
      "op@ondolith.test",
      "로그인",
      "user 00000000-0000-0000-0000-000000000001",
      "",
      "127.0.0.1",
    ]);
  });

  it("total 101, 1쪽 → 「다음」은 ?page=2, 「이전」 없음. 누르면 page=2 를 부르고 「이전」이 생긴다", async () => {
    const f = renderOplog((page) => json(200, { items: [row({})], page: Number(page), page_size: 100, total: 101 }));
    const next = await screen.findByRole("link", { name: "다음" });
    expect(next).toHaveAttribute("href", "/admin/oplog?page=2");
    expect(screen.queryByRole("link", { name: "이전" })).toBeNull();
    expect(pages(f)).toEqual(["1"]);
    await userEvent.setup().click(next);
    expect(await screen.findByRole("link", { name: "이전" })).toHaveAttribute("href", "/admin/oplog?page=1");
    expect(screen.queryByRole("link", { name: "다음" })).toBeNull();
    expect(pages(f)).toEqual(["1", "2"]);
  });

  it("total 100, 1쪽(100행) → 「다음」 없음", async () => {
    renderOplog(() => json(200, { items: [row({})], page: 1, page_size: 100, total: 100 }));
    await screen.findByRole("table", { name: "작업 로그" });
    expect(screen.queryByRole("link", { name: "다음" })).toBeNull();
  });

  it("양의 정수가 아닌 ?page= 는 1쪽으로 본다", async () => {
    const f = renderOplog(() => json(200, { items: [], page: 1, page_size: 100, total: 0 }), "/admin/oplog?page=abc");
    await screen.findByRole("table", { name: "작업 로그" });
    expect(pages(f)).toEqual(["1"]);
  });

  it("쪽을_넘기면_h2_로_초점_M_3", async () => {
    renderOplog((page) => json(200, { items: [row({})], page: Number(page), page_size: 100, total: 101 }));
    await userEvent.setup().click(await screen.findByRole("link", { name: "다음" }));
    await screen.findByRole("link", { name: "이전" });
    expect(screen.getByRole("heading", { name: "작업 로그" })).toHaveFocus();
  });

  it("page_0_과_2147483648_은_1쪽, 2147483647 은 그 쪽", async () => {
    for (const [query, want] of [["0", "1"], ["2147483648", "1"], ["2147483647", "2147483647"]]) {
      const f = renderOplog(() => json(200, { items: [], page: 1, page_size: 100, total: 0 }), `/admin/oplog?page=${query}`);
      await waitFor(() => expect(pages(f)).toEqual([want]));
      cleanup();
      vi.unstubAllGlobals();
    }
  });

  it("빈_쪽이면_첫_쪽으로_M_9 — 「이전」 대신 ?page=1", async () => {
    renderOplog(() => json(200, { items: [], page: 5, page_size: 100, total: 3 }), "/admin/oplog?page=5");
    expect(await screen.findByRole("link", { name: "첫 쪽으로" })).toHaveAttribute("href", "/admin/oplog?page=1");
    expect(screen.queryByRole("link", { name: "이전" })).toBeNull();
    expect(screen.queryByRole("link", { name: "다음" })).toBeNull();
  });

  it("401_이면_로그인_안내와_next_링크_M_9", async () => {
    renderOplog(() => json(401, { status: 401, detail: "로그인이 필요합니다" }), "/admin/oplog?page=2");
    expect(await screen.findByRole("alert")).toHaveTextContent("로그인이 끝났습니다");
    expect(screen.getByRole("link", { name: "다시 로그인" })).toHaveAttribute("href", "/login?next=%2Fadmin%2Foplog%3Fpage%3D2");
  });

  it("403 → 「권한이 없습니다」, 제목은 남는다", async () => {
    renderOplog(() => json(403, { status: 403, detail: "권한이 없습니다" }));
    expect(await screen.findByText("권한이 없습니다")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "작업 로그" })).toBeInTheDocument();
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("그 밖의 실패 → 「작업 로그를 불러오지 못했습니다」", async () => {
    renderOplog(() => json(500, {}));
    expect(await screen.findByRole("alert")).toHaveTextContent("작업 로그를 불러오지 못했습니다");
  });

  it("화면을 떠나면 캐시를 남기지 않는다 — 다시 들어오면 앞 사람의 행이 응답 전에 보이지 않는다", async () => {
    let hang = false;
    const f = mockApi(() =>
      hang ? new Promise<Response>(() => {}) : json(200, { items: [row({})], page: 1, page_size: 100, total: 1 }),
    );
    renderWithQuery(
      <Routes>
        <Route path="/admin/oplog" element={<><OplogPage /><Link to="/away">나가기</Link></>} />
        <Route path="/away" element={<Link to="/admin/oplog">돌아가기</Link>} />
      </Routes>,
      "/admin/oplog",
    );
    const u = userEvent.setup();
    await screen.findByRole("table", { name: "작업 로그" });
    hang = true;
    await u.click(screen.getByRole("link", { name: "나가기" }));
    await new Promise((r) => setTimeout(r, 10));
    await u.click(await screen.findByRole("link", { name: "돌아가기" }));
    await screen.findByRole("heading", { name: "작업 로그" });
    await vi.waitFor(() => expect(f).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole("table")).toBeNull();
  });
});
