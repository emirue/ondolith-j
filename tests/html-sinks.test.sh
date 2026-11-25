#!/usr/bin/env bash
# HTML 싱크 검사(1-C1 스펙 8.4). 고정 입력으로 검사 함수를 먼저 보고, 끝에 실제 src/front/src 를 본다. Docker 가 필요 없다.
set -u
cd "$(dirname "$0")/.."

# 시험 파일(*.test.*, test-*.ts(x))이 아닌 파일에서 세는 이름
NAMES=(dangerouslySetInnerHTML innerHTML outerHTML insertAdjacentHTML document.write srcdoc 'eval(' 'new Function' localStorage sessionStorage 'href={'
  setHTMLUnsafe parseHTMLUnsafe createContextualFragment)

# 예외 표 — 이 한 곳에만 더한다(PR 에서 보이게). 경로는 src 기준|이름|최대 횟수|사유
EXCEPTIONS=(
  "shared/ui/RichHtml.tsx|dangerouslySetInnerHTML|1|서버 정화 두 번 + DOMPurify(1-C1 스펙 8.1)"
)

RICH=shared/ui/RichHtml.tsx
RICH_FORBIDDEN=(addHook IN_PLACE RETURN_DOM SAFE_FOR_TEMPLATES CUSTOM_ELEMENT_HANDLING ADD_TAGS setConfig
  ADD_ATTR ALLOW_UNKNOWN_PROTOCOLS USE_PROFILES SAFE_FOR_XML ADD_DATA_URI_TAGS ALLOWED_NAMESPACES PARSER_MEDIA_TYPE WHOLE_DOCUMENT)
RICH_REASON="서버가 저장·내보낼 때 허용 목록으로 정화한 본문(1-C1 스펙 3절). 브라우저 파서로 한 번 더 거른다"

allowed() { # <src 기준 경로> <이름> → 허용 횟수
  local e
  for e in "${EXCEPTIONS[@]}"; do
    case $e in "$1|$2|"*) e=${e#"$1|$2|"}; echo "${e%%|*}"; return ;; esac
  done
  echo 0
}

# hits <파일> <이름> → 「파일:줄: 이름」 을 출현마다 한 줄
hits() { grep -n -o -F -- "$2" "$1" | while IFS= read -r h; do echo "$1:${h%%:*}: $2"; done; }

# check_sinks <src 폴더> — 위반을 「파일:줄: 이름」 으로 찍고, 있으면 1
check_sinks() {
  local root=$1 bad=0 f name n
  while IFS= read -r f; do
    for name in "${NAMES[@]}"; do
      n=$(grep -o -F -- "$name" "$f" | wc -l | tr -d ' ')
      if [ "$n" -gt "$(allowed "${f#"$root"/}" "$name")" ]; then hits "$f" "$name"; bad=1; fi
    done
  done < <(find "$root" -name node_modules -prune -o -type f ! -name '*.test.*' ! -name 'test-*.ts' ! -name 'test-*.tsx' -print)
  f=$root/$RICH
  if [ -f "$f" ]; then
    # 넣는 값이 같은 식 안에서 정화를 지나는지 — 넣는 줄마다 sanitize 가 있어야 하고, 그런 줄이 있어야 한다
    if ! grep -F 'dangerouslySetInnerHTML' "$f" | grep -q -F 'DOMPurify.sanitize(' ||
      grep -F 'dangerouslySetInnerHTML' "$f" | grep -q -v -F 'DOMPurify.sanitize('; then
      echo "$f:0: DOMPurify.sanitize( 없음"; bad=1
    fi
    if ! grep -q -F "$RICH_REASON" "$f"; then echo "$f:0: 사유 주석 없음"; bad=1; fi
    for name in "${RICH_FORBIDDEN[@]}"; do
      if grep -q -F -- "$name" "$f"; then hits "$f" "$name"; bad=1; fi
    done
  fi
  return $bad
}

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
pass=0; fail=0

# 깨끗한 트리를 새로 만든다: 올바른 RichHtml 과 평범한 파일
fresh() {
  rm -rf "$tmp/src"; mkdir -p "$tmp/src/shared/ui" "$tmp/src/features"
  printf '%s\n' 'import DOMPurify from "dompurify";' "// $RICH_REASON" \
    'export const R = ({ html }: { html: string }) => <div dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(html, C) }} />;' \
    > "$tmp/src/shared/ui/RichHtml.tsx"
  printf '%s\n' 'export const A = () => <a>x</a>;' > "$tmp/src/features/A.tsx"
}

# expect <이름> <기대 종료코드> [출력에 있어야 할 글자]
expect() {
  local out got
  out=$(check_sinks "$tmp/src"); got=$?
  if [ "$got" -ne "$2" ]; then fail=$((fail+1)); echo "FAIL: $1 (기대 $2, 실제 $got) $out"; return; fi
  case $out in *"${3:-}"*) pass=$((pass+1)) ;; *) fail=$((fail+1)); echo "FAIL: $1 (출력에 「$3」 없음: $out)" ;; esac
}

