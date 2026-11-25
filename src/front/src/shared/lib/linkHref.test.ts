import { describe, expect, it } from "vitest";
import { links } from "@/test-vectors";
import { linkHref } from "./linkHref";

describe("linkHref — 프런트 링크 판정(1-C2 스펙 4절)", () => {
  it("links_json_의_reject_는_모두_null", () => {
    for (const v of links.reject) expect(linkHref(v), JSON.stringify(v)).toBeNull();
  });

  it("links_json_의_allow_는_모두_null_이_아니다", () => {
    for (const v of links.allow) expect(linkHref(v), v).not.toBeNull();
  });

  it("끝_개행은_null", () => {
    expect(linkHref("/about\n")).toBeNull();
  });

  it("앞뒤_공백은_null", () => {
    expect(linkHref(" /about")).toBeNull();
    expect(linkHref("/about ")).toBeNull();
  });

  it("new_URL_이_던지면_null", () => {
    // 서버 LinkCheck 는 받지만 WHATWG 파서는 마지막 마디가 숫자인 호스트를 IPv4 로 읽어 던진다(스펙 4절 「서버보다 엄격한 곳」)
    expect(() => new URL("https://a.1/")).toThrow();
    expect(linkHref("https://a.1/")).toBeNull();
  });

  it("그릴_값은_브라우저가_푼_값", () => {
    expect(linkHref("/about?x=1#top")).toBe("/about?x=1#top");
    expect(linkHref("https://example.com")).toBe("https://example.com/");
  });
});
