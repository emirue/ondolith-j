import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, mockApi, renderWithQuery } from "@/test-api";
import { SettingsPage } from "./SettingsPage";

afterEach(() => vi.unstubAllGlobals());

/** /api/me·/api/site 는 고정, 저장 요청에는 save 가 답한다. */
function renderSettings(save: () => Response, canUpdate = true) {
  const f = mockApi((req) => {
    if (req.url.endsWith("/api/me")) {
      return json(200, { user: { id: "u1", email: "op@ondolith.test", role: "operator" }, can: { "admin.access": true, "settings.update": canUpdate } });
    }
    if (req.url.endsWith("/api/site")) return json(200, { name: "옛 이름", type: "cms" });
    return save();
  });
  renderWithQuery(
    <Routes>
      <Route path="/admin/settings" element={<SettingsPage />} />
    </Routes>,
    "/admin/settings",
    { notice: true },
  );
  return f;
}

const saves = (f: ReturnType<typeof mockApi>) => f.mock.calls.filter(([req]) => req.url.endsWith("/api/admin/settings"));

async function submit(name: string) {
  const u = userEvent.setup();
  const input = await screen.findByLabelText("사이트 이름");
  await u.clear(input);
  if (name) await u.type(input, name);
  await u.click(screen.getByRole("button", { name: "저장" }));
  return input;
}

describe("사이트 설정", () => {
  it("처음 값은 /api/site 의 이름", async () => {
    renderSettings(() => new Response(null, { status: 204 }));
    expect(await screen.findByLabelText("사이트 이름")).toHaveValue("옛 이름");
  });

  it("204 → 알림 칸에 「사이트 설정을 저장했습니다」, 보낸 값은 앞뒤를 뺀 값", async () => {
    const f = renderSettings(() => new Response(null, { status: 204 }));
    await submit(" 새 이름 ");
    await waitFor(() => expect(screen.getByTestId("notice")).toHaveTextContent("사이트 설정을 저장했습니다"));
    expect(await saves(f)[0]![0].json()).toEqual({ name: "새 이름" });
    // 성공 뒤 site 쿼리를 무효화해 다시 읽는다(머리글·홈의 이름)
    await waitFor(() => expect(f.mock.calls.filter(([req]) => req.url.endsWith("/api/site"))).toHaveLength(2));
  });

  it("422 errors.name → 칸 옆 메시지, 알림 칸은 빈 칸", async () => {
    renderSettings(() => json(422, { status: 422, detail: "입력이 올바르지 않습니다", errors: { name: "100자 이하" } }));
    await submit("새 이름");
    expect(await screen.findByText("100자 이하")).toHaveAttribute("id", "name-error");
    expect(screen.getByLabelText("사이트 이름")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByTestId("notice")).toBeEmptyDOMElement();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("401 → 「로그인이 끝났습니다」와 지금 주소로 돌아오는 링크, 입력값은 그대로", async () => {
    renderSettings(() => json(401, { status: 401, detail: "로그인이 필요합니다" }));
    const input = await submit("새 이름");
    expect(await screen.findByRole("alert")).toHaveTextContent("로그인이 끝났습니다");
    expect(screen.getByRole("link", { name: "다시 로그인" })).toHaveAttribute("href", "/login?next=%2Fadmin%2Fsettings");
    expect(input).toHaveValue("새 이름");
  });

  it("403 → 「권한이 없습니다」", async () => {
    renderSettings(() => json(403, { status: 403, detail: "권한이 없습니다" }));
    await submit("새 이름");
    expect(await screen.findByRole("alert")).toHaveTextContent("권한이 없습니다");
  });

  it("429 → 「잠시 뒤 다시 시도하세요」", async () => {
    renderSettings(() => json(429, { status: 429, detail: "요청이 너무 많습니다" }));
    await submit("새 이름");
    expect(await screen.findByRole("alert")).toHaveTextContent("잠시 뒤 다시 시도하세요");
  });

  it("필수 칸이 빈 제출은 보내지 않고 잠그지도 않는다 — 값을 넣으면 보낸다", async () => {
    const f = renderSettings(() => new Response(null, { status: 204 }));
    await submit("​");
    expect(await screen.findByText("필수입니다")).toBeInTheDocument();
    expect(saves(f)).toHaveLength(0);
    await submit("새 이름");
    await waitFor(() => expect(saves(f)).toHaveLength(1));
  });

  it("can['settings.update'] 가 false 면 폼 대신 「권한이 없습니다」", async () => {
    renderSettings(() => new Response(null, { status: 204 }), false);
    expect(await screen.findByText("권한이 없습니다")).toBeInTheDocument();
    expect(screen.queryByLabelText("사이트 이름")).toBeNull();
    expect(screen.getByRole("heading", { name: "사이트 설정" })).toBeInTheDocument();
  });
});
