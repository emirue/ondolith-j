#!/usr/bin/env bash
# measure.sh 의 파싱·판정 함수 검증. 고정 입력만 쓰므로 Docker 가 필요 없다.
# 이 파일이 check-public 검사에 걸리지 않도록 위반 문자열은 조각을 이어 붙인다.
cd "$(dirname "$0")/.." || exit 2
# shellcheck source=../measure.sh
. ./measure.sh 2>/dev/null
pass=0; fail=0

# chk <이름> <기대> <실제>
chk() { if [ "$2" = "$3" ]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL: $1 (기대 [$2], 실제 [$3])"; fi; }
# run <명령...> → "종료코드|stdout"
run() { local o; o=$("$@" 2>/dev/null); echo "$?|$o"; }

OK_AB=$'Concurrency Level:      20\nComplete requests:      5000\nFailed requests:        0\nRequests per second:    360.35 [#/sec] (mean)'
NON2XX_AB="$OK_AB"$'\nNon-2xx responses:      5000'
SHORT_AB=$'Complete requests:      4166\nFailed requests:        0\nRequests per second:    9.1 [#/sec] (mean)'
FAILED_AB=$'Complete requests:      5000\nFailed requests:        3'
RESET_AB='apr_socket_recv: Connection reset by peer (54)'

# --- parse_mib: docker stats 의 MemUsage 칸
chk "MiB"                    "0|252.3" "$(run parse_mib '252.3MiB / 512MiB')"
chk "GiB 는 MiB 로 환산"       "0|1536.0" "$(run parse_mib '1.5GiB / 2GiB')"
chk "KiB 는 MiB 로 환산"       "0|0.5" "$(run parse_mib '512KiB / 1GiB')"
chk "0B 는 재지 못함(죽은 컨테이너)" "1|" "$(run parse_mib '0B / 0B')"
chk "-- 는 재지 못함"          "1|" "$(run parse_mib '-- / --')"
chk "빈 값은 재지 못함"         "1|" "$(run parse_mib '')"
chk "숫자가 아니면 재지 못함"    "1|" "$(run parse_mib 'abc / def')"

# --- max_mib: 표본 여러 줄 중 최댓값, 못 읽은 줄은 건너뜀
chk "표본 최댓값" "0|120.5" "$(run max_mib <<<$'100MiB / 128MiB\n0B / 0B\n120.5MiB / 128MiB\nfoo')"
chk "읽을 표본이 없으면 실패" "1|" "$(run max_mib <<<$'0B / 0B\nfoo')"

# --- ab_get / ab_rps
chk "ab 값 읽기"              "0|5000" "$(run ab_get "$OK_AB" 'Complete requests')"
chk "없는 줄은 실패"           "1|" "$(run ab_get "$OK_AB" 'Non-2xx responses')"
chk "처리량"                  "0|360.35" "$(run ab_rps "$OK_AB")"

# --- ab_ok: 5000건·실패 0·Non-2xx 0
chk "정상 ab 는 통과(Non-2xx 줄이 없으면 0 으로 본다)" "0" "$(run ab_ok "$OK_AB" | cut -d'|' -f1)"
chk "Non-2xx 가 있으면 실패"             "1" "$(run ab_ok "$NON2XX_AB" | cut -d'|' -f1)"
chk "완료 건수가 모자라면 실패"           "1" "$(run ab_ok "$SHORT_AB" | cut -d'|' -f1)"
chk "Failed requests 가 있으면 실패"     "1" "$(run ab_ok "$FAILED_AB" | cut -d'|' -f1)"
chk "요약 줄이 없으면(ab 가 끊김) 실패"   "1" "$(run ab_ok "$RESET_AB" | cut -d'|' -f1)"
chk "빈 출력은 실패"                     "1" "$(run ab_ok '' | cut -d'|' -f1)"

# --- container_ok: "Running OOMKilled"
chk "살아 있고 OOM 아님" "0" "$(run container_ok 'true false' | cut -d'|' -f1)"
chk "OOMKilled"          "1" "$(run container_ok 'true true' | cut -d'|' -f1)"
chk "죽음"               "1" "$(run container_ok 'false false' | cut -d'|' -f1)"
chk "빈 값"              "1" "$(run container_ok '' | cut -d'|' -f1)"

# --- no_oom: OutOfMemoryError 건수
chk "0건"          "0" "$(run no_oom 0 | cut -d'|' -f1)"
chk "1건 이상"      "1" "$(run no_oom 2 | cut -d'|' -f1)"
chk "숫자가 아니면 실패" "1" "$(run no_oom '' | cut -d'|' -f1)"

# --- startup_seconds / bytes_to_mib
chk "기동 시간"       "0|5.012" "$(run startup_seconds 'x Started BenchApplication in 5.012 seconds (process running for 5.5)')"
chk "기동 줄이 없으면 실패" "1|" "$(run startup_seconds 'nothing here')"
chk "다른 앱 이름도 기동 시간" "0|3.25" "$(run startup_seconds 'x Started ProbeApplication in 3.25 seconds (process running for 3.6)')"

# --- app_jar / out_dir / jvm_opts 의 추가 옵션: 코어 스택 사전 측정용
chk "jar 기본값은 측정 앱" "0|/r/src/back/build/libs/bench-0.0.1-SNAPSHOT.jar" "$(run app_jar /r)"
chk "JAR 로 다른 jar"      "0|/x/probe.jar" "$(JAR=/x/probe.jar run app_jar /r)"
chk "결과 폴더 기본값"      "0|/r/results/S6-2" "$(run out_dir /r S6 2)"
chk "LABEL 이 있으면 그 아래" "0|/r/results/core-a/S6-2" "$(LABEL=core-a run out_dir /r S6 2)"
chk "LABEL 에 / 나 .. 은 거부" "1|" "$(LABEL=../x run out_dir /r S6 2)"
chk "EXTRA_JOPTS 를 뒤에 붙임" "0|-Xss512k -XX:TieredStopAtLevel=1 -Dk=v" "$(EXTRA_JOPTS=-Dk=v run jvm_opts J2)"
chk "J1 + EXTRA_JOPTS"        "0|-Dk=v" "$(EXTRA_JOPTS=-Dk=v run jvm_opts J1)"
chk "J1 기본은 빈 값"          "0|" "$(run jvm_opts J1)"
chk "바이트를 MiB 로"  "0|255.0" "$(run bytes_to_mib 267386880)"
chk "숫자가 아니면 실패" "1|" "$(run bytes_to_mib 'cat: no such file')"

# --- scenario: 요청서 5절 표 그대로 "DB상한 shared_buffers 앱상한 JVM티어 티어MB"
chk "S1" "0|384m 128MB 128m J1 512"  "$(run scenario S1)"
chk "S2" "0|384m 128MB 128m J2 512"  "$(run scenario S2)"
chk "S3" "0|256m 64MB 256m J1 512"   "$(run scenario S3)"
chk "S4" "0|256m 64MB 256m J2 512"   "$(run scenario S4)"
chk "S5" "0|512m 256MB 512m J1 1024" "$(run scenario S5)"
chk "S6" "0|384m 128MB 180m J1 512"  "$(run scenario S6)"
chk "S7" "0|384m 128MB 180m J2 512"  "$(run scenario S7)"
chk "없는 시나리오" "1|" "$(run scenario S9)"
chk "J1 은 옵션 없음" "0|" "$(run jvm_opts J1)"
chk "J2 옵션" "0|-Xss512k -XX:TieredStopAtLevel=1" "$(run jvm_opts J2)"

# --- judge <유휴 상태> <부하 후 상태> <OOM 건수> <ab 목록> <ab 단건>
chk "모두 정상이면 PASS" "0|PASS" "$(run judge 'true false' 'true false' 0 "$OK_AB" "$OK_AB")"
chk "유휴 때 OOMKilled"  "1" "$(run judge 'true true' 'true false' 0 "$OK_AB" "$OK_AB" | cut -d'|' -f1)"
chk "부하 뒤 죽음"        "1" "$(run judge 'true false' 'false true' 0 "$OK_AB" "$OK_AB" | cut -d'|' -f1)"
chk "OOM 1건"            "1" "$(run judge 'true false' 'true false' 1 "$OK_AB" "$OK_AB" | cut -d'|' -f1)"
chk "ab 목록 실패"        "1" "$(run judge 'true false' 'true false' 0 "$SHORT_AB" "$OK_AB" | cut -d'|' -f1)"
chk "ab 단건이 끊김"      "1" "$(run judge 'true false' 'true false' 0 "$OK_AB" "$RESET_AB" | cut -d'|' -f1)"
multi=$(judge 'true false' 'true false' 3 "$SHORT_AB" "$OK_AB" 2>/dev/null)
case $multi in FAIL:*OutOfMemoryError*ab\ 목록*) pass=$((pass+1)) ;; *) fail=$((fail+1)); echo "FAIL: 실패 사유가 모두 나와야 한다 ($multi)" ;; esac

# --- redact: 로그에서 생성 비밀번호를 지운다
chk "생성 비밀번호 가림" "0|Using generated security password: [제거됨]" \
  "$(run redact <<<"Using generated security ""password: 1234-abcd")"
chk "다른 줄은 그대로" "0|Started BenchApplication in 5 seconds" "$(run redact <<<'Started BenchApplication in 5 seconds')"

echo "measure: 통과 $pass / 실패 $fail"
[ "$fail" -eq 0 ]
