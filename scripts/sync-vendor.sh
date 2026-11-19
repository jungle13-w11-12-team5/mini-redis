#!/usr/bin/env bash
# 원본 저장소 3개 → vendor/ 복사 + VENDOR.md에 커밋 SHA 기록.
# 원본에 커밋 안 된 변경이 있으면 거부한다 (복사본이 어느 커밋인지 말할 수 없게 되므로).
# 사용: scripts/sync-vendor.sh [all|mini-react|minidb|minired]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${MINI_SRC:-$ROOT/..}"   # 원본 셋이 있는 폴더 (기본: 이 저장소의 형제 폴더)
VENDOR="$ROOT/vendor"
LOG="$ROOT/VENDOR.md"
TARGET="${1:-all}"

die() { echo "sync-vendor: $*" >&2; exit 1; }

require_clean() {   # $1 = 원본 경로
  local dirty
  dirty="$(git -C "$1" status --porcelain)"
  [ -z "$dirty" ] || die "$1 에 커밋 안 된 변경이 있음 — 커밋한 뒤 다시:
$dirty"
}

record() {   # $1 이름  $2 원본 경로  $3 복사 범위
  local sha branch
  sha="$(git -C "$2" rev-parse HEAD)"
  branch="$(git -C "$2" rev-parse --abbrev-ref HEAD)"
  local row="| $1 | \`$(basename "$2")\` | \`$branch\` | \`$sha\` | $(date +%F) | $3 |"
  # 같은 이름의 줄을 바꿔 끼운다 (없으면 표 끝에 추가)
  if grep -q "^| $1 |" "$LOG"; then
    awk -v name="$1" -v row="$row" 'index($0, "| " name " |") == 1 { print row; next } { print }' "$LOG" > "$LOG.tmp"
    mv "$LOG.tmp" "$LOG"
  else
    awk -v row="$row" '{ print } /^\|---/ && !done { print row; done = 1 }' "$LOG" > "$LOG.tmp"
    mv "$LOG.tmp" "$LOG"
  fi
  echo "sync-vendor: $1 ← $(basename "$2") $branch ${sha:0:7}"
}

# 커밋된 내용만 꺼낸다 (git archive). autocrlf를 꺼야 셸 스크립트가 CRLF로 바뀌지 않는다
extract() {   # $1 원본  $2 대상  $3.. 경로들
  local src="$1" dst="$2"; shift 2
  rm -rf "$dst"; mkdir -p "$dst"
  git -C "$src" -c core.autocrlf=false archive --format=tar HEAD -- "$@" | tar -x -C "$dst"
}

sync_react() {
  local s="$SRC/mini-react"
  require_clean "$s"
  (cd "$s" && npm run build --silent)
  # 빌드 산출물(dist/)은 무시 파일이라 깨끗함 검사에 안 걸린다. 빌드 뒤에도 다시 검사
  require_clean "$s"
  local d="$VENDOR/mini-react"
  rm -rf "$d"; mkdir -p "$d"
  cp "$s/dist/index.js" "$d/"
  cp -r "$s/dist/engine" "$s/dist/runtime" "$d/"
  record mini-react "$s" '`npm run build` 뒤 `dist/index.js` · `dist/engine/` · `dist/runtime/`'
}

sync_db() {
  local s="$SRC/미니DBMS"
  require_clean "$s"
  extract "$s" "$VENDOR/minidb" src tests bench CMakeLists.txt CMakePresets.json
  record minidb "$s" '`src/` · `tests/` · `bench/` · `CMakeLists.txt` · `CMakePresets.json`'
}

sync_redis() {
  local s="$SRC/미니레디스"
  require_clean "$s"
  extract "$s" "$VENDOR/tmp-minired" server
  rm -rf "$VENDOR/minired"; mv "$VENDOR/tmp-minired/server" "$VENDOR/minired"; rm -rf "$VENDOR/tmp-minired"
  record minired "$s" '`server/` (git archive라 build · .gradle 없음)'
}

[ -f "$LOG" ] || die "VENDOR.md 없음"
case "$TARGET" in
  all) sync_react; sync_db; sync_redis ;;
  mini-react) sync_react ;;
  minidb) sync_db ;;
  minired) sync_redis ;;
  *) die "알 수 없는 대상: $TARGET" ;;
esac
