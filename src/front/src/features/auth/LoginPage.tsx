import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { useNavigate, useSearchParams } from "react-router";
import { z } from "zod";
import { api } from "@/shared/api/client";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { Label } from "@/shared/ui/label";
import { safeNext } from "./safeNext";

const loginSchema = z.object({
  email: z.string().trim().min(1, "필수입니다").max(254, "254자 이하"),
  password: z.string().min(1, "필수입니다").max(1000, "1000자 이하"),
});

type LoginValues = z.infer<typeof loginSchema>;

/** 로그인 화면(P-101). 실패 사유는 서버 문구 그대로 — 서버가 계정 존재를 구분하지 않는다(FR-201). */
export function LoginPage() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  const alertRef = useRef<HTMLParagraphElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const queries = useQueryClient();
  const form = useForm<LoginValues>({ resolver: zodResolver(loginSchema), defaultValues: { email: "", password: "" } });
  const { register, formState: { errors, isSubmitting } } = form;

  // 오류가 뜨면 알림으로 포커스를 옮겨 키보드·스크린 리더 사용자도 알게 한다
  useEffect(() => {
    if (error) alertRef.current?.focus();
  }, [error]);

  const onSubmit = form.handleSubmit(async (values) => {
    setError(null);
    try {
      const { response, error: problem } = await api.POST("/api/auth/login", { body: values });
      if (response.status === 204) {
        queries.removeQueries({ queryKey: ["me"] });
        queries.removeQueries({ queryKey: ["admin"] });
        navigate(safeNext(params.get("next")), { replace: true });
        return;
      }
      if (response.status === 429) return setError("잠시 뒤 다시 시도하세요");
      setError(problem?.detail ?? `로그인하지 못했습니다(${response.status})`);
    } catch {
      setError("서버에 연결하지 못했습니다");
    }
  });

  return (
    <main className="mx-auto max-w-sm p-6">
      <h1 ref={heading} tabIndex={-1} className="mb-4 text-2xl font-semibold outline-none">로그인</h1>
      {error && (
        <p ref={alertRef} tabIndex={-1} role="alert" className="mb-4 rounded-md border border-destructive p-3 text-sm text-destructive">
          {error}
        </p>
      )}
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {(["email", "password"] as const).map((name) => (
          <div key={name} className="space-y-1">
            <Label htmlFor={name}>{name === "email" ? "이메일" : "비밀번호"}</Label>
            <Input id={name} type={name === "email" ? "email" : "password"}
              autoComplete={name === "email" ? "username" : "current-password"}
              aria-invalid={errors[name] ? true : undefined} aria-describedby={errors[name] ? `${name}-error` : undefined}
              {...register(name)} />
            {errors[name] && <p id={`${name}-error`} className="text-sm text-destructive">{errors[name]?.message}</p>}
          </div>
        ))}
        <Button type="submit" disabled={isSubmitting}>로그인</Button>
      </form>
    </main>
  );
}
