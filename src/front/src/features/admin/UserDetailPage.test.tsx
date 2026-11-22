import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { REAUTH_TYPE } from "@/shared/api/useSave";
import { json, mockApi, renderWithQuery } from "@/test-api";
import { UserDetailPage } from "./UserDetailPage";

afterEach(() => vi.unstubAllGlobals());

const ID = "00000000-0000-0000-0000-0000000000d1";
const AT = "2026-11-21T03:04:05Z";
const PASSWORD = "reauth-pass-fake-3";
const ADMIN = { id: "00000000-0000-0000-0000-0000000000a1", key: "admin", name: "관리자", is_superuser: true };
const EDITOR = { id: "00000000-0000-0000-0000-0000000000a2", key: "editor", name: "편집자", is_superuser: false };
const OPERATOR = { id: "00000000-0000-0000-0000-0000000000a3", key: "operator", name: "운영자", is_superuser: false };
const ALL = { "user.update": true, "role.assign": true };

type User = { is_active: boolean; roles: (typeof ADMIN)[] } & Record<string, unknown>;
const user = (over: Partial<User> = {}): User => ({
  id: ID,
  email: "target@ondolith.test",
  display_name: "대상",
  is_active: true,
  created_at: AT,
  roles: [EDITOR],
  assignable_roles: [ADMIN, EDITOR, OPERATOR],
  ...over,
});

const problem = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/problem+json" } });
const NO_CONTENT = () => new Response(null, { status: 204 });

let client: QueryClient;
function Grab() {
  client = useQueryClient();
  return null;
}

type State = { user: User; gate?: Promise<void>; sent: { path: string; body: string }[] };

/** GET 은 state.user 를 낸다(state.gate 가 있으면 풀릴 때까지 기다린다). POST 는 post 가 답하고 state 를 바꿀 수 있다. */
function renderDetail({ can = ALL as Record<string, boolean>, post = (_p: string, _s: State): Response | Promise<Response> => NO_CONTENT(), initial = user() } = {}) {
  const state: State = { user: initial, sent: [] };
  const f = mockApi(async (req) => {
    const path = new URL(req.url).pathname;
    if (path === "/api/me") return json(200, { can });
    if (req.method === "GET") {
      await state.gate;
      return json(200, state.user);
    }
    state.sent.push({ path, body: await req.text() });
    return post(path, state);
  });
  renderWithQuery(
    <>
      <Grab />
      <Routes>
        <Route path="/admin/users/:id" element={<UserDetailPage />} />
      </Routes>
    </>,
    `/admin/users/${ID}`,
    { notice: true },
  );
  return { f, state };
}

const gets = (f: ReturnType<typeof mockApi>) => f.mock.calls.filter(([req]) => req.method === "GET" && new URL(req.url).pathname === `/api/admin/users/${ID}`);
const settle = () => act(() => new Promise((r) => setTimeout(r, 50)));
const heading = () => screen.getByRole("heading", { name: "사용자 상세" });
const rolesList = () => screen.getByRole("list", { name: "가진 역할" });

