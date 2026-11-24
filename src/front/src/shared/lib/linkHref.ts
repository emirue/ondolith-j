import { safeNext } from "@/features/auth/safeNext";

// 서버 LinkCheck.java 와 같은 규칙. Java matches()(전체 일치)라 모두 ^…$ 이고 m 플래그를 쓰지 않는다
const CHARS = /^[A-Za-z0-9._~!$&()*+,;=:@/?#%-]{1,500}$/;
const INTERNAL = /^\/(?!\/).*$/;
const EXTERNAL = /^https?:\/\/[A-Za-z0-9.-]+(?::([0-9]{1,5}))?(?:[/?#].*)?$/;
const DOT_SEGMENT = /^(?:\.|%2e){1,2}$/i;

/**
 * 링크 값 판정(1-C2 스펙 4절). 그릴 수 있으면 브라우저가 푼 값, 아니면 null.
 * 서버 LinkCheck 규칙을 지난 뒤 브라우저 URL 파서로 한 번 더 본다 — 그래서 서버보다 엄격한 꼴이 있다(https://a.1/ 등).
 */
export function linkHref(url: string): string | null {
  if (!CHARS.test(url)) return null;
  if (INTERNAL.test(url)) {
    if (url.split(/[?#]/)[0].split("/").some((s) => DOT_SEGMENT.test(s))) return null;
    const t = safeNext(url);
    return t !== "/" || url === "/" ? t : null;
  }
  const m = EXTERNAL.exec(url);
  if (!m || (m[1] !== undefined && (Number(m[1]) < 1 || Number(m[1]) > 65535))) return null;
  try {
    const u = new URL(url);
    return u.protocol === "http:" || u.protocol === "https:" ? u.href : null;
  } catch {
    return null;
  }
}
