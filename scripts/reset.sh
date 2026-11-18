#!/usr/bin/env bash
# 같은 출발점으로 (Q28): DB 볼륨을 지우고 다시 시드, 레디스는 새 컨테이너(메모리만이라 비어 있음),
# 데모 서버도 새로 (통계 0, 시작 INCR → users:ver 1). UPDATE가 쌓이면 스캔이 느려져 "캐시 끔" 기준선이 흔들리기 때문
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose rm -sf demo minidb minired seed > /dev/null 2>&1
docker volume rm -f mini-stack_dbdata > /dev/null
docker compose up -d --wait demo > /dev/null 2>&1
docker compose logs seed | grep 'seed:' | tail -1
