# VENDOR — 복사해 온 원본과 고정 버전

`vendor/` 아래는 원본 저장소의 **복사본**이다. 손으로 고치지 않는다.
원본에서 고치고 커밋한 뒤 `scripts/sync-vendor.sh [all|mini-react|minidb|minired]`로 다시 복사한다.
스크립트는 원본에 커밋 안 된 변경이 있으면 거부하고, 아래 표의 SHA를 갱신한다.

| 이름 | 원본 폴더 | 브랜치 | 커밋 SHA | 복사 날짜 | 복사 범위 |
|---|---|---|---|---|---|
| minired | `미니레디스` | `feat/phase1-server` | `5186c6cc122bf03aa0bc172c7e4015189fb46f5d` | 2026-11-18 | `server/` (git archive라 build · .gradle 없음) |
| minidb | `미니DBMS` | `feat/update-delete` | `c7efc7ce37cf6938519bbd9c3e6837533a7fe7c1` | 2026-11-18 | `src/` · `tests/` · `bench/` · `CMakeLists.txt` · `CMakePresets.json` |
| mini-react | `mini-react` | `release/v1` | `a4b26133dd2f3b9a17d7a6b998b810ed522fc7d1` | 2026-11-18 | `npm run build` 뒤 `dist/index.js` · `dist/engine/` · `dist/runtime/` |

## 쓰는 곳
- `vendor/mini-react` → `demo/web/` 화면이 `/vendor/mini-react/index.js`로 import (엔진 + 런타임만)
- `vendor/minidb` → `docker/minidb.Dockerfile`이 `minidb` 타깃만 release로 빌드. tests · bench는 CMake가 소스를 요구해서 같이 복사
- `vendor/minired` → 원본 `Dockerfile` 그대로 빌드
