import { screen } from "@testing-library/react";
import { afterEach, describe, expect, it, onTestFinished, vi } from "vitest";
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

  it("관리자_묶음을_불러오지_못하면_대체_화면", async () => {
    // 배포 뒤 옛 chunk 가 404 인 꼴(스펙 8절): lazy 의 import 가 거절된다. App 을 새로 읽어 앞 시험이 채운 lazy 캐시를 피한다
    vi.resetModules();
    vi.doMock("@/features/auth/AdminGate", () => {
      throw new Error("chunk 없음");
    });
    onTestFinished(() => vi.doUnmock("@/features/auth/AdminGate"));
    const { App: Fresh } = await import("./App");
    mockApi((req) => (req.url.endsWith("/api/me") ? json(200, { user: null, can: {} }) : json(200, { state: "running", version: "t" })));
    renderWithQuery(<Fresh />, "/admin/pages/new");
    const heading = await screen.findByRole("heading", { level: 1, name: "관리자 화면을 불러오지 못했습니다. 새로 고침하세요" });
    expect(heading).toHaveFocus();
  });

  it("응답이 없으면(전환 중 새로고침) 대기 화면", async () => {
    mockApi(() => {
      throw new TypeError("연결 실패");
    });
    renderWithQuery(<App />);
    expect(await screen.findByRole("heading", { name: "사이트를 준비하고 있습니다" })).toBeInTheDocument();
  });
});
