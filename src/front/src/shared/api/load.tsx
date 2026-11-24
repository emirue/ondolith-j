import { useLocation } from "react-router";
import { SaveAlert } from "@/shared/api/useSave";

/** 조회 화면의 queryFn 이 실패 응답에 던진다. 상태 코드로 LoadFailure 가 안내를 고른다. */
export class LoadError extends Error {
  constructor(readonly status: number) {
    super(`load ${status}`);
  }
}

/** 조회 실패 안내(1-B2 스펙 9절): 401 은 로그인 링크(SaveAlert 와 같은 모양), 403 은 권한 없음, 그 밖은 failed. */
export function LoadFailure({ error, failed }: { error: unknown; failed: string }) {
  const location = useLocation();
  const status = error instanceof LoadError ? error.status : 0;
  if (status === 401) {
    return <SaveAlert failure={{ message: "로그인이 끝났습니다", login: `/login?next=${encodeURIComponent(location.pathname + location.search)}` }} />;
  }
  if (status === 403) return <p>권한이 없습니다</p>;
  return <p role="alert">{failed}</p>;
}

/** 주소의 ?page=. 양의 정수이고 2147483647(서버 int) 이하면 그 수, 아니면 1쪽. */
export const parsePage = (v: string | null) => (v && /^[1-9]\d*$/.test(v) && Number(v) <= 2147483647 ? Number(v) : 1);
