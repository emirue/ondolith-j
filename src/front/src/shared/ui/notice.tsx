import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { useLocation } from "react-router";

type Notice = { show(message: string): void; clear(): void };

const NoticeContext = createContext<Notice | null>(null);

/**
 * 저장 성공 알림(FR-724). 빈 role="status" 칸을 미리 그려 둬야 화면 낭독기가 바뀐 글을 읽는다.
 * 다른 화면으로 가면 지운다. 세션·저장소를 쓰지 않아 새로 고치면 없다(Q3).
 */
export function NoticeProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState("");
  const { pathname } = useLocation();
  useEffect(() => setMessage(""), [pathname]);
  const notice = useMemo<Notice>(() => ({ show: setMessage, clear: () => setMessage("") }), []);
  return (
    <NoticeContext.Provider value={notice}>
      <p role="status" data-testid="notice" className="mx-auto max-w-3xl px-6 text-sm">{message}</p>
      {children}
    </NoticeContext.Provider>
  );
}

export function useNotice(): Notice {
  const notice = useContext(NoticeContext);
  // 조용히 넘어가면 성공 알림이 사라진 것을 아무도 모른다
  if (!notice) throw new Error("useNotice 는 NoticeProvider 안에서만 쓴다");
  return notice;
}
