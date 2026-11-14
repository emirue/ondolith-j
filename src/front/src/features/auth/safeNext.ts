/**
 * 로그인 뒤 갈 곳. 같은 사이트 경로만 받는다(열린 리디렉트 방지, 1-A 스펙 7절):
 * `/` 로 시작하고 `//`·`/\`(브라우저가 다른 호스트로 읽는다)로 시작하지 않으며 제어 문자가 없을 때만.
 */
export function safeNext(raw: string | null): string {
  if (!raw || !raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) return "/";
  if (/[\u0000-\u001f\u007f]/.test(raw)) return "/";
  return raw;
}
