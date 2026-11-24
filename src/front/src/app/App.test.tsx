import { screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, mockApi, renderWithQuery } from "@/test-api";
import { App } from "./App";

afterEach(() => vi.unstubAllGlobals());

describe("첫 진입 분기", () => {
  it("health 가 install 이면 설치 화면", async () => {
    mockApi(() => json(200, { state: "install", version: "t" }));
    renderWithQuery(<App />);
    expect(await screen.findByRole("heading", { name: "사이트 설치" })).toBeInTheDocument();
  });

  it("health 가 running 이면 홈에 사이트 이름", async () => {
    mockApi((req) =>
      req.url.endsWith("/api/site") ? json(200, { name: "내 사이트", type: "cms" }) : json(200, { state: "running", version: "t" }),
    );
    renderWithQuery(<App />);
    expect(await screen.findByRole("heading", { name: "내 사이트" })).toBeInTheDocument();
  });

  it("운영 상태에서 /login 이면 로그인 화면", async () => {
    mockApi((req) => (req.url.endsWith("/api/me") ? json(200, { user: null, can: {} }) : json(200, { state: "running", version: "t" })));
    renderWithQuery(<App />, "/login");
    expect(await screen.findByRole("heading", { name: "로그인" })).toBeInTheDocument();
  });

  it("관리자 하위 주소에서 로그인하지 않았으면 로그인 화면", async () => {
    mockApi((req) => {
      if (req.url.endsWith("/api/admin")) return json(401, { status: 401, detail: "로그인이 필요합니다" });
      if (req.url.endsWith("/api/me")) return json(200, { user: null, can: {} });
      return json(200, { state: "running", version: "t" });
    });
    renderWithQuery(<App />, "/admin/oplog?page=2");
    expect(await screen.findByRole("heading", { name: "로그인" })).toBeInTheDocument();
  });

  it("운영 상태에서 없는 주소면 찾을 수 없습니다", async () => {
    mockApi((req) => (req.url.endsWith("/api/me") ? json(200, { user: null, can: {} }) : json(200, { state: "running", version: "t" })));
    renderWithQuery(<App />, "/없는/주소");
    expect(await screen.findByRole("heading", { name: "찾을 수 없습니다" })).toBeInTheDocument();
  });

  it("운영 상태에서 /about 이면 공개 페이지", async () => {
    mockApi((req) => {
      if (req.url.endsWith("/api/me")) return json(200, { user: null, can: {} });
      if (req.url.endsWith("/api/site")) return json(200, { name: "내 사이트", type: "cms" });
      if (req.url.endsWith("/api/pages/about")) return json(200, { slug: "about", title: "소개", body_html: "<p>a</p>" });
      return json(200, { state: "running", version: "t" });
    });
    renderWithQuery(<App />, "/about");
    expect(await screen.findByRole("heading", { level: 1, name: "소개" })).toBeInTheDocument();
  });

  it("응답이 없으면(전환 중 새로고침) 대기 화면", async () => {
    mockApi(() => {
      throw new TypeError("연결 실패");
    });
    renderWithQuery(<App />);
    expect(await screen.findByRole("heading", { name: "사이트를 준비하고 있습니다" })).toBeInTheDocument();
  });
});
