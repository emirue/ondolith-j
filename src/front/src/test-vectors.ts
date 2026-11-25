import { readFileSync } from "node:fs";
import path from "node:path";

// 서버 정화 시험과 같은 파일(1-C1 스펙 13.2). 프런트는 읽기만 한다
export type Vector = { id: string; input: string; server: string };

export const vectors: Vector[] = JSON.parse(
  readFileSync(path.resolve(import.meta.dirname, "../../back/src/test/resources/richtext/vectors.json"), "utf8"),
);

// 서버 LinkCheck 시험과 같은 파일(1-C1 스펙 13.3). 프런트 linkHref 가 같은 값을 본다(1-C2 스펙 4절)
export const links: { reject: string[]; allow: string[] } = JSON.parse(
  readFileSync(path.resolve(import.meta.dirname, "../../back/src/test/resources/richtext/links.json"), "utf8"),
);

// 편집기 ↔ 서버 짝 고정 파일(1-C2 스펙 5.1). input 을 실은 편집기의 getHTML() 이 editor 다
export type EditorRow = { id: string; input: string; editor: string };

export const editorRows: EditorRow[] = JSON.parse(
  readFileSync(path.resolve(import.meta.dirname, "../../back/src/test/resources/richtext/editor.json"), "utf8"),
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

// 편집기에는 Image 확장이 없다(1-C3)
export const EDITOR_TAGS = ALLOWED_TAGS.filter((t) => t !== "img");

// 1-C2 스펙 5.2 불변식: frontInvariant 판정 + 속성은 a[href]·ol[start] 뿐, start 는 ^[0-9]{1,6}$.
// E2E 가 evaluate 로 넘기므로 frontInvariant 를 부르지 않고 안에 다 둔다. linkHref 판정은 Vitest 시험이 따로 본다
export function editorInvariant(root: Element, tags: string[]): string[] {
  const URL_OK = /^(https?:\/\/|\/(?![\/\\]))/i;
  const bad: string[] = [];
  for (const el of root.querySelectorAll("*")) {
    const tag = el.tagName.toLowerCase();
    if (!tags.includes(tag)) bad.push(`허용 밖 요소 ${tag}`);
    for (const { name, value } of el.attributes) {
      if (tag === "a" && name === "href") {
        if (!URL_OK.test(value)) bad.push(`a href="${value}"`);
      } else if (tag === "ol" && name === "start") {
        if (!/^[0-9]{1,6}$/.test(value)) bad.push(`ol start="${value}"`);
      } else bad.push(`${tag} 의 ${name}`);
    }
  }
  return bad;
}
