import { useMutation } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { Link, useLocation } from "react-router";
import { useNotice } from "@/shared/ui/notice";

export type SaveFailure = { message: string; login?: string };

const FAILURES: Record<number, string> = { 403: "권한이 없습니다", 429: "잠시 뒤 다시 시도하세요" };

/**
 * 상태를 바꾸는 요청의 공용 훅(1-B1 스펙 6절). 성공 문구는 필수다(W6-45).
 * FR-727: isPending 은 다음 그리기에서야 보여 같은 틱의 두 번째 호출을 막지 못한다 — 동기 잠금(useRef)으로 막는다.
 * ponytail: 65초 시한이 없다 — 앞 요청이 끝나지 않으면 풀리지 않는다. 응답 없는 요청이 실제로 보이면 시한을 넣는다.
 */
export function useSave<V>(o: {
  send: (values: V) => Promise<{ response: Response; error?: unknown }>;
  success: string;
  onSuccess?: () => void;
}) {
  const notice = useNotice();
  const location = useLocation();
  const lock = useRef(false);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<SaveFailure | null>(null);
  const mutation = useMutation({ mutationFn: o.send });

  const save = (values: V) => {
    if (lock.current) return;
    lock.current = true;
    notice.clear();
    setFailure(null);
    setFieldErrors({});
    mutation
      .mutateAsync(values)
      .then(({ response, error }) => {
        if (response.ok) {
          notice.show(o.success);
          o.onSuccess?.();
        } else if (response.status === 422) {
          setFieldErrors((error as { errors?: Record<string, string> } | undefined)?.errors ?? {});
        } else if (response.status === 401) {
          // 자동으로 옮기지 않는다 — 입력이 남는다
          setFailure({ message: "로그인이 끝났습니다", login: `/login?next=${encodeURIComponent(location.pathname + location.search)}` });
        } else {
          setFailure({ message: FAILURES[response.status] ?? "저장하지 못했습니다" });
        }
      })
      .catch(() => setFailure({ message: "저장하지 못했습니다" }))
      .finally(() => {
        lock.current = false;
      });
  };

  return { save, pending: mutation.isPending, fieldErrors, failure };
}

/** 저장 실패 알림. 401 이면 지금 주소로 돌아오는 로그인 링크. */
export function SaveAlert({ failure }: { failure: SaveFailure | null }) {
  if (!failure) return null;
  return (
    <p role="alert" className="my-4 rounded-md border border-destructive p-3 text-sm text-destructive">
      {failure.message}
      {failure.login && <> <Link to={failure.login} className="underline">다시 로그인</Link></>}
    </p>
  );
}
