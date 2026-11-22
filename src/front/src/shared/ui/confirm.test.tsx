import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { api } from "@/shared/api/client";
import { REAUTH_TYPE, useSave } from "@/shared/api/useSave";
import { mockApi, renderWithQuery } from "@/test-api";
import { ConfirmButton } from "./confirm";

const ID = "00000000-0000-0000-0000-000000000001";
const PASSWORD = "reauth-pass-fake-2";
const problem = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/problem+json" } });
const REAUTH = () => problem(403, { type: REAUTH_TYPE, status: 403, detail: "비밀번호를 다시 입력하세요" });
const NO_CONTENT = () => new Response(null, { status: 204 });

/** 실제 api 를 지나는 가짜 fetch — 응답은 시험이 직접 끝낸다(problem+json 파싱까지 시험한다). */
function pendingApi() {
  const replies: ((r: Response) => void)[] = [];
  const fetch = mockApi(() => new Promise<Response>((reply) => replies.push(reply)));
  const answer = async (res: Response) => {
    await waitFor(() => expect(replies.length).toBeGreaterThan(0));
    await act(async () => replies.shift()!(res));
  };
  const paths = () => fetch.mock.calls.map(([req]) => new URL((req as Request).url).pathname);
  return { fetch, answer, paths };
}

function Harness({ destructive = true }: { destructive?: boolean }) {
  const save = useSave({
    send: (id: string) => api.POST("/api/admin/users/{id}/deactivate", { params: { path: { id } } }),
    success: "사용자를 비활성화했습니다",
  });
  return <ConfirmButton label="비활성화" title="사용자를 비활성화할까요?" description="로그인할 수 없게 됩니다." destructive={destructive} save={save} values={ID} />;
}

const DEACTIVATE = `/api/admin/users/${ID}/deactivate`;
const settle = () => act(() => new Promise((r) => setTimeout(r, 50)));
const trigger = () => screen.getByRole("button", { name: "비활성화" });
const dialog = () => screen.queryByRole("alertdialog", { name: "사용자를 비활성화할까요?" });

