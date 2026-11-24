import { z } from "zod";

// 서버 SiteName.java 와 같은 세 정규식. String.prototype.trim 은 집합이 달라(BOM 을 빼고 \p{Z} 와 다르다) 쓰지 않는다
const EDGES = /^[\p{Z}\t\n\v\f\r]+|[\p{Z}\t\n\v\f\r]+$/gu;
const INVISIBLE = /^[\p{Z}\p{C}]*$/u;
const CONTROL = /\p{Cc}/u;

export const stripSiteName = (v: string) => v.replace(EDGES, "");

/** 사이트 이름 규칙(1-B1 스펙 8절)에 길이 상한만 다르게. 출력은 앞뒤를 뺀 값, 오류는 서버처럼 첫 위반 하나. */
export const nameSchema = (max: number) =>
  z
    .string()
    .overwrite(stripSiteName)
    .superRefine((v, ctx) => {
      const message = INVISIBLE.test(v) ? "필수입니다" : CONTROL.test(v) ? "쓸 수 없는 문자가 있습니다" : v.length > max ? `${max}자 이하` : null;
      if (message) ctx.addIssue({ code: "custom", message });
    });

/** 사이트 이름. 설치 폼과 사이트 설정이 같이 쓴다. 사용자 표시 이름은 nameSchema(50). */
export const siteNameSchema = nameSchema(100);
