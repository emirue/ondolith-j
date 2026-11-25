import { readFileSync } from "node:fs";
import path from "node:path";
import { render } from "@testing-library/react";
import DOMPurify from "dompurify";
import { describe, expect, it, vi } from "vitest";
import { frontInvariant, vectors } from "@/test-vectors";
import { RichHtml } from "./RichHtml";

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
    expect(vectors.length).toBe(96);
    const bad = vectors.flatMap((v) => frontInvariant(rich(v.input)).map((b) => `${v.id}: ${b}`));
    expect(bad).toEqual([]);
  });

  it("서버_출력은_DOMPurify_가_바꾸지_않는다", () => {
    const diff = vectors.filter((v) => rich(v.server).innerHTML !== v.server).map((v) => `${v.id}: ${rich(v.server).innerHTML}`);
    expect(diff).toEqual([]);
  });

  it("서버_허용_목록_밖의_속성은_남지_않는다", () => {
    expect(rich('<p class="c" id="i" title="t" style="color:red" data-x="1" aria-label="l">a</p>').innerHTML).toBe("<p>a</p>");
  });

  it("같은_html_로_다시_그려도_sanitize_는_한_번", () => {
    const spy = vi.spyOn(DOMPurify, "sanitize");
    try {
      const { container, rerender } = render(<RichHtml html="<p>a</p>" />);
      rerender(<RichHtml html="<p>a</p>" />);
      expect(spy).toHaveBeenCalledTimes(1);
      // html 이 바뀌면 다시 거른다 — 빈 의존성([])으로 헛통과하지 않게
      rerender(<RichHtml html="<p>b</p>" />);
      expect(spy).toHaveBeenCalledTimes(2);
      expect(container.querySelector(".rich")!.innerHTML).toBe("<p>b</p>");
    } finally {
      spy.mockRestore();
    }
  });

  it("사유_주석이_있다", () => {
    const src = readFileSync(path.resolve(import.meta.dirname, "RichHtml.tsx"), "utf8");
    expect(src).toContain("서버가 저장·내보낼 때 허용 목록으로 정화한 본문(1-C1 스펙 3절). 브라우저 파서로 한 번 더 거른다");
  });
});
