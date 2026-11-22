import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";

/** /admin/roles — 역할(A-403). Task 10 이 채운다. */
export function RolesPage() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  return <h2 ref={heading} tabIndex={-1} className="mb-4 text-xl font-semibold outline-none">역할</h2>;
}
