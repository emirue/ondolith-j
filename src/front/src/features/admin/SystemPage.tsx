import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";

/** /admin/system — 시스템 정보(A-602). 내용은 Task 7. */
export function SystemPage() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  return <h2 ref={heading} tabIndex={-1} className="mb-4 text-xl font-semibold outline-none">시스템 정보</h2>;
}
