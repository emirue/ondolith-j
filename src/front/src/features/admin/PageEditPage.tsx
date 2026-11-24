import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { useForm, type FieldPath } from "react-hook-form";
import { useNavigate } from "react-router";
import { z } from "zod";
import { api } from "@/shared/api/client";
import type { components } from "@/shared/api/schema";
import { SaveAlert, useSave } from "@/shared/api/useSave";
import { nameSchema } from "@/shared/lib/siteName";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { Label } from "@/shared/ui/label";
import { RichEditor, useRichEditor } from "./RichEditor";

// 서버 Slug.error 와 같은 규칙·문구. 예약어는 서버 422 가 알린다(목록을 프런트에 두지 않는다)
const pageSchema = z.object({
  title: nameSchema(200),
  slug: z.string().min(1, "필수입니다").max(100, "100자 이하").regex(/^[a-z0-9][a-z0-9-]*$/, "소문자·숫자·하이픈만 쓸 수 있습니다"),
});

type PageValues = z.infer<typeof pageSchema>;

/** /admin/pages/new — 새 페이지(A-302 작성). 본문은 편집기가 쥔다 — 폼 상태에 사본을 두지 않는다(스펙 3.4). */
export function PageEditPage() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  const navigate = useNavigate();
  const queries = useQueryClient();
  const editor = useRichEditor("");
  const form = useForm<PageValues>({ resolver: zodResolver(pageSchema), defaultValues: { title: "", slug: "" } });
  const save = useSave<PageValues, components["schemas"]["Created"]>({
    send: (values) => api.POST("/api/admin/pages", { body: { ...values, body_html: editor?.getHTML() ?? "" } }),
    success: "페이지를 만들었습니다",
    noticePath: (d) => `/admin/pages/${d?.id}`,
    onSuccess: (d) => {
      queries.invalidateQueries({ queryKey: ["admin", "pages"] });
      navigate(`/admin/pages/${d?.id}`);
    },
    focus: (field) => (field === "body_html" ? editor?.commands.focus() : form.setFocus(field as FieldPath<PageValues>)),
  });
  const errors = form.formState.errors;
  const field = (name: FieldPath<PageValues>, label: string) => {
    const error = errors[name]?.message ?? save.fieldErrors[name];
    return (
      <div className="space-y-1">
        <Label htmlFor={name}>{label}</Label>
        <Input id={name} autoComplete="off" aria-invalid={error ? true : undefined} aria-describedby={error ? `${name}-error` : undefined} {...form.register(name)} />
        {error && <p id={`${name}-error`} className="text-sm text-destructive">{error}</p>}
      </div>
    );
  };
  return (
    <>
      <h2 ref={heading} tabIndex={-1} className="mb-4 text-xl font-semibold outline-none">새 페이지</h2>
      <form onSubmit={form.handleSubmit(save.save)} noValidate className="max-w-2xl space-y-4">
        <SaveAlert failure={save.failure} />
        {field("title", "제목")}
        {field("slug", "주소")}
        <RichEditor editor={editor} label="본문" labelId="body_html-label" error={save.fieldErrors.body_html} errorId="body_html-error" />
        <Button type="submit" aria-disabled={save.pending || undefined} className="aria-disabled:opacity-50">
          {save.pending ? "처리 중…" : "만들기"}
        </Button>
      </form>
    </>
  );
}
