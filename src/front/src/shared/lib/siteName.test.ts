import { describe, expect, it } from "vitest";
import { nameSchema, siteNameSchema, stripSiteName } from "./siteName";

// 서버 SiteNameTest 와 같은 입력 표(null 은 TS 에 없어 뺐다)
const error = (v: string) => {
  const r = siteNameSchema.safeParse(v);
  return r.success ? null : r.error.issues.map((i) => i.message);
};

describe("사이트 이름 규칙", () => {
  it("앞뒤의 공백과 NBSP 를 빼고 가운데는 둔다", () => {
    expect(stripSiteName("  이름  ")).toBe("이름");
    expect(stripSiteName("a b")).toBe("a b");
  });

  it("비었거나 공백·보이지 않는 문자만이면 필수입니다", () => {
    for (const v of ["", "   ", "  ", "​"]) expect(error(v), JSON.stringify(v)).toEqual(["필수입니다"]);
  });

  it("길이는 앞뒤를 뺀 뒤 잰다", () => {
    expect(siteNameSchema.parse("  " + "가".repeat(100) + " ")).toBe("가".repeat(100));
    expect(siteNameSchema.parse(" " + "가".repeat(100) + " ")).toHaveLength(100);
    expect(error("가".repeat(101))).toEqual(["100자 이하"]);
    expect(error("가")).toBeNull();
  });

  it("가운데에 제어 문자가 있으면 거부한다", () => {
    for (const v of ["a\u0000b", "a\nb", "a\tb", "a\u007Fb"]) expect(error(v), JSON.stringify(v)).toEqual(["쓸 수 없는 문자가 있습니다"]);
  });

  it("오류는 첫 위반 하나 — 필수, 제어 문자, 길이 순(서버와 같다)", () => {
    expect(error("\u0000")).toEqual(["필수입니다"]);
    expect(error("a\t" + "가".repeat(100))).toEqual(["쓸 수 없는 문자가 있습니다"]);
  });

  it("nameSchema(50) — 같은 규칙에 길이만 50(사용자 표시 이름)", () => {
    const r = nameSchema(50).safeParse("가".repeat(51));
    expect(r.success ? null : r.error.issues.map((i) => i.message)).toEqual(["50자 이하"]);
    expect(nameSchema(50).parse(" " + "가".repeat(50) + " ")).toBe("가".repeat(50));
    expect(nameSchema(50).safeParse(" ").success).toBe(false);
  });
});
