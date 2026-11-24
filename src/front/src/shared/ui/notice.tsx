import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useLocation } from "react-router";

type Notice = { show(message: string, path?: string): void; clear(): void };

const NoticeContext = createContext<Notice | null>(null);

/**
 * 저장 성공 알림(FR-724). 빈 role="status" 칸을 미리 그려 둬야 화면 낭독기가 바뀐 글을 읽는다.
 * 알림은 보일 주소를 갖는다(기본은 지금 주소) — 생성 뒤 상세로 옮길 때 상세 주소를 넘긴다.
 * 그 주소에서만 보이고, 그 주소를 떠나면 지운다. 세션·저장소를 쓰지 않아 새로 고치면 없다(Q3).
 */
export function NoticeProvider({ children }: { children: ReactNode }) {
  const [notice, setNotice] = useState<{ message: string; path: string } | null>(null);
  const { pathname } = useLocation();
  // show 는 한 번만 만든다 — 지금 주소는 ref 로 읽는다
  const here = useRef(pathname);
  useEffect(() => {
    here.current = pathname;
    setNotice((n) => (n?.path === pathname ? n : null));
  }, [pathname]);
  const value = useMemo<Notice>(
    () => ({ show: (message, path) => setNotice({ message, path: path ?? here.current }), clear: () => setNotice(null) }),
    [],
  );
  return (
    <NoticeContext.Provider value={value}>
      <p role="status" data-testid="notice" className="mx-auto max-w-3xl px-6 text-sm">{notice?.path === pathname ? notice.message : ""}</p>
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
