import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Link, Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, mockApi, renderWithQuery } from "@/test-api";
import { PageView } from "./PageView";

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup(); // React 가 올린 <title> 을 먼저 거두게 — 남은 것은 시험이 넣은 정적 <title>
  document.head.querySelectorAll("title").forEach((t) => t.remove());
});

const site = () => json(200, { name: "사이트", type: "cms" });
const about = () => json(200, { slug: "about", title: "소개", body_html: "<p><strong>굵게</strong></p>" });

function renderPage(route: string, respond: (req: Request) => Response) {
  const f = mockApi(respond);
  renderWithQuery(
    <>
      <Link to="/about">소개로</Link>
      <Routes>
        <Route path="/:slug" element={<PageView />} />
      </Routes>
    </>,
    route,
  );
  return f;
}

const robots = () => document.head.querySelectorAll('meta[name="robots"][content="noindex"]');

describe("공개 페이지", () => {
  it("200_이면_h1_제목_본문_문서_제목", async () => {
    // 빌드의 index.html 처럼 정적 <title> 이 먼저 있다 — React <title> 이 그 앞에 서야 이긴다
    const fixed = document.createElement("title");
    fixed.textContent = "ondolith-j";
    document.head.append(fixed);
    renderPage("/about", (req) => (req.url.endsWith("/api/site") ? site() : about()));
    const h1 = await screen.findByRole("heading", { level: 1, name: "소개" });
    await waitFor(() => expect(h1).toHaveFocus());
    expect(document.querySelector(".rich strong")?.textContent).toBe("굵게");
    await waitFor(() => expect(document.title).toBe("소개 – 사이트"));
  });

  it("404_면_찾을_수_없습니다와_noindex", async () => {
    renderPage("/about", () => json(404, { status: 404, detail: "페이지를 찾을 수 없습니다" }));
    expect(await screen.findByRole("heading", { name: "찾을 수 없습니다" })).toBeInTheDocument();
    expect(screen.getByText("없는 페이지이거나 주소가 바뀌었습니다. 주소를 확인하세요.")).toBeInTheDocument();
    expect(robots()).toHaveLength(1);
  });

  it("500_이면_고정_문구이고_서버_detail_을_그리지_않는다", async () => {
    renderPage("/about", () => json(500, { status: 500, detail: "비밀-detail-x" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("페이지를 불러오지 못했습니다");
    expect(document.body.textContent).not.toContain("비밀-detail-x");
    expect(document.body.textContent).not.toContain("about");
  });

  it("연결_실패도_고정_문구", async () => {
    renderPage("/about", () => {
      throw new TypeError("연결 실패");
    });
    expect(await screen.findByRole("alert")).toHaveTextContent("페이지를 불러오지 못했습니다");
  });

  it.each(["/Foo", "/a.b", "/-a", "/a_b", `/${"a".repeat(101)}`])("형식이_틀린_슬러그는_요청이_0 %s", async (route) => {
    const f = renderPage(route, about);
    expect(await screen.findByRole("heading", { name: "찾을 수 없습니다" })).toBeInTheDocument();
    expect(f).not.toHaveBeenCalled();
  });

  it("100자_슬러그는_요청한다", async () => {
    const slug = "a".repeat(100);
    const f = renderPage(`/${slug}`, (req) => (req.url.endsWith("/api/site") ? site() : about()));
    expect(await screen.findByRole("heading", { level: 1, name: "소개" })).toBeInTheDocument();
    expect(f.mock.calls.map(([req]) => new URL(req.url).pathname)).toContain(`/api/pages/${slug}`);
  });

  it("NotFound_에서_유효한_페이지로_이동하면_noindex_가_남지_않는다", async () => {
    renderPage("/Foo", (req) => (req.url.endsWith("/api/site") ? site() : about()));
    await screen.findByRole("heading", { name: "찾을 수 없습니다" });
    expect(robots()).toHaveLength(1);
    await userEvent.setup().click(screen.getByRole("link", { name: "소개로" }));
    expect(await screen.findByRole("heading", { level: 1, name: "소개" })).toBeInTheDocument();
    expect(document.head.querySelectorAll('meta[name="robots"]')).toHaveLength(0);
  });
});
