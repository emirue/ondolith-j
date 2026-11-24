import { useEffect, useRef, useState } from "react";
import { Link, useLocation } from "react-router";
import { api } from "@/shared/api/client";
import { useNotice } from "@/shared/ui/notice";

export type SaveFailure = { message: string; login?: string };

/** 재인증 403 의 Problem type. 서버의 다른 403(권한 없음·CSRF·R 규칙)에는 type 이 없다. */
export const REAUTH_TYPE = "urn:ondolithj:problem:reauth-required";

export type Save<V> = {
  save(values: V): void;
  pending: boolean;
  fieldErrors: Record<string, string>;
  failure: SaveFailure | null;
  done: boolean;
  reauthNeeded: boolean;
  reauthError: string | null;
  reauth(password: string): void;
  cancel(): void;
};

type Problem = { type?: unknown; detail?: unknown; errors?: Record<string, string> } | null | undefined;

const FAILURES: Record<number, string> = { 403: "권한이 없습니다", 429: "잠시 뒤 다시 시도하세요" };
/** 서버 detail 은 코드 상수 문구다(요청 값이 섞이지 않는다). React 가 이스케이프해 그린다. */
const DETAILED = [403, 404, 409];

const isReauth = (status: number, problem: Problem) => status === 403 && problem?.type === REAUTH_TYPE;

/**
 * 상태를 바꾸는 요청의 공용 훅(1-B1 스펙 6절, 1-B2 스펙 9절). 성공 문구는 필수다(W6-45).
 * FR-727: 같은 틱의 두 번째 호출은 다시 그리기 전이라 상태로 막지 못한다 — 동기 잠금(useRef)으로 막는다.
 * useMutation 을 쓰지 않는다 — 변경 캐시가 보낸 값(variables)을 남겨, 비밀번호 칸이 있는 폼과 재인증 비밀번호가 캐시에 남는다.
 * 확인 단추(ConfirmButton) 하나에 useSave 하나를 쓴다 — 재인증 상태·실패를 단추마다 보이므로, 나눠 쓰면 모든 단추의 대화상자·알림이 함께 뜬다.
 * ponytail: 65초 시한이 없다 — 앞 요청이 끝나지 않으면 풀리지 않는다. 응답 없는 요청이 실제로 보이면 시한을 넣는다.
 */
export function useSave<V, R = unknown>(o: {
  send: (values: V) => Promise<{ response: Response; data?: R; error?: unknown }>;
  success: string;
  onSuccess?: (data: R | undefined) => void;
  noticePath?: (data: R | undefined) => string;
  focus?: (field: string) => void;
}): Save<V> {
  const notice = useNotice();
  const location = useLocation();
  const lock = useRef(false);
  // 재인증 뒤 다시 보낼 값. 메모리에만 둔다
  const kept = useRef<{ values: V } | null>(null);
  const [pending, setPending] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<SaveFailure | null>(null);
  const [done, setDone] = useState(false);
  const [reauthNeeded, setReauthNeeded] = useState(false);
  const [reauthError, setReauthError] = useState<string | null>(null);

  // M-1: 그린 뒤에 옮긴다 — 칸에 aria-describedby 가 붙은 다음이라야 오류를 읽는다
  useEffect(() => {
    const first = Object.keys(fieldErrors)[0];
    if (first) o.focus?.(first);
  }, [fieldErrors]);

  const fail = (status: number, problem: Problem) => {
    kept.current = null;
    setReauthNeeded(false);
    if (status === 401) {
      // 자동으로 옮기지 않는다 — 입력이 남는다
      setFailure({ message: "로그인이 끝났습니다", login: `/login?next=${encodeURIComponent(location.pathname + location.search)}` });
    } else {
      const detail = DETAILED.includes(status) && typeof problem?.detail === "string" ? problem.detail : null;
      setFailure({ message: detail || FAILURES[status] || "저장하지 못했습니다" });
    }
  };

  const send = (values: V) =>
    // send 가 동기로 던져도 거절로 받는다 — 아니면 잠금이 풀리지 않는다
    Promise.resolve()
      .then(() => o.send(values))
      .then(({ response, data, error }) => {
        const problem = error as Problem;
        if (isReauth(response.status, problem)) {
          kept.current = { values };
          setReauthNeeded(true);
          return;
        }
        if (response.ok) {
          kept.current = null;
          setReauthNeeded(false);
          setDone(true);
          notice.show(o.success, o.noticePath?.(data));
          o.onSuccess?.(data);
        } else if (response.status === 422) {
          kept.current = null;
          setReauthNeeded(false);
          // M-2: 422 는 role="alert" 를 띄우지 않는다 — 초점이 간 칸의 aria-describedby 가 읽는다
          setFieldErrors(problem?.errors ?? {});
        } else {
          fail(response.status, problem);
        }
      })
      .catch(() => fail(0, null));

  const run = (job: () => Promise<void>) => {
    if (lock.current) return;
    lock.current = true;
    setPending(true);
    job().finally(() => {
      lock.current = false;
      setPending(false);
    });
  };

  const save = (values: V) =>
    run(() => {
      notice.clear();
      setFailure(null);
      setFieldErrors({});
      setDone(false);
      setReauthError(null);
      return send(values);
    });

  // 비밀번호는 인자로만 지나간다 — 상태·ref·캐시에 두지 않는다
  const reauth = (password: string) => {
    const k = kept.current;
    if (!k) return;
    run(() => {
      setReauthError(null);
      return api
        .POST("/api/admin/reauth", { body: { password } })
        .then(({ response, error }) => {
          const problem = error as Problem;
          // 사용자가 누를 때만 여기 온다 — 다시 보낸 요청이 또 재인증 403 이면 다시 묻고 멈춘다
          if (response.ok) return send(k.values);
          if (isReauth(response.status, problem)) setReauthError("비밀번호가 맞지 않습니다");
          else if (response.status === 429) setReauthError(FAILURES[429]);
          else fail(response.status, problem);
        })
        .catch(() => fail(0, null));
    });
  };

  const cancel = () => {
    kept.current = null;
    setReauthNeeded(false);
    setReauthError(null);
  };

  return { save, pending, fieldErrors, failure, done, reauthNeeded, reauthError, reauth, cancel };
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
