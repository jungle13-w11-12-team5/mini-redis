#!/usr/bin/env bash
# 벤치 한 세트 (Q15 · Q28): 쓰기 0 · 1 · 10% × 캐시 켬 · 끔. 회차마다 DB 재시드 · 레디스 비움.
# 결과: bench/results/raw/<회차>.json (k6 요약 + 서버 측 DBSIZE · .tbl 크기) → bench/summarize.py가 표로
# 사용: bench/run.sh            (환경 변수 DURATION=30s VUS=32 WRITES="0 1 10" CACHES="on off")
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash가 /data 같은 경로를 Windows 경로로 바꾸지 않게
cd "$(dirname "$0")/.."
DURATION="${DURATION:-30s}"; VUS="${VUS:-32}"
WRITES="${WRITES:-0 1 10}"; CACHES="${CACHES:-on off}"
mkdir -p bench/results/raw

for w in $WRITES; do
  for c in $CACHES; do
    run="w${w}-cache-${c}"
    echo "== $run"
    bash scripts/reset.sh
    curl -sf -X POST "http://127.0.0.1:${DEMO_PORT:-8088}/api/settings?cache=$c&mode=gen" > /dev/null
    tbl_before=$(docker compose exec -T minidb stat -c %s /data/users.tbl)
    docker compose --profile bench run --rm -T k6 run -q \
      -e RUN="$run" -e WRITE_PCT="$w" -e VUS="$VUS" -e DURATION="$DURATION" /bench/cache.js
    stats=$(curl -sf "http://127.0.0.1:${DEMO_PORT:-8088}/api/stats")
    tbl_after=$(docker compose exec -T minidb stat -c %s /data/users.tbl)
    # 서버 측 값을 같은 JSON에 덧붙인다
    python -I - "bench/results/raw/$run.json" "$stats" "$tbl_before" "$tbl_after" <<'EOF'
import json, sys
path, stats, before, after = sys.argv[1], json.loads(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
d = json.load(open(path, encoding="utf-8"))
d["server"] = {"stats": stats, "tblBefore": before, "tblAfter": after}
json.dump(d, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
EOF
  done
done
python -I bench/summarize.py
