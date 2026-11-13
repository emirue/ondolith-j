import { useEffect, useRef } from "react";

/** 화면이 바뀌면 새 화면 제목에 포커스를 옮긴다(스크린 리더가 바뀐 화면을 읽게). */
export function useFocusOnMount<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  useEffect(() => ref.current?.focus(), []);
  return ref;
}
