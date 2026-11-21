import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";

/** /admin/oplog — 작업 로그(A-601). 내용은 Task 7. */
export function OplogPage() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  return <h2 ref={heading} tabIndex={-1} className="mb-4 text-xl font-semibold outline-none">작업 로그</h2>;
}