fresh; expect "깨끗한 트리는 통과" 0

for name in "${NAMES[@]}"; do
  fresh; printf 'ok\nconst x = %s;\n' "$name" > "$tmp/src/features/B.ts"
  expect "다른 파일의 $name 은 실패" 1 "$tmp/src/features/B.ts:2: $name"
done

fresh; printf '%s\n' 'export const B = () => <div dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(h) }} />;' > "$tmp/src/features/B.tsx"
expect "RichHtml 밖의 dangerouslySetInnerHTML 은 sanitize 가 있어도 실패" 1 "$tmp/src/features/B.tsx:1: dangerouslySetInnerHTML"

fresh; printf '%s\n' 'export const S = () => <p dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(h) }} />;' >> "$tmp/src/shared/ui/RichHtml.tsx"
expect "RichHtml 의 두 번째 사용은 실패" 1 "RichHtml.tsx:4: dangerouslySetInnerHTML"

fresh; sed -i.bak 's/DOMPurify\.sanitize(html, C)/html/' "$tmp/src/shared/ui/RichHtml.tsx"; rm "$tmp/src/shared/ui/RichHtml.tsx.bak"
expect "RichHtml 에 DOMPurify.sanitize( 가 없으면 실패" 1 "RichHtml.tsx:0: DOMPurify.sanitize( 없음"

fresh; printf '%s\n' 'const clean = DOMPurify.sanitize(h);' 'export const T = ({ h }: { h: string }) => <p dangerouslySetInnerHTML={{ __html: h }} />;' > "$tmp/src/shared/ui/RichHtml.tsx"
printf '// %s\n' "$RICH_REASON" >> "$tmp/src/shared/ui/RichHtml.tsx"
expect "넣는 줄에 sanitize 가 없으면 파일에 있어도 실패" 1 "RichHtml.tsx:0: DOMPurify.sanitize( 없음"

fresh; sed -i.bak '2d' "$tmp/src/shared/ui/RichHtml.tsx"; rm "$tmp/src/shared/ui/RichHtml.tsx.bak"
expect "RichHtml 에 사유 주석이 없으면 실패" 1 "RichHtml.tsx:0: 사유 주석 없음"

for name in "${RICH_FORBIDDEN[@]}"; do
  fresh; printf '%s\n' "DOMPurify.$name;" >> "$tmp/src/shared/ui/RichHtml.tsx"
  expect "RichHtml 의 $name 은 실패" 1 "RichHtml.tsx:4: $name"
done

# 지금 쓰는 ADD_URI_SAFE_ATTR 는 ADD_ATTR 과 글자가 달라 걸리지 않는다
fresh; printf '%s\n' 'const C = { ADD_URI_SAFE_ATTR: ["start"] };' >> "$tmp/src/shared/ui/RichHtml.tsx"
expect "RichHtml 의 ADD_URI_SAFE_ATTR 는 통과" 0

fresh
printf '%s\n' 'expect(el.innerHTML).toBe(x); localStorage; href={a}' > "$tmp/src/features/A.test.tsx"
printf '%s\n' 'el.innerHTML; eval(x)' > "$tmp/src/features/B.test.ts"
printf '%s\n' 'el.innerHTML' > "$tmp/src/test-vectors.ts"
printf '%s\n' 'el.outerHTML' > "$tmp/src/test-api.tsx"
mkdir -p "$tmp/src/node_modules/x"; printf '%s\n' 'el.innerHTML' > "$tmp/src/node_modules/x/index.js"
expect "시험 파일과 node_modules 는 대상 밖" 0

fresh; printf '%s\n' 'const a = 1;' 'el.innerHTML = s; el.innerHTML = t;' > "$tmp/src/features/C.ts"
expect "같은 줄의 두 번째 출현도 센다" 1 "C.ts:2: innerHTML
$tmp/src/features/C.ts:2: innerHTML"

# 실제 트리
if out=$(check_sinks src/front/src); then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL: src/front/src 에 위반이 있다"; echo "$out"; fi

echo "html-sinks: 통과 $pass / 실패 $fail"
[ "$fail" -eq 0 ]
