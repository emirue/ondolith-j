#!/usr/bin/env bash
# 시나리오 하나를 한 번 측정하고 판정한다.
#   ./measure.sh <S1..S7> [회차=1]   측정. 판정 실패 exit 1, 하네스 실패 exit 2
#   ./measure.sh env                 측정 머신의 아키텍처·java -version 기록
#   ./measure.sh clean               bench-* 컨테이너·네트워크·gradle 볼륨 삭제
# 위쪽 함수는 tests/measure.test.sh 가 source 해서 검증한다(Docker 불필요).

AB_N=5000  # ab 요청 수. 판정 기준이기도 하다

# docker stats 의 "사용량 / 상한" 칸에서 사용량을 MiB 숫자로. 0B·--·비숫자는 "재지 못함"(실패).
parse_mib() {
	local out
	out=$(printf '%s\n' "${1%% /*}" | awk '
		/^[0-9]+(\.[0-9]+)?(KiB|MiB|GiB)$/ {
			v = $0; sub(/[A-Za-z]+$/, "", v); u = substr($0, length(v) + 1)
			printf "%.1f\n", v * (u == "KiB" ? 1 / 1024 : u == "GiB" ? 1024 : 1); ok = 1
		}
		END { exit !ok }')
	[ -n "$out" ] && printf '%s\n' "$out"
}

# stdin 의 MemUsage 줄들 중 최댓값(MiB). 못 읽은 줄은 건너뛰고, 하나도 못 읽으면 실패.
max_mib() {
	local line v max=""
	while IFS= read -r line; do
		v=$(parse_mib "$line") || continue
		max=$(awk -v a="${max:-0}" -v b="$v" 'BEGIN { print (b > a) ? b : a }')
	done
	[ -n "$max" ] && printf '%s\n' "$max"
}

# ab 출력에서 "키: 값" 의 숫자를 읽는다. 그 줄이 없으면 실패.
ab_get() {
	printf '%s\n' "$1" | awk -F: -v k="$2" '$1 == k { gsub(/[^0-9.]/, "", $2); print $2; f = 1 } END { exit !f }'
}

ab_rps() { ab_get "$1" "Requests per second"; }

# 통과 조건: 완료 AB_N 건, 실패 0, Non-2xx 0. Non-2xx 줄은 없으면 0 으로 본다.
# 요약 줄이 없으면(앱이 부하 중에 죽어 ab 가 끊김) 0 건 실패가 아니라 실패다.
ab_ok() {
	local c f n
	c=$(ab_get "$1" "Complete requests") || { echo "ab 요약 줄 없음" >&2; return 1; }
	f=$(ab_get "$1" "Failed requests") || { echo "ab 요약 줄 없음" >&2; return 1; }
	n=$(ab_get "$1" "Non-2xx responses") || n=0
	[ "$c" = "$AB_N" ] && [ "$f" = 0 ] && [ "$n" = 0 ]
}

# 완료/실패/Non-2xx 한 줄 요약. 요약 줄이 없으면 "끊김".
ab_brief() {
	local c
	c=$(ab_get "$1" "Complete requests") || { echo "끊김"; return; }
	echo "$c/$(ab_get "$1" "Failed requests" || echo -)/$(ab_get "$1" "Non-2xx responses" || echo 0)"
}

# "Running OOMKilled" 가 "true false" 여야 한다.
container_ok() { [ "$1" = "true false" ]; }

# OutOfMemoryError 건수가 숫자 0 이어야 한다.
no_oom() { case $1 in '' | *[!0-9]*) return 1 ;; esac; [ "$1" -eq 0 ]; }

startup_seconds() {
	local s
	s=$(printf '%s\n' "$1" | sed -n -E 's/.*Started [A-Za-z0-9_]+ in ([0-9.]+) seconds.*/\1/p' | head -1)
	[ -n "$s" ] && printf '%s\n' "$s"
}

bytes_to_mib() {
	case $1 in '' | *[!0-9]*) return 1 ;; esac
	awk -v b="$1" 'BEGIN { printf "%.1f\n", b / 1048576 }'
}

