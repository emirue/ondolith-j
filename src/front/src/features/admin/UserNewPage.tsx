import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import type { InputHTMLAttributes } from "react";
import { useForm, type FieldPath } from "react-hook-form";
import { useNavigate } from "react-router";
import { z } from "zod";
import { api } from "@/shared/api/client";
import type { components } from "@/shared/api/schema";
import { SaveAlert, useSave } from "@/shared/api/useSave";
import { passwordSchema } from "@/shared/lib/password";
import { nameSchema } from "@/shared/lib/siteName";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { Label } from "@/shared/ui/label";

/**
 * 서버(UsersController.CreateUser)와 같은 규칙. 확인 칸은 프런트만 본다 — 1-E 전에는 비밀번호를 바꿀 길이 없어
 * 오타 난 초기 비밀번호가 그 이메일을 묶어 버린다(1-B2 스펙 9절).
 */
const newUserSchema = z
  .object({
    email: z.email("이메일 형식이 아닙니다").max(254, "254자 이하"),
    display_name: nameSchema(50),
    password: passwordSchema,
    password_confirm: z.string(),
  })
  .refine((v) => v.password === v.password_confirm, { path: ["password_confirm"], message: "비밀번호 확인이 다릅니다" });

type NewUserValues = z.infer<typeof newUserSchema>;

/** /admin/users/new — 새 사용자(A-402 생성). 성공하면 상세로 가고, 알림은 상세 주소에서 보인다. */
export function UserNewPage() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  const navigate = useNavigate();
  const queries = useQueryClient();
  const form = useForm<NewUserValues>({
    resolver: zodResolver(newUserSchema),
    defaultValues: { email: "", display_name: "", password: "", password_confirm: "" },
  });
  // useSave 는 useMutation 을 쓰지 않는다 — 비밀번호가 변경 캐시에 남지 않는다
  const save = useSave<NewUserValues, components["schemas"]["Created"]>({
    send: ({ password_confirm: _, ...body }) => api.POST("/api/admin/users", { body }),
    success: "사용자를 만들었습니다",
    noticePath: (d) => `/admin/users/${d?.id}`,
    onSuccess: (d) => {
      queries.invalidateQueries({ queryKey: ["admin", "users"] });
      navigate(`/admin/users/${d?.id}`);
    },
    focus: (field) => form.setFocus(field as FieldPath<NewUserValues>),
  });
  const errors = form.formState.errors;
  const field = (name: FieldPath<NewUserValues>, label: string, props: InputHTMLAttributes<HTMLInputElement> = {}) => {
    const error = errors[name]?.message ?? save.fieldErrors[name];
    return (
      <div className="space-y-1">
        <Label htmlFor={name}>{label}</Label>
        <Input id={name} aria-invalid={error ? true : undefined} aria-describedby={error ? `${name}-error` : undefined} {...props} {...form.register(name)} />
        {error && <p id={`${name}-error`} className="text-sm text-destructive">{error}</p>}
      </div>
    );
  };
  return (
    <>
      <h2 ref={heading} tabIndex={-1} className="mb-4 text-xl font-semibold outline-none">새 사용자</h2>
      <form onSubmit={form.handleSubmit(save.save)} noValidate className="max-w-lg space-y-4">
        <SaveAlert failure={save.failure} />
        {field("email", "이메일", { type: "email", autoComplete: "off" })}
        {field("display_name", "표시 이름", { autoComplete: "off" })}
        {field("password", "비밀번호", { type: "password", autoComplete: "new-password" })}
        {field("password_confirm", "비밀번호 확인", { type: "password", autoComplete: "new-password" })}
        <Button type="submit" aria-disabled={save.pending || undefined} className="aria-disabled:opacity-50">
          {save.pending ? "처리 중…" : "만들기"}
        </Button>
      </form>
    </>
  );
}
