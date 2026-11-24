import { z } from "zod";

const bytes = (v: string) => new TextEncoder().encode(v).length;

/** 서버 Password.java 와 같은 규칙. 설치와 새 사용자가 같이 쓴다. */
export const passwordSchema = z.string().min(10, "10자 이상").refine((v) => bytes(v) <= 72, "72바이트 이하(한글은 약 24자)");
