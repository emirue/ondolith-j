#!/usr/bin/env bash
# .githooks/pre-commit 검증: 임시 저장소에서 실제로 커밋해 본다.
set -u
root=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
pass=0; fail=0

# commit_with <이메일> <파일 내용>  → 커밋 종료코드
commit_with() {
  rm -rf "$tmp/repo"; git init -q "$tmp/repo"
  git -C "$tmp/repo" config user.name tester
  git -C "$tmp/repo" config user.email "$1"
  git -C "$tmp/repo" config core.hooksPath "$root/.githooks"
  git -C "$tmp/repo" config commit.gpgsign false
  printf '%s\n' "$2" > "$tmp/repo/a.txt"
  git -C "$tmp/repo" add a.txt
  git -C "$tmp/repo" commit -q -m t >/dev/null 2>&1
}

check() { # 이름 기대코드 실제코드
  if [ "$3" -eq "$2" ]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL: $1 (기대 $2, 실제 $3)"; fi
}

commit_with "tester@users.noreply.github.com" "hello"; check "noreply + 깨끗한 파일은 커밋됨" 0 $?
commit_with "tester@""example.com" "hello";              check "실제 메일이면 커밋 차단" 1 $?
commit_with "tester@users.noreply.github.com" "/Use""rs/someone"; check "스테이지된 파일에 홈 경로가 있으면 차단" 1 $?
commit_with "" "hello";                                  check "메일이 없으면 커밋 차단" 1 $?

echo "pre-commit: 통과 $pass / 실패 $fail"
[ "$fail" -eq 0 ]
