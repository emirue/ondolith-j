#!/usr/bin/env bash
# public 저장소에 올라가면 안 되는 것(실제 메일·홈 경로·비밀 값)을 찾는다.
# 사용: check-public.sh <파일>...   위반이 있으면 "파일:줄: 사유"를 찍고 exit 1.
# 위반 내용 자체는 출력하지 않는다(비밀이 로그에 남지 않게).
# 이 파일이 자기 검사에 걸리지 않도록 정규식에 [ ] 를 끼워 쓴다.
# ponytail: 정규식 몇 개짜리 휴리스틱. 더 필요하면 gitleaks 같은 전용 도구로 교체.
set -u

EMAIL='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
EMAIL_OK='(@users\.noreply\.github\.com|:noreply@anthropic\.com)$'
bad=0

# report <파일> <사유> <grep -n 결과>
report() {
  [ -n "$3" ] || return 0
  printf '%s\n' "$3" | cut -d: -f1 | sort -un | while read -r n; do echo "$1:$n: $2"; done
  bad=1
}

# hits <파일> <정규식>
hits() { grep -I -n -E -- "$2" "$1" 2>/dev/null; }

for f in "$@"; do
  [ -f "$f" ] || continue
  report "$f" "실제 메일 주소" "$(grep -I -n -o -E "$EMAIL" "$f" 2>/dev/null | grep -v -E "$EMAIL_OK")"
  report "$f" "홈 절대 경로" "$(hits "$f" '[/]Users[/]')"
  report "$f" "AWS 액세스 키" "$(hits "$f" 'AKI[A][0-9A-Z]{16}')"
  report "$f" "개인키 헤더" "$(hits "$f" '-----BEGIN [A-Z ]*PRIVATE KEY-----')"
  report "$f" "GitHub 토큰" "$(hits "$f" 'gh[pousr]_[A-Za-z0-9]{36,}')"
  report "$f" "Spring 생성 비밀번호 로그" "$(hits "$f" 'Using generated security passwor[d]')"
done

[ "$bad" -eq 0 ]
