import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { useNavigate } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { mockApi, renderWithQuery } from "@/test-api";
import { REAUTH_TYPE, SaveAlert, useSave } from "./useSave";

type Sent = { response: Response; data?: unknown; error?: unknown };

/** 응답을 시험이 직접 끝내는 가짜 send. */
function pendingSend() {
  const finishers: ((r: Sent) => void)[] = [];
  const send = vi.fn((_: string) => new Promise<Sent>((resolve) => finishers.push(resolve)));
  const finish = async (status: number, error?: unknown, data?: unknown) => {
    await waitFor(() => expect(finishers.length).toBeGreaterThan(0));
    await act(async () => finishers.shift()!({ response: new Response(null, { status }), error, data }));
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

  it("send 가 동기로 던져도 풀린다", async () => {
    const send = vi
      .fn<(v: string) => Promise<Sent>>()
      .mockImplementationOnce(() => {
        throw new TypeError("동기 실패");
      })
      .mockResolvedValue({ response: new Response(null, { status: 204 }) });
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

const PASSWORD = "reauth-pass-fake-1";
const REAUTH = { type: REAUTH_TYPE, status: 403, detail: "비밀번호를 다시 입력하세요" };
const problem = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/problem+json" } });

/** POST /api/admin/reauth 응답을 시험이 직접 끝내는 가짜 fetch. */
function pendingReauth() {
  const replies: ((r: Response) => void)[] = [];
  const fetch = mockApi(() => new Promise<Response>((reply) => replies.push(reply)));
  const answer = async (res: Response) => {
    await waitFor(() => expect(replies.length).toBeGreaterThan(0));
    await act(async () => replies.shift()!(res));
  };
  return { fetch, answer };
}

/** 응답이 더 오지 않는지 — 짧게(상한 있는) 기다린 뒤 센다. */
const settle = () => act(() => new Promise((r) => setTimeout(r, 50)));

let client: QueryClient;

function ReauthProbe(props: { send: (v: string) => Promise<Sent>; focus?: (field: string) => void; onSuccess?: (data: unknown) => void; noticePath?: (data: unknown) => string }) {
  client = useQueryClient();
  const s = useSave({ success: "저장했습니다", ...props });
  return (
    <>
      <button type="button" onClick={() => s.save("v")}>{s.pending ? "처리 중…" : "저장"}</button>
      <button type="button" onClick={() => s.reauth(PASSWORD)}>재인증</button>
      <button type="button" onClick={s.cancel}>그만</button>
      <output data-testid="state">{JSON.stringify({ reauthNeeded: s.reauthNeeded, reauthError: s.reauthError, done: s.done })}</output>
      <SaveAlert failure={s.failure} />
    </>
  );
}

const state = () => JSON.parse(screen.getByTestId("state").textContent!) as { reauthNeeded: boolean; reauthError: string | null; done: boolean };
const click = (name: string) => fireEvent.click(screen.getByRole("button", { name }));

describe("useSave 재인증", () => {
  it("재인증_403_이면_재인증_상태가_되고_reauth_204_면_원_요청을_같은_값으로_한_번_더", async () => {
    const { send, finish } = pendingSend();
    const { fetch, answer } = pendingReauth();
    renderWithQuery(<ReauthProbe send={send} />, "/", { notice: true });
    click("저장");
    await finish(403, REAUTH);
    expect(state().reauthNeeded).toBe(true);
    expect(screen.queryByRole("alert")).toBeNull();
    click("재인증");
    await answer(new Response(null, { status: 204 }));
    await finish(204);
    expect(send).toHaveBeenCalledTimes(2);
    expect(send.mock.calls[1][0]).toBe(send.mock.calls[0][0]);
    expect(fetch).toHaveBeenCalledTimes(1);
    const req = fetch.mock.calls[0][0] as Request;
    expect(new URL(req.url).pathname).toBe("/api/admin/reauth");
    expect(await req.json()).toEqual({ password: PASSWORD });
    expect(state()).toEqual({ reauthNeeded: false, reauthError: null, done: true });
    expect(screen.getByTestId("notice")).toHaveTextContent("저장했습니다");
  });

  it("다시_보낸_원_요청이_또_재인증_403_이면_다시_묻고_저절로_되풀이하지_않는다", async () => {
    const { send, finish } = pendingSend();
    const { fetch, answer } = pendingReauth();
    renderWithQuery(<ReauthProbe send={send} />, "/", { notice: true });
    click("저장");
    await finish(403, REAUTH);
    click("재인증");
    await answer(new Response(null, { status: 204 }));
    await finish(403, REAUTH);
    await settle();
    expect(state().reauthNeeded).toBe(true);
    expect(send).toHaveBeenCalledTimes(2);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("재인증이_틀리면_칸_오류_요청은_재인증_하나", async () => {
    const { send, finish } = pendingSend();
    const { fetch, answer } = pendingReauth();
    renderWithQuery(<ReauthProbe send={send} />, "/", { notice: true });
    click("저장");
    await finish(403, REAUTH);
    click("재인증");
    await answer(problem(403, REAUTH));
    await settle();
    expect(state()).toEqual({ reauthNeeded: true, reauthError: "비밀번호가 맞지 않습니다", done: false });
    expect(send).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("재인증_요청의_type_없는_403_은_일반_실패로_재인증을_끝낸다", async () => {
    const { send, finish } = pendingSend();
    const { answer } = pendingReauth();
    renderWithQuery(<ReauthProbe send={send} />, "/", { notice: true });
    click("저장");
    await finish(403, REAUTH);
    click("재인증");
    await answer(problem(403, { status: 403, detail: "권한이 없습니다" }));
    expect(state().reauthNeeded).toBe(false);
    expect(screen.getByRole("alert")).toHaveTextContent("권한이 없습니다");
    // 남긴 값을 버렸다 — 다시 재인증해도 보내지 않는다
    click("재인증");
    await settle();
    expect(send).toHaveBeenCalledTimes(1);
  });

  it("cancel_뒤에는_요청이_없다", async () => {
    const { send, finish } = pendingSend();
    const { fetch } = pendingReauth();
    renderWithQuery(<ReauthProbe send={send} />, "/", { notice: true });
    click("저장");
    await finish(403, REAUTH);
    click("그만");
    expect(state().reauthNeeded).toBe(false);
    click("재인증");
    await settle();
    expect(fetch).not.toHaveBeenCalled();
    expect(send).toHaveBeenCalledTimes(1);
  });

  it("비밀번호가_쿼리_캐시와_변경_캐시에_없다", async () => {
    const { send, finish } = pendingSend();
    const { answer } = pendingReauth();
    renderWithQuery(<ReauthProbe send={send} />, "/", { notice: true });
    click("저장");
    await finish(403, REAUTH);
    click("재인증");
    await answer(problem(403, REAUTH));
    await settle();
    const mutations = JSON.stringify(client.getMutationCache().getAll().map((m) => m.state.variables));
    const queries = JSON.stringify(client.getQueryCache().getAll().map((q) => [q.queryKey, q.state.data]));
    expect(mutations).not.toContain(PASSWORD);
    expect(queries).not.toContain(PASSWORD);
  });

  it("재인증_429_는_잠시_뒤_문구_남긴_값_그대로_다시_204_면_원_요청_한_번", async () => {
    const { send, finish } = pendingSend();
    const { fetch, answer } = pendingReauth();
    renderWithQuery(<ReauthProbe send={send} />, "/", { notice: true });
    click("저장");
    await finish(403, REAUTH);
    click("재인증");
    await answer(problem(429, { status: 429, detail: "잠시 뒤 다시 시도하세요" }));
    expect(state()).toEqual({ reauthNeeded: true, reauthError: "잠시 뒤 다시 시도하세요", done: false });
    click("재인증");
    await answer(new Response(null, { status: 204 }));
    await finish(204);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(send).toHaveBeenCalledTimes(2);
    expect(send.mock.calls[1][0]).toBe(send.mock.calls[0][0]);
    expect(state().done).toBe(true);
  });

  it("재인증이_401_이면_로그인_안내", async () => {
    const { send, finish } = pendingSend();
    const { answer } = pendingReauth();
    renderWithQuery(<ReauthProbe send={send} />, "/admin/users/x", { notice: true });
    click("저장");
    await finish(403, REAUTH);
    click("재인증");
    await answer(problem(401, { status: 401, detail: "로그인이 필요합니다" }));
    expect(state().reauthNeeded).toBe(false);
    expect(screen.getByRole("alert")).toHaveTextContent("로그인이 끝났습니다");
  });

  it("다시_보낸_원_요청이_401_이면_로그인_안내", async () => {
    const { send, finish } = pendingSend();
    const { answer } = pendingReauth();
    renderWithQuery(<ReauthProbe send={send} />, "/admin/users/x", { notice: true });
    click("저장");
    await finish(403, REAUTH);
    click("재인증");
    await answer(new Response(null, { status: 204 }));
    await finish(401);
    expect(state().reauthNeeded).toBe(false);
    expect(screen.getByRole("alert")).toHaveTextContent("로그인이 끝났습니다");
    expect(screen.getByRole("link", { name: "다시 로그인" })).toHaveAttribute("href", "/login?next=%2Fadmin%2Fusers%2Fx");
  });

  it("재인증_연결_실패여도_풀리고_알림", async () => {
    const { send, finish } = pendingSend();
    mockApi(() => {
      throw new TypeError("연결 실패");
    });
    renderWithQuery(<ReauthProbe send={send} />, "/", { notice: true });
    click("저장");
    await finish(403, REAUTH);
    click("재인증");
    expect(await screen.findByRole("alert")).toHaveTextContent("저장하지 못했습니다");
    expect(screen.getByRole("button", { name: "저장" })).toBeInTheDocument();
  });
});

describe("useSave 응답", () => {
  it("422_뒤_첫_오류_칸에_초점_M_1_role_alert_없음_M_2", async () => {
    const { send, finish } = pendingSend();
    const focus = vi.fn();
    renderWithQuery(<ReauthProbe send={send} focus={focus} />, "/", { notice: true });
    click("저장");
    await finish(422, { status: 422, errors: { email: "형식이 틀렸습니다", display_name: "필수입니다" } });
    await waitFor(() => expect(focus).toHaveBeenCalledWith("email"));
    expect(focus).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("409_는_서버_detail_을_role_alert_로_이스케이프해서", async () => {
    const { send, finish } = pendingSend();
    const { container } = renderWithQuery(<ReauthProbe send={send} />, "/", { notice: true });
    click("저장");
    await finish(409, { status: 409, detail: "<b>마지막</b> 관리자입니다" });
    expect(screen.getByRole("alert")).toHaveTextContent("<b>마지막</b> 관리자입니다");
    expect(container.querySelector("b")).toBeNull();
  });

  it("404_와_type_없는_403_도_서버_detail_detail_이_없으면_지금_문구", async () => {
    const { send, finish } = pendingSend();
    renderWithQuery(<ReauthProbe send={send} />, "/", { notice: true });
    click("저장");
    await finish(404, { status: 404, detail: "찾을 수 없습니다" });
    expect(screen.getByRole("alert")).toHaveTextContent("찾을 수 없습니다");
    click("저장");
    await finish(403, { status: 403, detail: "다른 거절 문구" });
    expect(screen.getByRole("alert")).toHaveTextContent("다른 거절 문구");
    expect(state().reauthNeeded).toBe(false);
    click("저장");
    await finish(403);
    expect(screen.getByRole("alert")).toHaveTextContent("권한이 없습니다");
  });

  it("onSuccess_가_응답_본문을_받고_noticePath_의_주소에서_알림이_보인다", async () => {
    const { send, finish } = pendingSend();
    function Moving() {
      const navigate = useNavigate();
      return (
        <ReauthProbe
          send={send}
          noticePath={(d) => `/users/${(d as { id: string }).id}`}
          onSuccess={(d) => navigate(`/users/${(d as { id: string }).id}`)}
        />
      );
    }
    renderWithQuery(<Moving />, "/users/new", { notice: true });
    click("저장");
    await finish(201, undefined, { id: "u1" });
    expect(screen.getByTestId("notice")).toHaveTextContent("저장했습니다");
    expect(state().done).toBe(true);
  });
});
