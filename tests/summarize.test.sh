#!/usr/bin/env bash
# scripts/summarize.sh 검증. measure.sh 가 쓰는 summary.txt 와 같은 형식의 고정 입력을 쓴다.
cd "$(dirname "$0")/.." || exit 2
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
pass=0; fail=0

# mk <시나리오> <회차> <판정> <기동> <앱유휴> <DB유휴> <티어> <앱최고> <cgroup> <DB최고> <처리량> <요청>
mk() {
  mkdir -p "$tmp/$1-$2"
  {
    echo "시나리오=$1 회차=$2 판정=$3"
    echo "기동(s)=$4 앱유휴(MiB)=$5 DB유휴(MiB)=$6 티어(MB)=$7"
    echo "부하중앱최고표본(MiB)=$8 앱cgroup최고(MiB)=$9 부하중DB최고(MiB)=${10}"
    echo "처리량(req/s)=${11} 요청=${12}"
  } > "$tmp/$1-$2/summary.txt"
}

ok="목록 5000/0/0 단건 5000/0/0"
mk S5 1 PASS 5.2 255.5 52.9 1024 323.2 327.1 56.8 "284.17 / 1101.97" "$ok"
mk S5 2 PASS 4.8 250.0 51.0 1024 320.0 325.0 55.0 "290.00 / 1200.00" "$ok"
mk S5 3 PASS 5.0 252.0 50.0 1024 321.0 326.0 54.0 "288.00 / 1150.00" "$ok"
mk S1 1 "FAIL: 부하 뒤 컨테이너 이상(false true)" 7.5 127.6 46.1 512 127.9 "읽지 못함" 51.0 "162.46 / 535.70" "$ok"
mk S1 2 "FAIL: 부하 뒤 컨테이너 이상(false true), ab 목록 실패, ab 단건 실패" 8.0 128.0 46.0 512 128.0 "읽지 못함" 50.0 "- / -" "목록 끊김 단건 끊김"
mk S1 3 PASS 8.2 127.0 47.0 512 127.5 255.0 52.0 "170.00 / 540.00" "목록 5000/2/1 단건 5000/0/0"

out=$(bash scripts/summarize.sh "$tmp" 2>&1); rc=$?

has() { # 이름 기대부분문자열
  case $out in *"$2"*) pass=$((pass+1)) ;; *) fail=$((fail+1)); echo "FAIL: $1 (없음: $2)" ;; esac
}

[ "$rc" -eq 0 ] && pass=$((pass+1)) || { fail=$((fail+1)); echo "FAIL: 종료코드 $rc"; }
has "표 머리글"                    "| 시나리오 | 기동(s) | 앱 유휴(MiB) | DB 유휴(MiB) | 합계/티어(MiB)"
has "S5 한 줄"                     "| S5 |"
has "S5 기동 범위"                  "4.8–5.2"
has "S5 앱 유휴 범위"               "250.0–255.5"
has "S5 합계/티어"                  "/ 1024"
has "S5 처리량"                     "284.17–290.00"
has "S5 전부 통과"                  "3/3 통과"
has "S1 한 줄"                     "| S1 |"
has "S1 통과 횟수"                  "1/3 통과"
has "S1 실패 사유가 보인다"          "부하 뒤 컨테이너 이상"
has "cgroup 일부 못 읽음은 표시"     "255.0 (2회 읽지 못함)"
has "S1 실패 수는 Failed+Non-2xx 합계와 끊김 횟수"  "3건, 끊김 1회"
has "S5 실패 수 0"                  "| 0건 |"
# 같은 실패 사유는 한 번만(S1 은 두 회차가 같은 사유)
s1=$(printf '%s\n' "$out" | grep '^| S1 |')
n=$(printf '%s' "$s1" | grep -o '부하 뒤 컨테이너 이상' | wc -l | tr -d ' ')
[ "$n" = 1 ] && pass=$((pass+1)) || { fail=$((fail+1)); echo "FAIL: 같은 실패 사유가 ${n}번 나온다"; }
# 시나리오 순서: S1 이 S5 보다 먼저
case $out in *"| S1 |"*"| S5 |"*) pass=$((pass+1)) ;; *) fail=$((fail+1)); echo "FAIL: S1 이 S5 보다 먼저 나와야 한다" ;; esac

# 결과가 하나도 없으면 실패(빈 표를 성공처럼 내지 않는다)
empty=$(mktemp -d); bash scripts/summarize.sh "$empty" >/dev/null 2>&1; rc=$?; rmdir "$empty"
[ "$rc" -ne 0 ] && pass=$((pass+1)) || { fail=$((fail+1)); echo "FAIL: 결과가 없는데 성공으로 끝났다"; }

echo "summarize: 통과 $pass / 실패 $fail"
[ "$fail" -eq 0 ]
