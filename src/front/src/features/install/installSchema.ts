import { z } from "zod";

const bytes = (v: string) => new TextEncoder().encode(v).length;

/** 서버(InstallRequest)와 같은 규칙. 편의일 뿐이고 서버 검증이 기준이다. */
export const installSchema = z
  .object({
    db_host: z.string().min(1, "필수입니다").max(255, "255자 이하").regex(/^[A-Za-z0-9.:[\]-]+$/, "호스트 이름 또는 IP 주소만"),
    db_port: z.number({ message: "1~65535" }).int("1~65535").min(1, "1~65535").max(65535, "1~65535"),
    db_user: z.string().min(1, "필수입니다").max(63, "63자 이하"),
    db_password: z.string().max(255, "255자 이하"),
    db_name: z.string().min(1, "필수입니다").max(63, "63자 이하"),
    db_sslmode: z.enum(["disable", "require", "verify-ca", "verify-full"], { message: "선택하세요" }),
    site_name: z.string().trim().min(1, "필수입니다").max(100, "100자 이하"),
    site_type: z.enum(["cms", "shop"]),
    site_url: z.string().regex(/^https?:\/\/[^\s/?#]+\S*$/, "http·https 절대 주소"),
    admin_email: z.email("이메일 형식이 아닙니다").max(254, "254자 이하"),
    admin_password: z.string().min(10, "10자 이상").refine((v) => bytes(v) <= 72, "72바이트 이하(한글은 약 24자)"),
    admin_password_confirm: z.string(),
  })
  .refine((v) => v.admin_password === v.admin_password_confirm, {
    path: ["admin_password_confirm"],
    message: "비밀번호 확인이 다릅니다",
  });

export type InstallValues = z.infer<typeof installSchema>;
