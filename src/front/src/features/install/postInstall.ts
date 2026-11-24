import { api } from "@/shared/api/client";
import type { InstallValues } from "./installSchema";

export type InstallResult =
  | { ok: true }
  | { ok: false; detail: string; errors?: Record<string, string> };

/**
 * POST /api/install. 202 면 ok, 아니면 Problem Details 의 detail·errors.
 * useMutation 을 쓰지 않는다 — 변경 캐시가 보낸 값(DB·관리자 비밀번호)을 남긴다(1-B2 최종 검토 c, useSave 와 같은 이유).
 * 진행 상태는 폼의 isSubmitting 이 맡는다.
 */
export async function postInstall(values: InstallValues): Promise<InstallResult> {
  const { response, error } = await api.POST("/api/install", { body: values });
  if (response.status === 202) return { ok: true };
  return {
    ok: false,
    detail: error?.detail ?? `설치하지 못했습니다(${response.status})`,
    errors: error?.errors,
  };
}
