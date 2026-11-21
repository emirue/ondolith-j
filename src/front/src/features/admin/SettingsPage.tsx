import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";

/** /admin/settings — 사이트 설정(A-201). 내용은 Task 6. */
export function SettingsPage() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  return <h2 ref={heading} tabIndex={-1} className="mb-4 text-xl font-semibold outline-none">사이트 설정</h2>;
}
