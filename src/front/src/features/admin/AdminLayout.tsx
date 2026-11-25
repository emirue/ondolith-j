import type { ReactElement } from "react";
import { NavLink, Outlet, Route } from "react-router";
import { useLogout, useMe } from "@/features/auth/useMe";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { Button } from "@/shared/ui/button";
import { OplogPage } from "./OplogPage";
import { PageEditPage } from "./PageEditPage";
import { PagesPage } from "./PagesPage";
import { RolesPage } from "./RolesPage";
import { SettingsPage } from "./SettingsPage";
import { SystemPage } from "./SystemPage";
import { UserDetailPage } from "./UserDetailPage";
import { UserNewPage } from "./UserNewPage";
import { UsersPage } from "./UsersPage";

/**
 * 관리자 화면 표(1-B1 스펙 6절, 1-B2 스펙 9절). 라우트와 메뉴를 둘 다 여기서 만든다(D81). menu 가 false 면 라우트만.
 * 관리자 쿼리 키는 모두 ["admin", …] 아래 — 로그인·로그아웃이 지운다(adminCache.test).
 */
export const ADMIN_SCREENS: {
  path: "settings" | "oplog" | "system" | "users" | "roles" | "users/new" | "users/:id" | "pages" | "pages/new";
  name: string;
  perm: "settings.update" | "log.view" | "settings.view" | "user.view" | "role.view" | "user.create" | "user.update" | "page.view" | "page.create";
  menu: boolean;
  element: ReactElement;
}[] = [
  { path: "settings", name: "사이트 설정", perm: "settings.update", menu: true, element: <SettingsPage /> },
  { path: "oplog", name: "작업 로그", perm: "log.view", menu: true, element: <OplogPage /> },
  { path: "system", name: "시스템 정보", perm: "settings.view", menu: true, element: <SystemPage /> },
  { path: "users", name: "사용자", perm: "user.view", menu: true, element: <UsersPage /> },
  { path: "roles", name: "역할", perm: "role.view", menu: true, element: <RolesPage /> },
  { path: "pages", name: "페이지", perm: "page.view", menu: true, element: <PagesPage /> },
  { path: "users/new", name: "새 사용자", perm: "user.create", menu: false, element: <UserNewPage /> },
  { path: "users/:id", name: "사용자 상세", perm: "user.update", menu: false, element: <UserDetailPage /> },
  { path: "pages/new", name: "새 페이지", perm: "page.create", menu: false, element: <PageEditPage /> },
];

/** `/admin` 의 자식 라우트. 권한과 관계없이 모두 둔다 — 숨김은 메뉴만, 막는 것은 서버다. `/admin` 자체는 빈 대시보드(Q6, M-4: element 가 없으면 라우터가 경고한다). */
export const adminRoutes = (
  <>
    <Route index element={null} />
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
          {ADMIN_SCREENS.filter((s) => s.menu && can?.[s.perm] === true).map((s) => (
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
