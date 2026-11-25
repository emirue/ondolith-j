import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Link, Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { REAUTH_TYPE } from "@/shared/api/useSave";
import { json, mockApi, renderWithQuery } from "@/test-api";
import { RolesPage } from "./RolesPage";

afterEach(() => vi.unstubAllGlobals());

const R_ADMIN = "00000000-0000-0000-0000-0000000000a1";
const R_MEMBER = "00000000-0000-0000-0000-0000000000a2";
const R_OPERATOR = "00000000-0000-0000-0000-0000000000a3";
const PASSWORD = "reauth-pass-fake-4";

type Grant = { key: string; effective: boolean };
type Role = { id: string; key: string; name: string; description: string; is_superuser: boolean; permissions: Grant[] };
const roles = (): Role[] => [
  { id: R_ADMIN, key: "admin", name: "관리자", description: "설치 관리자", is_superuser: true, permissions: [] },
  { id: R_MEMBER, key: "member", name: "회원", description: "로그인한 모든 사람", is_superuser: false, permissions: [{ key: "log.view", effective: false }] },
  {
    id: R_OPERATOR,
    key: "operator",
    name: "운영자",
    description: "사이트 운영",
    is_superuser: false,
    permissions: [{ key: "admin.access", effective: true }, { key: "log.view", effective: true }],
  },
];
const PERMISSIONS = [
  { key: "admin.access", description: "관리자 화면" },
  { key: "log.view", description: "작업 로그 보기" },
  { key: "user.view", description: "사용자 목록 보기" },
];

const problem = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/problem+json" } });
const NO_CONTENT = () => new Response(null, { status: 204 });

type State = { roles: Role[]; sent: { path: string; body: string }[] };

function renderRoles({ can = { "role.manage": true } as Record<string, boolean>, post = (_p: string, _s: State): Response => NO_CONTENT(), get = (s: State) => json(200, { roles: s.roles, permissions: PERMISSIONS }) } = {}) {
  const state: State = { roles: roles(), sent: [] };
  const f = mockApi(async (req) => {
    const path = new URL(req.url).pathname;
    if (path === "/api/me") return json(200, { can });
    if (req.method === "GET") return get(state);
    state.sent.push({ path, body: await req.text() });
    return post(path, state);
  });
  renderWithQuery(<RolesPage />, "/admin/roles", { notice: true });
  return { f, state };
}

const role = async (name: string) => screen.findByRole("region", { name });
const grant = (section: HTMLElement, key: string) => within(within(section).getByRole("list", { name: "권한" })).getByText(key).closest("li")!;
const heading = () => screen.getByRole("heading", { level: 2, name: "역할" });
const settle = () => act(() => new Promise((r) => setTimeout(r, 50)));

