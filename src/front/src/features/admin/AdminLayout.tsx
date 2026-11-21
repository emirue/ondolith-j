import type { ReactElement } from "react";
import { NavLink, Outlet, Route } from "react-router";
import { useLogout, useMe } from "@/features/auth/useMe";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { Button } from "@/shared/ui/button";
import { OplogPage } from "./OplogPage";
import { SettingsPage } from "./SettingsPage";
import { SystemPage } from "./SystemPage";

/** 관리자 화면 표(스펙 6절). 라우트와 메뉴를 둘 다 여기서 만든다(D81). */
export const ADMIN_SCREENS: {
  path: "settings" | "oplog" | "system";
  name: string;
  perm: "settings.update" | "log.view" | "settings.view";
  element: ReactElement;
}[] = [
  { path: "settings", name: "사이트 설정", perm: "settings.update", element: <SettingsPage /> },
  { path: "oplog", name: "작업 로그", perm: "log.view", element: <OplogPage /> },
  { path: "system", name: "시스템 정보", perm: "settings.view", element: <SystemPage /> },
];

/** `/admin` 의 자식 라우트. 권한과 관계없이 모두 둔다 — 숨김은 메뉴만, 막는 것은 서버다. `/admin` 자체는 빈 대시보드(Q6). */
export const adminRoutes = (
  <>
    <Route index />
    {ADMIN_SCREENS.map((s) => <Route key={s.path} path={s.path} element={s.element} />)}
  </>
);

/** 관리자 셸: 제목, 메뉴(`/api/me` 의 can 이 true 인 항목만), 로그아웃, 하위 화면. */
export function AdminLayout() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  const logout = useLogout();
  const can = useMe().data?.can;
  return (
    <main className="mx-auto max-w-3xl p-6">
      <h1 ref={heading} tabIndex={-1} className="mb-4 text-2xl font-semibold outline-none">관리자</h1>
      <Button type="button" variant="outline" onClick={logout}>로그아웃</Button>
      <nav aria-label="관리자 메뉴" className="my-4">
        <ul className="flex gap-4">
          {ADMIN_SCREENS.filter((s) => can?.[s.perm] === true).map((s) => (
            <li key={s.path}>
              <NavLink to={`/admin/${s.path}`} className="underline aria-[current=page]:font-semibold">{s.name}</NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <Outlet />
    </main>
  );
}
