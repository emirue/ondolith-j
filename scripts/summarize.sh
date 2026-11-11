#!/usr/bin/env bash
# results/<시나리오>-<회차>/summary.txt 를 모아 시나리오별 한 줄(회차들의 최소–최대) 마크다운 표를 만든다.
# 사용: scripts/summarize.sh [결과 디렉터리=results]   결과가 없으면 exit 1.
# 못 읽은 값은 0 이나 빈칸이 아니라 「읽지 못함」으로 남긴다.
set -u
dir=${1:-results}
set -- "$dir"/S[0-9]*-[0-9]*/summary.txt
[ -f "$1" ] || { echo "결과 없음: $dir" >&2; exit 1; }

awk '
# "키=값" 에서 값을 꺼낸다. 값은 다음 " 키=" 앞까지(값에 공백이 있을 수 있다).
function val(s, k,   i, r) {
	i = index(s, k "="); if (!i) return ""
	r = substr(s, i + length(k) + 1)
	if (match(r, / [^ =]+=/)) r = substr(r, 1, RSTART - 1)
	return r
}
function add(id, f, v) {
	f = id SUBSEP f
	if (v ~ /^[0-9]+(\.[0-9]+)?$/) {
		if (!(f in cnt) || v + 0 < mn[f] + 0) mn[f] = v
		if (!(f in cnt) || v + 0 > mx[f] + 0) mx[f] = v
		cnt[f]++
	} else miss[f]++
}
function rng(id, f,   r) {
	f = id SUBSEP f
	if (!(f in cnt)) return "읽지 못함"
	r = (mn[f] == mx[f]) ? mn[f] : mn[f] "–" mx[f]
	return (f in miss) ? r " (" miss[f] "회 읽지 못함)" : r
}
# 요청 칸 "목록 완료/실패/Non2xx 단건 …" → 실패+Non-2xx 합계, 끊긴 회차 수
function reqs(id, s,   p, i, k, cut) {
	k = split(s, p, " ")
	for (i = 2; i <= k; i += 2) {
		if (p[i] == "끊김") { cut = 1; continue }
		split(p[i], q, "/"); bad[id] += q[2] + q[3]
	}
	if (cut) cuts[id]++
}
FNR == 1 { file++ }
{ buf[file] = buf[file] " " $0 }
END {
	for (j = 1; j <= file; j++) {
		s = buf[j]; id = val(s, "시나리오")
		if (!(id in runs)) order[++ns] = id
		runs[id]++
		v = val(s, "판정")
		if (v == "PASS") passed[id]++
		else for (k = 1; k <= split(substr(v, 7), rs, ", "); k++)  # 사유를 하나씩, 중복 없이
			if (index(why[id], rs[k]) == 0) why[id] = why[id] (why[id] ? "; " : "") rs[k]
		a = val(s, "앱유휴(MiB)"); d = val(s, "DB유휴(MiB)")
		add(id, "기동", val(s, "기동(s)")); add(id, "앱", a); add(id, "DB", d)
		add(id, "합", (a ~ /^[0-9.]+$/ && d ~ /^[0-9.]+$/) ? sprintf("%.1f", a + d) : "-")
		tier[id] = val(s, "티어(MB)")
		add(id, "앱최고", val(s, "부하중앱최고표본(MiB)")); add(id, "cg", val(s, "앱cgroup최고(MiB)"))
		add(id, "DB최고", val(s, "부하중DB최고(MiB)"))
		split(val(s, "처리량(req/s)"), t, " / "); add(id, "목록", t[1]); add(id, "단건", t[2])
		bad[id] += 0; reqs(id, val(s, "요청"))
	}
	print "| 시나리오 | 기동(s) | 앱 유휴(MiB) | DB 유휴(MiB) | 합계/티어(MiB) | 부하 중 앱 최고(표본, MiB) | 앱 cgroup 최고(MiB) | 부하 중 DB 최고(MiB) | 처리량 목록·단건(req/s) | 실패 수 | 판정 |"
	print "|---|---|---|---|---|---|---|---|---|---|---|"
	for (i = 1; i <= ns; i++) {
		id = order[i]
		fails = bad[id] "건" (cuts[id] ? ", 끊김 " cuts[id] "회" : "")
		verdict = (passed[id] + 0) "/" runs[id] " 통과" (why[id] ? " (실패: " why[id] ")" : "")
		printf "| %s | %s | %s | %s | %s / %s | %s | %s | %s | 목록 %s · 단건 %s | %s | %s |\n", id,
			rng(id, "기동"), rng(id, "앱"), rng(id, "DB"), rng(id, "합"), tier[id],
			rng(id, "앱최고"), rng(id, "cg"), rng(id, "DB최고"), rng(id, "목록"), rng(id, "단건"), fails, verdict
	}
}' "$@"
