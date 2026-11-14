import { useQuery } from "@tanstack/react-query";
import { Navigate } from "react-router";
import { api } from "@/shared/api/client";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { Button } from "@/shared/ui/button";
import { useLogout } from "./useMe";

/** /admin — 관리자 트리의 문(1-A). 막는 것은 서버의 GET /api/admin 이고, 여기서는 그 결과로 화면을 고른다. 셸 내용은 1-B. */
export function AdminGate() {
  const admin = useQuery({
    queryKey: ["admin"],
    queryFn: async () => (await api.GET("/api/admin")).response.status,
  });
  if (admin.isPending) return null;
  if (admin.data === 401) return <Navigate to={`/login?next=${encodeURIComponent("/admin")}`} replace />;
  if (admin.data === 403) return <Message title="권한이 없습니다" />;
  if (admin.data !== 200) return <Message title="관리자 화면을 불러오지 못했습니다" />;
  return <AdminShell />;
}

function AdminShell() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  const logout = useLogout();
  return (
    <main className="mx-auto max-w-3xl p-6">
      <h1 ref={heading} tabIndex={-1} className="mb-4 text-2xl font-semibold outline-none">관리자</h1>
      <Button type="button" variant="outline" onClick={logout}>로그아웃</Button>
    </main>
  );
}

function Message({ title }: { title: string }) {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  return (
    <main className="mx-auto max-w-lg p-6">
      <h1 ref={heading} tabIndex={-1} className="text-2xl font-semibold outline-none">{title}</h1>
    </main>
  );
}
