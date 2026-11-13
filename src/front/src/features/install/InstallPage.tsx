import { zodResolver } from "@hookform/resolvers/zod";
import { useState, type InputHTMLAttributes, type ReactNode } from "react";
import { useForm, type FieldPath } from "react-hook-form";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { Label } from "@/shared/ui/label";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { installSchema, normalizeSiteUrl, type InstallValues } from "./installSchema";
import { useInstall } from "./useInstall";

/** 설치 화면(P-001). 서버 검증(422)은 칸 옆에, DB 접속 실패 등은 폼 위에 보인다. */
export function InstallPage({ onSubmitted }: { onSubmitted: () => void }) {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  const [formError, setFormError] = useState<string | null>(null);
  const install = useInstall();
  const form = useForm<InstallValues>({
    resolver: zodResolver(installSchema),
    defaultValues: {
      db_host: "", db_port: 5432, db_user: "", db_password: "", db_name: "",
      db_sslmode: "" as InstallValues["db_sslmode"], site_name: "", site_type: "cms",
      site_url: window.location.origin, admin_email: "", admin_password: "", admin_password_confirm: "",
    },
  });
  const { register, formState: { errors, isSubmitting } } = form;

  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null);
    try {
      const r = await install.mutateAsync({ ...values, site_url: normalizeSiteUrl(values.site_url) });
      if (r.ok) return onSubmitted();
      if (r.errors && Object.keys(r.errors).length > 0) {
        // 첫 오류 칸으로 포커스를 옮겨 키보드·스크린 리더 사용자도 알게 한다
        Object.entries(r.errors).forEach(([field, message], i) => {
          form.setError(field as FieldPath<InstallValues>, { message }, { shouldFocus: i === 0 });
        });
      } else {
        setFormError(r.detail);
      }
    } catch {
      setFormError("서버에 연결하지 못했습니다. 서버가 떠 있는지 확인하세요");
    }
  });

  const field = (name: FieldPath<InstallValues>, label: string, props: InputHTMLAttributes<HTMLInputElement> = {}) => (
    <Field name={name} label={label} error={errors[name]?.message}>
      <Input id={name} aria-invalid={errors[name] ? true : undefined} aria-describedby={errors[name] ? `${name}-error` : undefined}
        {...props} {...register(name, props.type === "number" ? { valueAsNumber: true } : {})} />
    </Field>
  );

  return (
    <main className="mx-auto max-w-lg p-6">
      <h1 ref={heading} tabIndex={-1} className="mb-4 text-2xl font-semibold outline-none">사이트 설치</h1>
      {formError && <p role="alert" className="mb-4 rounded-md border border-destructive p-3 text-sm text-destructive">{formError}</p>}
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <fieldset className="space-y-3">
          <legend className="font-medium">데이터베이스</legend>
          {field("db_host", "DB 호스트", { autoComplete: "off" })}
          {field("db_port", "DB 포트", { type: "number" })}
          {field("db_user", "DB 사용자", { autoComplete: "off" })}
          {field("db_password", "DB 비밀번호", { type: "password", autoComplete: "new-password" })}
          {field("db_name", "DB 이름", { autoComplete: "off" })}
          <Field name="db_sslmode" label="SSL 모드" error={errors.db_sslmode?.message}>
            <select id="db_sslmode" className="h-9 w-full rounded-md border px-2" aria-invalid={errors.db_sslmode ? true : undefined}
              aria-describedby={errors.db_sslmode ? "db_sslmode-error" : undefined} {...register("db_sslmode")}>
              <option value="">선택하세요</option>
              <option value="disable">disable</option>
              <option value="require">require</option>
              <option value="verify-ca">verify-ca</option>
              <option value="verify-full">verify-full</option>
            </select>
          </Field>
        </fieldset>
        <fieldset className="space-y-3">
          <legend className="font-medium">사이트</legend>
          {field("site_name", "사이트 이름")}
          <Field name="site_type" label="사이트 유형" error={errors.site_type?.message}>
            <select id="site_type" className="h-9 w-full rounded-md border px-2" {...register("site_type")}>
              <option value="cms">cms</option>
              <option value="shop">shop</option>
            </select>
          </Field>
          {field("site_url", "사이트 주소", { type: "url" })}
        </fieldset>
        <fieldset className="space-y-3">
          <legend className="font-medium">관리자</legend>
          {field("admin_email", "관리자 이메일", { type: "email", autoComplete: "username" })}
          {field("admin_password", "관리자 비밀번호", { type: "password", autoComplete: "new-password" })}
          {field("admin_password_confirm", "비밀번호 확인", { type: "password", autoComplete: "new-password" })}
        </fieldset>
        <Button type="submit" disabled={isSubmitting}>설치</Button>
      </form>
    </main>
  );
}

function Field({ name, label, error, children }: { name: string; label: string; error?: string; children: ReactNode }) {
  return (
    <div className="space-y-1">
      <Label htmlFor={name}>{label}</Label>
      {children}
      {error && <p id={`${name}-error`} className="text-sm text-destructive">{error}</p>}
    </div>
  );
}
