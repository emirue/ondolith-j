import createClient from "openapi-fetch";
import type { paths } from "./schema";

/** document.cookie 에서 값 하나. 없으면 null. */
function cookie(name: string): string | null {
  const hit = document.cookie.split("; ").find((c) => c.startsWith(`${name}=`));
  return hit ? decodeURIComponent(hit.slice(name.length + 1)) : null;
}

/**
 * 백엔드 API 클라이언트(타입은 openapi.json 에서 생성). 같은 출처로만 보낸다.
 * 안전하지 않은 메서드에는 Spring Security 의 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 붙인다(스펙 3절).
 */
export const api = createClient<paths>({
  baseUrl: globalThis.location?.origin ?? "",
  // 부를 때마다 찾는다 — 시험이 fetch 를 바꿔 끼울 수 있게
  fetch: (request) => globalThis.fetch(request),
});

api.use({
  onRequest({ request }) {
    if (request.method === "GET" || request.method === "HEAD") return request;
    const token = cookie("XSRF-TOKEN");
    if (token) request.headers.set("X-XSRF-TOKEN", token);
    return request;
  },
});
