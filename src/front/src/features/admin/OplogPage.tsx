import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router";
import { api } from "@/shared/api/client";
import { LoadError, LoadFailure, parsePage } from "@/shared/api/load";
import type { components } from "@/shared/api/schema";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";

type OplogItem = components["schemas"]["OplogItem"];

/** 동작 이름표(1-B1 스펙 6절, 1-B2 스펙 8절, 1-C1 스펙 7절). 모르는 키는 그대로 보인다. */
const ACTIONS: Record<string, string> = {
  "auth.login": "로그인",
  "settings.update": "사이트 설정 변경",
  "user.create": "사용자 생성",
  "user.deactivate": "사용자 비활성화",
  "user.activate": "사용자 활성화",
  "user.grant_role": "역할 부여",
  "user.revoke_role": "역할 빼기",
  "role.grant_permission": "역할에 권한 부여",
  "role.revoke_permission": "역할에서 권한 빼기",
  "user.denied": "사용자 작업 거절",
  "role.denied": "역할 작업 거절",
  "page.create": "페이지 작성",
  "page.update": "페이지 수정",
  "page.delete": "페이지 삭제",
  "page.publish": "페이지 발행",
  "page.unpublish": "페이지 발행 취소",
};

function actor(item: OplogItem) {
  if (item.actor_email === "") return "시스템";
  if (item.actor_user_id == null && item.actor_email) return `(삭제된 계정) ${item.actor_email}`;
  return item.actor_email;
}

/** /admin/oplog — 작업 로그(A-601). 쪽은 주소의 ?page= (뒤로 가기가 된다). 실패는 LoadError 로 던져 LoadFailure 가 가른다. */
export function OplogPage() {
  const [params] = useSearchParams();
  const page = parsePage(params.get("page"));
  const heading = useFocusOnMount<HTMLHeadingElement>(page);
  const oplog = useQuery({
    queryKey: ["admin", "oplog", page],
    // 떠나면 지운다 — 로그아웃 뒤 같은 탭의 다른 계정에게 앞 사람의 행이 응답 전에 보이지 않게
    gcTime: 0,
    queryFn: async () => {
      const { data, response } = await api.GET("/api/admin/oplog", { params: { query: { page } } });
      if (!response.ok || !data) throw new LoadError(response.status);
      return data;
    },
  });
  const data = oplog.data;
  return (
    <>
      <h2 ref={heading} tabIndex={-1} className="mb-4 text-xl font-semibold outline-none">작업 로그</h2>
      {oplog.isError && <LoadFailure error={oplog.error} failed="작업 로그를 불러오지 못했습니다" />}
      {data && (
        <>
          <table aria-label="작업 로그" className="w-full text-left text-sm">
            <thead>
              <tr>
                {["시각", "주체", "동작", "대상", "요약", "IP"].map((h) => <th key={h} scope="col">{h}</th>)}
              </tr>
            </thead>
            <tbody>
              {(data.items ?? []).map((item) => (
                <tr key={item.id}>
                  <td>{item.created_at && new Date(item.created_at).toLocaleString()}</td>
                  <td>{actor(item)}</td>
                  <td>{ACTIONS[item.action ?? ""] ?? item.action}</td>
                  <td>{[item.target_type, item.target_id].filter(Boolean).join(" ")}</td>
                  <td>{item.summary}</td>
                  <td>{item.ip}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <nav aria-label="작업 로그 쪽" className="mt-4 flex gap-4">
            {page > 1 && (data.items?.length ? (
              <Link to={`?page=${page - 1}`} className="underline">이전</Link>
            ) : (
              // M-9: 범위 밖 쪽(주소를 손으로 고침)에서 「이전」을 누르며 거슬러 오르지 않게
              <Link to="?page=1" className="underline">첫 쪽으로</Link>
            ))}
            {page * (data.page_size ?? 0) < (data.total ?? 0) && <Link to={`?page=${page + 1}`} className="underline">다음</Link>}
          </nav>
        </>
      )}
    </>
  );
}
