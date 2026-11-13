import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, mockApi, renderWithQuery } from "@/test-api";
import { WaitingScreen } from "./WaitingScreen";

afterEach(() => vi.unstubAllGlobals());

describe("전환 대기 화면", () => {
  it("대기 문구를 aria-live 로 알리고 제목에 포커스를 둔다", async () => {
    mockApi(() => json(503, {}));
    renderWithQuery(<WaitingScreen onRunning={() => {}} intervalMs={10} timeoutMs={10_000} />);
    expect(await screen.findByRole("status")).toHaveTextContent("사이트를 준비하고 있습니다");
    expect(screen.getByRole("heading", { name: "사이트를 준비하고 있습니다" })).toHaveFocus();
  });

  it("실패가 이어지다 running 이 되면 알린다", async () => {
    let n = 0;
    mockApi(() => {
      n += 1;
      if (n <= 3) throw new TypeError("연결 실패");
      return json(200, { state: "running", version: "t" });
    });
    const onRunning = vi.fn();
    renderWithQuery(<WaitingScreen onRunning={onRunning} intervalMs={10} timeoutMs={10_000} />);
    await waitFor(() => expect(onRunning).toHaveBeenCalled());
    expect(n).toBeGreaterThanOrEqual(4);
  });

  it("제한 시간이 지나면 안내와 다시 시도, 누르면 다시 기다린다", async () => {
    mockApi(() => json(200, { state: "install", version: "t" }));
    renderWithQuery(<WaitingScreen onRunning={() => {}} intervalMs={10} timeoutMs={60} />);
    expect(await screen.findByText("오래 걸립니다. 서버 로그를 확인하세요")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(screen.getByRole("status")).toHaveTextContent("사이트를 준비하고 있습니다");
    expect(screen.getByRole("heading", { name: "사이트를 준비하고 있습니다" })).toHaveFocus();
  });
});
