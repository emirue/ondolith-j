import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";

/** 없는 주소(1-C1 스펙 8.2). 문서의 HTTP 상태는 200 이라 검색 엔진에는 noindex 로 알린다. 요청 주소를 되비추지 않는다. */
export function NotFound() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  return (
    <main className="mx-auto max-w-lg p-6">
      <meta name="robots" content="noindex" />
      <h1 ref={heading} tabIndex={-1} className="text-2xl font-semibold outline-none">찾을 수 없습니다</h1>
      <p className="mt-2">없는 페이지이거나 주소가 바뀌었습니다. 주소를 확인하세요.</p>
    </main>
  );
}
