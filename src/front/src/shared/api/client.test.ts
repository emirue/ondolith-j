import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "./client";

describe("api 클라이언트", () => {
  const fetchMock = vi.fn(async (_request: Request) => new Response(null, { status: 202 }));

  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=abc%3D; path=/";
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    fetchMock.mockClear();
  });

  const sent = () => fetchMock.mock.calls[0]![0];

  it("POST 에 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 붙인다", async () => {
    await api.POST("/api/install", {
      body: {
        db_host: "localhost", db_port: 5432, db_user: "app", db_name: "app", db_sslmode: "disable",
        site_name: "시험", site_type: "cms", site_url: "https://site.ondolith.test",
        admin_email: "admin@ondolith.test", admin_password: "not-a-real-pass", admin_password_confirm: "not-a-real-pass",
      },
    });
    expect(sent().headers.get("X-XSRF-TOKEN")).toBe("abc=");
  });

  it("GET 에는 붙이지 않는다", async () => {
    await api.GET("/api/health");
    expect(sent().headers.get("X-XSRF-TOKEN")).toBeNull();
  });
});
