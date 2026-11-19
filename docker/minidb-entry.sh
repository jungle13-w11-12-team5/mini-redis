#!/usr/bin/env bash
# users 스키마가 없으면 만들고 서버를 띄운다. exec → minidb가 PID 1이라 SIGTERM을 직접 받음
set -euo pipefail
DATA="${MINIDB_DATA:-/data}"
mkdir -p "$DATA"
if [ ! -f "$DATA/users.schema" ]; then
  printf 'name VARCHAR(16)\nage INT\n' > "$DATA/users.schema"
fi
exec minidb serve --data "$DATA" --port "${MINIDB_PORT:-8080}" --workers "${MINIDB_WORKERS:-8}"
