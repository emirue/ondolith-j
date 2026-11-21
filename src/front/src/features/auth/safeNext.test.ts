import { describe, expect, it } from "vitest";
import { safeNext } from "./safeNext";

describe("safeNext — 로그인 뒤 갈 곳(열린 리디렉트 방지)", () => {
  it.each([
    ["/admin", "/admin"],
    ["/admin?tab=1#x", "/admin?tab=1#x"],
    ["//evil.test", "/"],
    ["/\\evil.test", "/"],
    ["https://evil.test", "/"],
    ["javascript:alert(1)", "/"],
    ["admin", "/"],
    ["/a\n", "/"],
    ["", "/"],
    [null, "/"],
  ])("%s → %s", (raw, expected) => {
    expect(safeNext(raw)).toBe(expected);
  });
});
