import { useEffect, useRef } from "react";

/** 화면이 바뀌면 새 화면 제목에 포커스를 옮긴다(스크린 리더가 바뀐 화면을 읽게). 의존 값(쪽 번호 등)이 바뀌어도 다시 옮긴다(M-3). */
export function useFocusOnMount<T extends HTMLElement>(...deps: unknown[]) {
  const ref = useRef<T>(null);
  useEffect(() => ref.current?.focus(), deps);
  return ref;
}
