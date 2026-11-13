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

  it("응답이 없으면(전환 중 새로고침) 대기 화면", async () => {
    mockApi(() => {
      throw new TypeError("연결 실패");
    });
    renderWithQuery(<App />);
    expect(await screen.findByRole("heading", { name: "사이트를 준비하고 있습니다" })).toBeInTheDocument();
  });
});
