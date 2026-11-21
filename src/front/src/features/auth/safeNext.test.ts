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
    // 점 마디를 풀면 // 가 된다 — 라우터의 정규화에 기대지 않는다(1-B1 최종 검토 M-6)
    ["/.//evil.test", "/"],
    ["/a/..//evil.test", "/"],
    ["/admin/./oplog?page=2", "/admin/oplog?page=2"],
    ["", "/"],
    [null, "/"],
  ])("%s → %s", (raw, expected) => {
    expect(safeNext(raw)).toBe(expected);
  });
});