describe("사용자 상세", () => {
  it("칸: 이메일·표시 이름·상태·만든 날, 가진 역할", async () => {
    renderDetail();
    expect(await screen.findByText("target@ondolith.test")).toBeInTheDocument();
    expect(screen.getByText("대상")).toBeInTheDocument();
    expect(screen.getByText("활성")).toBeInTheDocument();
    expect(screen.getByText(new Date(AT).toLocaleDateString())).toBeInTheDocument();
    expect(within(rolesList()).getByText("편집자")).toBeInTheDocument();
  });

  it("비활성화는_확인_뒤_POST_하나_알림과_다시_읽기", async () => {
    const { f, state } = renderDetail({
      post: (_p, s) => {
        s.user = { ...s.user, is_active: false };
        return NO_CONTENT();
      },
    });
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "비활성화" }));
    const dialog = screen.getByRole("alertdialog", { name: "사용자를 비활성화할까요?" });
    expect(dialog).toHaveTextContent("이 계정의 모든 세션이 끝납니다");
    act(() => client.setQueryData(["admin", "users", 1, ""], { items: [] }));
    await u.click(within(dialog).getByRole("button", { name: "확인" }));
    expect(await screen.findByRole("button", { name: "활성화" })).toBeInTheDocument();
    expect(state.sent.map((s) => s.path)).toEqual([`/api/admin/users/${ID}/deactivate`]);
    expect(screen.getByTestId("notice")).toHaveTextContent("사용자를 비활성화했습니다");
    expect(screen.getByText("비활성")).toBeInTheDocument();
    expect(gets(f)).toHaveLength(2);
    expect(client.getQueryState(["admin", "users", 1, ""])?.isInvalidated).toBe(true);
  });

  it("비활성화_성공_뒤_연_단추가_사라지면_초점은_h2 — 다시 읽기가 Radix 의 초점 복귀보다 늦어도", async () => {
    const { state } = renderDetail({
      post: (_p, s) => {
        s.user = { ...s.user, is_active: false };
        return NO_CONTENT();
      },
    });
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "비활성화" }));
    let release!: () => void;
    state.gate = new Promise((r) => (release = r));
    await u.click(screen.getByRole("button", { name: "확인" }));
    // 대화상자가 닫히고 Radix 가 연 단추로 초점을 돌린 뒤에 다시 읽기가 온다
    await settle();
    expect(screen.getByRole("button", { name: "비활성화" })).toHaveFocus();
    await act(async () => release());
    await screen.findByRole("button", { name: "활성화" });
    await waitFor(() => expect(heading()).toHaveFocus());
    await settle();
    expect(heading()).toHaveFocus();
  });

  it("취소하면_POST_0", async () => {
    const { state } = renderDetail();
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "비활성화" }));
    await u.click(screen.getByRole("button", { name: "취소" }));
    await settle();
    expect(state.sent).toEqual([]);
  });

  it("활성_계정에는_비활성화만_비활성에는_활성화만 — 활성화는 묻지 않고 바로 보낸다", async () => {
    const { state } = renderDetail({
      initial: user({ is_active: false }),
      post: (_p, s) => {
        s.user = { ...s.user, is_active: true };
        return NO_CONTENT();
      },
    });
    const u = userEvent.setup();
    const activate = await screen.findByRole("button", { name: "활성화" });
    expect(screen.queryByRole("button", { name: "비활성화" })).toBeNull();
    await u.click(activate);
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(await screen.findByRole("button", { name: "비활성화" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "활성화" })).toBeNull();
    expect(state.sent.map((s) => s.path)).toEqual([`/api/admin/users/${ID}/activate`]);
    expect(screen.getByTestId("notice")).toHaveTextContent("사용자를 활성화했습니다");
    await waitFor(() => expect(heading()).toHaveFocus());
  });

  it("부여는_바로_보내고_가진_역할은_고르기에_없다 — 부여한 역할이 빠진 뒤 다음 부여는 남은 역할", async () => {
    const { state } = renderDetail({
      post: (_p, s) => {
        const id = JSON.parse(s.sent.at(-1)!.body).role_id;
        s.user = { ...s.user, roles: [...s.user.roles, [ADMIN, OPERATOR].find((r) => r.id === id)!] };
        return NO_CONTENT();
      },
    });
    const u = userEvent.setup();
    const select = await screen.findByRole("combobox", { name: "줄 역할" });
    expect(within(select).getAllByRole("option").map((o) => o.textContent)).toEqual(["관리자", "운영자"]);
    await u.selectOptions(select, "운영자");
    await u.click(screen.getByRole("button", { name: "부여" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    await waitFor(() => expect(within(rolesList()).getByText("운영자")).toBeInTheDocument());
    expect(screen.getByTestId("notice")).toHaveTextContent("역할을 부여했습니다");
    expect(within(screen.getByRole("combobox", { name: "줄 역할" })).getAllByRole("option").map((o) => o.textContent)).toEqual(["관리자"]);
    // 고른 값(운영자)이 목록에서 빠졌다 — 남은 값을 보내야 한다
    await u.click(screen.getByRole("button", { name: "부여" }));
    await waitFor(() => expect(state.sent).toHaveLength(2));
    expect(state.sent.map((s) => [s.path, JSON.parse(s.body)])).toEqual([
      [`/api/admin/users/${ID}/roles`, { role_id: OPERATOR.id }],
      [`/api/admin/users/${ID}/roles`, { role_id: ADMIN.id }],
    ]);
    // 줄 역할이 남지 않으면 고르기와 부여가 없다. 연 단추가 사라졌으니 초점은 h2
    await waitFor(() => expect(screen.queryByRole("combobox", { name: "줄 역할" })).toBeNull());
    expect(screen.queryByRole("button", { name: "부여" })).toBeNull();
    await waitFor(() => expect(heading()).toHaveFocus());
  });

  it("역할_빼기는_확인_뒤_POST_하나_줄이_사라지면_초점은_h2", async () => {
    const { state } = renderDetail({
      initial: user({ roles: [EDITOR, OPERATOR] }),
      post: (_p, s) => {
        s.user = { ...s.user, roles: [OPERATOR] };
        return NO_CONTENT();
      },
    });
    const u = userEvent.setup();
    const row = within(await screen.findByRole("list", { name: "가진 역할" })).getByText("편집자").closest("li")!;
    await u.click(within(row).getByRole("button", { name: "빼기" }));
    const dialog = screen.getByRole("alertdialog", { name: "역할을 뺄까요?" });
    await u.click(within(dialog).getByRole("button", { name: "확인" }));
    await waitFor(() => expect(within(rolesList()).queryByText("편집자")).toBeNull());
    expect(state.sent.map((s) => [s.path, JSON.parse(s.body)])).toEqual([[`/api/admin/users/${ID}/roles/revoke`, { role_id: EDITOR.id }]]);
    expect(screen.getByTestId("notice")).toHaveTextContent("역할을 뺐습니다");
    await waitFor(() => expect(heading()).toHaveFocus());
    await settle();
    expect(heading()).toHaveFocus();
  });

  it("R1_403_이면_서버_문구", async () => {
    renderDetail({ post: () => problem(403, { status: 403, detail: "자기 계정의 역할은 변경할 수 없습니다" }) });
    await userEvent.setup().click(await screen.findByRole("button", { name: "부여" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("자기 계정의 역할은 변경할 수 없습니다");
  });

  it("422_errors_role_id_는_고르기_옆에_보이고_초점은_고르기 — role=alert 없음(M-2)", async () => {
    renderDetail({ post: () => problem(422, { status: 422, errors: { role_id: "줄 수 없는 역할입니다" } }) });
    await userEvent.setup().click(await screen.findByRole("button", { name: "부여" }));
    const select = screen.getByRole("combobox", { name: "줄 역할" });
    await waitFor(() => expect(select).toHaveFocus());
    expect(screen.getByText("줄 수 없는 역할입니다")).toHaveAttribute("id", select.getAttribute("aria-describedby"));
    expect(select).toHaveAttribute("aria-invalid", "true");
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("재인증_403_이면_비밀번호_칸_입력_뒤_원_요청_한_번", async () => {
    let calls = 0;
    const { state } = renderDetail({
      post: (path, s) => {
        if (path === "/api/admin/reauth") return NO_CONTENT();
        if (calls++ === 0) return problem(403, { type: REAUTH_TYPE, status: 403, detail: "비밀번호를 다시 입력하세요" });
        s.user = { ...s.user, is_active: false };
        return NO_CONTENT();
      },
    });
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "비활성화" }));
    await u.click(screen.getByRole("button", { name: "확인" }));
    await u.type(await screen.findByLabelText("관리자 비밀번호 재확인"), `${PASSWORD}{Enter}`);
    expect(await screen.findByRole("button", { name: "활성화" })).toBeInTheDocument();
    expect(state.sent.map((s) => s.path)).toEqual([`/api/admin/users/${ID}/deactivate`, "/api/admin/reauth", `/api/admin/users/${ID}/deactivate`]);
    expect(screen.getByTestId("notice")).toHaveTextContent("사용자를 비활성화했습니다");
  });

  it("can_role_assign_이_false_면_부여와_빼기가_없다", async () => {
    renderDetail({ can: { "user.update": true, "role.assign": false } });
    await screen.findByRole("button", { name: "비활성화" });
    expect(within(rolesList()).getByText("편집자")).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "줄 역할" })).toBeNull();
    expect(screen.queryByRole("button", { name: "부여" })).toBeNull();
    expect(screen.queryByRole("button", { name: "빼기" })).toBeNull();
  });

  it("can_user_update_가_false_면_비활성화·활성화가_없다", async () => {
    renderDetail({ can: { "user.update": false, "role.assign": true } });
    await screen.findByRole("button", { name: "부여" });
    expect(screen.queryByRole("button", { name: "비활성화" })).toBeNull();
    expect(screen.queryByRole("button", { name: "활성화" })).toBeNull();
  });

  it("없는 사용자(404)면 「사용자를 불러오지 못했습니다」", async () => {
    mockApi((req) => (new URL(req.url).pathname === "/api/me" ? json(200, { can: ALL }) : problem(404, { status: 404, detail: "찾을 수 없습니다" })));
    renderWithQuery(
      <Routes>
        <Route path="/admin/users/:id" element={<UserDetailPage />} />
      </Routes>,
      `/admin/users/${ID}`,
      { notice: true },
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("사용자를 불러오지 못했습니다");
  });
});
