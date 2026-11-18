#!/usr/bin/env bash
# 데모 기능 테스트 (C4): 떠 있는 compose 스택에 curl로. 레디스 · DB를 실제로 멈췄다 살린다.
# 사용: docker compose up -d --build && bash demo/test/e2e.sh
# 데이터를 바꾼다 (id 101~103의 age) → 벤치 전에는 scripts/reset.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
B="${DEMO_URL:-http://127.0.0.1:8088}"
PASS=0; FAIL=0
ok()   { PASS=$((PASS + 1)); echo "  ok   $*"; }
bad()  { FAIL=$((FAIL + 1)); echo "  FAIL $*"; }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 — 기대 '$3', 실제 '$2'"; fi; }
has()   { case "$2" in *"$3"*) ok "$1" ;; *) bad "$1 — '$3' 없음: ${2:0:200}" ;; esac; }

# 응답 → 전역 CODE · XC(X-Cache) · BODY
req() {
  local h; h=$(mktemp)
  BODY=$(curl -s -D "$h" "$@")
  CODE=$(awk 'NR==1{print $2}' "$h")
  XC=$(grep -i '^x-cache:' "$h" | tr -d '\r' | awk '{print $2}')
  rm -f "$h"
}
redis() { curl -s -X POST --data-binary "$1" "$B/api/console/redis"; }
setting() { curl -s -X POST "$B/api/settings?$1" > /dev/null; }
wait_healthy() {   # $1 서비스
  for _ in $(seq 1 60); do
    [ "$(docker compose ps --format '{{.Health}}' "$1")" = healthy ] && return 0
    sleep 0.5
  done
  return 1
}

setting "cache=on&mode=gen"

echo "## 읽기 — cache-aside"
req "$B/api/users/101"; check "id 첫 조회 miss" "$XC" miss; check "200" "$CODE" 200
first="$BODY"
req "$B/api/users/101"; check "id 두 번째 hit" "$XC" hit; check "hit 본문 = miss 본문" "$BODY" "$first"
has "NDJSON 마지막 줄 ok:true" "$BODY" '{"ok":true,"rowCount":1}'
req "$B/api/users/999999"; check "없는 id 404" "$CODE" 404; check "없는 id miss" "$XC" miss
req "$B/api/users/999999"; check "없는 id도 캐시 (hit)" "$XC" hit; check "hit도 404" "$CODE" 404
ver=$(redis "GET users:ver" | sed -E 's/.*"reply":"\\"([0-9]+)\\"".*/\1/')
ttl=$(redis "TTL users:v$ver:id:999999" | sed -E 's/.*\(integer\) ([0-9-]+).*/\1/')
[ "$ttl" -ge 1 ] && [ "$ttl" -le 5 ] && ok "빈 결과 TTL ≤ 5초 ($ttl)" || bad "빈 결과 TTL $ttl"
ttl=$(redis "TTL users:v$ver:id:101" | sed -E 's/.*\(integer\) ([0-9-]+).*/\1/')
[ "$ttl" -ge 50 ] && [ "$ttl" -le 60 ] && ok "일반 TTL 60초 ($ttl)" || bad "일반 TTL $ttl"

echo "## 검색 — 전체 스캔"
req "$B/api/users?name=lee"; check "검색 miss" "$XC" miss
req "$B/api/users?name=lee"; check "검색 hit" "$XC" hit
req "$B/api/users?name=kim"; check "큰 결과(>256KB)는 캐시 안 함" "$XC" skip; check "skip도 200" "$CODE" 200
req "$B/api/users?name=kim"; check "skip은 다음에도 skip" "$XC" skip
req "$B/api/users?name=%EA%B9%80%EB%AF%BC%EC%88%98"; check "한글 이름 200" "$CODE" 200; has "한글 결과" "$BODY" '"김민수"'

echo "## 입력 검사 · SQL 문자열"
req "$B/api/users?name=%27%20OR%20%271%27%3D%271"; check "' OR '1'='1 → 200" "$CODE" 200
has "인젝션은 0행 (따옴표가 이름의 일부)" "$BODY" '"rowCount":0'
req "$B/api/users?name=o%27neil"; check "작은따옴표 이름 200" "$CODE" 200
req "$B/api/users?name=%FF%FE"; check "깨진 UTF-8 → 400" "$CODE" 400
req "$B/api/users?name=%EA%B9"; check "잘린 한글 → 400" "$CODE" 400
req "$B/api/users?name=12345678901234567"; check "17바이트 → 400" "$CODE" 400
req "$B/api/users?name=1234567890123456"; check "16바이트 경계 → 200" "$CODE" 200
req "$B/api/users?name=%EA%B9%80%EB%AF%BC%EC%88%98%EA%B9%80%EB%AF%BC"; check "한글 5자(15B) → 200" "$CODE" 200
req "$B/api/users?name=%EA%B9%80%EB%AF%BC%EC%88%98%EA%B9%80%EB%AF%BC%EC%88%98"; check "한글 6자(18B) → 400" "$CODE" 400
req "$B/api/users?name=a%0Ab"; check "제어 문자 → 400" "$CODE" 400
req "$B/api/users?name="; check "빈 이름 → 400" "$CODE" 400
req "$B/api/users/abc"; check "id 문자 → 400" "$CODE" 400
req "$B/api/users/0"; check "id 0 → 400" "$CODE" 400
req "$B/api/users/99999999999999999999"; check "id 넘침 → 400" "$CODE" 400
req -X PUT "$B/api/users/101?age=abc"; check "age 문자 → 400" "$CODE" 400
req -X PUT "$B/api/users/101"; check "바꿀 값 없음 → 400" "$CODE" 400