# 요청서 5절 표: "DB상한 shared_buffers 앱상한 JVM옵션 티어MB"
scenario() {
	case $1 in
		S1) echo "384m 128MB 128m J1 512" ;;
		S2) echo "384m 128MB 128m J2 512" ;;
		S3) echo "256m 64MB 256m J1 512" ;;
		S4) echo "256m 64MB 256m J2 512" ;;
		S5) echo "512m 256MB 512m J1 1024" ;;
		S6) echo "384m 128MB 180m J1 512" ;;
		S7) echo "384m 128MB 180m J2 512" ;;
		*) return 1 ;;
	esac
}

# J1 = 옵션 없음, J2 = 스레드 스택 축소 + C2 끄기(힙 밖 메모리 절감). EXTRA_JOPTS 는 뒤에 붙인다.
jvm_opts() {
	local o=""
	[ "$1" = J2 ] && o="-Xss512k -XX:TieredStopAtLevel=1"
	echo "${o}${o:+${EXTRA_JOPTS:+ }}${EXTRA_JOPTS:-}"
}

# 잴 jar. 기본은 측정 앱, JAR 로 바꾼다(코어 스택 사전 측정).
app_jar() { echo "${JAR:-$1/bench/build/libs/bench-0.0.1-SNAPSHOT.jar}"; }

# 결과 폴더. LABEL 이 있으면 results/<LABEL>/ 아래(기존 결과를 덮지 않는다). LABEL 은 영숫자·-·_ 만.
out_dir() {
	case ${LABEL:-} in
		'') echo "$1/results/$2-$3" ;;
		*[!A-Za-z0-9_-]*) return 1 ;;
		*) echo "$1/results/$LABEL/$2-$3" ;;
	esac
}

# 대상(TARGET=bench|core)별 "<기동 확인> <부하 목록 칸> <부하 단건 칸>" 경로.
# 코어는 설치 모드도 /api/health 가 200 이라, 운영 모드에만 있는 /api/site 로 떴는지 본다.
target_paths() {
	case ${TARGET:-bench} in
		bench) echo "/api/posts/1 /api/posts?page=3 /api/posts/500" ;;
		core) echo "/api/site /api/site /api/health" ;;
		*) return 1 ;;
	esac
}

# core_config <DB 호스트> <seal.key> <installed.at>: 코어를 운영 모드로 띄울 설정 파일.
# 계정 m/m 은 일회용 측정 DB 용이다. 파일은 results/ 밖 임시 폴더에 두고 끝나면 지운다.
core_config() {
	printf '%s\n' "db.url=jdbc:postgresql://$1:5432/m" db.user=m db.password=m site.url=http://measure.ondolith.test \
		cookies.secure=false "seal.key=$2" upload.dir=/tmp/uploads backup.dir=/tmp/backups "installed.at=$3"
}

# 7절 판정: <유휴 상태> <부하 후 상태> <OOM 건수> <ab 목록> <ab 단건>. 하나라도 깨지면 FAIL.
judge() {
	local r=""
	container_ok "$1" || r="$r, 유휴 때 컨테이너 이상($1)"
	container_ok "$2" || r="$r, 부하 뒤 컨테이너 이상($2)"
	no_oom "$3" || r="$r, OutOfMemoryError($3건)"
	ab_ok "$4" 2>/dev/null || r="$r, ab 목록 실패"
	ab_ok "$5" 2>/dev/null || r="$r, ab 단건 실패"
	if [ -z "$r" ]; then echo PASS; else echo "FAIL:${r#,}"; return 1; fi
}

# 공개 저장소에 남는 로그에서 Spring Security 가 찍는 생성 비밀번호를 지운다.
redact() { sed -E 's/(Using generated security passwor[d]: ).*/\1[제거됨]/'; }

# ---------------------------------------------------------------- 실행부 (Docker 필요)

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
NET=bench-net DB=bench-db APP=bench-app
HTTPD=httpd:2.4-alpine  # ab·wget 용

hf() { echo "하네스 실패: $*" >&2; exit 2; }
cleanup() {
	docker rm -f "$APP" "$DB" >/dev/null 2>&1; docker network rm "$NET" >/dev/null 2>&1
	[ -n "${CORE_DIR:-}" ] && rm -rf "$CORE_DIR"
	return 0
}
log() { echo "$*" | tee -a "$OUT/run.log"; }
state() { docker inspect -f '{{.State.Running}} {{.State.OOMKilled}}' "$1" 2>/dev/null; }

