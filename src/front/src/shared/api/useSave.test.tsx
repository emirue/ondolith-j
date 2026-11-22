import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderWithQuery } from "@/test-api";
import { SaveAlert, useSave } from "./useSave";

type Sent = { response: Response; error?: unknown };

/** 응답을 시험이 직접 끝내는 가짜 send. */
function pendingSend() {
  const finishers: ((r: Sent) => void)[] = [];
  const send = vi.fn((_: string) => new Promise<Sent>((resolve) => finishers.push(resolve)));
  const finish = async (status: number) => {
    await waitFor(() => expect(finishers.length).toBeGreaterThan(0));
    await act(async () => finishers.shift()!({ response: new Response(null, { status }) }));
  };
  return { send, finish };
}

function Probe({ send }: { send: (v: string) => Promise<Sent> }) {
  const s = useSave({ send, success: "저장했습니다" });
  return (
    <>
      <button type="button" aria-disabled={s.pending || undefined} onClick={() => s.save("v")}>
        {s.pending ? "처리 중…" : "저장"}
      </button>
      <SaveAlert failure={s.failure} />
    </>
  );
}

describe("useSave", () => {
  it("빠르게 두 번 누르면 요청은 하나, 보내는 동안 「처리 중…」·aria-disabled", async () => {
    const { send, finish } = pendingSend();
    renderWithQuery(<Probe send={send} />, "/", { notice: true });
    const button = screen.getByRole("button");
    // 같은 틱에 두 번 — 사이에 다시 그려지지 않아 isPending 으로는 막지 못한다
    fireEvent.click(button);
    fireEvent.click(button);
    expect(await screen.findByRole("button", { name: "처리 중…" })).toHaveAttribute("aria-disabled", "true");
    expect(send).toHaveBeenCalledTimes(1);
    await finish(204);
    expect(screen.getByTestId("notice")).toHaveTextContent("저장했습니다");
  });

  it("204 로 끝나면 풀려 다시 보낼 수 있다", async () => {
    const { send, finish } = pendingSend();
    renderWithQuery(<Probe send={send} />, "/", { notice: true });
    fireEvent.click(screen.getByRole("button"));
    await finish(204);
    const button = await screen.findByRole("button", { name: "저장" });
    expect(button).not.toHaveAttribute("aria-disabled");
    fireEvent.click(button);
    await waitFor(() => expect(send).toHaveBeenCalledTimes(2));
  });

  it("500 으로 끝나도 풀리고, 「저장하지 못했습니다」 알림", async () => {
    const { send, finish } = pendingSend();
    renderWithQuery(<Probe send={send} />, "/", { notice: true });
    fireEvent.click(screen.getByRole("button"));
    await finish(500);
    expect(screen.getByRole("alert")).toHaveTextContent("저장하지 못했습니다");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(send).toHaveBeenCalledTimes(2));
  });

  it("연결 실패(send 가 던짐)여도 풀린다", async () => {
    const send = vi.fn().mockRejectedValueOnce(new TypeError("연결 실패")).mockResolvedValue({ response: new Response(null, { status: 204 }) });
    renderWithQuery(<Probe send={send} />, "/", { notice: true });
    fireEvent.click(screen.getByRole("button"));
    expect(await screen.findByRole("alert")).toHaveTextContent("저장하지 못했습니다");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(send).toHaveBeenCalledTimes(2));
  });

  it("다음 제출을 시작하면 앞 알림을 지운다", async () => {
    const { send, finish } = pendingSend();
    renderWithQuery(<Probe send={send} />, "/", { notice: true });
    fireEvent.click(screen.getByRole("button"));
    await finish(204);
    expect(screen.getByTestId("notice")).toHaveTextContent("저장했습니다");
    fireEvent.click(screen.getByRole("button"));
    expect(screen.getByTestId("notice")).toBeEmptyDOMElement();
  });

  it("성공 문구는 필수다(npm run typecheck 가 검증)", () => {
    function NoSuccess({ send }: { send: (v: string) => Promise<Sent> }) {
      // @ts-expect-error success 가 필수다
      useSave({ send });
      return null;
    }
    expect(NoSuccess).toBeTypeOf("function");
  });
});
