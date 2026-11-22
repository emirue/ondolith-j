import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Link, Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { LocationProbe, json, mockApi, renderWithQuery } from "@/test-api";
import { UserNewPage } from "./UserNewPage";

afterEach(() => vi.unstubAllGlobals());

const ID = "00000000-0000-0000-0000-00000000000b";
const PASSWORD = "fake-pass-new-user-1";

let client: QueryClient;
function Grab() {
  client = useQueryClient();
  return null;
}

/** 생성 요청에는 respond 가 답한다. 상세 자리에는 지금 주소와 다른 화면 링크. */
function renderNew(respond: () => Response | Promise<Response>) {
  const f = mockApi(() => respond());
  renderWithQuery(
    <>
      <Grab />
      <Routes>
        <Route path="/admin/users/new" element={<UserNewPage />} />
        <Route path="/admin/users/:id" element={<><LocationProbe /><Link to="/admin/oplog">다른 화면</Link></>} />
        <Route path="/admin/oplog" element={<p>다른 화면</p>} />
      </Routes>
    </>,
    "/admin/users/new",
    { notice: true },
  );
  return f;
}

const posts = (f: ReturnType<typeof mockApi>) => f.mock.calls.filter(([req]) => req.method === "POST");

async function fill({ email = "new@ondolith.test", name = "새 사람", password = PASSWORD, confirm = undefined as string | undefined } = {}) {
  confirm ??= password;
  const u = userEvent.setup();
  for (const [label, v] of [["이메일", email], ["표시 이름", name], ["비밀번호", password], ["비밀번호 확인", confirm]]) {
    const input = screen.getByLabelText(label!);
    await u.clear(input);
    if (v) {
      await u.click(input);
      await u.paste(v);
    }
  }
  await u.click(screen.getByRole("button", { name: "만들기" }));
}

/** 응답이 반영될 때까지 한 틱 넘긴다. */
const settle = () => new Promise((r) => setTimeout(r, 0));

describe("새 사용자", () => {
  it("비밀번호_확인이_다르면_보내지_않는다", async () => {
    const f = renderNew(() => json(201, { id: ID }));
    await fill({ confirm: PASSWORD + "x" });
    expect(await screen.findByText("비밀번호 확인이 다릅니다")).toBeInTheDocument();
    await settle();
    expect(posts(f)).toHaveLength(0);
  });

  it("201_이면_상세_주소로_가고_알림이_보이고_다른_화면에_가면_없다 — 확인 칸은 보내지 않는다, 목록을 무효화한다", async () => {
    const f = renderNew(() => json(201, { id: ID }));
    client.setQueryData(["admin", "users", 1, ""], { items: [] });
    await fill({ email: "new@ondolith.test", name: " 새 사람 " });
    expect(await screen.findByTestId("location")).toHaveTextContent(`/admin/users/${ID}`);
    expect(screen.getByTestId("notice")).toHaveTextContent("사용자를 만들었습니다");
    expect(await posts(f)[0]![0].json()).toEqual({ email: "new@ondolith.test", display_name: "새 사람", password: PASSWORD });
    expect(client.getQueryState(["admin", "users", 1, ""])?.isInvalidated).toBe(true);
    await userEvent.setup().click(screen.getByRole("link", { name: "다른 화면" }));
    await screen.findByText("다른 화면", { selector: "p" });
    expect(screen.getByTestId("notice")).toBeEmptyDOMElement();
  });

  it("비밀번호가_쿼리_캐시와_변경_캐시에_없다", async () => {
    renderNew(() => json(201, { id: ID }));
    await fill();
    await screen.findByTestId("location");
    await settle();
    const mutations = JSON.stringify(client.getMutationCache().getAll().map((m) => m.state.variables));
    const queries = JSON.stringify(client.getQueryCache().getAll().map((q) => [q.queryKey, q.state.data]));
    expect(mutations).not.toContain(PASSWORD);
    expect(queries).not.toContain(PASSWORD);
  });

  it("422_errors_email_이면_그_칸에_초점", async () => {
    renderNew(() => json(422, { status: 422, detail: "입력이 올바르지 않습니다", errors: { email: "이메일 형식이 아닙니다" } }));
    await fill();
    expect(await screen.findByText("이메일 형식이 아닙니다")).toHaveAttribute("id", "email-error");
    const email = screen.getByLabelText("이메일");
    await waitFor(() => expect(email).toHaveFocus());
    expect(email).toHaveAttribute("aria-describedby", "email-error");
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("409_이면_서버_문구", async () => {
    renderNew(() => json(409, { status: 409, detail: "이미 사용 중인 이메일입니다" }));
    await fill();
    expect(await screen.findByRole("alert")).toHaveTextContent("이미 사용 중인 이메일입니다");
  });

  it("표시 이름 51자·공백만은 보내지 않는다", async () => {
    const f = renderNew(() => json(201, { id: ID }));
    await fill({ name: "가".repeat(51) });
    expect(await screen.findByText("50자 이하")).toBeInTheDocument();
    await fill({ name: " " });
    expect(await screen.findByText("필수입니다")).toBeInTheDocument();
    await settle();
    expect(posts(f)).toHaveLength(0);
  });

  it("비밀번호 9자·73바이트는 보내지 않는다(서버와 같은 규칙)", async () => {
    const f = renderNew(() => json(201, { id: ID }));
    await fill({ password: "123456789" });
    expect(await screen.findByText("10자 이상")).toBeInTheDocument();
    await fill({ password: "가".repeat(24) + "a" });
    expect(await screen.findByText("72바이트 이하(한글은 약 24자)")).toBeInTheDocument();
    await settle();
    expect(posts(f)).toHaveLength(0);
  });
});