# wait_for <초> <명령...>: 1초 간격으로 성공할 때까지.
wait_for() { local n=$1 i=0; shift; while [ "$i" -lt "$n" ]; do "$@" >/dev/null 2>&1 && return 0; i=$((i + 1)); sleep 1; done; return 1; }

# run_ab <이름> <URL>: ab 를 돌리며 앱·DB 메모리 표본을 뜬다.
run_ab() {
	docker run --rm --network "$NET" "$HTTPD" ab -n "$AB_N" -c 20 "$2" >"$OUT/ab-$1.txt" 2>&1 &
	local pid=$!
	: >"$OUT/samples-$1.txt"
	while kill -0 "$pid" 2>/dev/null; do
		docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' "$APP" "$DB" >>"$OUT/samples-$1.txt" 2>/dev/null || sleep 1
	done
	wait "$pid"
}

# peak_of <컨테이너> <표본 파일>...: 표본 중 최댓값(MiB), 없으면 "재지 못함"
peak_of() { local n=$1; shift; cat "$@" | grep "^$n " | sed "s/^$n //" | max_mib || echo "재지 못함"; }

measure() {
	local id=$1 run=${2:-1} cfg DB_MEM SB APP_MEM J TIER JOPTS jar paths BOOT_PATH LOAD_LIST LOAD_ONE
	paths=$(target_paths) || hf "TARGET 은 bench 또는 core"
	read -r BOOT_PATH LOAD_LIST LOAD_ONE <<<"$paths"
	cfg=$(scenario "$id") || hf "알 수 없는 시나리오: $id (S1~S7)"
	read -r DB_MEM SB APP_MEM J TIER <<<"$cfg"
	JOPTS=$(jvm_opts "$J")
	jar=$(app_jar "$ROOT")
	[ -f "$jar" ] || hf "jar 가 없다: $jar (측정 앱은 bench 의 bootJar, CLAUDE.md 참고)"
	OUT=$(out_dir "$ROOT" "$id" "$run") || hf "LABEL 은 영숫자·-·_ 만"
	rm -rf "$OUT"; mkdir -p "$OUT"
	cleanup
	log "== $id 회차 $run${LABEL:+ [$LABEL]} 대상 ${TARGET:-bench}: DB ${DB_MEM}/sb=${SB}, 앱 ${APP_MEM}, JVM ${J}${JOPTS:+ ($JOPTS)}, 티어 ${TIER}MB, jar $(basename "$jar")"

	# 1. 네트워크와 DB. 준비는 TCP(-h 127.0.0.1)로 본다: 초기화 중 임시 서버는 소켓만 연다.
	docker network create "$NET" >/dev/null || hf "네트워크 생성"
	docker run -d --name "$DB" --network "$NET" --cpus=1 --memory="$DB_MEM" \
		-e POSTGRES_USER=m -e POSTGRES_PASSWORD=m -e POSTGRES_DB=m \
		postgres:18-alpine -c shared_buffers="$SB" >/dev/null || hf "DB 컨테이너 시작"
	wait_for 60 docker exec "$DB" pg_isready -h 127.0.0.1 -U m || hf "DB 가 60초 안에 준비되지 않음"
	# 2·3. 스키마·데이터와 앱. JOPTS 는 단어 분리가 필요해 따옴표를 쓰지 않는다.
	if [ "${TARGET:-bench}" = core ]; then
		# 코어는 빈 DB 에 스스로 마이그레이션한다. DB 접속값은 설정 파일에서만 온다(환경변수를 주지 않는다).
		CORE_DIR=$(mktemp -d) || hf "임시 폴더"
		core_config "$DB" "$(openssl rand -base64 32)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$CORE_DIR/ondolith-j.properties" \
			|| hf "코어 설정 파일"
		# shellcheck disable=SC2086
		docker run -d --name "$APP" --network "$NET" --cpus=1 --memory="$APP_MEM" \
			-v "$jar":/app.jar:ro -v "$CORE_DIR":/conf:ro \
			eclipse-temurin:25-jre java $JOPTS -jar /app.jar --config=/conf/ondolith-j.properties >/dev/null \
			|| hf "앱 컨테이너 시작"
	else
		docker exec -i "$DB" psql -h 127.0.0.1 -U m -d m -v ON_ERROR_STOP=1 -q <"$ROOT/bench/seed.sql" >/dev/null || hf "seed.sql 적용"
		# shellcheck disable=SC2086
		docker run -d --name "$APP" --network "$NET" --cpus=1 --memory="$APP_MEM" \
			-e SPRING_DATASOURCE_URL="jdbc:postgresql://$DB:5432/m" \
			-e SPRING_DATASOURCE_USERNAME=m -e SPRING_DATASOURCE_PASSWORD=m \
			-v "$jar":/app.jar:ro \
			eclipse-temurin:25-jre java $JOPTS -jar /app.jar >/dev/null || hf "앱 컨테이너 시작"
	fi

	# 4. 「떴다」는 프로세스가 아니라 응답이다: 기동 확인 경로가 200 일 때까지 90초.
	# ponytail: 앱이 일찍 죽어도 90초까지 기다린다. 길어지면 루프 안에서 state 를 본다.
	local booted=yes
	docker run --rm --network "$NET" -e P="$BOOT_PATH" "$HTTPD" sh -c \
		'i=0; while [ $i -lt 90 ]; do wget -q -T 2 -O /dev/null "http://bench-app:8080$P" 2>/dev/null && exit 0; i=$((i+1)); sleep 1; done; exit 1' \
		|| booted=no
	docker logs "$APP" 2>&1 | redact | tail -n 40 >"$OUT/app-log-tail.txt"
	if [ "$booted" = no ]; then
		log "상태: $(state "$APP")  (로그 끝: app-log-tail.txt)"
		finish "$id" "$run" "FAIL: 90초 안에 200 응답 없음" "-" "-" "-" "-" "-" "-" "-" "-" "-"
		return
	fi
	local startup
	startup=$(startup_seconds "$(docker logs "$APP" 2>&1)") || startup="읽지 못함"

	# 5. 데우기: 측정 앱은 목록 page 0~9, 단건 1~10 을 한 번씩. 코어는 사이트 설정을 넣고 두 경로를 10번씩
	if [ "${TARGET:-bench}" = core ]; then
		docker exec "$DB" psql -h 127.0.0.1 -U m -d m -v ON_ERROR_STOP=1 -q \
			-c "INSERT INTO settings (key, value) VALUES ('site.name', '측정 사이트'), ('site.type', 'cms')" \
			|| hf "사이트 설정 넣기"
		docker run --rm --network "$NET" -e A="$LOAD_LIST" -e B="$LOAD_ONE" "$HTTPD" sh -c \
			'for i in 1 2 3 4 5 6 7 8 9 10; do wget -q -O /dev/null "http://bench-app:8080$A"; wget -q -O /dev/null "http://bench-app:8080$B"; done' \
			|| log "데우기 중 오류"
	else
		docker run --rm --network "$NET" "$HTTPD" sh -c \
			'for p in 0 1 2 3 4 5 6 7 8 9; do wget -q -O /dev/null "http://bench-app:8080/api/posts?page=$p"; done
			 for i in 1 2 3 4 5 6 7 8 9 10; do wget -q -O /dev/null "http://bench-app:8080/api/posts/$i"; done' \
			|| log "데우기 중 오류"
	fi

	# 6. 유휴: 5초 뒤. 살아 있는지 먼저 본다 — 죽은 컨테이너의 stats 0B 가 좋은 성적처럼 보이면 안 된다.
	sleep 5
	local idle_state app_raw db_raw app_idle db_idle
	idle_state=$(state "$APP")
	app_raw=$(docker stats --no-stream --format '{{.MemUsage}}' "$APP" 2>&1)
	db_raw=$(docker stats --no-stream --format '{{.MemUsage}}' "$DB" 2>&1)
	log "유휴 상태: $idle_state | 앱 [$app_raw] DB [$db_raw]"
	app_idle=$(parse_mib "$app_raw") || app_idle="재지 못함"
	db_idle=$(parse_mib "$db_raw") || db_idle="재지 못함"

	# 7. 부하: 목록·단건 차례로
	run_ab list "http://$APP:8080$LOAD_LIST"
	run_ab one "http://$APP:8080$LOAD_ONE"
	local ab_list ab_one app_peak db_peak
	ab_list=$(cat "$OUT/ab-list.txt"); ab_one=$(cat "$OUT/ab-one.txt")
	app_peak=$(peak_of "$APP" "$OUT"/samples-list.txt "$OUT"/samples-one.txt)
	db_peak=$(peak_of "$DB" "$OUT"/samples-list.txt "$OUT"/samples-one.txt)

	# 8. 부하 뒤: 생사, OOM, cgroup 최고(커널 기록, 기동·파일 캐시 포함)
	local after_state oom cg_raw cg
	after_state=$(state "$APP")
	oom=$(docker logs "$APP" 2>&1 | grep -c OutOfMemoryError); oom=${oom:-0}
	cg_raw=$(docker exec "$APP" cat /sys/fs/cgroup/memory.peak 2>&1)
	cg=$(bytes_to_mib "$cg_raw") || cg="읽지 못함"
	log "부하 뒤 상태: $after_state | OOM ${oom}건 | cgroup 최고 [$cg]"
	docker logs "$APP" 2>&1 | redact | tail -n 40 >"$OUT/app-log-tail.txt"

	local verdict
	verdict=$(judge "$idle_state" "$after_state" "$oom" "$ab_list" "$ab_one")
	# 유휴 값을 숫자로 못 읽으면 통과로 두지 않는다(7절 6번 항목).
	if [ "$app_idle" = "재지 못함" ] || [ "$db_idle" = "재지 못함" ]; then
		case $verdict in PASS) verdict="FAIL: 유휴 메모리 재지 못함" ;; *) verdict="$verdict, 유휴 메모리 재지 못함" ;; esac
	fi
	finish "$id" "$run" "$verdict" "$startup" "$app_idle" "$db_idle" "$TIER" "$app_peak" "$cg" "$db_peak" \
		"$(ab_rps "$ab_list" || echo -) / $(ab_rps "$ab_one" || echo -)" \
		"목록 $(ab_brief "$ab_list") 단건 $(ab_brief "$ab_one")"
}

