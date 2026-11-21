import { screen, within } from "@testing-library/react";
import { Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, mockApi, renderWithQuery } from "@/test-api";
import { SystemPage } from "./SystemPage";

afterEach(() => vi.unstubAllGlobals());

const AT = "2026-11-20T01:02:03Z";

function renderSystem(res: () => Response) {
  mockApi(res);
  renderWithQuery(
    <Routes>
      <Route path="/admin/system" element={<SystemPage />} />
    </Routes>,
    "/admin/system",
  );
}

describe("시스템 정보", () => {
  it("버전·DB 서버 판·마이그레이션 표·대기 수 0", async () => {
    renderSystem(() =>
      json(200, {
        version: "0.0.1-test",
        database: { server_version: "18.1" },
        pending_count: 0,
        migrations: [
          { version: "1", description: "init", installed_on: AT, success: true },
          { version: "2", description: "auth", installed_on: AT, success: true },
          { version: "3", description: "oplog", installed_on: AT, success: false },
        ],
      }),
    );
    const table = await screen.findByRole("table", { name: "마이그레이션" });
    expect(within(table).getAllByRole("columnheader").map((th) => th.textContent)).toEqual(["버전", "설명", "적용 시각", "성공"]);
    const rows = within(table).getAllByRole("row").slice(1).map((tr) => within(tr).getAllByRole("cell").map((td) => td.textContent));
    expect(rows).toEqual([
      ["1", "init", new Date(AT).toLocaleString(), "성공"],
      ["2", "auth", new Date(AT).toLocaleString(), "성공"],
      ["3", "oplog", new Date(AT).toLocaleString(), "실패"],
    ]);
    const term = (name: string) => screen.getByText(name, { selector: "dt" }).nextElementSibling?.textContent;
    expect(term("버전")).toBe("0.0.1-test");
    expect(term("DB 서버 판")).toBe("18.1");
    expect(term("대기 중인 마이그레이션")).toBe("0");
  });

  it("403 → 「권한이 없습니다」, 제목은 남는다", async () => {
    renderSystem(() => json(403, { status: 403, detail: "권한이 없습니다" }));
    expect(await screen.findByText("권한이 없습니다")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "시스템 정보" })).toBeInTheDocument();
  });

  it("그 밖의 실패 → 「시스템 정보를 불러오지 못했습니다」", async () => {
    renderSystem(() => json(500, {}));
    expect(await screen.findByRole("alert")).toHaveTextContent("시스템 정보를 불러오지 못했습니다");
  });
});