echo "## 쓰기 두 길 — 세대 번호 (Q13-C)"
v0=$(redis "GET users:ver" | sed -E 's/.*\\"([0-9]+)\\".*/\1/')
req -X PUT "$B/api/users/101?age=41"; check "API 쓰기 200" "$CODE" 200; has "INCR" "$BODY" "INCR users:ver"
v1=$(redis "GET users:ver" | sed -E 's/.*\\"([0-9]+)\\".*/\1/')
check "ver +1" "$v1" "$((v0 + 1))"
req "$B/api/users/101"; check "API 쓰기 뒤 miss" "$XC" miss; has "새 값" "$BODY" '"age"' ; has "새 값 41" "$BODY" ",41]"
req "$B/api/users?name=lee"; check "검색 키도 같이 낡음 (miss)" "$XC" miss
req "$B/api/users/101"; check "다시 hit" "$XC" hit
req -X PUT "$B/api/db/users/101?age=42"; check "DB 직접 쓰기 200" "$CODE" 200
req "$B/api/users/101"; check "DB 직접 뒤 hit (레디스는 모름)" "$XC" hit; has "옛 값 41이 나감" "$BODY" ",41]"
req -X PUT "$B/api/users/101?age=43"; req "$B/api/users/101"; has "API 쓰기로 바로잡힘" "$BODY" ",43]"
req -X PUT "$B/api/users/999999?age=1"; check "없는 id 쓰기 404" "$CODE" 404

echo "## C2 무효화 경합 — 읽기가 DB에서 옛 값을 받은 뒤 · SET 전에 쓰기(UPDATE + 무효화)가 끝남"
race() {   # $1 mode  $2 id  → 경합 뒤 다음 읽기의 BODY · XC
  setting "mode=$1"
  curl -s -X PUT "$B/api/users/$2?age=50" > /dev/null
  redis "DEL users:id:$2" > /dev/null
  curl -s -H "X-Test-Pause-Ms: 1500" "$B/api/users/$2" > /dev/null &   # 옛 값(50)을 읽고 1.5초 멈춤
  sleep 0.5
  curl -s -X PUT "$B/api/users/$2?age=51" > /dev/null                  # UPDATE + (INCR | DEL)
  wait
  req "$B/api/users/$2"
}
race del 102; check "DEL 방식: 경합 뒤 hit" "$XC" hit; has "DEL 방식: 옛 값 50이 TTL까지 남음" "$BODY" ",50]"
race gen 103; check "세대 번호: 경합 뒤 miss" "$XC" miss; has "세대 번호: 새 값 51" "$BODY" ",51]"
setting "mode=gen"

echo "## 캐시 끔"
setting "cache=off"
req "$B/api/users/101"; check "끔 → off" "$XC" off
setting "cache=on"

echo "## 직접 호출 창 (Q26)"
has "허용: DBSIZE" "$(redis DBSIZE)" "(integer)"
has "거부: INCR" "$(redis 'INCR users:ver')" "허용 안 된 명령"
has "거부: FLUSHALL" "$(redis FLUSHALL)" "허용 안 된 명령"
has "SET 따옴표" "$(redis 'SET demo:x "a b"')" '"OK'
has "GET 따옴표" "$(redis 'GET demo:x')" 'a b'
has "DB 직접 SELECT" "$(curl -s -X POST --data-binary 'SELECT * FROM users WHERE id = 1' "$B/api/console/db")" 'rowCount'

echo "## 레디스 다운 → DB 우회 (Q21)"
docker compose stop minired > /dev/null 2>&1
s=$(date +%s%N); req "$B/api/users/104"; ms=$(( ($(date +%s%N) - s) / 1000000 ))
check "레디스 다운 읽기 200" "$CODE" 200; check "X-Cache bypass" "$XC" bypass
[ "$ms" -lt 1000 ] && ok "우회 응답 ${ms}ms" || bad "우회가 느림 ${ms}ms"
req -X PUT "$B/api/users/104?age=60"; check "레디스 다운 쓰기: DB는 성공" "$CODE" 200; has "무효화 실패 보고" "$BODY" "실패"
docker compose start minired > /dev/null 2>&1; wait_healthy minired
req "$B/api/users/104"; check "레디스 복구 뒤 miss (비어 있음)" "$XC" miss
req "$B/api/users/104"; check "레디스 복구 뒤 hit" "$XC" hit

echo "## DB 다운 → 캐시에 안 씀 (Q21)"
req "$B/api/users/105"   # 캐시 채움
docker compose stop minidb > /dev/null 2>&1
req "$B/api/users/105"; check "DB 다운이어도 캐시 hit은 응답" "$XC" hit
req "$B/api/users/106"; check "DB 다운 + miss → 503" "$CODE" 503
docker compose start minidb > /dev/null 2>&1; wait_healthy minidb
req "$B/api/users/106"; check "DB 복구 뒤 miss (실패는 캐시 안 됨)" "$XC" miss; check "DB 복구 뒤 200" "$CODE" 200

echo "## 추적 (Q25)"
h=$(curl -s -o /dev/null -D - -H "X-Trace: 1" "$B/api/users/107" | grep -i '^x-trace:')
has "추적: ver 읽기" "$h" "GET users:ver"; has "추적: DB 호출" "$h" "POST /query"; has "추적: SET" "$h" '"SET users:v'
h=$(curl -s -o /dev/null -D - "$B/api/users/107" | grep -ci '^x-trace:' || true)
check "추적 헤더는 요청할 때만" "$h" 0

echo
echo "e2e: 통과 $PASS · 실패 $FAIL"
[ "$FAIL" = 0 ]
