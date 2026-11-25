import { useQuery } from "@tanstack/react-query";
import { Navigate, Route, Routes, useLocation } from "react-router";
import { AdminLayout, adminRoutes } from "@/features/admin/AdminLayout";
import { api } from "@/shared/api/client";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";

/** `/admin/*` 아래 전부. App 이 lazy 로 가져와 관리 화면·편집기가 공개 묶음에 들지 않는다(1-C2 스펙 8절). */
export default function AdminTree() {
  return (
    <Routes>
      <Route element={<AdminGate />}>{adminRoutes}</Route>
    </Routes>
  );
}

/** /admin — 관리자 트리의 문(1-A). 막는 것은 서버의 GET /api/admin 이고, 여기서는 그 결과로 화면을 고른다. 통과하면 관리자 셸(1-B1). */
export function AdminGate() {
  const location = useLocation();
  const admin = useQuery({
    queryKey: ["admin"],
    queryFn: async () => (await api.GET("/api/admin")).response.status,
  });
  if (admin.isPending) return null;
  if (admin.data === 401) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;
  if (admin.data === 403) return <Message title="권한이 없습니다" />;
  if (admin.data !== 200) return <Message title="관리자 화면을 불러오지 못했습니다" />;
  return <AdminLayout />;
}

function Message({ title }: { title: string }) {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  return (
    <main className="mx-auto max-w-lg p-6">
      <h1 ref={heading} tabIndex={-1} className="text-2xl font-semibold outline-none">{title}</h1>
    </main>
  );
}
