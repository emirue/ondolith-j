import { useQuery } from "@tanstack/react-query";
import { api } from "@/shared/api/client";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";

/** /admin/system — 시스템 정보(A-602). server_version 이 오면 DB 에 연결된 것이다. 403 은 null 로 받는다. */
export function SystemPage() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  const system = useQuery({
    queryKey: ["admin", "system"],
    // 떠나면 지운다 — 로그아웃 뒤 같은 탭의 다른 계정에게 앞 값이 응답 전에 보이지 않게
    gcTime: 0,
    queryFn: async () => {
      const { data, response } = await api.GET("/api/admin/system");
      if (response.status === 403) return null;
      if (!response.ok || !data) throw new Error(`system ${response.status}`);
      return data;
    },
  });
  const data = system.data;
  return (
    <>
      <h2 ref={heading} tabIndex={-1} className="mb-4 text-xl font-semibold outline-none">시스템 정보</h2>
      {system.isError && <p role="alert">시스템 정보를 불러오지 못했습니다</p>}
      {data === null && <p>권한이 없습니다</p>}
      {data && (
        <>
          <dl className="mb-4 grid grid-cols-[auto_1fr] gap-x-4">
            <dt>버전</dt>
            <dd>{data.version}</dd>
            <dt>DB 서버 판</dt>
            <dd>{data.database?.server_version}</dd>
            <dt>대기 중인 마이그레이션</dt>
            <dd>{data.pending_count}</dd>
          </dl>
          <table aria-label="마이그레이션" className="w-full text-left text-sm">
            <thead>
              <tr>
                {["버전", "설명", "적용 시각", "성공"].map((h) => <th key={h} scope="col">{h}</th>)}
              </tr>
            </thead>
            <tbody>
              {(data.migrations ?? []).map((m) => (
                <tr key={m.version}>
                  <td>{m.version}</td>
                  <td>{m.description}</td>
                  <td>{m.installed_on && new Date(m.installed_on).toLocaleString()}</td>
                  <td>{m.success ? "성공" : "실패"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </>
  );
}
