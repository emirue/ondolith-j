import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { useMe } from "@/features/auth/useMe";
import { useSite } from "@/features/home/useSite";
import { api } from "@/shared/api/client";
import { SaveAlert, useSave } from "@/shared/api/useSave";
import { siteNameSchema } from "@/shared/lib/siteName";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { Label } from "@/shared/ui/label";

const settingsSchema = z.object({ name: siteNameSchema });

type SettingsValues = z.infer<typeof settingsSchema>;

/** /admin/settings — 사이트 설정(A-201). 메뉴 숨김은 편의다 — 권한이 없으면 폼 대신 안내, 막는 것은 서버다. */
export function SettingsPage() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  const me = useMe();
  const site = useSite();
  return (
    <>
      <h2 ref={heading} tabIndex={-1} className="mb-4 text-xl font-semibold outline-none">사이트 설정</h2>
      {me.isPending ? null : me.isError ? (
        // M-10: can 을 모르는 것이지 권한이 없는 것이 아니다
        <p role="alert">사이트 설정을 불러오지 못했습니다</p>
      ) : me.data?.can?.["settings.update"] !== true ? (
        <p>권한이 없습니다</p>
      ) : site.data ? (
        // 처음 값이 온 뒤에 폼을 그린다(defaultValues 는 첫 그리기 값만 쓴다). 저장 뒤 다시 읽기가 실패해도 폼은 남는다
        <SettingsForm name={site.data.name ?? ""} />
      ) : (
        site.isError && <p role="alert">사이트 설정을 불러오지 못했습니다</p>
      )}
    </>
  );
}

function SettingsForm({ name }: { name: string }) {
  const queries = useQueryClient();
  const form = useForm<SettingsValues>({ resolver: zodResolver(settingsSchema), defaultValues: { name } });
  const save = useSave({
    send: (body: SettingsValues) => api.POST("/api/admin/settings", { body }),
    success: "사이트 설정을 저장했습니다",
    onSuccess: () => queries.invalidateQueries({ queryKey: ["site"] }),
  });
  const error = form.formState.errors.name?.message ?? save.fieldErrors.name;
  return (
    <form onSubmit={form.handleSubmit(save.save)} noValidate className="max-w-lg space-y-4">
      <SaveAlert failure={save.failure} />
      <div className="space-y-1">
        <Label htmlFor="name">사이트 이름</Label>
        <Input id="name" aria-invalid={error ? true : undefined} aria-describedby={error ? "name-error" : undefined} {...form.register("name")} />
        {error && <p id="name-error" className="text-sm text-destructive">{error}</p>}
      </div>
      <Button type="submit" aria-disabled={save.pending || undefined} className="aria-disabled:opacity-50">
        {save.pending ? "처리 중…" : "저장"}
      </Button>
    </form>
  );
}
