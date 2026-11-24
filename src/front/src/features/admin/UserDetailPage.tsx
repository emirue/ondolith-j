import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { useParams } from "react-router";
import { useMe } from "@/features/auth/useMe";
import { api } from "@/shared/api/client";
import { LoadError, LoadFailure } from "@/shared/api/load";
import type { components } from "@/shared/api/schema";
import { useSave } from "@/shared/api/useSave";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { ConfirmButton } from "@/shared/ui/confirm";
import { Label } from "@/shared/ui/label";

type RoleRef = components["schemas"]["RoleRef"];
/** 성공했다. gone 이면 연 단추가 다시 읽은 화면에서 사라진다. */
type Done = (gone: boolean) => void;

/**
 * /admin/users/:id — 사용자 상세(A-402)와 역할 부여·빼기(A-405). 생성 알림이 이 주소에서 보이므로 다른 주소로 옮기지 않는다.
 * 단추마다 부품과 useSave 를 따로 둔다(ConfirmButton 하나에 useSave 하나). /api/me 가 실패하면 단추만 숨긴다(사용자 목록과 같다).
 */
export function UserDetailPage() {
  const { id = "" } = useParams();
  const heading = useFocusOnMount<HTMLHeadingElement>();
  const can = useMe().data?.can;
  const queries = useQueryClient();
  const user = useQuery({
    queryKey: ["admin", "user", id],
    // 떠나면 지운다 — 로그아웃 뒤 같은 탭의 다른 계정에게 앞 사람의 행이 응답 전에 보이지 않게
    gcTime: 0,
    queryFn: async () => {
      const { data, response } = await api.GET("/api/admin/users/{id}", { params: { path: { id } } });
      if (!response.ok || !data) throw new LoadError(response.status);
      return data;
    },
  });
  // 연 단추가 사라지면(비활성↔활성, 빼기, 마지막 역할 부여) Radix 가 초점을 돌릴 곳이 없다 — 다시 읽은 화면을 그린 뒤 h2 로.
  // 다시 읽기가 Radix 의 초점 복귀보다 먼저 와도 늦게 와도 그린 뒤라 같다
  const lost = useRef(false);
  useEffect(() => {
    if (!lost.current) return;
    lost.current = false;
    heading.current?.focus();
  }, [user.dataUpdatedAt]);
  const done: Done = (gone) => {
    if (gone) lost.current = true;
    queries.invalidateQueries({ queryKey: ["admin", "user", id] });
    queries.invalidateQueries({ queryKey: ["admin", "users"] });
  };

  const u = user.data;
  const assign = can?.["role.assign"] === true;
  const held = new Set(u?.roles?.map((r) => r.id));
  const options = (u?.assignable_roles ?? []).filter((r) => !held.has(r.id));
  return (
    <>
      <h2 ref={heading} tabIndex={-1} className="mb-4 text-xl font-semibold outline-none">사용자 상세</h2>
      {user.isError && <LoadFailure error={user.error} failed="사용자를 불러오지 못했습니다" />}
      {u && (
        <>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            <dt>이메일</dt>
            <dd>{u.email}</dd>
            <dt>표시 이름</dt>
            <dd>{u.display_name}</dd>
            <dt>상태</dt>
            <dd>{u.is_active ? "활성" : "비활성"}</dd>
            <dt>만든 날</dt>
            <dd>{u.created_at && new Date(u.created_at).toLocaleDateString()}</dd>
          </dl>
          {/* key — 상태가 바뀌면 새 단추와 새 useSave(앞 동작의 done·failure 를 넘기지 않는다) */}
          {can?.["user.update"] === true && <StatusButton key={String(u.is_active)} id={id} active={u.is_active === true} done={done} />}
          <h3 className="mt-6 mb-2 font-semibold">역할</h3>
          <ul aria-label="가진 역할" className="space-y-1">
            {(u.roles ?? []).map((r) => (
              <li key={r.id} className="flex items-center gap-2">
                {r.name}
                {assign && <RevokeRole id={id} role={r} done={done} />}
              </li>
            ))}
          </ul>
          {assign && options.length > 0 && <GrantRole id={id} options={options} done={done} />}
        </>
      )}
    </>
  );
}

function StatusButton({ id, active, done }: { id: string; active: boolean; done: Done }) {
  const path = { params: { path: { id } } };
  const save = useSave({
    send: () => (active ? api.POST("/api/admin/users/{id}/deactivate", path) : api.POST("/api/admin/users/{id}/activate", path)),
    success: active ? "사용자를 비활성화했습니다" : "사용자를 활성화했습니다",
    onSuccess: () => done(true),
  });
  return (
    <div className="my-4">
      {active ? (
        <ConfirmButton label="비활성화" title="사용자를 비활성화할까요?" description="이 계정의 모든 세션이 끝납니다" destructive save={save} values={null} />
      ) : (
        <ConfirmButton label="활성화" title="사용자를 활성화할까요?" save={save} values={null} />
      )}
    </div>
  );
}

function RevokeRole({ id, role, done }: { id: string; role: RoleRef; done: Done }) {
  const save = useSave({
    send: (body: { role_id?: string }) => api.POST("/api/admin/users/{id}/roles/revoke", { params: { path: { id } }, body }),
    success: "역할을 뺐습니다",
    onSuccess: () => done(true),
  });
  return <ConfirmButton label="빼기" title="역할을 뺄까요?" description={`이 사용자에게서 ${role.name} 역할을 뺍니다`} destructive save={save} values={{ role_id: role.id }} />;
}

function GrantRole({ id, options, done }: { id: string; options: RoleRef[]; done: Done }) {
  const field = useId();
  const select = useRef<HTMLSelectElement>(null);
  const [picked, setPicked] = useState<string>();
  // 부여한 역할은 다시 읽은 목록에서 빠진다 — 고른 값이 없어졌으면 첫 항목(빠진 값을 다시 보내지 않게)
  const value = options.find((o) => o.id === picked)?.id ?? options[0]?.id;
  const save = useSave({
    send: (body: { role_id?: string }) => api.POST("/api/admin/users/{id}/roles", { params: { path: { id } }, body }),
    success: "역할을 부여했습니다",
    onSuccess: () => done(options.length === 1),
    focus: () => select.current?.focus(),
  });
  const error = save.fieldErrors.role_id;
  return (
    <div className="mt-4 flex items-end gap-2">
      <div className="space-y-1">
        <Label htmlFor={field}>줄 역할</Label>
        <select
          ref={select}
          id={field}
          value={value}
          onChange={(e) => setPicked(e.target.value)}
          className="h-9 rounded-md border px-2"
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${field}-error` : undefined}
        >
          {options.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
        </select>
        {error && <p id={`${field}-error`} className="text-sm text-destructive">{error}</p>}
      </div>
      <ConfirmButton label="부여" title="역할을 부여할까요?" save={save} values={{ role_id: value }} describedBy={error ? `${field}-error` : undefined} />
    </div>
  );
}
