import { readFileSync } from "node:fs";
import path from "node:path";

// 서버 정화 시험과 같은 파일(1-C1 스펙 13.2). 프런트는 읽기만 한다
export type Vector = { id: string; input: string; server: string };

export const vectors: Vector[] = JSON.parse(
  readFileSync(path.resolve(import.meta.dirname, "../../back/src/test/resources/richtext/vectors.json"), "utf8"),
);

export const ALLOWED_TAGS = ["p", "br", "strong", "em", "h2", "h3", "ul", "ol", "li", "blockquote", "a", "img"];

// 1-C1 스펙 13.2 프런트 불변식. E2E(pages.spec.ts)가 이 함수를 그대로 브라우저에 넘긴다(locator.evaluate) —
// 그래서 바깥 변수를 읽지 않는다(허용 태그는 인자, 정규식은 안에)
export function frontInvariant(root: Element, tags: string[] = ALLOWED_TAGS): string[] {
  const URL_OK = /^(https?:\/\/|\/(?![\/\\]))/i;
  const bad: string[] = [];
  for (const el of root.querySelectorAll("*")) {
    const tag = el.tagName.toLowerCase();
    if (!tags.includes(tag)) bad.push(`허용 밖 요소 ${tag}`);
    for (const { name, value } of el.attributes) {
      if (name.toLowerCase().startsWith("on")) bad.push(`${tag} 의 ${name}`);
      // 빈 값: DOMPurify 3.5.0 은 설정과 무관하게 남긴다(purify.es.mjs 2226줄 `return !value`, 값은 앞뒤 공백을 잘라 본다).
      // 현재 문서 주소로 풀려 스크립트 자리가 아니고, 서버는 지운다(X23-40)
      if ((name === "href" || name === "src") && value !== "" && !URL_OK.test(value) && !(tag === "img" && name === "src" && /^data:/i.test(value)))
        bad.push(`${tag} ${name}="${value}"`);
    }
  }
  return bad;
}