describe("역할", () => {
  it("역할마다 이름·설명과 권한 목록", async () => {
    renderRoles();
    const op = await role("운영자");
    expect(op).toHaveTextContent("사이트 운영");
    expect(within(within(op).getByRole("list", { name: "권한" })).getAllByRole("listitem").map((li) => li.firstChild?.textContent)).toEqual(["admin.access", "log.view"]);
  });

  it("효력_없음_표시 — 효력 없는 부여에도 빼기가 있다", async () => {
    renderRoles();
    const member = grant(await role("회원"), "log.view");
    expect(member).toHaveTextContent("효력 없음 — 빼세요");
    expect(within(member).getByRole("button", { name: "빼기" })).toBeInTheDocument();
    expect(grant(await role("운영자"), "log.view")).not.toHaveTextContent("효력 없음");
  });

  it("admin_은_모든_권한_편집할_수_없음_고르기_없음", async () => {
    renderRoles();
    const admin = await role("관리자");
    expect(admin).toHaveTextContent("모든 권한(편집할 수 없음)");
    expect(within(admin).queryByRole("combobox")).toBeNull();
    expect(within(admin).queryByRole("button")).toBeNull();
  });

  it("role_manage_가_없으면_바꾸는_단추가_없다", async () => {
    renderRoles({ can: { "role.manage": false } });
    await role("운영자");
    await settle();
    expect(screen.getAllByRole("list", { name: "권한" })).toHaveLength(2);
    expect(screen.queryByRole("combobox")).toBeNull();
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("권한_빼기는_확인_대화상자 — 확인하면 POST 하나, 줄이 사라지면 초점은 h2", async () => {
    const { state } = renderRoles({
      post: (_p, s) => {
        s.roles = s.roles.map((r) => (r.id === R_OPERATOR ? { ...r, permissions: r.permissions.filter((g) => g.key !== "log.view") } : r));
        return NO_CONTENT();
      },
    });
    const u = userEvent.setup();
    await u.click(within(grant(await role("운영자"), "log.view")).getByRole("button", { name: "빼기" }));
    const dialog = screen.getByRole("alertdialog", { name: "권한을 뺄까요?" });
    expect(state.sent).toEqual([]);
    await u.click(within(dialog).getByRole("button", { name: "확인" }));
    await waitFor(() => expect(within(within(screen.getByRole("region", { name: "운영자" })).getByRole("list", { name: "권한" })).queryByText("log.view")).toBeNull());
    expect(state.sent.map((s) => [s.path, JSON.parse(s.body)])).toEqual([[`/api/admin/roles/${R_OPERATOR}/permissions/revoke`, { permission: "log.view" }]]);
    expect(screen.getByTestId("notice")).toHaveTextContent("권한을 뺐습니다");
    await waitFor(() => expect(heading()).toHaveFocus());
    await settle();
    expect(heading()).toHaveFocus();
  });

  it("부여_성공_뒤_역할_목록을_다시_읽는다 — 고르기에는 가지지 않은 권한만, 묻지 않고 바로 보낸다", async () => {
    const { f, state } = renderRoles({
      post: (_p, s) => {
        s.roles = s.roles.map((r) => (r.id === R_OPERATOR ? { ...r, permissions: [...r.permissions, { key: "user.view", effective: true }] } : r));
        return NO_CONTENT();
      },
    });
    const u = userEvent.setup();
    const op = await role("운영자");
    const select = within(op).getByRole("combobox", { name: "줄 권한" });
    expect(within(select).getAllByRole("option").map((o) => o.textContent)).toEqual(["user.view"]);
    await u.click(within(op).getByRole("button", { name: "부여" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    await waitFor(() => expect(grant(screen.getByRole("region", { name: "운영자" }), "user.view")).toBeInTheDocument());
    expect(state.sent.map((s) => [s.path, JSON.parse(s.body)])).toEqual([[`/api/admin/roles/${R_OPERATOR}/permissions`, { permission: "user.view" }]]);
    expect(f.mock.calls.filter(([req]) => new URL(req.url).pathname === "/api/admin/roles" && req.method === "GET")).toHaveLength(2);
    expect(screen.getByTestId("notice")).toHaveTextContent("권한을 부여했습니다");
  });

  it("권한을_바꾸면_/api/me_를_다시_읽는다 — 부여도 빼기도", async () => {
    const { f } = renderRoles();
    const me = () => f.mock.calls.filter(([req]) => new URL(req.url).pathname === "/api/me").length;
    const u = userEvent.setup();
    const op = await role("운영자");
    await waitFor(() => expect(me()).toBe(1));
    await u.click(within(op).getByRole("button", { name: "부여" }));
    await waitFor(() => expect(me()).toBe(2));
    await u.click(within(grant(op, "log.view")).getByRole("button", { name: "빼기" }));
    await u.click(within(screen.getByRole("alertdialog", { name: "권한을 뺄까요?" })).getByRole("button", { name: "확인" }));
    await waitFor(() => expect(me()).toBe(3));
  });

  it("R9_422_는_고르기_옆_칸_오류_초점은_고르기 — role=alert 없음(M-2)", async () => {
    renderRoles({ post: () => problem(422, { status: 422, errors: { permission: "모두가 갖는 역할에는 방문자 권한만 줄 수 있습니다" } }) });
    const member = await role("회원");
    await userEvent.setup().click(within(member).getByRole("button", { name: "부여" }));
    const select = within(member).getByRole("combobox", { name: "줄 권한" });
    await waitFor(() => expect(select).toHaveFocus());
    expect(within(member).getByText("모두가 갖는 역할에는 방문자 권한만 줄 수 있습니다")).toHaveAttribute("id", select.getAttribute("aria-describedby"));
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("재인증_뒤_R9_422_면_대화상자를_닫고_돌아온_부여_단추가_칸_오류를_읽는다", async () => {
    let calls = 0;
    const { state } = renderRoles({
      post: (path) => {
        if (path === "/api/admin/reauth") return NO_CONTENT();
        if (calls++ === 0) return problem(403, { type: REAUTH_TYPE, status: 403, detail: "비밀번호를 다시 입력하세요" });
        return problem(422, { status: 422, errors: { permission: "모두가 갖는 역할에는 방문자 권한만 줄 수 있습니다" } });
      },
    });
    const u = userEvent.setup();
    const member = await role("회원");
    await u.click(within(member).getByRole("button", { name: "부여" }));
    await u.type(await screen.findByLabelText("관리자 비밀번호 재확인"), `${PASSWORD}{Enter}`);
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(state.sent.map((s) => s.path)).toEqual([`/api/admin/roles/${R_MEMBER}/permissions`, "/api/admin/reauth", `/api/admin/roles/${R_MEMBER}/permissions`]);
    const button = within(member).getByRole("button", { name: "부여" });
    await waitFor(() => expect(button).toHaveFocus());
    const error = within(member).getByText("모두가 갖는 역할에는 방문자 권한만 줄 수 있습니다");
    expect(button.getAttribute("aria-describedby")).toBe(error.id);
  });

  it("visitor_only_역할에는_부여_고르기가_없다 — 방문자 권한이 없으면 R9 가 늘 거절한다", async () => {
    const list = roles().map((r) => ({ ...r, visitor_only: r.key === "member" }));
    renderRoles({ get: () => json(200, { roles: list, permissions: PERMISSIONS.map((p) => ({ ...p, visitor: false })) }) });
    const member = await role("회원");
    expect(within(member).queryByRole("combobox", { name: "줄 권한" })).toBeNull();
    expect(within(member).queryByRole("button", { name: "부여" })).toBeNull();
    // 빼기는 그대로 — 잘못 들어간 것을 빼는 길이다
    expect(within(grant(member, "log.view")).getByRole("button", { name: "빼기" })).toBeInTheDocument();
    expect(within(await role("운영자")).getByRole("combobox", { name: "줄 권한" })).toBeInTheDocument();
  });

  it("visitor_only_역할의_고르기에는_visitor_권한만", async () => {
    const list = roles().map((r) => ({ ...r, visitor_only: r.key === "member" }));
    const perms = PERMISSIONS.map((p) => ({ ...p, visitor: p.key === "user.view" }));
    renderRoles({ get: () => json(200, { roles: list, permissions: perms }) });
    const select = within(await role("회원")).getByRole("combobox", { name: "줄 권한" });
    expect(within(select).getAllByRole("option").map((o) => o.textContent)).toEqual(["user.view"]);
    const op = within(await role("운영자")).getByRole("combobox", { name: "줄 권한" });
    expect(within(op).getAllByRole("option").map((o) => o.textContent)).toEqual(["user.view"]);
  });

  it("그 밖의 실패 → 「역할을 불러오지 못했습니다」", async () => {
    renderRoles({ get: () => json(500, {}) });
    expect(await screen.findByRole("alert")).toHaveTextContent("역할을 불러오지 못했습니다");
  });

  it("화면을 떠나면 캐시를 남기지 않는다 — 다시 들어오면 앞 사람의 역할이 응답 전에 보이지 않는다", async () => {
    let hang = false;
    const f = mockApi((req) => {
      if (new URL(req.url).pathname === "/api/me") return json(200, { can: { "role.manage": true } });
      return hang ? new Promise<Response>(() => {}) : json(200, { roles: roles(), permissions: PERMISSIONS });
    });
    renderWithQuery(
      <Routes>
        <Route path="/admin/roles" element={<><RolesPage /><Link to="/away">나가기</Link></>} />
        <Route path="/away" element={<Link to="/admin/roles">돌아가기</Link>} />
      </Routes>,
      "/admin/roles",
      { notice: true },
    );
    const u = userEvent.setup();
    await role("운영자");
    hang = true;
    await u.click(screen.getByRole("link", { name: "나가기" }));
    await new Promise((r) => setTimeout(r, 10));
    await u.click(await screen.findByRole("link", { name: "돌아가기" }));
    await screen.findByRole("heading", { level: 2, name: "역할" });
    await vi.waitFor(() => expect(f.mock.calls.filter(([req]) => new URL(req.url).pathname === "/api/admin/roles")).toHaveLength(2));
    expect(screen.queryByRole("region")).toBeNull();
  });
});