# finish <id> <회차> <판정> <기동s> <앱유휴> <DB유휴> <티어> <앱최고(표본)> <앱cgroup최고> <DB최고> <req/s 목록 / 단건> <완료/실패/Non2xx>
finish() {
	{
		echo "시나리오=$1 회차=$2 판정=$3"
		echo "기동(s)=$4 앱유휴(MiB)=$5 DB유휴(MiB)=$6 티어(MB)=$7"
		echo "부하중앱최고표본(MiB)=$8 앱cgroup최고(MiB)=$9 부하중DB최고(MiB)=${10}"
		echo "처리량(req/s)=${11} 요청=${12}"
	} | tee "$OUT/summary.txt"
	case $3 in PASS) return 0 ;; *) return 1 ;; esac
}

main() {
	set -u
	# 호스트 부하를 줄이려고 항상 nice 19 로 다시 실행한다.
	if [ -z "${MEASURE_NICED:-}" ]; then exec env MEASURE_NICED=1 nice -n 19 "$0" "$@"; fi
	trap cleanup EXIT
	trap 'exit 130' INT TERM
	case ${1:-} in
		env)
			mkdir -p "$ROOT/results"
			{
				echo "docker server arch: $(docker version --format '{{.Server.Arch}}')"
				echo "docker server version: $(docker version --format '{{.Server.Version}}')"
				echo "--- eclipse-temurin:25-jre"
				docker run --rm eclipse-temurin:25-jre java -version 2>&1
			} | tee "$ROOT/results/env.txt" ;;
		clean) cleanup; docker volume rm bench-gradle >/dev/null 2>&1; echo "정리 완료" ;;
		S[0-9]*) measure "$@" ;;
		*) echo "사용: $0 <S1..S7> [회차] | env | clean" >&2; exit 2 ;;
	esac
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
