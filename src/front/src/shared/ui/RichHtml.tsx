import DOMPurify from "dompurify";

// 1-C1 스펙 8.1 의 설정 그대로. 엄격한 꼴(img 경로·링크 꼴)은 서버 몫이고, 이 겹은 스크립트가 돌 자리만 없앤다
const CONFIG = {
  ALLOWED_TAGS: ["p", "br", "strong", "em", "h2", "h3", "ul", "ol", "li", "blockquote", "a", "img"],
  ALLOWED_ATTR: ["href", "src", "alt", "start"],
  ALLOWED_URI_REGEXP: /^(?:https?:\/\/|\/(?![\/\\]))/i,
  ADD_URI_SAFE_ATTR: ["start"],
  ALLOW_DATA_ATTR: false,
  ALLOW_ARIA_ATTR: false,
};

// ponytail: 본문 안 내부 링크는 일반 <a> 라 누르면 문서를 다시 불러온다. SPA 이동이 필요하면 여기서 클릭을 가로챈다
export function RichHtml({ html }: { html: string }) {
  // 서버가 저장·내보낼 때 허용 목록으로 정화한 본문(1-C1 스펙 3절). 브라우저 파서로 한 번 더 거른다
  return <div className="rich" dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(html, CONFIG) }} />;
}
