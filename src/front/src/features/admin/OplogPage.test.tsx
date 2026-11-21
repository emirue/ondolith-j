import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
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

  it("양의 정수가 아닌 ?page= 는 1쪽으로 본다", async () => {
    const f = renderOplog(() => json(200, { items: [], page: 1, page_size: 100, total: 0 }), "/admin/oplog?page=abc");
    await screen.findByRole("table", { name: "작업 로그" });
    expect(pages(f)).toEqual(["1"]);
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
});
