import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, LocationProbe, mockApi, renderWithQuery } from "@/test-api";
import { LoginPage } from "./LoginPage";

afterEach(() => vi.unstubAllGlobals());

function renderLogin(route = "/login?next=/admin") {
  return renderWithQuery(
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="*" element={<LocationProbe />} />
    </Routes>,
    route,
  );
}

async function submit(email = "admin@ondolith.test", password = "login-pass-4b2e") {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("이메일"), email);
  await user.type(screen.getByLabelText("비밀번호"), password);
  await user.click(screen.getByRole("button", { name: "로그인" }));
}

describe("로그인 화면", () => {
  it("성공하면 next 로 간다", async () => {
    const api = mockApi((req) => (req.url.endsWith("/api/auth/login") ? new Response(null, { status: 204 }) : json(200, { user: null, can: {} })));
    renderLogin();
    await submit();
    expect(await screen.findByTestId("location")).toHaveTextContent("/admin");
    const login = api.mock.calls.map(([r]) => r).find((r) => r.url.endsWith("/api/auth/login"))!;
    expect(login.method).toBe("POST");
    expect(await login.json()).toEqual({ email: "admin@ondolith.test", password: "login-pass-4b2e" });
  });

  it("next 가 바깥 주소면 홈으로 간다", async () => {
    mockApi((req) => (req.url.endsWith("/api/auth/login") ? new Response(null, { status: 204 }) : json(200, { user: null, can: {} })));
    renderLogin("/login?next=//evil.test");
    await submit();
    expect(await screen.findByTestId("location")).toHaveTextContent(/^\/$/);
  });

  it("401 이면 서버 문구를 보이고 그 알림에 포커스", async () => {
    mockApi(() => json(401, { status: 401, detail: "이메일 또는 비밀번호가 맞지 않습니다" }));
    renderLogin();
    await submit();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("이메일 또는 비밀번호가 맞지 않습니다");
    expect(alert).toHaveFocus();
  });

  it("429 면 잠시 뒤 다시 시도하라고 한다", async () => {
    mockApi(() => json(429, { status: 429, detail: "잠시 뒤 다시 시도하세요" }));
    renderLogin();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("잠시 뒤 다시 시도하세요");
  });

  it("빈 칸이면 보내지 않는다", async () => {
    const api = mockApi(() => json(200, {}));
    renderLogin();
    fireEvent.click(screen.getByRole("button", { name: "로그인" }));
    expect(await screen.findAllByText("필수입니다")).toHaveLength(2);
    expect(api.mock.calls.some(([r]) => r.url.endsWith("/api/auth/login"))).toBe(false);
  });
});
