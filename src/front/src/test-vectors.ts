import { readFileSync } from "node:fs";
import path from "node:path";

// 서버 정화 시험과 같은 파일(1-C1 스펙 13.2). 프런트는 읽기만 한다
export type Vector = { id: string; input: string; server: string };

export const vectors: Vector[] = JSON.parse(
  readFileSync(path.resolve(import.meta.dirname, "../../back/src/test/resources/richtext/vectors.json"), "utf8"),
);

export const ALLOWED_TAGS = ["p", "br", "strong", "em", "h2", "h3", "ul", "ol", "li", "blockquote", "a", "img"];

// 1-C1 스펙 13.2 프런트 불변식. Task 9 E2E 가 브라우저에서 같은 판정을 돌린다 — 한쪽만 고치지 않는다
const URL_OK = /^(https?:\/\/|\/(?![\/\\]))/i;

export function frontInvariant(root: Element): string[] {
  const bad: string[] = [];
  for (const el of root.querySelectorAll("*")) {
    const tag = el.tagName.toLowerCase();
    if (!ALLOWED_TAGS.includes(tag)) bad.push(`허용 밖 요소 ${tag}`);
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
