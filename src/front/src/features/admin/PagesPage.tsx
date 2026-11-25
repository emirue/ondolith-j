import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router";
import { useMe } from "@/features/auth/useMe";
import { api } from "@/shared/api/client";
import { LoadError, LoadFailure, parsePage } from "@/shared/api/load";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";

/** 상태 이름표. 모르는 값은 그대로 보인다. */
const STATUS: Record<string, string> = { draft: "초안", published: "발행" };

/**
 * /admin/pages — 페이지 목록(A-301). 쪽은 주소의 ?page= (뒤로 가기가 된다). 검색은 없다(원문에 없다).
 * 제목은 늘 상세 링크다 — 목록과 상세가 같은 page.view 다(1-C2 계획 「다르게 정한 것」 9).
 * 주소는 글자다 — 초안의 공개 주소는 404 다.
 */
export function PagesPage() {
  const [params] = useSearchParams();
  const page = parsePage(params.get("page"));
  const heading = useFocusOnMount<HTMLHeadingElement>(page);
  const can = useMe().data?.can;
  const pages = useQuery({
    queryKey: ["admin", "pages", page],
    // 떠나면 지운다 — 로그아웃 뒤 같은 탭의 다른 계정에게 앞 사람의 행이 응답 전에 보이지 않게
    gcTime: 0,
    queryFn: async () => {
      const { data, response } = await api.GET("/api/admin/pages", { params: { query: { page } } });
      if (!response.ok || !data) throw new LoadError(response.status);
      return data;
    },
  });
  const data = pages.data;
  return (
    <>
      <h2 ref={heading} tabIndex={-1} className="mb-4 text-xl font-semibold outline-none">페이지</h2>
      {can?.["page.create"] === true && <Link to="/admin/pages/new" className="underline">새 페이지</Link>}
      {pages.isError && <LoadFailure error={pages.error} failed="페이지 목록을 불러오지 못했습니다" />}
      {data && (
        <>
          <table aria-label="페이지 목록" className="my-4 w-full text-left text-sm">
            <thead>
              <tr>
                {["제목", "주소", "상태", "수정 시각"].map((h) => <th key={h} scope="col">{h}</th>)}
              </tr>
            </thead>
            <tbody>
              {(data.items ?? []).map((p) => (
                <tr key={p.id}>
                  <td><Link to={`/admin/pages/${p.id}`} className="underline">{p.title}</Link></td>
                  <td>/{p.slug}</td>
                  <td>{STATUS[p.status ?? ""] ?? p.status}</td>
                  <td>{p.updated_at && new Date(p.updated_at).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <nav aria-label="페이지 목록 쪽" className="mt-4 flex gap-4">
            {page > 1 && (data.items?.length ? (
              <Link to={`?page=${page - 1}`} className="underline">이전</Link>
            ) : (
              // M-9: 범위 밖 쪽(주소를 손으로 고침)에서 「이전」을 누르며 거슬러 오르지 않게
              <Link to="?page=1" className="underline">첫 쪽으로</Link>
            ))}
            {data.has_next && <Link to={`?page=${page + 1}`} className="underline">다음</Link>}
          </nav>
        </>
      )}
    </>
  );
}
