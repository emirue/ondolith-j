import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, mockApi, renderWithQuery } from "@/test-api";
import { InstallPage } from "./InstallPage";

afterEach(() => vi.unstubAllGlobals());

async function fillValid() {
  const u = userEvent.setup();
  await u.type(screen.getByLabelText("DB 호스트"), "localhost");
  await u.type(screen.getByLabelText("DB 사용자"), "app");
  await u.type(screen.getByLabelText("DB 이름"), "app");
  await u.selectOptions(screen.getByLabelText("SSL 모드"), "disable");
  await u.type(screen.getByLabelText("사이트 이름"), "시험 사이트");
  await u.type(screen.getByLabelText("관리자 이메일"), "admin@ondolith.test");
  await u.type(screen.getByLabelText("관리자 비밀번호"), "not-a-real-pass");
  await u.type(screen.getByLabelText("비밀번호 확인"), "not-a-real-pass");
  return u;
}

describe("설치 화면", () => {
  it("사이트 주소를 현재 주소로 미리 채운다", () => {
    renderWithQuery(<InstallPage onSubmitted={() => {}} />);
    expect(screen.getByLabelText("사이트 주소")).toHaveValue(window.location.origin);
  });

  it("202 를 받으면 대기 화면으로 넘긴다", async () => {
    const f = mockApi(() => new Response(null, { status: 202 }));
    const onSubmitted = vi.fn();
    renderWithQuery(<InstallPage onSubmitted={onSubmitted} />);
    const u = await fillValid();
    await u.click(screen.getByRole("button", { name: "설치" }));
    await waitFor(() => expect(onSubmitted).toHaveBeenCalled());
    expect(await f.mock.calls[0]![0].json()).toMatchObject({ db_host: "localhost", db_port: 5432, site_type: "cms" });
  });

  it("422 의 칸별 오류를 그 칸 옆에 보인다", async () => {
    mockApi(() => json(422, { detail: "입력이 올바르지 않습니다", errors: { db_port: "1~65535" } }));
    renderWithQuery(<InstallPage onSubmitted={() => {}} />);
    const u = await fillValid();
    await u.click(screen.getByRole("button", { name: "설치" }));
    expect(await screen.findByText("1~65535")).toBeInTheDocument();
    expect(screen.getByLabelText("DB 포트")).toHaveAttribute("aria-invalid", "true");
  });

  it("422 를 받으면 첫 오류 칸으로 포커스를 옮긴다", async () => {
    mockApi(() => json(422, { detail: "입력이 올바르지 않습니다", errors: { db_port: "1~65535" } }));
    renderWithQuery(<InstallPage onSubmitted={() => {}} />);
    const u = await fillValid();
    await u.click(screen.getByRole("button", { name: "설치" }));
    await waitFor(() => expect(screen.getByLabelText("DB 포트")).toHaveFocus());
  });

  it("사이트 주소는 URL 로 정규화해 보낸다(한글 도메인은 punycode)", async () => {
    const f = mockApi(() => new Response(null, { status: 202 }));
    renderWithQuery(<InstallPage onSubmitted={() => {}} />);
    const u = await fillValid();
    await u.clear(screen.getByLabelText("사이트 주소"));
    await u.type(screen.getByLabelText("사이트 주소"), "https://한국.kr/a|b");
    await u.click(screen.getByRole("button", { name: "설치" }));
    await waitFor(() => expect(f).toHaveBeenCalled());
    expect((await f.mock.calls[0]![0].json()).site_url).toBe("https://xn--3e0b707e.kr/a%7Cb");
  });

  it("DB 접속 실패 원인(400)은 폼 위에 보인다", async () => {
    mockApi(() => json(400, { detail: "데이터베이스에 접속하지 못했습니다: password authentication failed" }));
    renderWithQuery(<InstallPage onSubmitted={() => {}} />);
    const u = await fillValid();
    await u.click(screen.getByRole("button", { name: "설치" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("password authentication failed");
  });

  it("사이트 이름이 NBSP·영폭 공백뿐이면 보내기 전에 칸 옆에 「필수입니다」", async () => {
    const f = mockApi(() => new Response(null, { status: 202 }));
    renderWithQuery(<InstallPage onSubmitted={() => {}} />);
    const u = await fillValid();
    for (const name of ["\u00A0\u00A0", "\u200B"]) {
      await u.clear(screen.getByLabelText("사이트 이름"));
      await u.type(screen.getByLabelText("사이트 이름"), name);
      await u.click(screen.getByRole("button", { name: "설치" }));
      expect(await screen.findByText("필수입니다")).toHaveAttribute("id", "site_name-error");
    }
    expect(f).not.toHaveBeenCalled();
  });

  it("비밀번호 확인이 다르면 보내기 전에 칸 옆에 알린다", async () => {
    const f = mockApi(() => new Response(null, { status: 202 }));
    renderWithQuery(<InstallPage onSubmitted={() => {}} />);
    const u = await fillValid();
    await u.type(screen.getByLabelText("비밀번호 확인"), "x");
    await u.click(screen.getByRole("button", { name: "설치" }));
    expect(await screen.findByText("비밀번호 확인이 다릅니다")).toBeInTheDocument();
    expect(f).not.toHaveBeenCalled();
  });

  it("보낸 비밀번호가 쿼리·변경 캐시에 남지 않는다(1-B2 최종 검토 c)", async () => {
    mockApi(() => new Response(null, { status: 202 }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const onSubmitted = vi.fn();
    render(
      <QueryClientProvider client={client}>
        <InstallPage onSubmitted={onSubmitted} />
      </QueryClientProvider>,
    );
    const u = await fillValid();
    await u.type(screen.getByLabelText("DB 비밀번호"), "db-pass-not-real");
    await u.click(screen.getByRole("button", { name: "설치" }));
    await waitFor(() => expect(onSubmitted).toHaveBeenCalled());
    const cached = JSON.stringify([
      client.getMutationCache().getAll().map((m) => m.state),
      client.getQueryCache().getAll().map((q) => q.state),
    ]);
    expect(cached).not.toContain("not-a-real-pass");
    expect(cached).not.toContain("db-pass-not-real");
  });
});
