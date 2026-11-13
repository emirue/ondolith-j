import { z } from "zod";

const bytes = (v: string) => new TextEncoder().encode(v).length;

function isSiteUrl(v: string) {
  try {
    const u = new URL(v);
    return (u.protocol === "http:" || u.protocol === "https:") && u.hostname !== "";
  } catch {
    return false;
  }
}

/**
 * 브라우저 URL 로 정규화한다: 한글 도메인은 punycode. 그다음 RFC 3986 에 없는 문자(| ^ \ 등, URL 이 남겨 두는 것)를
 * 퍼센트 인코딩한다 — 서버의 java.net.URI 가 읽을 수 있어야 재시작 뒤에도 설정 파일이 읽힌다.
 */
export const normalizeSiteUrl = (v: string) =>
  new URL(v).href.replace(/[^A-Za-z0-9\-._~:/?#[\]@!$&'()*+,;=%]/g, (c) => encodeURIComponent(c));

/** 서버(InstallRequest)와 같은 규칙. 편의일 뿐이고 서버 검증이 기준이다. */
export const installSchema = z
  .object({
    db_host: z.string().min(1, "필수입니다").max(255, "255자 이하").regex(/^[A-Za-z0-9._:[\]-]+$/, "호스트 이름 또는 IP 주소만"),
    db_port: z.number({ message: "1~65535" }).int("1~65535").min(1, "1~65535").max(65535, "1~65535"),
    db_user: z.string().min(1, "필수입니다").max(63, "63자 이하"),
    db_password: z.string().max(255, "255자 이하"),
    db_name: z.string().min(1, "필수입니다").max(63, "63자 이하"),
    db_sslmode: z.enum(["disable", "require", "verify-ca", "verify-full"], { message: "선택하세요" }),
    site_name: z.string().trim().min(1, "필수입니다").max(100, "100자 이하"),
    site_type: z.enum(["cms", "shop"]),
    site_url: z.string().refine(isSiteUrl, "http·https 절대 주소"),
    admin_email: z.email("이메일 형식이 아닙니다").max(254, "254자 이하"),
    admin_password: z.string().min(10, "10자 이상").refine((v) => bytes(v) <= 72, "72바이트 이하(한글은 약 24자)"),
    admin_password_confirm: z.string(),
  })
  .refine((v) => v.admin_password === v.admin_password_confirm, {
    path: ["admin_password_confirm"],
    message: "비밀번호 확인이 다릅니다",
  });

export type InstallValues = z.infer<typeof installSchema>;
