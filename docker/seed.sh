#!/usr/bin/env bash
# 시드: users에 ROWS행 (기본 10만), 1000개 배치 INSERT. 같은 SEED면 같은 데이터.
# 이름 분포 — 검색 결과 크기가 다양하게 나오도록:
#   kim   : 전체의 20% (2만 행 ≈ 33만 바이트 → 캐시 크기 상한 256KB를 넘는 엣지, X-Cache: skip)
#   lee · park · 김민수 : 각 1%대
#   나머지: user00000 ~ user09999 고르게 (이름당 약 7행)
# Content-Type: text/plain 필수 — cpp-httplib는 form-urlencoded 본문을 8KB로 제한해 413을 낸다
# 이미 행이 있으면 아무것도 안 함 (FORCE=1이면 그래도 넣음)
set -euo pipefail
URL="${DB_URL:-http://minidb:8080}"
ROWS="${ROWS:-100000}"
BATCH=1000

for _ in $(seq 1 100); do curl -sf "$URL/health" >/dev/null && break; sleep 0.2; done
curl -sf "$URL/health" >/dev/null || { echo "seed: DB에 연결 못 함 ($URL)" >&2; exit 1; }

existing=$(curl -s -X POST --data-binary "SELECT id FROM users WHERE id = 1" "$URL/query" | grep -c '^\[' || true)
if [ "$existing" != 0 ] && [ "${FORCE:-0}" != 1 ]; then
  echo "seed: 이미 데이터가 있음 — 건너뜀"
  exit 0
fi

start=$(date +%s%N)
awk -v rows="$ROWS" -v batch="$BATCH" -v seed="${SEED:-42}" 'BEGIN {
  srand(seed)
  for (i = 0; i < rows; i++) {
    r = rand()
    if (r < 0.20) name = "kim"
    else if (r < 0.215) name = "lee"
    else if (r < 0.225) name = "park"
    else if (r < 0.23) name = "김민수"
    else name = sprintf("user%05d", int(rand() * 10000))
    age = 18 + int(rand() * 60)
    printf "INSERT INTO users VALUES (\x27%s\x27, %d);", name, age
    if ((i + 1) % batch == 0 || i == rows - 1) printf "\n"
  }
}' | while IFS= read -r line; do
  out=$(printf '%s' "$line" | curl -s -H "Content-Type: text/plain" -X POST --data-binary @- "$URL/query")
  case "$out" in '{"ok":true'*) ;; *) echo "seed: 배치 실패: ${out:0:200}" >&2; exit 1 ;; esac
done
ms=$(( ($(date +%s%N) - start) / 1000000 ))
count=$(curl -s -X POST --data-binary "SELECT id FROM users WHERE name = 'kim'" "$URL/query" | tail -1)
echo "seed: $ROWS 행 완료 (${ms}ms). kim → $count"
