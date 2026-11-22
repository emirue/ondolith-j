import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";

/** /admin/users/:id — 사용자 상세(A-402). Task 10 이 채운다. */
export function UserDetailPage() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  return <h2 ref={heading} tabIndex={-1} className="mb-4 text-xl font-semibold outline-none">사용자 상세</h2>;
}
