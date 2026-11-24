import { readFileSync } from "node:fs";
import path from "node:path";
import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { frontInvariant, vectors } from "@/test-vectors";
import { RichHtml } from "./RichHtml";

// vectors.json 밖: 한 번 통과로는 멱등이 깨지던 입력(RichTextTest.빠진_껍데기가_자동_닫힘을_막던_꼴도_멱등이다).
// server 는 2026-11-24 RichText.clean 을 실제로 돌린 출력이다
const UNWRAPPED = [
  { id: "W1", input: "<p>a<object><p>b</p></object></p>", server: "<p>a</p><p>b</p><p></p>" },
  { id: "W2", input: "<ul><li>a<section><li>b</li></section></li></ul>", server: "<ul><li>a</li><li>b</li></ul>" },
  { id: "W3", input: '<a href="/a">x<object><a href="/b">y</a></object></a>', server: '<a href="/a">x</a><a href="/b">y</a>' },
  { id: "W4", input: "<h2>a<div><h2>b</h2></div></h2>", server: "<h2>a</h2><h2>b</h2>" },
];
const ALL = [...vectors, ...UNWRAPPED];

function rich(html: string) {
  return render(<RichHtml html={html} />).container.querySelector(".rich")!;
}

describe("RichHtml", () => {
  it("img_onerror_와_javascript_링크가_DOM_에_남지_않는다", () => {
    const root = rich('<img src=x onerror=alert(1)><a href="javascript:alert(1)">x</a>');
    expect(root.querySelector("[onerror]")).toBeNull();
    expect(root.innerHTML).not.toContain("javascript:");
    expect(root.textContent).toBe("x");
  });

  it("모든_입력에서_프런트_불변식이_성립한다", () => {
    expect(vectors.length).toBe(92);
    const bad = ALL.flatMap((v) => frontInvariant(rich(v.input)).map((b) => `${v.id}: ${b}`));
    expect(bad).toEqual([]);
  });

  it("서버_출력은_DOMPurify_가_바꾸지_않는다", () => {
    const diff = ALL.filter((v) => rich(v.server).innerHTML !== v.server).map((v) => `${v.id}: ${rich(v.server).innerHTML}`);
    expect(diff).toEqual([]);
  });

  it("서버_허용_목록_밖의_속성은_남지_않는다", () => {
    expect(rich('<p class="c" id="i" title="t" style="color:red" data-x="1" aria-label="l">a</p>').innerHTML).toBe("<p>a</p>");
  });

  it("사유_주석이_있다", () => {
    const src = readFileSync(path.resolve(import.meta.dirname, "RichHtml.tsx"), "utf8");
    expect(src).toContain("서버가 저장·내보낼 때 허용 목록으로 정화한 본문(1-C1 스펙 3절). 브라우저 파서로 한 번 더 거른다");
  });
});
