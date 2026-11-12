#!/usr/bin/env bash
# scripts/check-public.sh 검증. 고정 입력만 쓰므로 Docker 가 필요 없다.
# 이 파일 자체가 검사에 걸리지 않도록 위반 문자열은 조각을 이어 붙여 만든다.
set -u
cd "$(dirname "$0")/.."
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
pass=0; fail=0; LAST_OUT=""

# expect <이름> <기대 종료코드> <파일 내용>
expect() {
  local got
  printf '%s\n' "$3" > "$tmp/f.txt"
  LAST_OUT=$(bash scripts/check-public.sh "$tmp/f.txt" 2>&1); got=$?
  if [ "$got" -eq "$2" ]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL: $1 (기대 $2, 실제 $got)"; fi
}

expect "깨끗한 파일은 통과" 0 "hello"
expect "실제 메일 주소는 차단" 1 "contact: someone@""example.com"
expect "GitHub noreply 는 허용" 0 "id+name@users.noreply.github.com"
expect "Anthropic noreply 는 허용" 0 "Co-Authored-By: x <noreply@anthropic.com>"
expect "예약 TLD .test 는 허용(시험용 가짜)" 0 'email: "admin@ondolith.test"'
expect "예약 TLD .invalid 는 허용" 0 "x@nowhere.invalid"
expect ".test 로 끝나지 않으면 차단" 1 "someone@ondolith.test""er.com"
expect "홈 절대 경로는 차단" 1 "/Use""rs/someone/project"
expect "AWS 키는 차단" 1 "AKI""AABCDEFGHIJKLMNOP"
expect "개인키 헤더는 차단" 1 "-----BEGIN RSA ""PRIVATE KEY-----"
expect "GitHub 토큰은 차단" 1 "gh""p_$(printf 'a%.0s' {1..36})"
expect "Spring 생성 비밀번호 로그는 차단" 1 "Using generated security ""password: 1234"
expect "가려 둔(제거됨) 생성 비밀번호 줄은 통과" 0 "Using generated security ""password: [제거됨]"

expect "위반 보고에 파일과 줄이 나온다" 1 "ok
/Use""rs/someone"
case $LAST_OUT in
  *"$tmp/f.txt:2:"*) pass=$((pass+1)) ;;
  *) fail=$((fail+1)); echo "FAIL: 보고 형식 (출력: $LAST_OUT)" ;;
esac
case $LAST_OUT in
  *someone*) fail=$((fail+1)); echo "FAIL: 위반 내용 자체를 출력에 찍으면 안 된다" ;;
  *) pass=$((pass+1)) ;;
esac

# 검사 도구 자신과 하네스 문서가 검사에 걸리면 안 된다
self=$(find scripts tests .githooks .claude CLAUDE.md -type f 2>/dev/null)
if [ -n "$self" ] && bash scripts/check-public.sh $self >/dev/null 2>&1; then
  pass=$((pass+1))
else
  fail=$((fail+1)); echo "FAIL: 하네스 파일이 자기 검사에 걸린다(또는 파일 없음)"
fi

echo "check-public: 통과 $pass / 실패 $fail"
[ "$fail" -eq 0 ]
