import { useMutation } from "@tanstack/react-query";
import { api } from "@/shared/api/client";
import type { InstallValues } from "./installSchema";

export type InstallResult =
  | { ok: true }
  | { ok: false; detail: string; errors?: Record<string, string> };

/** POST /api/install. 202 면 ok, 아니면 Problem Details 의 detail·errors. */
export function useInstall() {
  return useMutation({
    mutationFn: async (values: InstallValues): Promise<InstallResult> => {
      const { response, error } = await api.POST("/api/install", { body: values });
      if (response.status === 202) return { ok: true };
      return {
        ok: false,
        detail: error?.detail ?? `설치하지 못했습니다(${response.status})`,
        errors: error?.errors,
      };
    },
  });
}
