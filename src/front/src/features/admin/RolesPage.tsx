import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { useMe } from "@/features/auth/useMe";
import { api } from "@/shared/api/client";
import { LoadError, LoadFailure } from "@/shared/api/load";
import type { components } from "@/shared/api/schema";
import { useSave } from "@/shared/api/useSave";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { ConfirmButton } from "@/shared/ui/confirm";
import { Label } from "@/shared/ui/label";

type RoleView = components["schemas"]["RoleView"];
/** 성공했다. gone 이면 연 단추가 다시 읽은 화면에서 사라진다. */
type Done = (gone: boolean) => void;

/**
 * /admin/roles — 역할과 권한(A-403·A-404). superuser 역할은 편집할 수 없다(R5).
 * 모두가 갖는 역할(visitor_only)의 고르기에는 방문자 권한만 둔다 — 나머지는 R9 가 늘 거절한다.
 * 단추마다 부품과 useSave 를 따로 둔다(ConfirmButton 하나에 useSave 하나). /api/me 가 실패하면 단추만 숨긴다(사용자 목록과 같다).
 */
export function RolesPage() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  const manage = useMe().data?.can?.["role.manage"] === true;
  const queries = useQueryClient();
  const roles = useQuery({
    queryKey: ["admin", "roles"],
    // 떠나면 지운다 — 로그아웃 뒤 같은 탭의 다른 계정에게 앞 사람의 행이 응답 전에 보이지 않게
    gcTime: 0,
    queryFn: async () => {
      const { data, response } = await api.GET("/api/admin/roles");
      if (!response.ok || !data) throw new LoadError(response.status);
      return data;
    },
  });
  // 연 단추가 사라지면(빼기, 마지막 권한 부여) Radix 가 초점을 돌릴 곳이 없다 — 다시 읽은 화면을 그린 뒤 h2 로
  const lost = useRef(false);
  useEffect(() => {
    if (!lost.current) return;
    lost.current = false;
    heading.current?.focus();
  }, [roles.dataUpdatedAt]);
  const done: Done = (gone) => {
    if (gone) lost.current = true;
    queries.invalidateQueries({ queryKey: ["admin", "roles"] });
    // 자기 역할의 권한이면 메뉴·단추가 바로 바뀌게(1-C2 스펙 7.4 (h))
    queries.invalidateQueries({ queryKey: ["me"] });
  };

  const all = (roles.data?.permissions ?? []).map((p) => p.key ?? "");
  const visitor = (roles.data?.permissions ?? []).filter((p) => p.visitor === true).map((p) => p.key ?? "");
  return (
    <>
      <h2 ref={heading} tabIndex={-1} className="mb-4 text-xl font-semibold outline-none">역할</h2>
      {roles.isError && <LoadFailure error={roles.error} failed="역할을 불러오지 못했습니다" />}
      {roles.data?.roles?.map((r) => <RoleSection key={r.id} role={r} all={r.visitor_only ? visitor : all} manage={manage} done={done} />)}
    </>
  );
}

function RoleSection({ role, all, manage, done }: { role: RoleView; all: string[]; manage: boolean; done: Done }) {
  const title = useId();
  const id = role.id ?? "";
  const grants = role.permissions ?? [];
  const held = new Set(grants.map((g) => g.key));
  const options = all.filter((k) => !held.has(k));
  return (
    <section aria-labelledby={title} className="mb-6">
      <h3 id={title} className="font-semibold">{role.name}</h3>
      <p className="mb-2 text-sm">{role.description}</p>
      {role.is_superuser ? (
        <p className="text-sm">모든 권한(편집할 수 없음)</p>
      ) : (
        <>
          <ul aria-label="권한" className="space-y-1 text-sm">
            {grants.map((g) => (
              <li key={g.key} className="flex items-center gap-2">
                <span>{g.key}</span>
                {/* R9: 모두가 갖는 역할(anonymous·member)의 방문자 권한 밖 부여 — 서버가 효력을 정한다 */}
                {g.effective === false && <span className="text-destructive">효력 없음 — 빼세요</span>}
                {manage && <RevokePermission id={id} permission={g.key ?? ""} done={done} />}
              </li>
            ))}
          </ul>
          {manage && options.length > 0 && <GrantPermission id={id} options={options} done={done} />}
        </>
      )}
    </section>
  );
}

function RevokePermission({ id, permission, done }: { id: string; permission: string; done: Done }) {
  const save = useSave({
    send: (body: { permission?: string }) => api.POST("/api/admin/roles/{id}/permissions/revoke", { params: { path: { id } }, body }),
    success: "권한을 뺐습니다",
    onSuccess: () => done(true),
  });
  return <ConfirmButton label="빼기" title="권한을 뺄까요?" description={`이 역할에서 ${permission} 권한을 뺍니다`} destructive save={save} values={{ permission }} />;
}

function GrantPermission({ id, options, done }: { id: string; options: string[]; done: Done }) {
  const field = useId();
  const select = useRef<HTMLSelectElement>(null);
  const [picked, setPicked] = useState<string>();
  // 부여한 권한은 다시 읽은 목록에서 빠진다 — 고른 값이 없어졌으면 첫 항목(빠진 값을 다시 보내지 않게)
  const value = options.includes(picked ?? "") ? picked : options[0];
  const save = useSave({
    send: (body: { permission?: string }) => api.POST("/api/admin/roles/{id}/permissions", { params: { path: { id } }, body }),
    success: "권한을 부여했습니다",
    onSuccess: () => done(options.length === 1),
    focus: () => select.current?.focus(),
  });
  const error = save.fieldErrors.permission;
  return (
    <div className="mt-2 flex items-end gap-2">
      <div className="space-y-1">
        <Label htmlFor={field}>줄 권한</Label>
        <select
          ref={select}
          id={field}
          value={value}
          onChange={(e) => setPicked(e.target.value)}
          className="h-9 rounded-md border px-2"
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${field}-error` : undefined}
        >
          {options.map((k) => <option key={k} value={k}>{k}</option>)}
        </select>
        {error && <p id={`${field}-error`} className="text-sm text-destructive">{error}</p>}
      </div>
      <ConfirmButton label="부여" title="권한을 부여할까요?" save={save} values={{ permission: value }} describedBy={error ? `${field}-error` : undefined} />
    </div>
  );
}