describe("ConfirmButton", () => {
  it("destructive_는_누르면_대화상자_요청_0", async () => {
    const { fetch } = pendingApi();
    renderWithQuery(<Harness />, "/", { notice: true });
    await userEvent.setup().click(trigger());
    expect(dialog()).toBeInTheDocument();
    expect(screen.getByText("로그인할 수 없게 됩니다.")).toBeInTheDocument();
    await settle();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("취소하면_요청_0_다시_누를_수_있다", async () => {
    const { fetch } = pendingApi();
    renderWithQuery(<Harness />, "/", { notice: true });
    const u = userEvent.setup();
    await u.click(trigger());
    await u.click(screen.getByRole("button", { name: "취소" }));
    expect(dialog()).toBeNull();
    await u.click(trigger());
    expect(dialog()).toBeInTheDocument();
    await settle();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("초점은_대화상자_안에_갇히고_Esc_로_취소되며_닫히면_연_단추로_돌아온다", async () => {
    const { fetch } = pendingApi();
    renderWithQuery(<Harness />, "/", { notice: true });
    const u = userEvent.setup();
    await u.click(trigger());
    const cancel = screen.getByRole("button", { name: "취소" });
    expect(cancel).toHaveFocus();
    await u.tab();
    expect(screen.getByRole("button", { name: "확인" })).toHaveFocus();
    await u.tab();
    expect(cancel).toHaveFocus();
    await u.keyboard("{Escape}");
    expect(dialog()).toBeNull();
    expect(trigger()).toHaveFocus();
    await settle();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("확인하면_요청_1_보내는_동안_대화상자는_열려_있고_처리_중", async () => {
    const { answer, paths } = pendingApi();
    renderWithQuery(<Harness />, "/", { notice: true });
    const u = userEvent.setup();
    await u.click(trigger());
    await u.click(screen.getByRole("button", { name: "확인" }));
    await waitFor(() => expect(paths()).toEqual([DEACTIVATE]));
    expect(dialog()).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "처리 중…" })).toHaveAttribute("aria-disabled", "true");
    await answer(NO_CONTENT());
    expect(dialog()).toBeNull();
  });

  it("확인을_빠르게_두_번_눌러도_요청_1", async () => {
    const { answer, paths } = pendingApi();
    renderWithQuery(<Harness />, "/", { notice: true });
    await userEvent.setup().click(trigger());
    const confirm = screen.getByRole("button", { name: "확인" });
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    await settle();
    expect(paths()).toEqual([DEACTIVATE]);
    await answer(NO_CONTENT());
  });

  it("비destructive_는_바로_요청_1", async () => {
    const { answer, paths } = pendingApi();
    renderWithQuery(<Harness destructive={false} />, "/", { notice: true });
    await userEvent.setup().click(trigger());
    await waitFor(() => expect(paths()).toEqual([DEACTIVATE]));
    expect(dialog()).toBeNull();
    await answer(NO_CONTENT());
    expect(screen.getByTestId("notice")).toHaveTextContent("사용자를 비활성화했습니다");
  });

  it("재인증_403_이면_같은_대화상자에_비밀번호_칸_type_password_autocomplete_current_password", async () => {
    const { answer, paths } = pendingApi();
    renderWithQuery(<Harness />, "/", { notice: true });
    const u = userEvent.setup();
    await u.click(trigger());
    const opened = dialog();
    await u.click(screen.getByRole("button", { name: "확인" }));
    await answer(REAUTH());
    expect(dialog()).toBe(opened);
    const field = screen.getByLabelText("관리자 비밀번호 재확인");
    expect(field).toHaveAttribute("type", "password");
    expect(field).toHaveAttribute("autocomplete", "current-password");
    expect(field).toHaveFocus();
    await u.type(field, PASSWORD);
    await u.click(screen.getByRole("button", { name: "확인" }));
    await answer(NO_CONTENT());
    await answer(NO_CONTENT());
    expect(paths()).toEqual([DEACTIVATE, "/api/admin/reauth", DEACTIVATE]);
  });

  it("비destructive_도_재인증_403_이면_대화상자가_비밀번호_칸으로_뜬다", async () => {
    const { answer } = pendingApi();
    renderWithQuery(<Harness destructive={false} />, "/", { notice: true });
    await userEvent.setup().click(trigger());
    await answer(REAUTH());
    expect(dialog()).toBeInTheDocument();
    expect(screen.getByLabelText("관리자 비밀번호 재확인")).toHaveFocus();
  });

  it("비밀번호가_틀리면_대화상자_안에_비밀번호가_맞지_않습니다", async () => {
    const { answer, paths } = pendingApi();
    renderWithQuery(<Harness destructive={false} />, "/", { notice: true });
    const u = userEvent.setup();
    await u.click(trigger());
    await answer(REAUTH());
    await u.type(screen.getByLabelText("관리자 비밀번호 재확인"), "wrong-pass-fake{Enter}");
    await answer(REAUTH());
    expect(screen.getByRole("alert")).toHaveTextContent("비밀번호가 맞지 않습니다");
    expect(dialog()).toContainElement(screen.getByRole("alert"));
    expect(paths()).toEqual([DEACTIVATE, "/api/admin/reauth"]);
  });

  it("비밀번호_칸이_비면_보내지_않고_필수입니다", async () => {
    const { answer, paths } = pendingApi();
    renderWithQuery(<Harness />, "/", { notice: true });
    const u = userEvent.setup();
    await u.click(trigger());
    await u.click(screen.getByRole("button", { name: "확인" }));
    await answer(REAUTH());
    await u.click(screen.getByRole("button", { name: "확인" }));
    await settle();
    const field = screen.getByLabelText("관리자 비밀번호 재확인");
    expect(screen.getByText("필수입니다")).toHaveAttribute("id", field.getAttribute("aria-describedby"));
    expect(field).toHaveAttribute("aria-invalid", "true");
    expect(paths()).toEqual([DEACTIVATE]);
  });

  it("성공하면_대화상자가_닫히고_칸이_DOM_에_없고_초점은_연_단추", async () => {
    const { answer } = pendingApi();
    renderWithQuery(<Harness />, "/", { notice: true });
    const u = userEvent.setup();
    await u.click(trigger());
    await u.click(screen.getByRole("button", { name: "확인" }));
    await answer(REAUTH());
    await u.type(screen.getByLabelText("관리자 비밀번호 재확인"), `${PASSWORD}{Enter}`);
    await answer(NO_CONTENT());
    await answer(NO_CONTENT());
    expect(dialog()).toBeNull();
    expect(document.querySelector('input[type="password"]')).toBeNull();
    expect(screen.getByTestId("notice")).toHaveTextContent("사용자를 비활성화했습니다");
    // Radix 는 닫힌 뒤 setTimeout 으로 초점을 돌린다
    await waitFor(() => expect(trigger()).toHaveFocus());
  });

  it("409_면_닫고_밖에_서버_detail_다시_누르면_열린다", async () => {
    const { answer } = pendingApi();
    renderWithQuery(<Harness />, "/", { notice: true });
    const u = userEvent.setup();
    await u.click(trigger());
    await u.click(screen.getByRole("button", { name: "확인" }));
    await answer(problem(409, { status: 409, detail: "마지막 활성 관리자는 비활성화할 수 없습니다" }));
    expect(dialog()).toBeNull();
    expect(screen.getByRole("alert")).toHaveTextContent("마지막 활성 관리자는 비활성화할 수 없습니다");
    await u.click(trigger());
    expect(dialog()).toBeInTheDocument();
  });

  it("type_없는_403_은_재인증이_아니라_닫고_알림", async () => {
    const { answer } = pendingApi();
    renderWithQuery(<Harness />, "/", { notice: true });
    const u = userEvent.setup();
    await u.click(trigger());
    await u.click(screen.getByRole("button", { name: "확인" }));
    await answer(problem(403, { status: 403, detail: "권한이 없습니다" }));
    expect(dialog()).toBeNull();
    expect(screen.queryByLabelText("관리자 비밀번호 재확인")).toBeNull();
    expect(screen.getByRole("alert")).toHaveTextContent("권한이 없습니다");
  });
});
