import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router";
import { useMe } from "@/features/auth/useMe";
import { api } from "@/shared/api/client";
import { LoadError, LoadFailure, parsePage } from "@/shared/api/load";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { Label } from "@/shared/ui/label";

/** 쪽 링크. 검색어를 잃지 않는다(Review Focus 2). */
const pageLink = (page: number, q: string) => `?${new URLSearchParams({ page: String(page), ...(q && { q }) })}`;

/** /admin/users — 사용자 목록(A-401). 쪽·검색어는 주소의 ?page=·?q= (뒤로 가기가 된다). */
export function UsersPage() {
  const [params, setParams] = useSearchParams();
  const page = parsePage(params.get("page"));
  const q = params.get("q") ?? "";
  // 검색어도 — 제출하면 칸이 key={q} 로 다시 그려져 초점이 body 로 떨어진다
  const heading = useFocusOnMount<HTMLHeadingElement>(page, q);
  const can = useMe().data?.can;
  const users = useQuery({
    queryKey: ["admin", "users", page, q],
    // 떠나면 지운다 — 로그아웃 뒤 같은 탭의 다른 계정에게 앞 사람의 행이 응답 전에 보이지 않게
    gcTime: 0,
    queryFn: async () => {
      const { data, response } = await api.GET("/api/admin/users", { params: { query: { page, q: q || undefined } } });
      if (!response.ok || !data) throw new LoadError(response.status);
      return data;
    },
  });
  const data = users.data;
  return (
    <>
      <h2 ref={heading} tabIndex={-1} className="mb-4 text-xl font-semibold outline-none">사용자</h2>
      {can?.["user.create"] === true && <Link to="/admin/users/new" className="underline">새 사용자</Link>}
      {/* 검색은 주소만 바꾼다 — 보내는 요청이 없어 잠그지 않는다 */}
      <form
        role="search"
        className="my-4 flex items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          const v = String(new FormData(e.currentTarget).get("q") ?? "");
          setParams(v ? { q: v } : {});
        }}
      >
        <div className="space-y-1">
          <Label htmlFor="q">검색</Label>
          <Input id="q" name="q" type="search" key={q} defaultValue={q} />
        </div>
        <Button type="submit" variant="outline">검색</Button>
      </form>
      {users.isError && <LoadFailure error={users.error} failed="사용자 목록을 불러오지 못했습니다" />}
      {data && (
        <>
          <table aria-label="사용자 목록" className="w-full text-left text-sm">
            <thead>
              <tr>
                {["이메일", "표시 이름", "상태", "역할", "만든 날"].map((h) => <th key={h} scope="col">{h}</th>)}
              </tr>
            </thead>
            <tbody>
              {(data.items ?? []).map((u) => (
                <tr key={u.id}>
                  <td>{can?.["user.update"] === true ? <Link to={`/admin/users/${u.id}`} className="underline">{u.email}</Link> : u.email}</td>
                  <td>{u.display_name}</td>
                  <td>{u.is_active ? "활성" : "비활성"}</td>
                  <td>{(u.roles ?? []).join(", ")}</td>
                  <td>{u.created_at && new Date(u.created_at).toLocaleDateString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <nav aria-label="사용자 목록 쪽" className="mt-4 flex gap-4">
            {page > 1 && (data.items?.length ? (
              <Link to={pageLink(page - 1, q)} className="underline">이전</Link>
            ) : (
              // M-9: 범위 밖 쪽(주소를 손으로 고침)에서 「이전」을 누르며 거슬러 오르지 않게
              <Link to={pageLink(1, q)} className="underline">첫 쪽으로</Link>
            ))}
            {data.has_next && <Link to={pageLink(page + 1, q)} className="underline">다음</Link>}
          </nav>
        </>
      )}
    </>
  );
}
